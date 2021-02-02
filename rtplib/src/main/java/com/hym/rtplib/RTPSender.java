package com.hym.rtplib;

import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.foundation.AHandler;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.net.ANetworkSession;
import com.hym.rtplib.util.AvcUtils;
import com.hym.rtplib.util.CheckUtils;
import com.hym.rtplib.util.RTPUtils;
import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class RTPSender extends AHandler implements RTPBase, MediaConstants, Errno {
    private static final String TAG = RTPSender.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int WHAT_INIT_DONE = 0;
    public static final int WHAT_ERROR = 1;
    public static final int WHAT_NETWORK_STALL = 2;
    public static final int WHAT_INFORM_SENDER = 3;

    private static final int WHAT_RTP_NOTIFY = 0;
    private static final int WHAT_RTCP_NOTIFY = 1;

    private static final int MAX_NUM_TS_PACKETS_PER_RTP_PACKET = (MAX_UDP_PACKET_SIZE - 12) / 188;
    private static final int MAX_HISTORY_SIZE = 1024;
    private static final int SOURCE_ID = 0xdeadbeef;

    private final ANetworkSession mNetSession;
    private final AMessage mNotify;
    private TransportMode mRTPMode;
    private TransportMode mRTCPMode;
    private int mRTPSessionID;
    private int mRTCPSessionID;
    private boolean mRTPConnected;
    private boolean mRTCPConnected;

    private long mLastNTPTime;
    private int mLastRTPTime;
    private int mNumRTPSent;
    private int mNumRTPOctetsSent;
    private final int mNumSRsSent;

    private int mRTPSeqNo;

    private final Deque<ABuffer> mHistory = new LinkedList<>();
    private int mHistorySize;

    public RTPSender(ANetworkSession netSession, AMessage notify, Looper looper) {
        super(looper);
        mNetSession = netSession;
        mNotify = notify;
        mRTPMode = TransportMode.TRANSPORT_UNDEFINED;
        mRTCPMode = TransportMode.TRANSPORT_UNDEFINED;
        mRTPSessionID = 0;
        mRTCPSessionID = 0;
        mRTPConnected = false;
        mRTCPConnected = false;
        mLastNTPTime = 0;
        mLastRTPTime = 0;
        mNumRTPSent = 0;
        mNumRTPOctetsSent = 0;
        mNumSRsSent = 0;
        mRTPSeqNo = 0;
        mHistorySize = 0;
    }

    public int initAsync(String remoteHost, int remoteRTPPort, TransportMode rtpMode,
            int remoteRTCPPort, TransportMode rtcpMode, final int[] localRTPPort) {
        if (mRTPMode != TransportMode.TRANSPORT_UNDEFINED
                || rtpMode == TransportMode.TRANSPORT_UNDEFINED
                || rtpMode == TransportMode.TRANSPORT_NONE
                || rtcpMode == TransportMode.TRANSPORT_UNDEFINED) {
            return INVALID_OPERATION;
        }

        CheckUtils.checkNotEqual(rtpMode, TransportMode.TRANSPORT_TCP_INTERLEAVED);
        CheckUtils.checkNotEqual(rtcpMode, TransportMode.TRANSPORT_TCP_INTERLEAVED);

        if ((rtcpMode == TransportMode.TRANSPORT_NONE && remoteRTCPPort >= 0)
                || (rtcpMode != TransportMode.TRANSPORT_NONE && remoteRTCPPort < 0)) {
            return INVALID_OPERATION;
        }

        AMessage rtpNotify = AMessage.obtain(WHAT_RTP_NOTIFY, this);

        AMessage rtcpNotify = null;
        if (remoteRTCPPort >= 0) {
            rtcpNotify = AMessage.obtain(WHAT_RTCP_NOTIFY, this);
        }

        CheckUtils.checkEqual(mRTPSessionID, 0);
        CheckUtils.checkEqual(mRTCPSessionID, 0);

        while (true) {
            localRTPPort[0] = RTPBase.pickRandomRTPPort();

            int err1;
            try {
                if (rtpMode == TransportMode.TRANSPORT_UDP) {
                    mRTPSessionID = mNetSession.createUDPSession(
                            localRTPPort[0], remoteHost, remoteRTPPort, rtpNotify);
                } else {
                    CheckUtils.checkEqual(rtpMode, TransportMode.TRANSPORT_TCP);
                    mRTPSessionID = mNetSession.createTCPDatagramSession(
                            localRTPPort[0], remoteHost, remoteRTPPort, rtpNotify);
                }
                err1 = OK;
            } catch (IOException e) {
                err1 = -EIO;
                Log.e(TAG, "initAsync exception", e);
            }

            if (err1 != OK) {
                continue;
            }

            if (remoteRTCPPort < 0) {
                break;
            }

            int err2;
            try {
                if (rtcpMode == TransportMode.TRANSPORT_UDP) {
                    mRTCPSessionID = mNetSession.createUDPSession(
                            localRTPPort[0] + 1, remoteHost, remoteRTCPPort, rtcpNotify);
                } else {
                    CheckUtils.checkEqual(rtcpMode, TransportMode.TRANSPORT_TCP);
                    mRTCPSessionID = mNetSession.createTCPDatagramSession(
                            localRTPPort[0] + 1, remoteHost, remoteRTCPPort, rtcpNotify);
                }
                err2 = OK;
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

        if (rtpMode == TransportMode.TRANSPORT_UDP) {
            mRTPConnected = true;
        }

        if (rtcpMode == TransportMode.TRANSPORT_UDP) {
            mRTCPConnected = true;
        }

        mRTPMode = rtpMode;
        mRTCPMode = rtcpMode;

        if (mRTPMode == TransportMode.TRANSPORT_UDP
                && (mRTCPMode == TransportMode.TRANSPORT_UDP
                || mRTCPMode == TransportMode.TRANSPORT_NONE)) {
            notifyInitDone(OK);
        }

        return OK;
    }

    public int queueBuffer(ABuffer buffer, int packetType, PacketizationMode mode) {
        int err;

        switch (mode) {
            case PACKETIZATION_NONE:
                err = queueRawPacket(buffer, packetType);
                break;

            case PACKETIZATION_TRANSPORT_STREAM:
                err = queueTSPackets(buffer, packetType);
                break;

            case PACKETIZATION_H264:
                err = queueAVCBuffer(buffer, packetType);
                break;

            default:
                throw new RuntimeException("TRESPASS");
        }

        return err;
    }

    protected void onMessageReceived(AMessage msg) {
        switch (msg.getWhat()) {
            case WHAT_RTP_NOTIFY:
            case WHAT_RTCP_NOTIFY:
                onNetNotify(msg.getWhat() == WHAT_RTP_NOTIFY, msg);
                break;

            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private static long getNowNTP() {
        // FIXME ?
        long nowUs = System.currentTimeMillis() * 1000L;

        nowUs += ((70L * 365 + 17) * 24) * 60 * 60 * 1_000_000L;

        long hi = nowUs / 1_000_000L;
        long lo = ((1L << 32) * (nowUs % 1_000_000L)) / 1_000_000L;

        return (hi << 32) | lo;
    }

    private int queueRawPacket(ABuffer tsPackets, int packetType) {
        CheckUtils.checkLessOrEqual(tsPackets.size(), MAX_UDP_PACKET_SIZE - 12);

        long timeUs = tsPackets.meta().getLong(TIME_US);
        ABuffer udpPacket = new ABuffer(12 + tsPackets.size());
        udpPacket.setInt32Data(mRTPSeqNo);
        ByteBuffer udpData = udpPacket.data();

        udpData.put(0, (byte) 0x80);
        udpData.put(1, (byte) packetType);

        udpData.put(2, (byte) ((mRTPSeqNo >>> 8) & 0xff));
        udpData.put(3, (byte) (mRTPSeqNo & 0xff));
        ++mRTPSeqNo;

        int rtpTime = (int) ((timeUs * 9) / 100L);

        udpData.put(4, (byte) (rtpTime >>> 24));
        udpData.put(5, (byte) ((rtpTime >>> 16) & 0xff));
        udpData.put(6, (byte) ((rtpTime >>> 8) & 0xff));
        udpData.put(7, (byte) (rtpTime & 0xff));

        udpData.put(8, (byte) (SOURCE_ID >>> 24));
        udpData.put(9, (byte) ((SOURCE_ID >>> 16) & 0xff));
        udpData.put(10, (byte) ((SOURCE_ID >>> 8) & 0xff));
        udpData.put(11, (byte) (SOURCE_ID & 0xff));

        udpData.position(12);

        ByteBuffer tsData = (ByteBuffer) tsPackets.data().limit(tsPackets.size());
        udpData.put(tsData);

        return sendRTPPacket(
                udpPacket,
                true /* storeInHistory */,
                true /* timeValid */,
                TimeUtils.getMonotonicMicroTime());
    }

    private int queueTSPackets(ABuffer tsPackets, int packetType) {
        CheckUtils.checkEqual(0, tsPackets.size() % 188);

        long timeUs = tsPackets.meta().getLong(TIME_US);

        int srcOffset = 0;
        while (srcOffset < tsPackets.size()) {
            ABuffer udpPacket = getABuffer(TS_ABUFS);

            udpPacket.setInt32Data(mRTPSeqNo);

            ByteBuffer rtp = udpPacket.data();
            rtp.put(0, (byte) 0x80);
            rtp.put(1, (byte) packetType);

            rtp.put(2, (byte) ((mRTPSeqNo >>> 8) & 0xff));
            rtp.put(3, (byte) (mRTPSeqNo & 0xff));
            ++mRTPSeqNo;

            long nowUs = TimeUtils.getMonotonicMicroTime();
            int rtpTime = (int) ((nowUs * 9) / 100L);

            rtp.put(4, (byte) (rtpTime >>> 24));
            rtp.put(5, (byte) ((rtpTime >>> 16) & 0xff));
            rtp.put(6, (byte) ((rtpTime >>> 8) & 0xff));
            rtp.put(7, (byte) (rtpTime & 0xff));

            rtp.put(8, (byte) (SOURCE_ID >>> 24));
            rtp.put(9, (byte) ((SOURCE_ID >>> 16) & 0xff));
            rtp.put(10, (byte) ((SOURCE_ID >>> 8) & 0xff));
            rtp.put(11, (byte) (SOURCE_ID & 0xff));

            rtp.position(12);

            int numTSPackets = (tsPackets.size() - srcOffset) / 188;
            if (numTSPackets > MAX_NUM_TS_PACKETS_PER_RTP_PACKET) {
                numTSPackets = MAX_NUM_TS_PACKETS_PER_RTP_PACKET;
            }

            ByteBuffer tsData = tsPackets.data();
            tsData.position(srcOffset).limit(srcOffset + numTSPackets * 188);
            rtp.put(tsData);

            udpPacket.setRange(0, 12 + numTSPackets * 188);

            srcOffset += numTSPackets * 188;
            boolean isLastPacket = (srcOffset == tsPackets.size());

            int err = sendRTPPacket(
                    udpPacket,
                    true /* storeInHistory */,
                    isLastPacket /* timeValid */,
                    timeUs);

            if (err != OK) {
                recycleABuffer(udpPacket);

                return err;
            }
        }

        return OK;
    }

    // Map value: true for in using state, false otherwise
    private static final Map<ABuffer, Boolean> TS_ABUFS = new ArrayMap<>();
    private static final Map<ABuffer, Boolean> AVC_ABUFS = new ArrayMap<>();

    private static ABuffer getABuffer(Map<ABuffer, Boolean> map) {
        if (map == null || (map != TS_ABUFS && map != AVC_ABUFS)) {
            return null;
        }
        synchronized (map) {
            ABuffer candidate = null;

            for (Map.Entry<ABuffer, Boolean> entry : map.entrySet()) {
                if (!entry.getValue()) {
                    entry.setValue(true);
                    candidate = entry.getKey();
                    if (DEBUG) {
                        Log.w(TAG, "cache hit ABuffer " + candidate + ' ' + candidate.capacity());
                    }
                    break;
                }
            }

            if (candidate == null) {
                if (map == TS_ABUFS) {
                    candidate = new ABuffer(12 + MAX_NUM_TS_PACKETS_PER_RTP_PACKET * 188);
                } else if (map == AVC_ABUFS) {
                    candidate = new ABuffer(MAX_UDP_PACKET_SIZE);
                }
                if (DEBUG) {
                    Log.w(TAG, "new ABuffer " + candidate + ' ' + candidate.capacity());
                }
                map.put(candidate, true);
            }

            return candidate;
        }
    }

    private static void recycleABuffer(ABuffer buffer) {
        if (buffer == null) {
            return;
        }
        buffer.reset();
        synchronized (TS_ABUFS) {
            if (TS_ABUFS.computeIfPresent(buffer, (key, oldValue) -> false) != null) {
                if (DEBUG) {
                    Log.w(TAG, "recycle ABuffer " + buffer + ' ' + buffer.capacity());
                }
                return;
            }
        }
        synchronized (AVC_ABUFS) {
            if (AVC_ABUFS.computeIfPresent(buffer, (key, oldValue) -> false) != null) {
                if (DEBUG) {
                    Log.w(TAG, "recycle ABuffer " + buffer + ' ' + buffer.capacity());
                }
                return;
            }
        }
    }

    private int queueAVCBuffer(ABuffer accessUnit, int packetType) {
        long timeUs = accessUnit.meta().getLong(TIME_US);
        int rtpTime = (int) (timeUs * 9 / 100L);
        List<ABuffer> packets = new LinkedList<>();
        ABuffer out = getABuffer(AVC_ABUFS);
        int outBytesUsed = 12;  // Placeholder for RTP header.

        ByteBuffer data = accessUnit.data();
        int size = accessUnit.size();
        final ByteBuffer[] inOutData = new ByteBuffer[]{data};
        final int[] inOutSize = new int[]{size};
        final ByteBuffer[] nalStart = new ByteBuffer[1];
        final int[] nalSize = new int[1];
        while (AvcUtils.getNextNALUnit(inOutData, inOutSize, nalStart, nalSize,
                true /* startCodeFollows */) == OK) {
            int bytesNeeded = nalSize[0] + 2;
            if (outBytesUsed == 12) {
                ++bytesNeeded;
            }

            if (outBytesUsed + bytesNeeded > out.capacity()) {
                boolean emitSingleNALPacket = false;

                if (outBytesUsed == 12 && outBytesUsed + nalSize[0] <= out.capacity()) {
                    // We haven't emitted anything into the current packet yet and
                    // this NAL unit fits into a single-NAL-unit-packet while
                    // it wouldn't have fit as part of a STAP-A packet.

                    ByteBuffer outData = (ByteBuffer) out.data().position(outBytesUsed);
                    ByteBuffer nalData = (ByteBuffer) nalStart[0].rewind().limit(nalSize[0]);
                    outData.put(nalData);
                    outBytesUsed += nalSize[0];

                    emitSingleNALPacket = true;
                }

                if (outBytesUsed > 12) {
                    out.setRange(0, outBytesUsed);
                    packets.add(out);
                    out = getABuffer(AVC_ABUFS);
                    outBytesUsed = 12;  // Placeholder for RTP header
                }

                if (emitSingleNALPacket) {
                    continue;
                }
            }

            if (outBytesUsed + bytesNeeded <= out.capacity()) {
                ByteBuffer dst = ((ByteBuffer) out.data().position(outBytesUsed)).slice();

                if (outBytesUsed == 12) {
                    dst.put((byte) 24);  // STAP-A header
                }

                dst.put((byte) ((nalSize[0] >>> 8) & 0xff));
                dst.put((byte) (nalSize[0] & 0xff));
                ByteBuffer nalData = (ByteBuffer) nalStart[0].rewind().limit(nalSize[0]);
                dst.put(nalData);

                outBytesUsed += bytesNeeded;
                continue;
            }

            // This single NAL unit does not fit into a single RTP packet,
            // we need to emit an FU-A.

            CheckUtils.checkEqual(outBytesUsed, 12);

            int nal0 = nalStart[0].get(0) & 0xFF;
            int nalType = nal0 & 0x1f;
            int nri = (nal0 >>> 5) & 3;

            int srcOffset = 1;
            while (srcOffset < nalSize[0]) {
                int copy = out.capacity() - outBytesUsed - 2;
                if (copy > nalSize[0] - srcOffset) {
                    copy = nalSize[0] - srcOffset;
                }

                ByteBuffer dst = ((ByteBuffer) out.data().position(outBytesUsed)).slice();
                dst.put(0, (byte) ((nri << 5) | 28));

                dst.put(1, (byte) nalType);

                if (srcOffset == 1) {
                    dst.put(1, (byte) (nalType | 0x80));
                }

                if (srcOffset + copy == nalSize[0]) {
                    dst.put(1, (byte) (nalType | 0x40));
                }

                ByteBuffer nalData = (ByteBuffer) nalStart[0]
                        .position(srcOffset).limit(srcOffset + copy);
                dst.position(2);
                dst.put(nalData);
                srcOffset += copy;

                out.setRange(0, outBytesUsed + copy + 2);

                packets.add(out);
                out = getABuffer(AVC_ABUFS);
                outBytesUsed = 12;  // Placeholder for RTP header
            }
        }

        if (outBytesUsed > 12) {
            out.setRange(0, outBytesUsed);
            packets.add(out);
        }

        while (!packets.isEmpty()) {
            ABuffer outBuf = packets.remove(0);
            outBuf.setInt32Data(mRTPSeqNo);
            boolean last = packets.isEmpty();
            ByteBuffer dstData = outBuf.data();

            dstData.put(0, (byte) 0x80);

            dstData.put(1, (byte) packetType);
            if (last) {
                dstData.put(1, (byte) (packetType | 1 << 7));  // M-bit
            }

            dstData.put(2, (byte) ((mRTPSeqNo >>> 8) & 0xff));
            dstData.put(3, (byte) (mRTPSeqNo & 0xff));
            ++mRTPSeqNo;

            dstData.put(4, (byte) (rtpTime >>> 24));
            dstData.put(5, (byte) ((rtpTime >>> 16) & 0xff));
            dstData.put(6, (byte) ((rtpTime >>> 8) & 0xff));
            dstData.put(7, (byte) (rtpTime & 0xff));
            dstData.put(8, (byte) (SOURCE_ID >>> 24));
            dstData.put(9, (byte) ((SOURCE_ID >>> 16) & 0xff));
            dstData.put(10, (byte) ((SOURCE_ID >>> 8) & 0xff));
            dstData.put(11, (byte) (SOURCE_ID & 0xff));

            dstData.position(12);

            int err = sendRTPPacket(outBuf, true /* storeInHistory */);

            if (err != OK) {
                recycleABuffer(outBuf);

                while ((!packets.isEmpty())) {
                    recycleABuffer(packets.remove(0));
                }

                return err;
            }
        }

        return OK;
    }

    private int sendRTPPacket(ABuffer packet, boolean storeInHistory) {
        return sendRTPPacket(packet, storeInHistory, false, -1L);
    }

    private int sendRTPPacket(ABuffer packet, boolean storeInHistory,
            boolean timeValid, long timeUs) {
        CheckUtils.check(mRTPConnected);

        int err = mNetSession.sendRequest(
                mRTPSessionID, packet.data(), packet.size(), timeValid, timeUs);

        if (DEBUG) {
            Log.d(TAG, String.format("sendRTPPacket session[%d] result[%d] >>>>>>>>>>>>",
                    mRTPSessionID, err));
        }

        if (err != OK) {
            return err;
        }

        mLastNTPTime = getNowNTP();
        mLastRTPTime = RTPUtils.U32_AT(packet.data(), 4);

        ++mNumRTPSent;
        mNumRTPOctetsSent += packet.size() - 12;

        if (storeInHistory) {
            if (mHistorySize == MAX_HISTORY_SIZE) {
                recycleABuffer(mHistory.removeFirst());
            } else {
                ++mHistorySize;
            }
            mHistory.addLast(packet);
        }

        return OK;
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
                        errorOccuredDuringSend ? "send" : "receive", sessionID, err, detail));

                mNetSession.destroySession(sessionID);

                if (sessionID == mRTPSessionID) {
                    mRTPSessionID = 0;
                } else if (sessionID == mRTCPSessionID) {
                    mRTCPSessionID = 0;
                }

                if (!mRTPConnected
                        || (mRTPMode != TransportMode.TRANSPORT_NONE && !mRTCPConnected)) {
                    // We haven't completed initialization, attach the error
                    // to the notification instead.
                    notifyInitDone(err);
                    break;
                }

                notifyError(err);
                break;
            }

            case ANetworkSession.WHAT_DATAGRAM: {
                ABuffer data = msg.getThrow(DATA);

                if (isRTP) {
                    Log.w(TAG, "Huh? Received data on RTP connection...");
                } else {
                    onRTCPData(data);
                }
                break;
            }

            case ANetworkSession.WHAT_CONNECTED: {
                int sessionID = msg.getInt(SESSION_ID);

                if (isRTP) {
                    CheckUtils.checkEqual(mRTPMode, TransportMode.TRANSPORT_TCP);
                    CheckUtils.checkEqual(sessionID, mRTPSessionID);
                    mRTPConnected = true;
                } else {
                    CheckUtils.checkEqual(mRTCPMode, TransportMode.TRANSPORT_TCP);
                    CheckUtils.checkEqual(sessionID, mRTCPSessionID);
                    mRTCPConnected = true;
                }

                if (mRTPConnected
                        && (mRTCPMode == TransportMode.TRANSPORT_NONE || mRTCPConnected)) {
                    notifyInitDone(OK);
                }
                break;
            }

            case ANetworkSession.WHAT_NETWORK_STALL: {
                int numBytesQueued = msg.getInt(NUM_BYTES_QUEUED);

                notifyNetworkStall(numBytesQueued);
                break;
            }

            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private int onRTCPData(ABuffer buffer) {
        ByteBuffer data = buffer.data();
        int size = buffer.size();

        while (size > 0) {
            if (size < 8) {
                // Too short to be a valid RTCP header
                return ERROR_MALFORMED;
            }

            int data0 = data.get(0) & 0xFF;

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

            int data2 = data.get(2) & 0xFF;
            int data3 = data.get(3) & 0xFF;
            int headerLength = 4 * (data2 << 8 | data3) + 4;

            if (size < headerLength) {
                // Only received a partial packet?
                return ERROR_MALFORMED;
            }

            int data1 = data.get(1) & 0xFF;
            switch (data1) {
                case 200:
                case 201:  // RR
                    parseReceiverReport(data, headerLength);
                    break;

                case 202:  // SDES
                case 203:
                    break;

                case 204:  // APP
                    parseAPP(data, headerLength);
                    break;

                case 205:  // TSFB (transport layer specific feedback)
                    parseTSFB(data, headerLength);
                    break;

                case 206:  // PSFB (payload specific feedback)
                    // hexdump(data, headerLength);
                    break;

                default: {
                    Log.w(TAG, String.format("Unknown RTCP packet type %d of size %d",
                            data1, headerLength));
                    break;
                }
            }

            data = ((ByteBuffer) data.position(headerLength)).slice();
            size -= headerLength;
        }

        return OK;
    }

    private int parseReceiverReport(ByteBuffer data, int size) {
        float fractionLost = (data.get(12) & 0xFF) / 256.0f;

        Log.d(TAG, String.format("lost %.2f %% of packets during report interval",
                100.0f * fractionLost));

        return OK;
    }

    private int parseTSFB(ByteBuffer data, int size) {
        if ((data.get(0) & 0x1f) != 1) {
            return ERROR_UNSUPPORTED;  // We only support NACK for now.
        }

        int srcId = RTPUtils.U32_AT(data, 8);
        if (srcId != SOURCE_ID) {
            return ERROR_MALFORMED;
        }

        for (int i = 12; i < size; i += 4) {
            int seqNo = RTPUtils.U16_AT(data, i);
            short blp = RTPUtils.U16_AT(data, i + 2);

            boolean foundSeqNo = false;
            for (ABuffer buffer : mHistory) {
                int bufferSeqNo = buffer.getInt32Data() & 0xffff;

                boolean retransmit = false;
                if (bufferSeqNo == seqNo) {
                    retransmit = true;
                } else if (blp != 0) {
                    for (int j = 0; j < 16; ++j) {
                        if (((blp & (1 << j)) != 0)
                                && (bufferSeqNo == ((seqNo + j + 1) & 0xffff))) {
                            blp &= ~(1 << j);
                            retransmit = true;
                        }
                    }
                }

                if (retransmit) {
                    Log.d(TAG, "retransmitting seqNo " + bufferSeqNo);

                    CheckUtils.checkEqual(OK,
                            sendRTPPacket(buffer, false /* storeInHistory */));

                    if (bufferSeqNo == seqNo) {
                        foundSeqNo = true;
                    }

                    if (foundSeqNo && blp == 0) {
                        break;
                    }
                }
            }

            if (!foundSeqNo || blp != 0) {
                Log.d(TAG, String.format("Some sequence numbers were no longer available for "
                                + "retransmission (seqNo = %d, foundSeqNo = %d, blp = 0x%04x)",
                        seqNo, foundSeqNo, blp));

                if (!mHistory.isEmpty()) {
                    int earliest = mHistory.peekFirst().getInt32Data() & 0xffff;
                    int latest = mHistory.peekLast().getInt32Data() & 0xffff;

                    Log.d(TAG, String.format("have seq numbers from %d - %d", earliest, latest));
                }
            }
        }

        return OK;
    }

    private int parseAPP(ByteBuffer data, int size) {
        final int late_offset = 8;
        final String late_string = "late";
        final int avgLatencyUs_offset = late_offset + 4 /*late_string.length()*/;
        final int maxLatencyUs_offset = avgLatencyUs_offset + 8 /*sizeof(long)*/;

        if ((size >= (maxLatencyUs_offset + 8 /*sizeof(long)*/))
                && (data.get(late_offset) & 0xFF) == late_string.charAt(0)
                && (data.get(late_offset + 1) & 0xFF) == late_string.charAt(1)
                && (data.get(late_offset + 2) & 0xFF) == late_string.charAt(2)
                && (data.get(late_offset + 3) & 0xFF) == late_string.charAt(3)) {
            long avgLatencyUs = RTPUtils.U64_AT(data, avgLatencyUs_offset);
            long maxLatencyUs = RTPUtils.U64_AT(data, maxLatencyUs_offset);

            AMessage notify = mNotify.dup();
            notify.setInt(WHAT, WHAT_INFORM_SENDER);
            notify.setLong(AVG_LATENCY_US, avgLatencyUs);
            notify.setLong(MAX_LATENCY_US, maxLatencyUs);
            notify.post();
        }

        return OK;
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

    private void notifyNetworkStall(int numBytesQueued) {
        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_NETWORK_STALL);
        notify.setInt(NUM_BYTES_QUEUED, numBytesQueued);
        notify.post();
    }
}
