package com.hym.rtplib;

import android.media.MediaFormat;
import android.os.Looper;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.foundation.AHandler;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.net.ANetworkSession;
import com.hym.rtplib.util.AvcUtils;
import com.hym.rtplib.util.TimeUtils;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class MediaSender extends AHandler implements MediaConstants, Errno {
    private static final String TAG = MediaSender.class.getSimpleName();

    public static final int WHAT_INIT_DONE = 0;
    public static final int WHAT_ERROR = 1;
    public static final int WHAT_NETWORK_STALL = 2;
    public static final int WHAT_INFORM_SENDER = 3;

    private static final int WHAT_SENDER_NOTIFY = 0;

    private enum Mode {
        MODE_UNDEFINED,
        MODE_TRANSPORT_STREAM,
        MODE_ELEMENTARY_STREAMS,
    }

    private static class TrackInfo {
        final MediaFormat mFormat;
        final boolean mIsAudio;
        final int mFlags;
        final List<ABuffer> mAccessUnits = new LinkedList<>();

        int mPacketizerTrackIndex;
        RTPSender mSender;

        public TrackInfo(MediaFormat format, int flags) {
            mFormat = format;
            mIsAudio = format.getString(MediaFormat.KEY_MIME).toLowerCase().startsWith("audio/");
            mFlags = flags;
        }
    }

    private final ANetworkSession mNetSession;
    private final AMessage mNotify;

    private Mode mMode;
    private int mGeneration;

    private final List<TrackInfo> mTrackInfos = new ArrayList<>();

    private TSPacketizer mTSPacketizer;
    private RTPSender mTSSender;
    private long mPrevTimeUs;

    private int mInitDoneCount;

    public MediaSender(ANetworkSession netSession, AMessage notify) {
        mNetSession = netSession;
        mNotify = notify;
        mMode = Mode.MODE_UNDEFINED;
        mGeneration = 0;
        mPrevTimeUs = -1L;
        mInitDoneCount = 0;
    }

    public static final int FLAG_MANUALLY_PREPEND_SPS_PPS = 1;

    public int addTrack(MediaFormat format, int flags) {
        if (mMode != Mode.MODE_UNDEFINED) {
            return INVALID_OPERATION;
        }

        TrackInfo info = new TrackInfo(format, flags);
        info.mPacketizerTrackIndex = -1;

        int index = mTrackInfos.size();
        mTrackInfos.add(info);

        return index;
    }

    // If trackIndex == -1, initialize for transport stream muxing.
    public int initAsync(
            int trackIndex,
            String remoteHost,
            int remoteRTPPort,
            RTPSender.TransportMode rtpMode,
            int remoteRTCPPort,
            RTPSender.TransportMode rtcpMode,
            final int[] localRTPPort) {
        if (trackIndex < 0) {
            if (mMode != Mode.MODE_UNDEFINED) {
                return INVALID_OPERATION;
            }

            int flags = 0;
            mTSPacketizer = new TSPacketizer(flags);

            int err = OK;
            for (int i = 0; i < mTrackInfos.size(); ++i) {
                TrackInfo info = mTrackInfos.get(i);

                int packetizerTrackIndex = mTSPacketizer.addTrack(info.mFormat);

                if (packetizerTrackIndex < 0) {
                    err = packetizerTrackIndex;
                    break;
                }

                info.mPacketizerTrackIndex = packetizerTrackIndex;
            }

            if (err == OK) {
                AMessage notify = AMessage.obtain(WHAT_SENDER_NOTIFY, this);
                notify.setInt(GENERATION, mGeneration);
                mTSSender = new RTPSender(mNetSession, notify, Looper.myLooper());

                err = mTSSender.initAsync(
                        remoteHost,
                        remoteRTPPort,
                        rtpMode,
                        remoteRTCPPort,
                        rtcpMode,
                        localRTPPort);

                if (err != OK) {
                    mTSSender = null;
                }
            }

            if (err != OK) {
                for (int i = 0; i < mTrackInfos.size(); ++i) {
                    TrackInfo info = mTrackInfos.get(i);
                    info.mPacketizerTrackIndex = -1;
                }

                mTSPacketizer = null;
                return err;
            }

            mMode = Mode.MODE_TRANSPORT_STREAM;
            mInitDoneCount = 1;

            return OK;
        }

        if (mMode == Mode.MODE_TRANSPORT_STREAM) {
            return INVALID_OPERATION;
        }

        if (trackIndex >= mTrackInfos.size()) {
            return -ERANGE;
        }

        TrackInfo info = mTrackInfos.get(trackIndex);

        if (info.mSender != null) {
            return INVALID_OPERATION;
        }

        AMessage notify = AMessage.obtain(WHAT_SENDER_NOTIFY, this);
        notify.setInt(GENERATION, mGeneration);
        notify.setInt(TRACK_INDEX, trackIndex);

        info.mSender = new RTPSender(mNetSession, notify, Looper.myLooper());

        int err = info.mSender.initAsync(
                remoteHost,
                remoteRTPPort,
                rtpMode,
                remoteRTCPPort,
                rtcpMode,
                localRTPPort);

        if (err != OK) {
            info.mSender = null;

            return err;
        }

        if (mMode == Mode.MODE_UNDEFINED) {
            mInitDoneCount = mTrackInfos.size();
        }

        mMode = Mode.MODE_ELEMENTARY_STREAMS;

        return OK;
    }

    public int queueAccessUnit(int trackIndex, ABuffer accessUnit) {
        if (mMode == Mode.MODE_UNDEFINED) {
            return INVALID_OPERATION;
        }

        if (trackIndex >= mTrackInfos.size()) {
            return -ERANGE;
        }

        if (mMode == Mode.MODE_TRANSPORT_STREAM) {
            TrackInfo infoAddTo = mTrackInfos.get(trackIndex);
            infoAddTo.mAccessUnits.add(accessUnit);

            mTSPacketizer.extractCSDIfNecessary(infoAddTo.mPacketizerTrackIndex);

            while (true) {
                int minTrackIndex = -1;
                long minTimeUs = -1L;

                for (int i = 0; i < mTrackInfos.size(); ++i) {
                    TrackInfo tmpInfo = mTrackInfos.get(i);

                    if (tmpInfo.mAccessUnits.isEmpty()) {
                        minTrackIndex = -1;
                        minTimeUs = -1L;
                        break;
                    }

                    ABuffer firstAccessUnit = tmpInfo.mAccessUnits.get(0);
                    long timeUs = firstAccessUnit.meta().getLong(TIME_US);

                    if (minTrackIndex < 0 || timeUs < minTimeUs) {
                        minTrackIndex = i;
                        minTimeUs = timeUs;
                    }
                }

                if (minTrackIndex < 0) {
                    return OK;
                }

                TrackInfo minInfo = mTrackInfos.get(minTrackIndex);
                ABuffer accessUnitToPacketize = minInfo.mAccessUnits.remove(0);

                final ABuffer[] tsPackets = new ABuffer[1];
                int err = packetizeAccessUnit(
                        minTrackIndex, accessUnitToPacketize, tsPackets);

                if (err == OK) {
                    long timeUs = accessUnitToPacketize.meta().getLong(TIME_US);
                    tsPackets[0].meta().setLong(TIME_US, timeUs);

                    err = mTSSender.queueBuffer(
                            tsPackets[0],
                            33 /* packetType */,
                            RTPBase.PacketizationMode.PACKETIZATION_TRANSPORT_STREAM);
                }

                if (err != OK) {
                    return err;
                }
            }
        }

        TrackInfo info3 = mTrackInfos.get(trackIndex);

        return info3.mSender.queueBuffer(
                accessUnit,
                info3.mIsAudio ? 96 : 97 /* packetType */,
                info3.mIsAudio
                        ? RTPBase.PacketizationMode.PACKETIZATION_AAC
                        : RTPBase.PacketizationMode.PACKETIZATION_H264);
    }

    protected void onMessageReceived(AMessage msg) {
        switch (msg.getWhat()) {
            case WHAT_SENDER_NOTIFY: {
                int generation = msg.getInt(GENERATION);
                if (generation != mGeneration) {
                    break;
                }

                onSenderNotify(msg);
                break;
            }

            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private void onSenderNotify(AMessage msg) {
        int what = msg.getInt(WHAT);

        switch (what) {
            case RTPSender.WHAT_INIT_DONE: {
                --mInitDoneCount;

                int err = msg.getInt(ERR);

                if (err != OK) {
                    notifyInitDone(err);
                    ++mGeneration;
                    break;
                }

                if (mInitDoneCount == 0) {
                    notifyInitDone(OK);
                }
                break;
            }

            case RTPSender.WHAT_ERROR: {
                int err = msg.getInt(ERR);

                notifyError(err);
                break;
            }

            case WHAT_NETWORK_STALL: {
                int numBytesQueued = msg.getInt(NUM_BYTES_QUEUED);

                notifyNetworkStall(numBytesQueued);
                break;
            }

            case WHAT_INFORM_SENDER: {
                long avgLatencyUs = msg.getLong(AVG_LATENCY_US);
                long maxLatencyUs = msg.getLong(MAX_LATENCY_US);

                AMessage notify = mNotify.dup();
                notify.setInt(WHAT, WHAT_INFORM_SENDER);
                notify.setLong(AVG_LATENCY_US, avgLatencyUs);
                notify.setLong(MAX_LATENCY_US, maxLatencyUs);
                notify.post();
                break;
            }

            default:
                throw new RuntimeException("TRESPASS");
        }
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

    private int packetizeAccessUnit(
            int trackIndex,
            ABuffer accessUnit,
            final ABuffer[] tsPackets) {
        TrackInfo info = mTrackInfos.get(trackIndex);

        int flags = 0;

        boolean manuallyPrependSPSPPS =
                !info.mIsAudio
                        && (info.mFlags & FLAG_MANUALLY_PREPEND_SPS_PPS) != 0
                        && AvcUtils.isIDR(accessUnit);

        if (manuallyPrependSPSPPS) {
            flags |= TSPacketizer.PREPEND_SPS_PPS_TO_IDR_FRAMES;
        }

        long timeUs = TimeUtils.getMonotonicMicroTime();
        if (mPrevTimeUs < 0L || mPrevTimeUs + 100_000L <= timeUs) {
            flags |= TSPacketizer.EMIT_PCR;
            flags |= TSPacketizer.EMIT_PAT_AND_PMT;

            mPrevTimeUs = timeUs;
        }

        mTSPacketizer.packetize(
                info.mPacketizerTrackIndex,
                accessUnit,
                tsPackets,
                flags,
                null /*HDCP_private_data*/,
                0 /*sizeof(HDCP_private_data)*/,
                info.mIsAudio ? 2 : 0 /* numStuffingBytes */);

        return OK;
    }
}
