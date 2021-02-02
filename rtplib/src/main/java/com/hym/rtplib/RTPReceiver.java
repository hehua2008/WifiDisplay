package com.hym.rtplib;

import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.foundation.AHandler;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.net.ANetworkSession;
import com.hym.rtplib.util.CheckUtils;
import com.hym.rtplib.util.RTPUtils;
import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class RTPReceiver extends AHandler implements RTPBase, MediaConstants, Errno {
    private static final String TAG = RTPReceiver.class.getSimpleName();
    private static final boolean DEBUG = true;
    private static final boolean TRACK_PACKET_LOSS = false;

    public static final int WHAT_INIT_DONE = 0;
    public static final int WHAT_ERROR = 1;
    public static final int WHAT_ACCESS_UNIT = 2;
    public static final int WHAT_PACKET_LOST = 3;

    public static final int FLAG_AUTO_CONNECT = 1;

    private static final int WHAT_RTP_NOTIFY = 0;
    private static final int WHAT_RTCP_NOTIFY = 1;
    private static final int WHAT_SEND_RR = 2;

    private static final int SOURCE_ID = 0xdeadbeef;
    private static final int PACKET_LOST_AFTER_US = 100_000;
    private static final int REQUEST_RETRANSMISSION_AFTER_US = -1;

    private final ANetworkSession mNetSession;
    private final AMessage mNotify;
    private int mFlags;
    private TransportMode mRTPMode;
    private TransportMode mRTCPMode;
    private int mRTPSessionID;
    private int mRTCPSessionID;
    private boolean mRTPConnected;
    private boolean mRTCPConnected;

    private int mRTPClientSessionID;  // in TRANSPORT_TCP mode.
    private int mRTCPClientSessionID;  // in TRANSPORT_TCP mode.

    private SparseArray<PacketizationMode> mPacketTypes;
    private SparseArray<Source> mSources;

    public RTPReceiver(ANetworkSession netSession, AMessage notify, int flags, Looper looper) {
        super(looper);
        mNetSession = netSession;
        mNotify = notify;
        mFlags = flags;
        mRTPMode = TransportMode.TRANSPORT_UNDEFINED;
        mRTCPMode = TransportMode.TRANSPORT_UNDEFINED;
        mRTPSessionID = 0;
        mRTCPSessionID = 0;
        mRTPConnected = false;
        mRTCPConnected = false;
        mRTPClientSessionID = 0;
        mRTCPClientSessionID = 0;
    }

    public int registerPacketType(int packetType, PacketizationMode mode) {
        mPacketTypes.put(packetType, mode);

        return OK;
    }

    public int initAsync(TransportMode rtpMode, TransportMode rtcpMode, final int[] localRTPPort) {
        if (mRTPMode != TransportMode.TRANSPORT_UNDEFINED
                || rtpMode == TransportMode.TRANSPORT_UNDEFINED
                || rtpMode == TransportMode.TRANSPORT_NONE
                || rtcpMode == TransportMode.TRANSPORT_UNDEFINED) {
            return INVALID_OPERATION;
        }

        CheckUtils.checkNotEqual(rtpMode, TransportMode.TRANSPORT_TCP_INTERLEAVED);
        CheckUtils.checkNotEqual(rtcpMode, TransportMode.TRANSPORT_TCP_INTERLEAVED);

        AMessage rtpNotify = AMessage.obtain(WHAT_RTP_NOTIFY, this);

        AMessage rtcpNotify = null;
        if (rtcpMode != TransportMode.TRANSPORT_NONE) {
            rtcpNotify = AMessage.obtain(WHAT_RTCP_NOTIFY, this);
        }

        CheckUtils.checkEqual(mRTPSessionID, 0);
        CheckUtils.checkEqual(mRTCPSessionID, 0);

        while (true) {
            localRTPPort[0] = RTPBase.pickRandomRTPPort();

            int err1;
            try {
                if (rtpMode == TransportMode.TRANSPORT_UDP) {
                    mRTPSessionID = mNetSession.createUDPSession(localRTPPort[0], rtpNotify);
                } else {
                    CheckUtils.checkEqual(rtpMode, TransportMode.TRANSPORT_TCP);
                    mRTPSessionID = mNetSession.createTCPDatagramSession(
                            RTPUtils.INET_ANY, localRTPPort[0], rtpNotify);
                }
                err1 = OK;
            } catch (IOException e) {
                err1 = -EIO;
                Log.e(TAG, "initAsync exception", e);
            }

            if (err1 != OK) {
                continue;
            }

            int err2;
            try {
                if (rtcpMode == TransportMode.TRANSPORT_NONE) {
                    break;
                } else if (rtcpMode == TransportMode.TRANSPORT_UDP) {
                    mRTCPSessionID = mNetSession.createUDPSession(localRTPPort[0] + 1, rtcpNotify);
                    err2 = OK;
                } else {
                    CheckUtils.checkEqual(rtpMode, TransportMode.TRANSPORT_TCP);
                    mRTCPSessionID = mNetSession.createTCPDatagramSession(
                            RTPUtils.INET_ANY, localRTPPort[0] + 1, rtcpNotify);
                    err2 = OK;
                }
            } catch (IOException e) {
                err2 = -EIO;
                Log.e(TAG, "initAsync exception", e);
            }

            if (err2 == OK) {
                break;
            }

            mNetSession.destroySession(mRTPSessionID);
            mRTPSessionID = 0;
        }

        mRTPMode = rtpMode;
        mRTCPMode = rtcpMode;

        return OK;
    }

    public int connect(String remoteHost, int remoteRTPPort, int remoteRTCPPort) {
        int err;

        if (mRTPMode == TransportMode.TRANSPORT_UDP) {
            CheckUtils.check(!mRTPConnected);

            err = mNetSession.connectUDPSession(mRTPSessionID, remoteHost, remoteRTPPort);

            if (err != OK) {
                notifyInitDone(err);
                return err;
            }

            Log.d(TAG, "connectUDPSession RTP successful");

            mRTPConnected = true;
        }

        if (mRTCPMode == TransportMode.TRANSPORT_UDP) {
            CheckUtils.check(!mRTCPConnected);

            err = mNetSession.connectUDPSession(mRTCPSessionID, remoteHost, remoteRTCPPort);

            if (err != OK) {
                notifyInitDone(err);
                return err;
            }

            scheduleSendRR();

            Log.d(TAG, "connectUDPSession RTCP successful");

            mRTCPConnected = true;
        }

        if (mRTPConnected && (mRTCPConnected || mRTCPMode == TransportMode.TRANSPORT_NONE)) {
            notifyInitDone(OK);
        }

        return OK;
    }

    public int informSender(AMessage params) {
        if (!mRTCPConnected) {
            return INVALID_OPERATION;
        }

        long avgLatencyUs = params.getLong(AVG_LATENCY_US);
        long maxLatencyUs = params.getLong(MAX_LATENCY_US);

        ABuffer buf = new ABuffer(28);
        ByteBuffer bufData = buf.data();

        bufData.put(0, (byte) (0x80 | 0));
        bufData.put(1, (byte) 204);  // APP
        bufData.put(2, (byte) 0);

        CheckUtils.check((buf.size() % 4) == 0);
        bufData.put(3, (byte) ((buf.size() / 4) - 1));

        bufData.put(4, (byte) (SOURCE_ID >>> 24));  // SSRC
        bufData.put(5, (byte) ((SOURCE_ID >>> 16) & 0xff));
        bufData.put(6, (byte) ((SOURCE_ID >>> 8) & 0xff));
        bufData.put(7, (byte) (SOURCE_ID & 0xff));
        bufData.put(8, (byte) 'l');
        bufData.put(9, (byte) 'a');
        bufData.put(10, (byte) 't');
        bufData.put(11, (byte) 'e');

        bufData.put(12, (byte) (avgLatencyUs >> 56));
        bufData.put(13, (byte) ((avgLatencyUs >> 48) & 0xff));
        bufData.put(14, (byte) ((avgLatencyUs >> 40) & 0xff));
        bufData.put(15, (byte) ((avgLatencyUs >> 32) & 0xff));
        bufData.put(16, (byte) ((avgLatencyUs >> 24) & 0xff));
        bufData.put(17, (byte) ((avgLatencyUs >> 16) & 0xff));
        bufData.put(18, (byte) ((avgLatencyUs >> 8) & 0xff));
        bufData.put(19, (byte) (avgLatencyUs & 0xff));

        bufData.put(20, (byte) (maxLatencyUs >> 56));
        bufData.put(21, (byte) ((maxLatencyUs >> 48) & 0xff));
        bufData.put(22, (byte) ((maxLatencyUs >> 40) & 0xff));
        bufData.put(23, (byte) ((maxLatencyUs >> 32) & 0xff));
        bufData.put(24, (byte) ((maxLatencyUs >> 24) & 0xff));
        bufData.put(25, (byte) ((maxLatencyUs >> 16) & 0xff));
        bufData.put(26, (byte) ((maxLatencyUs >> 8) & 0xff));
        bufData.put(27, (byte) (maxLatencyUs & 0xff));

        bufData.position(28);
        int err = mNetSession.sendRequest(mRTCPSessionID, buf.data(), buf.size());
        if (DEBUG) {
            Log.d(TAG, String.format("informSender session[%d] result[%d] >>>>>>>>>>>>",
                    mRTCPSessionID, err));
        }

        return OK;
    }

    protected void onMessageReceived(AMessage msg) {
        switch (msg.getWhat()) {
            case WHAT_RTP_NOTIFY:
            case WHAT_RTCP_NOTIFY:
                onNetNotify(msg.getWhat() == WHAT_RTP_NOTIFY, msg);
                break;

            case WHAT_SEND_RR: {
                onSendRR();
                break;
            }

            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private void onNetNotify(boolean isRTP, AMessage msg) {
        int reason = msg.getInt(REASON);

        switch (reason) {
            case ANetworkSession.WHAT_ERROR: {
                int sessionID = msg.getInt(SESSION_ID);
                int err = msg.getInt(ERR);
                boolean errorOccuredDuringSend = msg.getBoolean(SEND);
                String detail = msg.getThrow(DETAIL);

                Log.e(TAG, String.format("An error occurred during %s in session[%d] (%d, %s)",
                        errorOccuredDuringSend ? "send" : "receive",
                        sessionID, err, detail));

                mNetSession.destroySession(sessionID);

                if (sessionID == mRTPSessionID) {
                    mRTPSessionID = 0;
                } else if (sessionID == mRTCPSessionID) {
                    mRTCPSessionID = 0;
                } else if (sessionID == mRTPClientSessionID) {
                    mRTPClientSessionID = 0;
                } else if (sessionID == mRTCPClientSessionID) {
                    mRTCPClientSessionID = 0;
                }

                if (!mRTPConnected
                        || (mRTCPMode != TransportMode.TRANSPORT_NONE && !mRTCPConnected)) {
                    notifyInitDone(err);
                    break;
                }

                notifyError(err);
                break;
            }

            case ANetworkSession.WHAT_DATAGRAM: {
                ABuffer data = msg.getThrow(DATA);

                if (isRTP) {
                    if ((mFlags & FLAG_AUTO_CONNECT) != 0) {
                        String fromAddr = msg.getThrow(FROM_ADDR);
                        int fromPort = msg.getThrow(FROM_PORT);

                        CheckUtils.checkEqual(OK,
                                connect(fromAddr, fromPort, fromPort + 1));

                        mFlags &= ~FLAG_AUTO_CONNECT;
                    }

                    onRTPData(data);
                } else {
                    onRTCPData(data);
                }
                break;
            }

            case ANetworkSession.WHAT_CLIENT_CONNECTED: {
                int sessionID = msg.getInt(SESSION_ID);

                if (isRTP) {
                    CheckUtils.checkEqual(mRTPMode, TransportMode.TRANSPORT_TCP);

                    if (mRTPClientSessionID != 0) {
                        // We only allow a single client connection.
                        mNetSession.destroySession(sessionID);
                        sessionID = 0;
                        break;
                    }

                    mRTPClientSessionID = sessionID;
                    mRTPConnected = true;
                } else {
                    CheckUtils.checkEqual(mRTCPMode, TransportMode.TRANSPORT_TCP);

                    if (mRTCPClientSessionID != 0) {
                        // We only allow a single client connection.
                        mNetSession.destroySession(sessionID);
                        sessionID = 0;
                        break;
                    }

                    mRTCPClientSessionID = sessionID;
                    mRTCPConnected = true;
                }

                if (mRTPConnected
                        && (mRTCPConnected || mRTCPMode == TransportMode.TRANSPORT_NONE)) {
                    notifyInitDone(OK);
                }
                break;
            }
        }
    }

    private int onRTPData(ABuffer buffer) {
        int size = buffer.size();
        if (size < 12) {
            // Too short to be a valid RTP header.
            return ERROR_MALFORMED;
        }

        ByteBuffer data = buffer.data();
        int data0 = (data.get(0) & 0xFF);

        if ((data0 >>> 6) != 2) {
            // Unsupported version.
            return ERROR_UNSUPPORTED;
        }

        if ((data0 & 0x20) != 0) {
            // Padding present.

            int paddingLength = data.get(size - 1) & 0xFF;

            if (paddingLength + 12 > size) {
                // If we removed this much padding we'd end up with something
                // that's too short to be a valid RTP header.
                return ERROR_MALFORMED;
            }

            size -= paddingLength;
        }

        int numCSRCs = data0 & 0x0f;

        int payloadOffset = 12 + 4 * numCSRCs;

        if (size < payloadOffset) {
            // Not enough data to fit the basic header and all the CSRC entries.
            return ERROR_MALFORMED;
        }

        if ((data0 & 0x10) != 0) {
            // Header eXtension present.

            if (size < payloadOffset + 4) {
                // Not enough data to fit the basic header, all CSRC entries
                // and the first 4 bytes of the extension header.

                return ERROR_MALFORMED;
            }

            ByteBuffer extensionData =
                    ((ByteBuffer) data.duplicate().position(payloadOffset)).slice();

            int extensionLength =
                    4 * ((extensionData.get(2) & 0xFF) << 8 | (extensionData.get(3) & 0xFF));

            if (size < payloadOffset + 4 + extensionLength) {
                return ERROR_MALFORMED;
            }

            payloadOffset += 4 + extensionLength;
        }

        int srcId = RTPUtils.U32_AT(data, 8);
        int rtpTime = RTPUtils.U32_AT(data, 4);
        int seqNo = RTPUtils.U16_AT(data, 2);

        AMessage meta = buffer.meta();
        int data1 = data.get(1) & 0xFF;
        meta.setInt(SSRC, srcId);
        meta.setInt(RTP_TIME, rtpTime);
        meta.setInt(PT, data1 & 0x7f);
        meta.setInt(MARKER, data1 >>> 7);

        buffer.setRange(payloadOffset, size - payloadOffset);

        int index = mSources.indexOfKey(srcId);
        Source source;
        if (index < 0) {
            source = new Source(this, srcId);
            // Source.super(receiver.getLooper());

            mSources.put(srcId, source);
        } else {
            source = mSources.valueAt(index);
        }

        source.onPacketReceived(seqNo, buffer);

        return OK;
    }

    private int onRTCPData(ABuffer data) {
        Log.d(TAG, "onRTCPData");
        return OK;
    }

    private static final byte[] RR_BYTES = {
            (byte) (0x80 | 0),
            (byte) 201,  // RR
            (byte) 0,
            (byte) 1,
            (byte) (SOURCE_ID >>> 24),  // SSRC
            (byte) ((SOURCE_ID >>> 16) & 0xff),
            (byte) ((SOURCE_ID >>> 8) & 0xff),
            (byte) (SOURCE_ID & 0xff)
    };

    private void onSendRR() {
        ABuffer buf = new ABuffer(MAX_UDP_PACKET_SIZE);
        buf.setRange(0, 0);
        ByteBuffer bufData = buf.data();

        bufData.put(RR_BYTES);
        buf.setRange(0, 8);

        int numReportBlocks = 0;
        for (int i = 0; i < mSources.size(); ++i) {
            int ssrc = mSources.keyAt(i);
            Source source = mSources.valueAt(i);

            if (numReportBlocks > 31 || buf.size() + 24 > buf.capacity()) {
                // Cannot fit another report block.
                break;
            }

            source.addReportBlock(ssrc, buf);
            ++numReportBlocks;
        }

        bufData.put(0, (byte) ((bufData.get(0) | numReportBlocks) & 0xFF));  // 5 bit

        int sizeInWordsMinus1 = 1 + 6 * numReportBlocks;
        bufData.put(2, (byte) ((sizeInWordsMinus1 >>> 8) & 0xff));
        bufData.put(3, (byte) (sizeInWordsMinus1 & 0xff));

        buf.setRange(0, (sizeInWordsMinus1 + 1) * 4);

        addSDES(buf);

        int err = mNetSession.sendRequest(mRTCPSessionID, buf.data(), buf.size());
        if (DEBUG) {
            Log.d(TAG, String.format("onSendRR session[%d] result[%d] >>>>>>>>>>>>",
                    mRTCPSessionID, err));
        }

        scheduleSendRR();
    }

    private void scheduleSendRR() {
        AMessage.obtain(WHAT_SEND_RR, this).post(5_000_000L);
    }

    private void addSDES(ABuffer buffer) {
        ByteBuffer bufData = ((ByteBuffer) buffer.data().position(buffer.size())).slice();

        bufData.put(0, (byte) (0x80 | 1));
        bufData.put(1, (byte) 202);  // SDES
        // bufData.put(2, (byte) 0);
        // bufData.put(3, (byte) 0);
        bufData.put(4, (byte) (SOURCE_ID >>> 24));  // SSRC
        bufData.put(5, (byte) ((SOURCE_ID >>> 16) & 0xff));
        bufData.put(6, (byte) ((SOURCE_ID >>> 8) & 0xff));
        bufData.put(7, (byte) (SOURCE_ID & 0xff));

        bufData.position(8);
        //int offset = 8;

        bufData.put((byte) 1);  // CNAME

        String cname = "stagefright@somewhere";
        ByteBuffer cnameBuf = StandardCharsets.UTF_8.encode(cname);
        bufData.put((byte) cnameBuf.remaining());
        bufData.put(cnameBuf);

        bufData.put((byte) 6);  // TOOL

        String tool = "stagefright/1.0";
        ByteBuffer toolBuf = StandardCharsets.UTF_8.encode(tool);
        bufData.put((byte) toolBuf.remaining());
        bufData.put(toolBuf);

        bufData.put((byte) 0);

        int mod = bufData.position() % 4;
        if (mod > 0) {
            int count = 4 - mod;
            switch (count) {
                case 3:
                    bufData.put((byte) 0);
                case 2:
                    bufData.put((byte) 0);
                case 1:
                    bufData.put((byte) 0);
            }
        }

        int numWords = (bufData.position() / 4) - 1;
        bufData.put(2, (byte) (numWords >>> 8));
        bufData.put(3, (byte) (numWords & 0xff));

        buffer.setRange(buffer.offset(), buffer.size() + bufData.position());
    }

    private void notifyInitDone(int err) {
        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_INIT_DONE);
        notify.setInt(ERR, err);
        notify.post();
    }

    private void notifyError(int err) {
        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_ERROR);
        notify.setInt(ERR, err);
        notify.post();
    }

    private void notifyPacketLost() {
        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_PACKET_LOST);
        notify.post();
    }

    private RTPAssembler makeAssembler(int packetType) {
        int index = mPacketTypes.indexOfKey(packetType);
        if (index < 0) {
            return null;
        }

        PacketizationMode mode = mPacketTypes.valueAt(index);

        switch (mode) {
            case PACKETIZATION_NONE:
            case PACKETIZATION_TRANSPORT_STREAM:
                return new RTPAssembler.TSAssembler(mNotify);

            case PACKETIZATION_H264:
                return new RTPAssembler.H264Assembler(mNotify);

            default:
                return null;
        }
    }

    private void requestRetransmission(int senderSSRC, int extSeqNo) {
        int blp = 0;

        ABuffer buf = new ABuffer(16);
        buf.setRange(0, 0);
        ByteBuffer bufData = buf.data();

        bufData.put(0, (byte) (0x80 | 1));  // generic NACK
        bufData.put(1, (byte) 205);  // TSFB
        bufData.put(2, (byte) 0);
        bufData.put(3, (byte) 3);
        // FIXME ? (8 9 10 11) or (4 5 6 7) ?
        bufData.put(4, (byte) ((senderSSRC >>> 24) & 0xff));
        bufData.put(5, (byte) ((senderSSRC >>> 16) & 0xff));
        bufData.put(6, (byte) ((senderSSRC >>> 8) & 0xff));
        bufData.put(7, (byte) ((senderSSRC & 0xff)));
        bufData.put(8, (byte) ((SOURCE_ID >>> 24) & 0xff));
        bufData.put(9, (byte) ((SOURCE_ID >>> 16) & 0xff));
        bufData.put(10, (byte) ((SOURCE_ID >>> 8) & 0xff));
        bufData.put(11, (byte) ((SOURCE_ID & 0xff)));
        bufData.put(12, (byte) ((extSeqNo >> 8) & 0xff));
        bufData.put(13, (byte) ((extSeqNo & 0xff)));
        bufData.put(14, (byte) ((blp >> 8) & 0xff));
        bufData.put(15, (byte) ((blp & 0xff)));

        bufData.position(16);
        buf.setRange(0, 16);

        int err = mNetSession.sendRequest(mRTCPSessionID, buf.data(), buf.size());
        if (DEBUG) {
            Log.d(TAG, String.format("requestRetransmission session[%d] result[%d] >>>>>>>>>>>>",
                    mRTCPSessionID, err));
        }
    }

    public static class Source extends AHandler {
        private static final int WHAT_RETRANSMIT = 0;
        private static final int WHAT_DECLARE_LOST = 1;

        private static final int MIN_SEQUENTIAL = 2;
        private static final int MAX_DROPOUT = 3000;
        private static final int MAX_MISORDER = 100;
        private static final int RTP_SEQ_MOD = 1 << 16;
        private static final long REPORT_INTERVAL_US = 10_000_000L;

        private final RTPReceiver mReceiver;
        private final int mSSRC;
        private boolean mFirst;
        private int mMaxSeq;
        private int mCycles;
        private int mBaseSeq;
        private int mReceived;
        private int mExpectedPrior;
        private int mReceivedPrior;

        private long mFirstArrivalTimeUs;
        private long mFirstRTPTimeUs;

        // Ordered by extended seq number.
        private List<ABuffer> mPackets;

        // StatusBits
        private static final int STATUS_DECLARED_LOST = 1;
        private static final int STATUS_REQUESTED_RETRANSMISSION = 2;
        private static final int STATUS_ARRIVED_LATE = 4;

        //#if TRACK_PACKET_LOSS
        private SparseIntArray mLostPackets;
//#endif

        private int mAwaitingExtSeqNo;
        private boolean mRequestedRetransmission;

        private int mActivePacketType;
        private RTPAssembler mActiveAssembler;

        private long mNextReportTimeUs;

        private int mNumDeclaredLost;
        private int mNumDeclaredLostPrior;

        private int mRetransmitGeneration;
        private int mDeclareLostGeneration;
        private boolean mDeclareLostTimerPending;

        public Source(RTPReceiver receiver, int ssrc) {
            super(receiver.getLooper());
            mReceiver = receiver;
            mSSRC = ssrc;
            mFirst = true;
            mMaxSeq = 0;
            mCycles = 0;
            mBaseSeq = 0;
            mReceived = 0;
            mExpectedPrior = 0;
            mReceivedPrior = 0;
            mFirstArrivalTimeUs = -1L;
            mFirstRTPTimeUs = -1L;
            mAwaitingExtSeqNo = -1;
            mRequestedRetransmission = false;
            mActivePacketType = -1;
            mNextReportTimeUs = -1L;
            mNumDeclaredLost = 0;
            mNumDeclaredLostPrior = 0;
            mRetransmitGeneration = 0;
            mDeclareLostGeneration = 0;
            mDeclareLostTimerPending = false;
        }

        public void onPacketReceived(int seq, ABuffer buffer) {
            if (mFirst) {
                buffer.setInt32Data(mCycles | seq);
                queuePacket(buffer);

                mFirst = false;
                mBaseSeq = seq;
                mMaxSeq = seq;
                ++mReceived;
                return;
            }

            int udelta = seq - mMaxSeq;

            if (udelta < MAX_DROPOUT) {
                // In order, with permissible gap.

                if (seq < mMaxSeq) {
                    // Sequence number wrapped - count another 64K cycle
                    mCycles += RTP_SEQ_MOD;
                }

                mMaxSeq = seq;

                ++mReceived;
            } else if (udelta <= RTP_SEQ_MOD - MAX_MISORDER) {
                // The sequence number made a very large jump
                return;
            } else {
                // Duplicate or reordered packet.
            }

            buffer.setInt32Data(mCycles | seq);
            queuePacket(buffer);
        }

        public void addReportBlock(int ssrc, ABuffer buf) {
            int extMaxSeq = mMaxSeq | mCycles;
            int expected = extMaxSeq - mBaseSeq + 1;

            long lost = (long) expected - (long) mReceived;
            if (lost > 0x7fffff) {
                lost = 0x7fffff;
            } else if (lost < -0x800000) {
                lost = -0x800000;
            }

            int expectedInterval = expected - mExpectedPrior;
            mExpectedPrior = expected;

            int receivedInterval = mReceived - mReceivedPrior;
            mReceivedPrior = mReceived;

            long lostInterval = expectedInterval - receivedInterval;

            byte fractionLost;
            if (expectedInterval == 0 || lostInterval <= 0) {
                fractionLost = 0;
            } else {
                fractionLost = (byte) ((lostInterval << 8) / expectedInterval);
            }

            ByteBuffer bufData = ((ByteBuffer) buf.data().position(buf.size())).slice();

            bufData.put(0, (byte) (ssrc >>> 24));
            bufData.put(1, (byte) ((ssrc >>> 16) & 0xff));
            bufData.put(2, (byte) ((ssrc >>> 8) & 0xff));
            bufData.put(3, (byte) (ssrc & 0xff));

            bufData.put(4, fractionLost);

            bufData.put(5, (byte) ((lost >> 16) & 0xff));
            bufData.put(6, (byte) ((lost >> 8) & 0xff));
            bufData.put(7, (byte) (lost & 0xff));

            bufData.put(8, (byte) (extMaxSeq >>> 24));
            bufData.put(9, (byte) ((extMaxSeq >>> 16) & 0xff));
            bufData.put(10, (byte) ((extMaxSeq >>> 8) & 0xff));
            bufData.put(11, (byte) (extMaxSeq & 0xff));

            // XXX TODO:

            bufData.put(12, (byte) 0x00);  // interarrival jitter
            bufData.put(13, (byte) 0x00);
            bufData.put(14, (byte) 0x00);
            bufData.put(15, (byte) 0x00);

            bufData.put(16, (byte) 0x00);  // last SR
            bufData.put(17, (byte) 0x00);
            bufData.put(18, (byte) 0x00);
            bufData.put(19, (byte) 0x00);

            bufData.put(20, (byte) 0x00);  // delay since last SR
            bufData.put(21, (byte) 0x00);
            bufData.put(22, (byte) 0x00);
            bufData.put(23, (byte) 0x00);

            bufData.position(24);
            buf.setRange(buf.offset(), buf.size() + 24);
        }

        protected void onMessageReceived(AMessage msg) {
            switch (msg.getWhat()) {
                case WHAT_RETRANSMIT: {
                    int generation = msg.getInt(GENERATION);

                    if (generation != mRetransmitGeneration) {
                        break;
                    }

                    mRequestedRetransmission = true;
                    mReceiver.requestRetransmission(mSSRC, mAwaitingExtSeqNo);

                    modifyPacketStatus(mAwaitingExtSeqNo, STATUS_REQUESTED_RETRANSMISSION);
                    break;
                }

                case WHAT_DECLARE_LOST: {
                    int generation = msg.getInt(GENERATION);

                    if (generation != mDeclareLostGeneration) {
                        break;
                    }

                    cancelTimers();

//#if TRACK_PACKET_LOSS
                    if (TRACK_PACKET_LOSS) {
                        Log.d(TAG, String.format("Lost packet extSeqNo %d %s",
                                mAwaitingExtSeqNo, mRequestedRetransmission ? '*' : ""));
                    }
//#endif

                    mRequestedRetransmission = false;
                    if (mActiveAssembler != null) {
                        mActiveAssembler.signalDiscontinuity();
                    }

                    modifyPacketStatus(mAwaitingExtSeqNo, STATUS_DECLARED_LOST);

                    // resync();
                    ++mAwaitingExtSeqNo;
                    ++mNumDeclaredLost;

                    mReceiver.notifyPacketLost();

                    dequeueMore();
                    break;
                }

                default:
                    throw new RuntimeException("TRESPASS");
            }
        }

        private void queuePacket(ABuffer packet) {
            int newExtendedSeqNo = packet.getInt32Data();

            if (mFirstArrivalTimeUs < 0L) {
                mFirstArrivalTimeUs = TimeUtils.getMonotonicMicroTime();

                int rtpTime = packet.meta().getInt(RTP_TIME);

                mFirstRTPTimeUs = (rtpTime * 100L) / 9L;
            }

            if (mAwaitingExtSeqNo >= 0 && newExtendedSeqNo < mAwaitingExtSeqNo) {
                // We're no longer interested in these. They're old.
                Log.d(TAG, "dropping stale extSeqNo " + newExtendedSeqNo);

                modifyPacketStatus(newExtendedSeqNo, STATUS_ARRIVED_LATE);
                return;
            }

            if (mPackets.isEmpty()) {
                mPackets.add(packet);
                dequeueMore();
                return;
            }

            for (int i = mPackets.size() - 1; i >= 0; i--) {
                int extendedSeqNo = mPackets.get(i).getInt32Data();

                if (extendedSeqNo == newExtendedSeqNo) {
                    // Duplicate packet.
                    return;
                }

                if (extendedSeqNo < newExtendedSeqNo) {
                    // Insert new packet after the one at "i".
                    mPackets.add(++i, packet);
                    break;
                }

                if (i == 0) {
                    // Insert new packet before the first existing one.
                    mPackets.add(0, packet);
                    break;
                }
            }

            dequeueMore();
        }

        private void dequeueMore() {
            long nowUs = TimeUtils.getMonotonicMicroTime();
            if (mNextReportTimeUs < 0L || nowUs >= mNextReportTimeUs) {
                if (mNextReportTimeUs >= 0L) {
                    int expected = (mMaxSeq | mCycles) - mBaseSeq + 1;

                    int expectedInterval = expected - mExpectedPrior;
                    mExpectedPrior = expected;

                    int receivedInterval = mReceived - mReceivedPrior;
                    mReceivedPrior = mReceived;

                    long lostInterval = (long) expectedInterval - (long) receivedInterval;

                    int declaredLostInterval = mNumDeclaredLost - mNumDeclaredLostPrior;

                    mNumDeclaredLostPrior = mNumDeclaredLost;

                    if (declaredLostInterval > 0) {
                        Log.d(TAG, String.format("lost %d packets (%.2f %%), declared %d lost",
                                lostInterval,
                                100.0f * lostInterval / expectedInterval,
                                declaredLostInterval));
                    }
                }

                mNextReportTimeUs = nowUs + REPORT_INTERVAL_US;

//#if TRACK_PACKET_LOSS
                if (TRACK_PACKET_LOSS) {
                    for (int i = 0; i < mLostPackets.size(); ++i) {
                        int key = mLostPackets.keyAt(i);
                        int value = mLostPackets.valueAt(i);

                        StringBuilder status = new StringBuilder();
                        if ((value & STATUS_REQUESTED_RETRANSMISSION) != 0) {
                            status.append("retrans ");
                        }
                        if ((value & STATUS_ARRIVED_LATE) != 0) {
                            status.append("arrived-late ");
                        }
                        Log.d(TAG, String.format("Packet %d declared lost %s", key, status));
                    }
                }
//#endif
            }

            ABuffer packet;
            while ((packet = getNextPacket()) != null) {
                if (mDeclareLostTimerPending) {
                    cancelTimers();
                }

                CheckUtils.checkGreaterOrEqual(mAwaitingExtSeqNo, 0);
//#if TRACK_PACKET_LOSS
                if (TRACK_PACKET_LOSS) {
                    mLostPackets.delete(mAwaitingExtSeqNo);
                }
//#endif

                int packetType = packet.meta().getInt(PT);

                if (packetType != mActivePacketType) {
                    mActiveAssembler = mReceiver.makeAssembler(packetType);
                    mActivePacketType = packetType;
                }

                if (mActiveAssembler != null) {
                    int err = mActiveAssembler.processPacket(packet);
                    if (err != OK) {
                        Log.d(TAG, "assembler returned error " + err);
                    }
                }

                ++mAwaitingExtSeqNo;
            }

            if (mDeclareLostTimerPending) {
                return;
            }

            if (mPackets.isEmpty()) {
                return;
            }

            CheckUtils.checkGreaterOrEqual(mAwaitingExtSeqNo, 0);
            ABuffer firstPacket = mPackets.get(0);
            int rtpTime = firstPacket.meta().getInt(RTP_TIME);
            long rtpUs = (rtpTime * 100L) / 9L;
            long maxArrivalTimeUs = mFirstArrivalTimeUs + rtpUs - mFirstRTPTimeUs;
            nowUs = TimeUtils.getMonotonicMicroTime();
            CheckUtils.checkLessThan(mAwaitingExtSeqNo, firstPacket.getInt32Data());

//#if TRACK_PACKET_LOSS
            if (TRACK_PACKET_LOSS) {
                Log.d(TAG, String.format("waiting for %d, comparing against %d, %d us left",
                        mAwaitingExtSeqNo,
                        firstPacket.getInt32Data(),
                        maxArrivalTimeUs - nowUs));
            }
//#endif

            postDeclareLostTimer(maxArrivalTimeUs + PACKET_LOST_AFTER_US);

            if (REQUEST_RETRANSMISSION_AFTER_US > 0L) {
                postRetransmitTimer(maxArrivalTimeUs + REQUEST_RETRANSMISSION_AFTER_US);
            }
        }

        private ABuffer getNextPacket() {
            if (mPackets.isEmpty()) {
                return null;
            }

            ABuffer packet = mPackets.get(0);
            int extSeqNo = packet.getInt32Data();

            if (mAwaitingExtSeqNo < 0) {
                mAwaitingExtSeqNo = extSeqNo;
            } else if (extSeqNo != mAwaitingExtSeqNo) {
                return null;
            }

            mPackets.remove(0);

            return packet;
        }

        private void resync() {
            mAwaitingExtSeqNo = -1;
        }

        private void modifyPacketStatus(int extSeqNo, int mask) {
//#if TRACK_PACKET_LOSS
            if (TRACK_PACKET_LOSS) {
                int index = mLostPackets.indexOfKey(extSeqNo);
                if (index < 0) {
                    mLostPackets.put(extSeqNo, mask);
                } else {
                    //mLostPackets.setValueAt(index, mLostPackets.valueAt(index) | mask);
                    mLostPackets.put(extSeqNo, mLostPackets.valueAt(index) | mask);
                }
            }
//#endif
        }

        private void postRetransmitTimer(long timeUs) {
            long delayUs = timeUs - TimeUtils.getMonotonicMicroTime();
            AMessage msg = AMessage.obtain(WHAT_RETRANSMIT, this);
            msg.setInt(GENERATION, mRetransmitGeneration);
            msg.post(delayUs);
        }

        private void postDeclareLostTimer(long timeUs) {
            CheckUtils.check(!mDeclareLostTimerPending);
            mDeclareLostTimerPending = true;

            long delayUs = timeUs - TimeUtils.getMonotonicMicroTime();
            AMessage msg = AMessage.obtain(WHAT_DECLARE_LOST, this);
            msg.setInt(GENERATION, mDeclareLostGeneration);
            msg.post(delayUs);
        }

        private void cancelTimers() {
            ++mRetransmitGeneration;
            ++mDeclareLostGeneration;
            mDeclareLostTimerPending = false;
        }
    }
}
