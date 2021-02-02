package com.hym.rtplib;

import android.media.AudioFormat;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.HandlerThread;
import android.os.Process;
import android.text.TextUtils;
import android.util.DisplayMetrics;
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
import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;

public class PlaybackSession extends AHandler implements MediaConstants, Errno {
    private static final String TAG = PlaybackSession.class.getSimpleName();

    public static final int WHAT_SESSION_DEAD = 0;
    public static final int WHAT_BINARY_DATA = 1;
    public static final int WHAT_SESSION_ESTABLISHED = 2;
    public static final int WHAT_SESSION_DESTROYED = 3;

    private static final int WHAT_MEDIA_PULLER_NOTIFY = 0;
    private static final int WHAT_CONVERTER_NOTIFY = 1;
    private static final int WHAT_TRACK_NOTIFY = 2;
    private static final int WHAT_UPDATE_SURFACE = 3;
    private static final int WHAT_PAUSE = 4;
    private static final int WHAT_RESUME = 5;
    private static final int WHAT_MEDIA_SENDER_NOTIFY = 6;
    private static final int WHAT_PULL_EXTRACTOR_SAMPLE = 7;

    private final MediaProjection mMediaProjection;
    private final DisplayMetrics mDisplayMetrics;
    private final ANetworkSession mNetSession;
    private final AMessage mNotify;
    private final String mMediaPath;

    private MediaSender mMediaSender;
    private int mLocalRTPPort;

    private boolean mWeAreDead;
    private boolean mPaused;

    private long mLastLifesignUs;

    private final SparseArray<Track> mTracks = new SparseArray<>();
    private int mVideoTrackIndex;

    private final long mPrevTimeUs;

    private MediaExtractor mExtractor;
    private SparseIntArray mExtractorTrackToInternalTrack = new SparseIntArray();
    private boolean mPullExtractorPending;
    private int mPullExtractorGeneration;
    private long mFirstSampleTimeRealUs;
    private long mFirstSampleTimeUs;

    public PlaybackSession(
            MediaProjection mediaProjection,
            DisplayMetrics displayMetrics,
            ANetworkSession netSession,
            AMessage notify,
            String path) {
        mMediaProjection = mediaProjection;
        mDisplayMetrics = displayMetrics;
        mNetSession = netSession;
        mNotify = notify;
        mLocalRTPPort = -1;
        mWeAreDead = false;
        mPaused = false;
        mLastLifesignUs = 0L;
        mVideoTrackIndex = -1;
        mPrevTimeUs = -1L;
        mPullExtractorPending = false;
        mPullExtractorGeneration = 0;
        mFirstSampleTimeRealUs = -1L;
        mFirstSampleTimeUs = -1L;
        mMediaPath = path;
    }

    public int init(
            String clientIP,
            int clientRtp,
            RTPSender.TransportMode rtpMode,
            int clientRtcp,
            RTPSender.TransportMode rtcpMode,
            boolean enableAudio,
            boolean usePCMAudio,
            boolean enableVideo,
            VideoFormats.FormatConfig videoConfig) {
        AMessage notify = AMessage.obtain(WHAT_MEDIA_SENDER_NOTIFY, this);
        mMediaSender = new MediaSender(mNetSession, notify);

        int err = setupPacketizer(
                enableAudio,
                usePCMAudio,
                enableVideo,
                videoConfig);

        final int[] localRTPPort = new int[1];

        if (err == OK) {
            err = mMediaSender.initAsync(
                    -1 /* trackIndex */,
                    clientIP,
                    clientRtp,
                    rtpMode,
                    clientRtcp,
                    rtcpMode,
                    localRTPPort);
            mLocalRTPPort = localRTPPort[0];
        }

        if (err != OK) {
            mLocalRTPPort = -1;

            mMediaSender.removeCallbacksAndMessages(null);
            mMediaSender = null;

            return err;
        }

        updateLiveness();

        return OK;
    }

    public void destroyAsync() {
        Log.d(TAG, "destroyAsync");

        for (int i = 0; i < mTracks.size(); ++i) {
            mTracks.valueAt(i).stopAsync();
        }
    }

    public int getRTPPort() {
        return mLocalRTPPort;
    }

    public long getLastLifesignUs() {
        return mLastLifesignUs;
    }

    public void updateLiveness() {
        mLastLifesignUs = TimeUtils.getMonotonicMicroTime();
    }

    public int play() {
        updateLiveness();

        AMessage.obtain(WHAT_RESUME, this).post();

        return OK;
    }

    public int pause() {
        updateLiveness();

        AMessage.obtain(WHAT_PAUSE, this).post();

        return OK;
    }

    public void requestIDRFrame() {
        for (int i = 0; i < mTracks.size(); ++i) {
            Track track = mTracks.valueAt(i);
            track.requestIDRFrame();
        }
    }

    protected void onMessageReceived(AMessage msg) {
        switch (msg.getWhat()) {
            case WHAT_CONVERTER_NOTIFY: {
                if (mWeAreDead) {
                    Log.d(TAG, "dropping msg '" + msg + "' because we're dead");

                    break;
                }

                int what = msg.getInt(WHAT);
                int trackIndex = msg.getInt(TRACK_INDEX);

                if (what == Converter.WHAT_ACCESS_UNIT) {
                    ABuffer accessUnit = msg.getThrow(ACCESS_UNIT);
                    Track track = mTracks.get(trackIndex);

                    int err = mMediaSender.queueAccessUnit(
                            track.getMediaSenderTrackIndex(),
                            accessUnit);

                    if (err != OK) {
                        notifySessionDead();
                    }
                    break;
                } else if (what == Converter.WHAT_EOS) {
                    Log.d(TAG, "output EOS on track " + trackIndex);

                    int index = mTracks.indexOfKey(trackIndex);
                    CheckUtils.checkGreaterOrEqual(index, 0);

                    Converter converter = mTracks.valueAt(index).getConverter();
                    converter.getLooper().quit();

                    mTracks.removeAt(index);

                    if (mTracks.size() == 0) {
                        Log.d(TAG, "Reached EOS");
                    }
                } else if (what != Converter.WHAT_SHUTDOWN_COMPLETED) {
                    CheckUtils.checkEqual(what, Converter.WHAT_ERROR);

                    int err = msg.getInt(ERR);

                    Log.e(TAG, "converter signaled error " + err);

                    notifySessionDead();
                }
                break;
            }

            case WHAT_MEDIA_SENDER_NOTIFY: {
                int what = msg.getInt(WHAT);

                if (what == MediaSender.WHAT_INIT_DONE) {
                    int err = msg.getInt(ERR);

                    if (err == OK) {
                        onMediaSenderInitialized();
                    } else {
                        notifySessionDead();
                    }
                } else if (what == MediaSender.WHAT_ERROR) {
                    notifySessionDead();
                } else if (what == MediaSender.WHAT_NETWORK_STALL) {
                    int numBytesQueued = msg.getInt(NUM_BYTES_QUEUED);

                    if (mVideoTrackIndex >= 0) {
                        Track videoTrack = mTracks.get(mVideoTrackIndex);

                        Converter converter = videoTrack.getConverter();
                        if (converter != null) {
                            converter.dropAFrame();
                        }
                    }
                } else if (what == MediaSender.WHAT_INFORM_SENDER) {
                    onSinkFeedback(msg);
                } else {
                    throw new RuntimeException("TRESPASS");
                }
                break;
            }

            case WHAT_TRACK_NOTIFY: {
                int what = msg.getInt(WHAT);
                int trackIndex = msg.getInt(TRACK_INDEX);

                if (what == Track.WHAT_STOPPED) {
                    Log.d(TAG, "Track " + trackIndex + " stopped");

                    Track track = mTracks.get(trackIndex);
                    track.removeCallbacksAndMessages(null);
                    mTracks.remove(trackIndex);
                    track = null;

                    if (mTracks.size() != 0) {
                        Log.d(TAG, "not all tracks are stopped yet");
                        break;
                    }

                    mMediaSender.removeCallbacksAndMessages(null);
                    mMediaSender = null;

                    AMessage notify = mNotify.dup();
                    notify.setInt(WHAT, WHAT_SESSION_DESTROYED);
                    notify.post();
                }
                break;
            }

            case WHAT_PAUSE: {
                if (mExtractor != null) {
                    ++mPullExtractorGeneration;
                    mFirstSampleTimeRealUs = -1L;
                    mFirstSampleTimeUs = -1L;
                }

                if (mPaused) {
                    break;
                }

                for (int i = 0; i < mTracks.size(); ++i) {
                    mTracks.valueAt(i).pause();
                }

                mPaused = true;
                break;
            }

            case WHAT_RESUME: {
                if (mExtractor != null) {
                    schedulePullExtractor();
                }

                if (!mPaused) {
                    break;
                }

                for (int i = 0; i < mTracks.size(); ++i) {
                    mTracks.valueAt(i).resume();
                }

                mPaused = false;
                break;
            }

            case WHAT_PULL_EXTRACTOR_SAMPLE: {
                int generation = msg.getInt(GENERATION);

                if (generation != mPullExtractorGeneration) {
                    break;
                }

                mPullExtractorPending = false;

                onPullExtractor();
                break;
            }

            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private int setupMediaPacketizer(boolean enableAudio, boolean enableVideo) {
        mExtractor = new MediaExtractor();

        int err;

        try {
            mExtractor.setDataSource(mMediaPath);
            err = OK;
        } catch (IOException e) {
            Log.e(TAG, "mExtractor.setDataSource " + mMediaPath + " failed");
            err = -EIO;
        }

        if (err != OK) {
            return err;
        }

        int n = mExtractor.getTrackCount();
        boolean haveAudio = false;
        boolean haveVideo = false;
        for (int i = 0; i < n; ++i) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);

            boolean isAudio = mime.toLowerCase().startsWith("audio/");
            boolean isVideo = mime.toLowerCase().startsWith("video/");

            if (isAudio && enableAudio && !haveAudio) {
                haveAudio = true;
            } else if (isVideo && enableVideo && !haveVideo) {
                haveVideo = true;
            } else {
                continue;
            }

            mExtractor.selectTrack(i);

            int trackIndex = mTracks.size();

            AMessage notify = AMessage.obtain(WHAT_TRACK_NOTIFY, this);
            notify.setInt(TRACK_INDEX, trackIndex);

            Track track = new Track(notify, isAudio);

            mTracks.put(trackIndex, track);

            mExtractorTrackToInternalTrack.put(i, trackIndex);

            if (isVideo) {
                mVideoTrackIndex = trackIndex;
            }

            int flags = MediaSender.FLAG_MANUALLY_PREPEND_SPS_PPS;

            int mediaSenderTrackIndex = mMediaSender.addTrack(format, flags);
            CheckUtils.checkGreaterOrEqual(mediaSenderTrackIndex, 0);

            track.setMediaSenderTrackIndex(mediaSenderTrackIndex);

            if ((haveAudio || !enableAudio) && (haveVideo || !enableVideo)) {
                break;
            }
        }

        return OK;
    }

    private int setupPacketizer(boolean enableAudio, boolean usePCMAudio, boolean enableVideo,
            VideoFormats.FormatConfig videoConfig) {
        CheckUtils.check(enableAudio || enableVideo);

        if (!TextUtils.isEmpty(mMediaPath)) {
            return setupMediaPacketizer(enableAudio, enableVideo);
        }

        if (enableVideo) {
            int err = addVideoSource(videoConfig);

            if (err != OK) {
                return err;
            }
        }

        if (!enableAudio) {
            return OK;
        }

        return addAudioSource(usePCMAudio);
    }

    private int addSource(boolean isVideo, MediaFormat outFormat) {
        HandlerThread pullThread = new HandlerThread("PullThread", Process.THREAD_PRIORITY_DISPLAY);
        pullThread.start();

        HandlerThread converterThread = new HandlerThread("ConverterThread",
                Process.THREAD_PRIORITY_DISPLAY);
        converterThread.start();

        int trackIndex = mTracks.size();

        AMessage notify = AMessage.obtain(WHAT_CONVERTER_NOTIFY, this);
        notify.setInt(TRACK_INDEX, trackIndex);

        Converter converter = new Converter(notify, converterThread.getLooper(), outFormat);

        int err = converter.init(mMediaProjection, mDisplayMetrics);
        if (err != OK) {
            Log.e(TAG, String.format("%s converter returned err %d", isVideo ? "video" : "audio",
                    err));

            converter.getLooper().quit();
            return err;
        }

        notify = AMessage.obtain(Converter.WHAT_MEDIA_PULLER_NOTIFY, converter);
        notify.setInt(TRACK_INDEX, trackIndex);

        MediaPuller puller = new MediaPuller(converter.getMediaEncoder(), pullThread.getLooper(),
                notify);

        notify = AMessage.obtain(WHAT_TRACK_NOTIFY, this);
        notify.setInt(TRACK_INDEX, trackIndex);

        Track track = new Track(notify, !isVideo, puller, converter);

        mTracks.put(trackIndex, track);

        if (isVideo) {
            mVideoTrackIndex = trackIndex;
        }

        int flags = 0;
        if (converter.needToManuallyPrependSPSPPS()) {
            flags |= MediaSender.FLAG_MANUALLY_PREPEND_SPS_PPS;
        }

        int mediaSenderTrackIndex = mMediaSender.addTrack(converter.getOutputFormat(), flags);
        CheckUtils.checkGreaterOrEqual(mediaSenderTrackIndex, 0);

        track.setMediaSenderTrackIndex(mediaSenderTrackIndex);

        return OK;
    }

    private int addVideoSource(VideoFormats.FormatConfig videoConfig) {
        int width = videoConfig.width;
        int height = videoConfig.height;
        int framesPerSecond = videoConfig.framesPerSecond;
        boolean interlaced = videoConfig.interlaced;

        int[] profileIdc = new int[1];
        int[] levelIdc = new int[1];
        int[] constraintSet = new int[1];
        /*
        CheckUtils.check(VideoFormats.getProfileLevel(
                videoConfig.profileType,
                videoConfig.levelType,
                profileIdc,
                levelIdc,
                constraintSet));
        */

        MediaFormat outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                width, height);
        outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framesPerSecond);

        outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        outFormat.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline /*profileIdc*/);
        outFormat.setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31 /*levelIdc*/);
        //outFormat.setInteger("constraint-set", constraintSet);

        return addSource(true /* isVideo */, outFormat);
    }

    private int addAudioSource(boolean usePCMAudio) {
        String mime = usePCMAudio ? MediaFormat.MIMETYPE_AUDIO_RAW : MediaFormat.MIMETYPE_AUDIO_AAC;
        MediaFormat outFormat = MediaFormat.createAudioFormat(
                mime,
                48000 /* sampleRate */,
                1 /* channelCount */);

        if (usePCMAudio) {
            outFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        }

        return addSource(false /* isVideo */, outFormat);
    }

    private int onMediaSenderInitialized() {
        for (int i = 0; i < mTracks.size(); ++i) {
            CheckUtils.checkEqual(OK, mTracks.valueAt(i).start());
        }

        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_SESSION_ESTABLISHED);
        notify.post();

        return OK;
    }

    private void notifySessionDead() {
        // Inform WifiDisplaySource of our premature death (wish).
        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_SESSION_DEAD);
        notify.post();

        mWeAreDead = true;
    }

    private void schedulePullExtractor() {
        if (mPullExtractorPending) {
            return;
        }

        long delayUs = 1_000_000L; // default delay is 1 sec
        long sampleTimeUs = mExtractor.getSampleTime();

        if (sampleTimeUs != -1L) {
            long nowUs = TimeUtils.getMonotonicMicroTime();

            if (mFirstSampleTimeRealUs < 0L) {
                mFirstSampleTimeRealUs = nowUs;
                mFirstSampleTimeUs = sampleTimeUs;
            }

            long whenUs = sampleTimeUs - mFirstSampleTimeUs + mFirstSampleTimeRealUs;
            delayUs = whenUs - nowUs;
        } else {
            Log.w(TAG, "could not get sample time");
        }

        AMessage msg = AMessage.obtain(WHAT_PULL_EXTRACTOR_SAMPLE, this);
        msg.setInt(GENERATION, mPullExtractorGeneration);
        msg.post(delayUs);

        mPullExtractorPending = true;
    }

    private void onPullExtractor() {
        ABuffer accessUnit = new ABuffer(1024 * 1024);
        int size = mExtractor.readSampleData(accessUnit.data(), 0);
        if (size == -1) {
            // EOS.
            return;
        }

        long timeUs = mExtractor.getSampleTime();
        CheckUtils.checkNotEqual(timeUs, -1L);

        accessUnit.meta().setLong(TIME_US, mFirstSampleTimeRealUs + timeUs - mFirstSampleTimeUs);

        int trackIndex = mExtractor.getSampleTrackIndex();
        CheckUtils.checkNotEqual(trackIndex, -1);

        AMessage msg = AMessage.obtain(WHAT_CONVERTER_NOTIFY, this);

        msg.setInt(TRACK_INDEX, mExtractorTrackToInternalTrack.get(trackIndex));

        msg.setInt(WHAT, Converter.WHAT_ACCESS_UNIT);
        msg.set(ACCESS_UNIT, accessUnit);
        msg.post();

        mExtractor.advance();

        schedulePullExtractor();
    }

    private void onSinkFeedback(AMessage msg) {
        // FIXME: Actually, avgLatencyUs and maxLatencyUs are not accurate
        long avgLatencyUs = msg.getLong(AVG_LATENCY_US);
        long maxLatencyUs = msg.getLong(MAX_LATENCY_US);

        Log.d(TAG, String.format("sink reports avg. latency of %d ms (max %d ms)",
                avgLatencyUs / 1000L, maxLatencyUs / 1000L));

        if (mVideoTrackIndex >= 0) {
            Track videoTrack = mTracks.get(mVideoTrackIndex);
            Converter converter = videoTrack.getConverter();

            if (converter != null) {
                int videoBitrate = converter.getVideoBitrate();

                if (avgLatencyUs > 300_000L) {
                    videoBitrate *= 0.6;
                } else if (avgLatencyUs < 100_000L) {
                    videoBitrate *= 1.1;
                }

                if (videoBitrate > 0) {
                    if (videoBitrate < VIDEO_BIT_RATE_MIN) {
                        videoBitrate = VIDEO_BIT_RATE_MIN;
                    } else if (videoBitrate > VIDEO_BIT_RATE_MAX) {
                        videoBitrate = VIDEO_BIT_RATE_MAX;
                    }

                    if (videoBitrate != converter.getVideoBitrate()) {
                        Log.d(TAG, String.format("setting video bitrate to %d bps", videoBitrate));

                        converter.setVideoBitrate(videoBitrate);
                    }
                }

                float frameRate = converter.getVideoFrameRate();

                if (avgLatencyUs > 300_000L) {
                    frameRate *= 0.9;
                } else if (avgLatencyUs < 200_000L) {
                    frameRate *= 1.1;
                }

                if (frameRate > 0) {
                    if (frameRate < FRAME_RATE_MIN) {
                        frameRate = FRAME_RATE_MIN;
                    } else if (frameRate > FRAME_RATE_MAX) {
                        frameRate = FRAME_RATE_MAX;
                    }

                    if (frameRate != converter.getVideoFrameRate()) {
                        Log.d(TAG, String.format("setting frame rate to %.2f FPS", frameRate));

                        converter.setVideoFrameRate(frameRate);
                    }
                }
            }
        }
    }

    private static class Track extends AHandler {
        public static final int WHAT_STOPPED = 0;

        private static final int WHAT_MEDIA_PULLER_STOPPED = 0;

        private final AMessage mNotify;
        private MediaPuller mMediaPuller;
        private Converter mConverter;
        private boolean mStarted;
        private int mMediaSenderTrackIndex;
        private final boolean mIsAudio;

        public Track(AMessage notify, boolean isAudio, MediaPuller mediaPuller,
                Converter converter) {
            mNotify = notify;
            mMediaPuller = mediaPuller;
            mConverter = converter;
            mStarted = false;
            mIsAudio = isAudio;
        }

        public Track(AMessage notify, boolean isAudio) {
            mNotify = notify;
            mStarted = false;
            mIsAudio = isAudio;
        }

        public Converter getConverter() {
            return mConverter;
        }

        public int getMediaSenderTrackIndex() {
            CheckUtils.checkGreaterOrEqual(mMediaSenderTrackIndex, 0);
            return mMediaSenderTrackIndex;
        }

        public void setMediaSenderTrackIndex(int index) {
            mMediaSenderTrackIndex = index;
        }

        public int start() {
            Log.d(TAG, "Track.start isAudio=" + mIsAudio);

            CheckUtils.check(!mStarted);

            int err = OK;

            if (mMediaPuller != null) {
                err = mMediaPuller.start();
            }

            if (err == OK) {
                mStarted = true;
            }

            return err;
        }

        public void stopAsync() {
            Log.d(TAG, "Track.stopAsync isAudio=" + mIsAudio);

            if (mConverter != null) {
                mConverter.shutdownAsync();
            }

            AMessage msg = AMessage.obtain(WHAT_MEDIA_PULLER_STOPPED, this);

            if (mStarted && mMediaPuller != null) {
                mMediaPuller.stopAsync(msg);
            } else {
                mStarted = false;
                msg.post();
            }
        }

        public void pause() {
            mMediaPuller.pause();
        }

        public void resume() {
            mMediaPuller.resume();
        }

        public void requestIDRFrame() {
            if (mIsAudio) {
                return;
            }
            mConverter.requestIDRFrame();
        }

        protected void onMessageReceived(AMessage msg) {
            switch (msg.getWhat()) {
                case WHAT_MEDIA_PULLER_STOPPED: {
                    mConverter = null;
                    mStarted = false;

                    AMessage notify = mNotify.dup();
                    notify.setInt(WHAT, WHAT_STOPPED);
                    notify.post();

                    Log.d(TAG, "Stopped " + (mIsAudio ? "audio" : "video") + " posted");
                    break;
                }

                default:
                    throw new RuntimeException("TRESPASS");
            }
        }
    }
}
