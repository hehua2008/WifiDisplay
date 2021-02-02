package com.hym.rtplib;

/*
   TimeSyncer allows us to synchronize time between a client and a server.
   The client sends a UDP packet containing its send-time to the server,
   the server sends that packet back to the client amended with information
   about when it was received as well as the time the reply was sent back.
   Finally the client receives the reply and has now enough information to
   compute the clock offset between client and server assuming that packet
   exchange is symmetric, i.e. time for a packet client.server and
   server.client is roughly equal.
   This exchange is repeated a number of times and the average offset computed
   over the 30% of packets that had the lowest roundtrip times.
   The offset is determined every 10 secs to account for slight differences in
   clock frequency.
*/

import android.util.Log;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.foundation.AHandler;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.net.ANetworkSession;
import com.hym.rtplib.util.CheckUtils;
import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;

public class TimeSyncer extends AHandler implements MediaConstants, Errno {
    private static final String TAG = TimeSyncer.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int WHAT_ERROR = 0;
    public static final int WHAT_TIME_OFFSET = 1;

    private static final int WHAT_START_SERVER = 0;
    private static final int WHAT_START_CLIENT = 1;
    private static final int WHAT_UDP_NOTIFY = 2;
    private static final int WHAT_SEND_PACKET = 3;
    private static final int WHAT_TIMED_OUT = 4;

    private static final int NUM_PACKETS_PER_BATCH = 30;
    private static final long TIMEOUT_DELAY_US = 500_000L;
    private static final long BATCH_DELAY_US = 60_000_000L;  // every minute

    private final ANetworkSession mNetSession;
    private final AMessage mNotify;

    private boolean mIsServer;
    private boolean mConnected;
    private int mUDPSession;
    private final int mSeqNo;
    private final double mTotalTimeUs;

    private List<TimeInfo> mHistory;

    private long mPendingT1;
    private int mTimeoutGeneration;

    public TimeSyncer(ANetworkSession netSession, AMessage notify) {
        mNetSession = netSession;
        mNotify = notify;
        mIsServer = false;
        mConnected = false;
        mUDPSession = 0;
        mSeqNo = 0;
        mTotalTimeUs = 0.0;
        mPendingT1 = 0L;
        mTimeoutGeneration = 0;
    }

    public void startServer(int localPort) {
        AMessage msg = AMessage.obtain(WHAT_START_SERVER, this);
        msg.setInt(LOCAL_PORT, localPort);
        msg.post();
    }

    public void startClient(String remoteHost, int remotePort) {
        AMessage msg = AMessage.obtain(WHAT_START_CLIENT, this);
        msg.set(REMOTE_HOST, remoteHost);
        msg.setInt(REMOTE_PORT, remotePort);
        msg.post();
    }

    protected void onMessageReceived(AMessage msg) {
        switch (msg.getWhat()) {
            case WHAT_START_CLIENT: {
                String remoteHost = msg.getThrow(REMOTE_HOST);
                int remotePort = msg.getInt(REMOTE_PORT);

                AMessage notify = AMessage.obtain(WHAT_UDP_NOTIFY, this);

                try {
                    mUDPSession = mNetSession.createUDPSession(
                            0 /* localPort */,
                            remoteHost,
                            remotePort,
                            notify);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                postSendPacket();
                break;
            }

            case WHAT_START_SERVER: {
                mIsServer = true;

                int localPort = msg.getInt(LOCAL_PORT);

                AMessage notify = AMessage.obtain(WHAT_UDP_NOTIFY, this);

                try {
                    mUDPSession = mNetSession.createUDPSession(localPort, notify);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                break;
            }

            case WHAT_SEND_PACKET: {
                if (mHistory.size() == 0) {
                    Log.d(TAG, "starting batch");
                }

                TimeInfo ti = new TimeInfo();
                ti.mT1 = TimeUtils.getMonotonicMicroTime();
                ByteBuffer tiData = ti.toByteBuffer();

                CheckUtils.checkEqual(OK,
                        mNetSession.sendRequest(mUDPSession, tiData, tiData.remaining()));

                mPendingT1 = ti.mT1;
                postTimeout();
                break;
            }

            case WHAT_TIMED_OUT: {
                int generation = msg.getInt(GENERATION);

                if (generation != mTimeoutGeneration) {
                    break;
                }

                Log.d(TAG, "timed out, sending another request");
                postSendPacket();
                break;
            }

            case WHAT_UDP_NOTIFY: {
                int reason = msg.getInt(REASON);

                switch (reason) {
                    case ANetworkSession.WHAT_ERROR: {
                        int sessionID = msg.getInt(SESSION_ID);
                        int err = msg.getInt(ERR);
                        String detail = msg.getThrow(DETAIL);

                        Log.e(TAG, String.format("An error occurred in session[%d] (%d, %s)",
                                sessionID, err, detail));

                        mNetSession.destroySession(sessionID);
                        cancelTimeout();
                        notifyError(err);
                        break;
                    }

                    case ANetworkSession.WHAT_DATAGRAM: {
                        int sessionID = msg.getInt(SESSION_ID);
                        ABuffer packet = msg.getThrow(DATA);
                        long arrivalTimeUs = packet.meta().getLong(ARRIVAL_TIME_US);

                        CheckUtils.checkEqual(packet.size(), TimeInfo.getSize());

                        TimeInfo ti = TimeInfo.fromByteBuffer(packet.data(), 0);

                        if (mIsServer) {
                            if (!mConnected) {
                                String fromAddr = msg.getThrow(FROM_ADDR);
                                int fromPort = msg.getThrow(FROM_PORT);

                                CheckUtils.checkEqual(OK,
                                        mNetSession.connectUDPSession(
                                                mUDPSession, fromAddr, fromPort));

                                mConnected = true;
                            }

                            ti.mT2 = arrivalTimeUs;
                            ti.mT3 = TimeUtils.getMonotonicMicroTime();
                            ByteBuffer tiData = ti.toByteBuffer();

                            CheckUtils.checkEqual(OK,
                                    mNetSession.sendRequest(
                                            mUDPSession, tiData, tiData.remaining()));
                        } else {
                            if (ti.mT1 != mPendingT1) {
                                break;
                            }

                            cancelTimeout();
                            mPendingT1 = 0;

                            ti.mT4 = arrivalTimeUs;

                            // One way delay for a packet to travel from client
                            // to server or back (assumed to be the same either way).
                            long delay = (ti.mT2 - ti.mT1 + ti.mT4 - ti.mT3) / 2;

                            // Offset between the client clock (T1, T4) and the
                            // server clock (T2, T3) timestamps.
                            long offset = (ti.mT2 - ti.mT1 - ti.mT4 + ti.mT3) / 2;

                            mHistory.add(ti);

                            Log.d(TAG, String.format("delay=%d us,\toffset=%d us", delay, offset));

                            if (mHistory.size() < NUM_PACKETS_PER_BATCH) {
                                postSendPacket(1_000_000L / 30);
                            } else {
                                notifyOffset();

                                Log.d(TAG, "batch done");

                                mHistory.clear();
                                postSendPacket(BATCH_DELAY_US);
                            }
                        }
                        break;
                    }

                    default:
                        throw new RuntimeException("TRESPASS");
                }

                break;
            }

            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private void postSendPacket() {
        postSendPacket(0);
    }

    private void postSendPacket(long delayUs) {
        AMessage.obtain(WHAT_SEND_PACKET, this).post(delayUs);
    }

    private void postTimeout() {
        AMessage msg = AMessage.obtain(WHAT_TIMED_OUT, this);
        msg.setInt(GENERATION, mTimeoutGeneration);
        msg.post(TIMEOUT_DELAY_US);
    }

    private void cancelTimeout() {
        ++mTimeoutGeneration;
    }

    private void notifyError(int err) {
        if (mNotify == null) {
            getLooper().quit();
            return;
        }

        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_ERROR);
        notify.setInt(ERR, err);
        notify.post();
    }

    private void notifyOffset() {
        mHistory.sort(ROUND_TRIP_TIME_COMPARATOR);

        long sum = 0L;
        int count = 0;

        // Only consider the third of the information associated with the best
        // (smallest) roundtrip times.
        for (int i = 0; i < mHistory.size() / 3; ++i) {
            TimeInfo ti = mHistory.get(i);

//#if 0
            if (DEBUG) {
                // One way delay for a packet to travel from client
                // to server or back (assumed to be the same either way).
                long delay = (ti.mT2 - ti.mT1 + ti.mT4 - ti.mT3) / 2;
            }
//#endif

            // Offset between the client clock (T1, T4) and the
            // server clock (T2, T3) timestamps.
            long offset = (ti.mT2 - ti.mT1 - ti.mT4 + ti.mT3) / 2;

            Log.d(TAG, String.format("(%d) RT: %d us, offset: %d us", i, ti.mT4 - ti.mT1, offset));

            sum += offset;
            ++count;
        }

        if (mNotify == null) {
            Log.d(TAG, "avg. offset is " + (sum / count));
            return;
        }

        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_TIME_OFFSET);
        notify.setLong(OFFSET, sum / count);
        notify.post();
    }

    private static class TimeInfo {
        long mT1;  // client timestamp at send
        long mT2;  // server timestamp at receive
        long mT3;  // server timestamp at send
        long mT4;  // client timestamp at receive

        private ByteBuffer toByteBuffer() {
            ByteBuffer buf = ByteBuffer.allocate(8 * 4);
            buf.putLong(mT1).putLong(mT2).putLong(mT3).putLong(mT4);
            buf.limit(buf.position()).rewind();
            return buf;
        }

        private static TimeInfo fromByteBuffer(ByteBuffer buffer, int start) {
            TimeInfo timeInfo = new TimeInfo();
            timeInfo.mT1 = buffer.getLong(start);
            timeInfo.mT2 = buffer.getLong(start + 8);
            timeInfo.mT3 = buffer.getLong(start + 16);
            timeInfo.mT4 = buffer.getLong(start + 24);
            return timeInfo;
        }

        private static int getSize() {
            return 32;
        }
    }

    private static final Comparator<TimeInfo> ROUND_TRIP_TIME_COMPARATOR = (ti1, ti2) -> {
        long rt1 = ti1.mT4 - ti1.mT1;
        long rt2 = ti2.mT4 - ti2.mT1;

        if (rt1 < rt2) {
            return -1;
        } else if (rt1 > rt2) {
            return 1;
        }

        return 0;
    };
}
