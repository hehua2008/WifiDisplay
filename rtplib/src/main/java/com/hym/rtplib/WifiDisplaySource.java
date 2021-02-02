package com.hym.rtplib;

import android.annotation.SuppressLint;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.Nullable;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.foundation.AHandler;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.net.ANetworkSession;
import com.hym.rtplib.net.ParsedMessage;
import com.hym.rtplib.util.CheckUtils;
import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WifiDisplaySource extends AHandler implements MediaConstants, Errno {
    private static final String TAG = WifiDisplaySource.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int WHAT_START = 0;
    private static final int WHAT_RTSP_NOTIFY = 1;
    private static final int WHAT_STOP = 2;
    private static final int WHAT_PAUSE = 3;
    private static final int WHAT_RESUME = 4;
    private static final int WHAT_REAP_DEAD_CLIENTS = 5;
    private static final int WHAT_PLAYBACK_SESSION_NOTIFY = 6;
    private static final int WHAT_KEEP_ALIVE = 7;
    //private static final int WHAT_HDCP_NOTIFY=8;
    private static final int WHAT_FINISH_STOP2 = 9;
    private static final int WHAT_TEARDOWN_TRIGGER_TIMED_OUT = 10;

    private static final long REAPER_INTERVAL_US = 1_000_000L;

    // We request that the dongle send us a "TEARDOWN" in order to
    // perform an orderly shutdown. We're willing to wait up to 2 secs
    // for this message to arrive, after that we'll force a disconnect
    // instead.
    private static final long TEARDOWN_TRIGGER_TIMEOU_SECS = 2L;

    private static final long PLAYBACK_SESSION_TIMEOUT_SECS = 30L;

    private static final long PLAYBACK_SESSION_TIMEOUT_US =
            PLAYBACK_SESSION_TIMEOUT_SECS * 1_000_000L;

    private static final String USER_AGENT =
            "stagefright/1.2 (Linux;Android " + Build.VERSION.RELEASE + ')';

    private State mState;
    private final VideoFormats mSupportedSourceVideoFormats = new VideoFormats();
    private final ANetworkSession mNetSession;
    private final MediaProjection mMediaProjection;
    private final DisplayMetrics mDisplayMetrics;
    private String mMediaPath;
    private InetAddress mInterfaceAddr;
    private int mSessionID;

    private AMessage mStopReplyTo;

    private String mWfdClientRtpPorts;
    private int mChosenRTPPort;  // extracted from "wfd_client_rtp_ports"

    private boolean mSinkSupportsVideo;
    private final VideoFormats mSupportedSinkVideoFormats = new VideoFormats();

    private VideoFormats.ResolutionType mChosenVideoResolutionType
            = VideoFormats.ResolutionType.RESOLUTION_CEA;
    private int mChosenVideoResolutionIndex;
    private VideoFormats.ProfileType mChosenVideoProfile;
    private VideoFormats.LevelType mChosenVideoLevel;

    private final VideoFormats.FormatConfig mChosenVideoConfig = new VideoFormats.FormatConfig();

    private boolean mSinkSupportsAudio;

    private boolean mUsingPCMAudio;
    private int mClientSessionID;

    private final ClientInfo mClientInfo = new ClientInfo();

    private boolean mReaperPending;

    private int mNextCSeq;

    private final ArrayMap<ResponseID, HandleRTSPResponseFunc> mResponseHandlers = new ArrayMap<>();

    private boolean mPlaybackSessionEstablished;

    private enum State {
        INITIALIZED,
        AWAITING_CLIENT_CONNECTION,
        AWAITING_CLIENT_SETUP,
        AWAITING_CLIENT_PLAY,
        ABOUT_TO_PLAY,
        PLAYING,
        PLAYING_TO_PAUSED,
        PAUSED,
        PAUSED_TO_PLAYING,
        AWAITING_CLIENT_TEARDOWN,
        STOPPING,
        STOPPED,
    }

    private enum TriggerType {
        TRIGGER_SETUP,
        TRIGGER_TEARDOWN,
        TRIGGER_PAUSE,
        TRIGGER_PLAY,
    }

    private static class ResponseID implements Comparable<ResponseID> {
        final int mSessionID;
        final int mCSeq;

        ResponseID(int sessionID, int cSeq) {
            mSessionID = sessionID;
            mCSeq = cSeq;
        }

        @Override
        public int compareTo(ResponseID o) {
            if (mSessionID == o.mSessionID) {
                if (mCSeq == o.mCSeq) {
                    return 0;
                } else if (mCSeq < o.mCSeq) {
                    return -1;
                }
            }

            if (mSessionID < o.mSessionID) {
                return -1;
            }

            return 1;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSessionID, mCSeq);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ResponseID)) {
                return false;
            }
            ResponseID o = (ResponseID) obj;
            return mSessionID == o.mSessionID && mCSeq == o.mCSeq;
        }
    }

    private static class ClientInfo {
        String mRemoteIP;
        String mLocalIP;
        int mLocalPort;
        int mPlaybackSessionID;
        PlaybackSession mPlaybackSession;
    }

    private interface HandleRTSPResponseFunc {
        int handle(int sessionID, ParsedMessage msg);
    }

    public WifiDisplaySource(Looper looper, ANetworkSession netSession,
            MediaProjection mediaProjection, DisplayMetrics displayMetrics, String path) {
        super(looper);
        mState = State.INITIALIZED;
        mNetSession = netSession;
        mMediaProjection = mediaProjection;
        mDisplayMetrics = displayMetrics;
        mSessionID = 0;
        mStopReplyTo = null;
        mChosenRTPPort = -1;
        mUsingPCMAudio = false;
        mClientSessionID = 0;
        mReaperPending = false;
        mNextCSeq = 1;
        mMediaPath = path;

        mSupportedSourceVideoFormats.disableAll();

        mSupportedSourceVideoFormats.setNativeResolution(
                VideoFormats.ResolutionType.RESOLUTION_CEA, 5);  // 1280x720 p30

        // Enable all resolutions up to 1280x720p30
        mSupportedSourceVideoFormats.enableResolutionUpto(
                VideoFormats.ResolutionType.RESOLUTION_CEA, 5,
                VideoFormats.ProfileType.PROFILE_CHP,  // Constrained High Profile
                VideoFormats.LevelType.LEVEL_32    // Level 3.2
        );
    }

    public static int postAndAwaitResponse(AMessage msg) {
        int err;
        try {
            AMessage response = msg.postAndAwaitResponse();
            err = (response == null) ? OK : response.getInt(ERR, OK);
        } catch (InterruptedException e) {
            Log.e(TAG, "postAndAwaitResponse failed", e);
            err = -EINTR;
        }

        return err;
    }

    public int start(String iface) {
        CheckUtils.checkEqual(mState, State.INITIALIZED);

        AMessage msg = AMessage.obtain(WHAT_START, this);
        msg.set(IFACE, iface);

        return postAndAwaitResponse(msg);
    }

    public int stop() {
        AMessage msg = AMessage.obtain(WHAT_STOP, this);

        return postAndAwaitResponse(msg);
    }

    public int pause() {
        AMessage msg = AMessage.obtain(WHAT_PAUSE, this);

        return postAndAwaitResponse(msg);
    }

    public int resume() {
        AMessage msg = AMessage.obtain(WHAT_RESUME, this);

        return postAndAwaitResponse(msg);
    }

    protected void onMessageReceived(AMessage msg) {
        switch (msg.getWhat()) {
            case WHAT_START: {
                String iface = msg.getThrow(IFACE);
                int err = OK;
                int colonPos = iface.indexOf(':');
                int port = WIFI_DISPLAY_DEFAULT_PORT;

                if (colonPos >= 0) {
                    try {
                        port = Integer.parseInt(iface.substring(colonPos + 1));
                        if (port > 65535) {
                            Log.w(TAG, "parse iface(" + iface + ") failed");
                            err = -EINVAL;
                        } else {
                            iface = iface.substring(0, colonPos);
                            //err = OK;
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "parse iface(" + iface + ") failed", e);
                        err = -EINVAL;
                    }
                } else {
                    port = WIFI_DISPLAY_DEFAULT_PORT;
                    //err = OK;
                }

                if (err == OK) {
                    try {
                        mInterfaceAddr = InetAddress.getByName(iface);
                        AMessage notify = AMessage.obtain(WHAT_RTSP_NOTIFY, this);
                        mSessionID = mNetSession.createRTSPServer(mInterfaceAddr, port, notify);
                        //err = OK;
                    } catch (UnknownHostException e) {
                        Log.w(TAG, "unknown host: " + iface);
                        err = -EINVAL;
                    } catch (IOException e) {
                        Log.e(TAG, mNetSession + " createRTSPServer failed", e);
                        err = -EIO;
                    }
                }

                mState = State.AWAITING_CLIENT_CONNECTION;

                AMessage response = AMessage.obtain();
                response.setInt(ERR, err);
                msg.postResponse(response);
                break;
            }

            case WHAT_RTSP_NOTIFY: {
                int reason = msg.getInt(REASON);

                switch (reason) {
                    case ANetworkSession.WHAT_ERROR: {
                        int sessionID = msg.getInt(SESSION_ID);
                        int err = msg.getInt(ERR);
                        String detail = msg.getThrow(DETAIL);

                        Log.e(TAG, String.format("An error occurred in session[%d] (%d, %s)",
                                sessionID, err, detail));

                        mNetSession.destroySession(sessionID);

                        if (sessionID == mClientSessionID) {
                            mClientSessionID = 0;

                            onDisplayError(UNKNOWN_ERROR);
                        }
                        break;
                    }

                    case ANetworkSession.WHAT_CLIENT_CONNECTED: {
                        int sessionID = msg.getInt(SESSION_ID);

                        if (mClientSessionID > 0) {
                            Log.e(TAG, "A client tried to connect, but we already have one");

                            mNetSession.destroySession(sessionID);
                            break;
                        }

                        CheckUtils.checkEqual(mState, State.AWAITING_CLIENT_CONNECTION);

                        mClientInfo.mRemoteIP = msg.getThrow(CLIENT_IP);
                        mClientInfo.mLocalIP = msg.getThrow(SERVER_IP);

                        if (mClientInfo.mRemoteIP.equals(mClientInfo.mLocalIP)) {
                            // Disallow connections from the local interface
                            // for security reasons.
                            mNetSession.destroySession(sessionID);
                            break;
                        }

                        mClientInfo.mLocalPort = msg.getInt(SERVER_PORT);
                        mClientInfo.mPlaybackSessionID = -1;

                        mClientSessionID = sessionID;

                        Log.d(TAG, "We now have a client (" + sessionID + ") connected");

                        mState = State.AWAITING_CLIENT_SETUP;

                        int err = sendM1(sessionID);
                        CheckUtils.checkEqual(err, OK);
                        break;
                    }

                    case ANetworkSession.WHAT_DATA: {
                        int err = onReceiveClientData(msg);

                        if (err != OK) {
                            onDisplayError(UNKNOWN_ERROR);
                        }

                        break;
                    }

                    case ANetworkSession.WHAT_NETWORK_STALL: {
                        break;
                    }

                    default:
                        throw new RuntimeException("TRESPASS");
                }
                break;
            }

            case WHAT_STOP: {
                mStopReplyTo = msg;

                CheckUtils.checkLessThan(mState, State.AWAITING_CLIENT_TEARDOWN);

                if (mState.ordinal() >= State.AWAITING_CLIENT_PLAY.ordinal()) {
                    // We have a session, i.e. a previous SETUP succeeded.

                    int err = sendTrigger(mClientSessionID, TriggerType.TRIGGER_TEARDOWN);

                    if (err == OK) {
                        mState = State.AWAITING_CLIENT_TEARDOWN;

                        AMessage.obtain(WHAT_TEARDOWN_TRIGGER_TIMED_OUT, this).post(
                                TEARDOWN_TRIGGER_TIMEOU_SECS * 1_000_000L);

                        break;
                    }

                    // fall through.
                }

                finishStop();
                break;
            }

            case WHAT_PAUSE: {
                int err = OK;

                if (mState != State.PLAYING) {
                    err = INVALID_OPERATION;
                } else {
                    mState = State.PLAYING_TO_PAUSED;
                    sendTrigger(mClientSessionID, TriggerType.TRIGGER_PAUSE);
                }

                AMessage response = AMessage.obtain();
                response.setInt(ERR, err);
                msg.postResponse(response);
                break;
            }

            case WHAT_RESUME: {
                int err = OK;

                if (mState != State.PAUSED) {
                    err = INVALID_OPERATION;
                } else {
                    mState = State.PAUSED_TO_PLAYING;
                    sendTrigger(mClientSessionID, TriggerType.TRIGGER_PLAY);
                }

                AMessage response = AMessage.obtain();
                response.setInt(ERR, err);
                msg.postResponse(response);
                break;
            }

            case WHAT_REAP_DEAD_CLIENTS: {
                mReaperPending = false;

                if (mClientSessionID == 0 || mClientInfo.mPlaybackSession == null) {
                    break;
                }

                if (mClientInfo.mPlaybackSession.getLastLifesignUs()
                        + PLAYBACK_SESSION_TIMEOUT_US < TimeUtils.getMonotonicMicroTime()) {
                    Log.d(TAG, "playback session timed out, reaping");

                    mNetSession.destroySession(mClientSessionID);
                    mClientSessionID = 0;

                    onDisplayError(UNKNOWN_ERROR);
                } else {
                    scheduleReaper();
                }
                break;
            }

            case WHAT_PLAYBACK_SESSION_NOTIFY: {
                int playbackSessionID = msg.getInt("playbackSessionID");
                int what = msg.getInt(WHAT);

                if (what == PlaybackSession.WHAT_SESSION_DEAD) {
                    Log.d(TAG, "playback session wants to quit");

                    onDisplayError(UNKNOWN_ERROR);
                } else if (what == PlaybackSession.WHAT_SESSION_ESTABLISHED) {
                    mPlaybackSessionEstablished = true;

                    if (!mSinkSupportsVideo) {
                        onDisplayConnected(
                                0, // width,
                                0, // height,
                                0,
                                0);
                    } else {
                        int width, height;

                        width = mChosenVideoConfig.width;
                        height = mChosenVideoConfig.height;

                        onDisplayConnected(
                                width,
                                height,
                                0,
                                playbackSessionID);
                    }

                    finishPlay();

                    if (mState == State.ABOUT_TO_PLAY) {
                        mState = State.PLAYING;
                    }
                } else if (what == PlaybackSession.WHAT_SESSION_DESTROYED) {
                    disconnectClient2();
                } else {
                    CheckUtils.checkEqual(what, PlaybackSession.WHAT_BINARY_DATA);

                    int channel = msg.getInt(CHANNEL);
                    ABuffer data = msg.getThrow(DATA);

                    CheckUtils.checkLessThan(channel, 0xff);
                    CheckUtils.checkLessThan(data.size(), 0xffff);

                    int sessionID = msg.getInt(SESSION_ID);

                    ByteBuffer header = ByteBuffer.allocate(4);
                    header.put((byte) '$');
                    header.put((byte) channel);
                    header.put((byte) (data.size() >>> 8));
                    header.put((byte) (data.size() & 0xff));

                    mNetSession.sendRequest(sessionID, header, header.remaining());

                    mNetSession.sendRequest(sessionID, data.data(), data.size());
                }
                break;
            }

            case WHAT_KEEP_ALIVE: {
                int sessionID = msg.getInt(SESSION_ID);

                if (mClientSessionID != sessionID) {
                    // Obsolete event, client is already gone.
                    break;
                }

                sendM16(sessionID);
                break;
            }

            case WHAT_TEARDOWN_TRIGGER_TIMED_OUT: {
                if (mState == State.AWAITING_CLIENT_TEARDOWN) {
                    Log.d(TAG, "TEARDOWN trigger timed out, forcing disconnection");

                    CheckUtils.check(mStopReplyTo != null);
                    finishStop();
                    break;
                }
                break;
            }

            case WHAT_FINISH_STOP2: {
                finishStop2();
                break;
            }

            //case WHAT_HDCP_NOTIFY:
            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private int sendM1(int sessionID) {
        StringBuilder request = new StringBuilder("OPTIONS * RTSP/1.0\r\n");
        appendCommonResponse(request, mNextCSeq);

        request.append(
                "Require: org.wfa.wfd1.0\r\n"
                        + "\r\n");

        int err = mNetSession.sendRequest(sessionID, request);

        if (err != OK) {
            return err;
        }

        registerResponseHandler(sessionID, mNextCSeq, this::onReceiveM1Response);

        ++mNextCSeq;

        return OK;
    }

    private int sendM3(int sessionID) {
        String body =
                //"wfd_content_protection\r\n" // deleted this line! for HDCP Authentication Skip
                "wfd_video_formats\r\n"
                        + "wfd_audio_codecs\r\n"
                        + "wfd_client_rtp_ports\r\n";

        StringBuilder request = new StringBuilder(
                "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n");
        appendCommonResponse(request, mNextCSeq);

        request.append("Content-Type: text/parameters\r\n");
        request.append("Content-Length: ").append(body.length()).append("\r\n");
        request.append("\r\n");
        request.append(body);

        int err = mNetSession.sendRequest(sessionID, request);

        if (err != OK) {
            return err;
        }

        registerResponseHandler(sessionID, mNextCSeq, this::onReceiveM3Response);

        ++mNextCSeq;

        return OK;
    }

    @SuppressLint("DefaultLocale")
    private int sendM4(int sessionID) {
        CheckUtils.checkEqual(sessionID, mClientSessionID);

        StringBuilder body = new StringBuilder();

        if (mSinkSupportsVideo) {
            body.append("wfd_video_formats: ");

            VideoFormats chosenVideoFormat = new VideoFormats();
            chosenVideoFormat.disableAll();
            chosenVideoFormat.setNativeResolution(
                    mChosenVideoResolutionType, mChosenVideoResolutionIndex);
            chosenVideoFormat.setProfileLevel(
                    mChosenVideoResolutionType, mChosenVideoResolutionIndex,
                    mChosenVideoProfile, mChosenVideoLevel);

            body.append(chosenVideoFormat.getFormatSpec(true /* forM4Message */));
            body.append("\r\n");

            body.append(String.format("custom_video_formats: %d %d %d %d %d\r\n",
                    mChosenVideoConfig.width,
                    mChosenVideoConfig.height,
                    mChosenVideoConfig.framesPerSecond,
                    mChosenVideoConfig.profileType.ordinal(),
                    mChosenVideoConfig.levelType.ordinal()));
        }

        if (mSinkSupportsAudio) {
            body.append(String.format("wfd_audio_codecs: %s\r\n",
                    mUsingPCMAudio
                            ? "LPCM 00000002 00" // 2 ch PCM 48kHz
                            : "AAC 00000001 00"));  // 2 ch AAC 48kHz
        }

        body.append(String.format("wfd_presentation_URL: rtsp://%s/wfd1.0/streamid=0 none\r\n",
                mClientInfo.mLocalIP));

        body.append(String.format("wfd_client_rtp_ports: %s\r\n", mWfdClientRtpPorts));

        StringBuilder request = new StringBuilder(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n");
        appendCommonResponse(request, mNextCSeq);

        request.append("Content-Type: text/parameters\r\n");
        request.append("Content-Length: ").append(body.length()).append("\r\n");
        request.append("\r\n");
        request.append(body);

        int err = mNetSession.sendRequest(sessionID, request);

        if (err != OK) {
            return err;
        }

        registerResponseHandler(sessionID, mNextCSeq, this::onReceiveM4Response);

        ++mNextCSeq;

        return OK;
    }

    // M5
    private int sendTrigger(int sessionID, TriggerType triggerType) {
        StringBuilder body = new StringBuilder("wfd_trigger_method: ");
        switch (triggerType) {
            case TRIGGER_SETUP:
                body.append("SETUP");
                break;
            case TRIGGER_TEARDOWN:
                Log.d(TAG, "Sending TEARDOWN trigger.");
                body.append("TEARDOWN");
                break;
            case TRIGGER_PAUSE:
                body.append("PAUSE");
                break;
            case TRIGGER_PLAY:
                body.append("PLAY");
                break;
            default:
                throw new RuntimeException("TRESPASS");
        }

        body.append("\r\n");

        StringBuilder request = new StringBuilder(
                "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n");
        appendCommonResponse(request, mNextCSeq);

        request.append("Content-Type: text/parameters\r\n");
        request.append("Content-Length: ").append(body.length()).append("\r\n");
        request.append("\r\n");
        request.append(body);

        int err = mNetSession.sendRequest(sessionID, request);

        if (err != OK) {
            return err;
        }

        registerResponseHandler(sessionID, mNextCSeq, this::onReceiveM5Response);

        ++mNextCSeq;

        return OK;
    }

    private int sendM16(int sessionID) {
        StringBuilder request = new StringBuilder(
                "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n");
        appendCommonResponse(request, mNextCSeq);

        CheckUtils.checkEqual(sessionID, mClientSessionID);
        request.append("Session: ").append(mClientInfo.mPlaybackSessionID).append("\r\n");
        request.append("\r\n");  // Empty body

        int err = mNetSession.sendRequest(sessionID, request);

        if (err != OK) {
            return err;
        }

        registerResponseHandler(sessionID, mNextCSeq, this::onReceiveM16Response);

        ++mNextCSeq;

        scheduleKeepAlive(sessionID);

        return OK;
    }

    private int onReceiveM1Response(int sessionID, ParsedMessage msg) {
        int statusCode = msg.getStatusCode();
        if (statusCode == 0) {
            return ERROR_MALFORMED;
        }

        if (statusCode != 200) {
            return ERROR_UNSUPPORTED;
        }

        return OK;
    }

    private int onReceiveM3Response(int sessionID, ParsedMessage msg) {
        int statusCode = msg.getStatusCode();
        if (statusCode == 0) {
            return ERROR_MALFORMED;
        }

        if (statusCode != 200) {
            return ERROR_UNSUPPORTED;
        }

        Parameters params = Parameters.parse(msg.getContent());

        if (params == null) {
            return ERROR_MALFORMED;
        }

        String value = params.getParameter("wfd_client_rtp_ports");
        if (value == null) {
            Log.e(TAG, "Sink doesn't report its choice of wfd_client_rtp_ports");
            return ERROR_MALFORMED;
        }

        int port0 = 0, port1 = 0;
        Pattern pattern = Pattern.compile("RTP/AVP/(?:UDP|TCP);unicast (\\d+) (\\d+) mode=play");
        Matcher m = pattern.matcher(value);
        if (m.matches()) {
            port0 = Integer.parseInt(m.group(1));
            port1 = Integer.parseInt(m.group(2));
            if (port0 == 0 || port0 > 65535 || port1 != 0) {
                Log.e(TAG, "Sink chose its wfd_client_rtp_ports poorly " + value);

                return ERROR_MALFORMED;
            }
        } else if ("RTP/AVP/TCP;interleaved mode=play".equals(value)) {
            Log.e(TAG, "Unsupported value for wfd_client_rtp_ports " + value);

            return ERROR_UNSUPPORTED;
        }

        mWfdClientRtpPorts = value;
        mChosenRTPPort = port0;

        mSinkSupportsVideo = false;

        Pattern custPattern = Pattern.compile("(\\d+) (\\d+) (\\d+) (\\d+) (\\d+)");
        Matcher cm;

        if ((value = params.getParameter("custom_video_formats")) != null
                && (cm = custPattern.matcher(value)).matches()) {
            mChosenVideoConfig.width = Integer.parseInt(cm.group(1));
            mChosenVideoConfig.height = Integer.parseInt(cm.group(2));
            mChosenVideoConfig.framesPerSecond = Integer.parseInt(cm.group(3));
            mChosenVideoProfile = VideoFormats.ProfileType.valueOf(Integer.parseInt(cm.group(4)));
            mChosenVideoLevel = VideoFormats.LevelType.valueOf(Integer.parseInt(cm.group(5)));
            Log.w(TAG, "Sink report its choice of custom_video_formats: " + value);
            mChosenVideoConfig.profileType = mChosenVideoProfile;
            mChosenVideoConfig.levelType = mChosenVideoLevel;
            mSinkSupportsVideo = true;
        } else {
            Log.w(TAG, "Sink doesn't report its choice of custom_video_formats");

            if ((value = params.getParameter("wfd_video_formats")) == null) {
                Log.e(TAG, "Sink doesn't report its choice of wfd_video_formats");
                return ERROR_MALFORMED;
            }

            if (!"none".equals(value)) {
                mSinkSupportsVideo = true;
                if (!mSupportedSinkVideoFormats.parseFormatSpec(value)) {
                    Log.e(TAG, "Failed to parse sink provided wfd_video_formats " + value);

                    return ERROR_MALFORMED;
                }

                VideoFormats.ResolutionType[] resolutionType = new VideoFormats.ResolutionType[1];
                int[] resolutionIndex = new int[1];
                VideoFormats.ProfileType[] profile = new VideoFormats.ProfileType[1];
                VideoFormats.LevelType[] level = new VideoFormats.LevelType[1];

                if (!VideoFormats.pickBestFormat(
                        mSupportedSinkVideoFormats,
                        mSupportedSourceVideoFormats,
                        resolutionType,
                        resolutionIndex,
                        profile,
                        level)) {
                    Log.e(TAG, "Sink and source share no commonly supported video formats");

                    return ERROR_UNSUPPORTED;
                }

                mChosenVideoResolutionType = resolutionType[0];
                mChosenVideoResolutionIndex = resolutionIndex[0];
                mChosenVideoProfile = profile[0];
                mChosenVideoLevel = level[0];

                VideoFormats.FormatConfig fc = VideoFormats.getConfiguration(
                        mChosenVideoResolutionType, mChosenVideoResolutionIndex);
                CheckUtils.check(fc != null);

                Log.d(TAG, "Picked video resolution " + fc);

                Log.d(TAG, String.format("Picked AVC profile %s, level %s",
                        mChosenVideoProfile, mChosenVideoLevel));

                mChosenVideoConfig.width = fc.width;
                mChosenVideoConfig.height = fc.height;
                mChosenVideoConfig.framesPerSecond = fc.framesPerSecond;
                mChosenVideoConfig.interlaced = fc.interlaced;
                mChosenVideoConfig.profileType = mChosenVideoProfile;
                mChosenVideoConfig.levelType = mChosenVideoLevel;
            } else {
                Log.d(TAG, "Sink doesn't support video at all.");
            }
        }

        if ((value = params.getParameter("wfd_audio_codecs")) == null) {
            Log.e(TAG, "Sink doesn't report its choice of wfd_audio_codecs");
            return ERROR_MALFORMED;
        }

        mSinkSupportsAudio = false;

        if (!"none".equals(value)) {
            // FIXME ?
            //mSinkSupportsAudio = true;
            mSinkSupportsAudio = false;

            int[] modes = new int[1];
            getAudioModes(value, "AAC", modes);

            boolean supportsAAC = (modes[0] & 1) != 0;  // AAC 2ch 48kHz

            getAudioModes(value, "LPCM", modes);

            boolean supportsPCM = (modes[0] & 2) != 0;  // LPCM 2ch 48kHz

            if (supportsAAC) {
                Log.d(TAG, "Using AAC audio.");
                mUsingPCMAudio = false;
            } else if (supportsPCM) {
                Log.d(TAG, "Using PCM audio.");
                mUsingPCMAudio = true;
            } else {
                Log.d(TAG, "Sink doesn't support an audio format we do.");
                return ERROR_UNSUPPORTED;
            }
        } else {
            Log.d(TAG, "Sink doesn't support audio at all.");
        }

        if (!mSinkSupportsVideo && !mSinkSupportsAudio) {
            Log.e(TAG, "Sink supports neither video nor audio...");
            return ERROR_UNSUPPORTED;
        }

        return sendM4(sessionID);
    }

    private int onReceiveM4Response(int sessionID, ParsedMessage msg) {
        int statusCode;
        if ((statusCode = msg.getStatusCode()) == 0) {
            return ERROR_MALFORMED;
        }

        if (statusCode != 200) {
            return ERROR_UNSUPPORTED;
        }

        return sendTrigger(sessionID, TriggerType.TRIGGER_SETUP);
    }

    private int onReceiveM5Response(int sessionID, ParsedMessage msg) {
        int statusCode;
        if ((statusCode = msg.getStatusCode()) == 0) {
            return ERROR_MALFORMED;
        }

        if (statusCode != 200) {
            return ERROR_UNSUPPORTED;
        }

        return OK;
    }

    private int onReceiveM16Response(int sessionID, ParsedMessage msg) {
        // If only the response was required to include a "Session:" header...

        CheckUtils.checkEqual(sessionID, mClientSessionID);

        if (mClientInfo.mPlaybackSession != null) {
            mClientInfo.mPlaybackSession.updateLiveness();
        }

        return OK;
    }

    private void registerResponseHandler(int sessionID, int cseq, HandleRTSPResponseFunc func) {
        ResponseID id = new ResponseID(sessionID, cseq);
        mResponseHandlers.put(id, func);
    }

    private int onReceiveClientData(AMessage msg) {
        int sessionID = msg.getInt(SESSION_ID);
        ParsedMessage data = msg.getThrow(DATA);

        Log.d(TAG, String.format(
                "onReceiveClientData session[%d] received <<<<<<------\n%s------<<<<<<",
                sessionID, data));

        String method = data.getRequestField(0);

        int cseq = data.getInt("cseq", -1);
        if (cseq == -1) {
            sendErrorResponse(sessionID, "400 Bad Request", -1 /* cseq */);
            return ERROR_MALFORMED;
        }

        if (method.startsWith("RTSP/")) {
            // This is a response.

            ResponseID id = new ResponseID(sessionID, cseq);

            int index = mResponseHandlers.indexOfKey(id);

            if (index < 0) {
                Log.w(TAG, "Received unsolicited server response, cseq " + cseq);
                return ERROR_MALFORMED;
            }

            HandleRTSPResponseFunc func = mResponseHandlers.valueAt(index);
            mResponseHandlers.removeAt(index);

            int err = func.handle(sessionID, data);

            if (err != OK) {
                Log.w(TAG, String.format("Response handler for session[%d], cseq[%d], err[%d]",
                        sessionID, cseq, err));

                return err;
            }

            return OK;
        }

        String version = data.getRequestField(2);
        if (!"RTSP/1.0".equals(version)) {
            sendErrorResponse(sessionID, "505 RTSP Version not supported", cseq);
            return ERROR_UNSUPPORTED;
        }

        int err;
        switch (method) {
            case "OPTIONS":
                err = onOptionsRequest(sessionID, cseq, data);
                break;
            case "SETUP":
                err = onSetupRequest(sessionID, cseq, data);
                break;
            case "PLAY":
                err = onPlayRequest(sessionID, cseq, data);
                break;
            case "PAUSE":
                err = onPauseRequest(sessionID, cseq, data);
                break;
            case "TEARDOWN":
                err = onTeardownRequest(sessionID, cseq, data);
                break;
            case "GET_PARAMETER":
                err = onGetParameterRequest(sessionID, cseq, data);
                break;
            case "SET_PARAMETER":
                err = onSetParameterRequest(sessionID, cseq, data);
                break;
            default:
                sendErrorResponse(sessionID, "405 Method Not Allowed", cseq);

                err = ERROR_UNSUPPORTED;
                break;
        }

        return err;
    }

    private int onOptionsRequest(int sessionID, int cseq, ParsedMessage data) {
        int[] playbackSessionID = new int[1];
        PlaybackSession playbackSession = findPlaybackSession(data, playbackSessionID);

        if (playbackSession != null) {
            playbackSession.updateLiveness();
        }

        StringBuilder response = new StringBuilder("RTSP/1.0 200 OK\r\n");
        appendCommonResponse(response, cseq);

        response.append(
                "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, "
                        + "GET_PARAMETER, SET_PARAMETER\r\n");

        response.append("\r\n");

        int err = mNetSession.sendRequest(sessionID, response);

        if (err == OK) {
            err = sendM3(sessionID);
        }

        return err;
    }

    @SuppressLint("DefaultLocale")
    private int onSetupRequest(int sessionID, int cseq, ParsedMessage data) {
        CheckUtils.checkEqual(sessionID, mClientSessionID);
        if (mClientInfo.mPlaybackSessionID != -1) {
            // We only support a single playback session per client.
            // This is due to the reversed keep-alive design in the wfd specs...
            sendErrorResponse(sessionID, "400 Bad Request", cseq);
            return ERROR_MALFORMED;
        }

        String transport = data.getString("transport");
        if (transport == null) {
            sendErrorResponse(sessionID, "400 Bad Request", cseq);
            return ERROR_MALFORMED;
        }

        RTPSender.TransportMode rtpMode = RTPSender.TransportMode.TRANSPORT_UDP;

        int clientRtp = -1;
        int clientRtcp = -1;
        if (transport.startsWith("RTP/AVP/TCP;")) {
            String interleaved = ParsedMessage.getAttribute(transport, "interleaved");
            boolean isInterleaved = false;
            String[] splitArr;
            if (interleaved != null && (splitArr = interleaved.split("-")).length == 2) {
                try {
                    clientRtp = Integer.parseInt(splitArr[0]);
                    clientRtcp = Integer.parseInt(splitArr[1]);
                    isInterleaved = true;
                } catch (NumberFormatException ignored) {
                }
            }
            if (isInterleaved) {
                rtpMode = RTPSender.TransportMode.TRANSPORT_TCP_INTERLEAVED;
            } else {
                boolean badRequest = false;

                String clientPort = ParsedMessage.getAttribute(transport, "client_port");
                if (clientPort == null) {
                    badRequest = true;
                } else {
                    String[] splitPorts = clientPort.split("-");
                    if (splitPorts.length == 2) {
                        try {
                            clientRtp = Integer.parseInt(splitPorts[0]);
                            clientRtcp = Integer.parseInt(splitPorts[1]);
                        } catch (NumberFormatException ignored) {
                            badRequest = true;
                        }
                    } else {
                        try {
                            clientRtp = Integer.parseInt(splitPorts[0]);
                            // No RTCP.
                            clientRtcp = -1;
                        } catch (NumberFormatException ignored) {
                            badRequest = true;
                        }
                    }
                }

                if (badRequest) {
                    sendErrorResponse(sessionID, "400 Bad Request", cseq);
                    return ERROR_MALFORMED;
                }

                rtpMode = RTPSender.TransportMode.TRANSPORT_TCP;
            }
        } else if (transport.startsWith("RTP/AVP;unicast;")
                || transport.startsWith("RTP/AVP/UDP;unicast;")) {
            boolean badRequest = false;

            String clientPort = ParsedMessage.getAttribute(transport, "client_port");

            if (clientPort == null) {
                badRequest = true;
            } else {
                String[] splitPorts = clientPort.split("-");
                if (splitPorts.length == 2) {
                    try {
                        clientRtp = Integer.parseInt(splitPorts[0]);
                        clientRtcp = Integer.parseInt(splitPorts[1]);
                    } catch (NumberFormatException ignored) {
                        badRequest = true;
                    }
                } else {
                    try {
                        clientRtp = Integer.parseInt(splitPorts[0]);
                        // No RTCP.
                        clientRtcp = -1;
                    } catch (NumberFormatException ignored) {
                        badRequest = true;
                    }
                }
            }

            if (badRequest) {
                sendErrorResponse(sessionID, "400 Bad Request", cseq);
                return ERROR_MALFORMED;
            }
//#if 1
            // The older LG dongles doesn't specify client_port=xxx apparently.
        } else if ("RTP/AVP/UDP;unicast".equals(transport)) {
            clientRtp = 19000;
            clientRtcp = -1;
//#endif
        } else {
            sendErrorResponse(sessionID, "461 Unsupported Transport", cseq);
            return ERROR_UNSUPPORTED;
        }

        int playbackSessionID = makeUniquePlaybackSessionID();

        AMessage notify = AMessage.obtain(WHAT_PLAYBACK_SESSION_NOTIFY, this);
        notify.setInt("playbackSessionID", playbackSessionID);
        notify.setInt(SESSION_ID, sessionID);

        PlaybackSession playbackSession = new PlaybackSession(mMediaProjection, mDisplayMetrics,
                mNetSession, notify, mMediaPath);

        String uri = data.getRequestField(1);

        if (!uri.toLowerCase().startsWith("rtsp://")) {
            sendErrorResponse(sessionID, "400 Bad Request", cseq);
            return ERROR_MALFORMED;
        }

        if (!uri.endsWith("/wfd1.0/streamid=0")) {
            sendErrorResponse(sessionID, "404 Not found", cseq);
            return ERROR_MALFORMED;
        }

        RTPSender.TransportMode rtcpMode = RTPSender.TransportMode.TRANSPORT_UDP;
        if (clientRtcp < 0) {
            rtcpMode = RTPSender.TransportMode.TRANSPORT_NONE;
        }

        int err = playbackSession.init(
                mClientInfo.mRemoteIP,
                clientRtp,
                rtpMode,
                clientRtcp,
                rtcpMode,
                mSinkSupportsAudio,
                mUsingPCMAudio,
                mSinkSupportsVideo,
                mChosenVideoConfig);

        if (err != OK) {
            playbackSession.removeCallbacksAndMessages(null);
            playbackSession = null;
        }

        switch (err) {
            case OK:
                break;
            case -ENOENT:
                sendErrorResponse(sessionID, "404 Not Found", cseq);
                return err;
            default:
                sendErrorResponse(sessionID, "403 Forbidden", cseq);
                return err;
        }

        mClientInfo.mPlaybackSessionID = playbackSessionID;
        mClientInfo.mPlaybackSession = playbackSession;

        StringBuilder response = new StringBuilder("RTSP/1.0 200 OK\r\n");
        appendCommonResponse(response, cseq, playbackSessionID);

        if (rtpMode == RTPSender.TransportMode.TRANSPORT_TCP_INTERLEAVED) {
            response.append(String.format(
                    "Transport: RTP/AVP/TCP;interleaved=%d-%d;",
                    clientRtp, clientRtcp));
        } else {
            int serverRtp = playbackSession.getRTPPort();

            String transportString = "UDP";
            if (rtpMode == RTPSender.TransportMode.TRANSPORT_TCP) {
                transportString = "TCP";
            }

            if (clientRtcp >= 0) {
                response.append(String.format(
                        "Transport: RTP/AVP/%s;unicast;client_port=%d-%d;"
                                + "server_port=%d-%d\r\n",
                        transportString, clientRtp, clientRtcp, serverRtp, serverRtp + 1));
            } else {
                response.append(String.format(
                        "Transport: RTP/AVP/%s;unicast;client_port=%d;"
                                + "server_port=%d\r\n",
                        transportString, clientRtp, serverRtp));
            }
        }

        response.append("\r\n");

        err = mNetSession.sendRequest(sessionID, response);

        if (err != OK) {
            return err;
        }

        mState = State.AWAITING_CLIENT_PLAY;

        scheduleReaper();
        scheduleKeepAlive(sessionID);

        return OK;
    }

    private int onPlayRequest(int sessionID, int cseq, ParsedMessage data) {
        int[] playbackSessionID = new int[1];
        PlaybackSession playbackSession = findPlaybackSession(data, playbackSessionID);

        if (playbackSession == null) {
            sendErrorResponse(sessionID, "454 Session Not Found", cseq);
            return ERROR_MALFORMED;
        }

        if (mState != State.AWAITING_CLIENT_PLAY
                && mState != State.PAUSED_TO_PLAYING
                && mState != State.PAUSED) {
            Log.w(TAG, "Received PLAY request but we're in state " + mState);

            sendErrorResponse(sessionID, "455 Method Not Valid in This State", cseq);

            return INVALID_OPERATION;
        }

        Log.d(TAG, "Received PLAY request");
        if (mPlaybackSessionEstablished) {
            finishPlay();
        } else {
            Log.d(TAG, "deferring PLAY request until session established");
        }

        StringBuilder response = new StringBuilder("RTSP/1.0 200 OK\r\n");
        appendCommonResponse(response, cseq, playbackSessionID[0]);
        response.append("Range: npt=now-\r\n");
        response.append("\r\n");

        int err = mNetSession.sendRequest(sessionID, response);

        if (err != OK) {
            return err;
        }

        if (mState == State.PAUSED_TO_PLAYING || mPlaybackSessionEstablished) {
            mState = State.PLAYING;
            return OK;
        }

        CheckUtils.checkEqual(mState, State.AWAITING_CLIENT_PLAY);
        mState = State.ABOUT_TO_PLAY;

        return OK;
    }

    private int onPauseRequest(int sessionID, int cseq, ParsedMessage data) {
        int[] playbackSessionID = new int[1];
        PlaybackSession playbackSession = findPlaybackSession(data, playbackSessionID);

        if (playbackSession == null) {
            sendErrorResponse(sessionID, "454 Session Not Found", cseq);
            return ERROR_MALFORMED;
        }

        Log.d(TAG, "Received PAUSE request");

        if (mState != State.PLAYING_TO_PAUSED && mState != State.PLAYING) {
            return INVALID_OPERATION;
        }

        int err = playbackSession.pause();
        CheckUtils.checkEqual(err, OK);

        StringBuilder response = new StringBuilder("RTSP/1.0 200 OK\r\n");
        appendCommonResponse(response, cseq, playbackSessionID[0]);
        response.append("\r\n");

        err = mNetSession.sendRequest(sessionID, response);

        if (err != OK) {
            return err;
        }

        mState = State.PAUSED;

        return err;
    }

    private int onTeardownRequest(int sessionID, int cseq, ParsedMessage data) {
        Log.d(TAG, "Received TEARDOWN request");

        int[] playbackSessionID = new int[1];
        PlaybackSession playbackSession = findPlaybackSession(data, playbackSessionID);

        if (playbackSession == null) {
            sendErrorResponse(sessionID, "454 Session Not Found", cseq);
            return ERROR_MALFORMED;
        }

        StringBuilder response = new StringBuilder("RTSP/1.0 200 OK\r\n");
        appendCommonResponse(response, cseq, playbackSessionID[0]);
        response.append("Connection: close\r\n");
        response.append("\r\n");

        mNetSession.sendRequest(sessionID, response);

        if (mState == State.AWAITING_CLIENT_TEARDOWN) {
            CheckUtils.check(mStopReplyTo != null);
            finishStop();
        } else {
            onDisplayError(UNKNOWN_ERROR);
        }

        return OK;
    }

    private int onGetParameterRequest(int sessionID, int cseq, ParsedMessage data) {
        int[] playbackSessionID = new int[1];
        PlaybackSession playbackSession = findPlaybackSession(data, playbackSessionID);

        if (playbackSession == null) {
            sendErrorResponse(sessionID, "454 Session Not Found", cseq);
            return ERROR_MALFORMED;
        }

        playbackSession.updateLiveness();

        StringBuilder response = new StringBuilder("RTSP/1.0 200 OK\r\n");
        appendCommonResponse(response, cseq, playbackSessionID[0]);
        response.append("\r\n");

        int err = mNetSession.sendRequest(sessionID, response);
        return err;

    }

    private int onSetParameterRequest(int sessionID, int cseq, ParsedMessage data) {
        int[] playbackSessionID = new int[1];
        PlaybackSession playbackSession = findPlaybackSession(data, playbackSessionID);

        if (playbackSession == null) {
            sendErrorResponse(sessionID, "454 Session Not Found", cseq);
            return ERROR_MALFORMED;
        }

        if (data.getContent().contains("wfd_idr_request\r\n")) {
            playbackSession.requestIDRFrame();
        }

        playbackSession.updateLiveness();

        StringBuilder response = new StringBuilder("RTSP/1.0 200 OK\r\n");
        appendCommonResponse(response, cseq, playbackSessionID[0]);
        response.append("\r\n");

        int err = mNetSession.sendRequest(sessionID, response);
        return err;
    }

    private void sendErrorResponse(int sessionID, String errorDetail, int cseq) {
        StringBuilder response = new StringBuilder();
        response.append("RTSP/1.0 ");
        response.append(errorDetail);
        response.append("\r\n");

        appendCommonResponse(response, cseq);

        response.append("\r\n");

        mNetSession.sendRequest(sessionID, response);
    }

    private void scheduleReaper() {
        if (mReaperPending) {
            return;
        }

        mReaperPending = true;
        AMessage.obtain(WHAT_REAP_DEAD_CLIENTS, this).post(REAPER_INTERVAL_US);
    }

    private void scheduleKeepAlive(int sessionID) {
        // We need to send updates at least 5 secs before the timeout is set to
        // expire, make sure the timeout is greater than 5 secs to begin with.
        CheckUtils.checkGreaterThan(PLAYBACK_SESSION_TIMEOUT_US, 5_000_000L);

        AMessage msg = AMessage.obtain(WHAT_KEEP_ALIVE, this);
        msg.setInt(SESSION_ID, sessionID);
        msg.post(PLAYBACK_SESSION_TIMEOUT_US - 5_000_000L);
    }

    private PlaybackSession findPlaybackSession(
            ParsedMessage data, final int[] playbackSessionID) {
        playbackSessionID[0] = data.getInt("session", -1);
        if (playbackSessionID[0] == -1) {
            // XXX the older dongles do not always include a "Session:" header.
            playbackSessionID[0] = mClientInfo.mPlaybackSessionID;
            return mClientInfo.mPlaybackSession;
        }

        if (playbackSessionID[0] != mClientInfo.mPlaybackSessionID) {
            return null;
        }

        return mClientInfo.mPlaybackSession;
    }

    private void finishStop() {
        Log.d(TAG, "finishStop");

        mState = State.STOPPING;

        disconnectClientAsync();
    }

    private void disconnectClientAsync() {
        Log.d(TAG, "disconnectClient");

        if (mClientInfo.mPlaybackSession == null) {
            disconnectClient2();
            return;
        }

        if (mClientInfo.mPlaybackSession != null) {
            Log.d(TAG, "Destroying PlaybackSession");
            mClientInfo.mPlaybackSession.destroyAsync();
        }
    }

    private void disconnectClient2() {
        Log.d(TAG, "disconnectClient2");

        if (mClientInfo.mPlaybackSession != null) {
            mClientInfo.mPlaybackSession.removeCallbacksAndMessages(null);
            mClientInfo.mPlaybackSession = null;
        }

        if (mClientSessionID != 0) {
            mNetSession.destroySession(mClientSessionID);
            mClientSessionID = 0;
        }

        mMediaProjection.stop();

        finishStopAfterDisconnectingClient();
    }

    private void finishStopAfterDisconnectingClient() {
        Log.d(TAG, "finishStopAfterDisconnectingClient");

        finishStop2();
    }

    private void finishStop2() {
        Log.d(TAG, "finishStop2");

        if (mSessionID != 0) {
            mNetSession.destroySession(mSessionID);
            mSessionID = 0;
        }

        Log.d(TAG, "We're stopped");
        mState = State.STOPPED;

        int err = OK;

        AMessage response = AMessage.obtain();
        response.setInt(ERR, err);
        mStopReplyTo.postResponse(response);
        mStopReplyTo = null;
    }

    private void finishPlay() {
        PlaybackSession playbackSession = mClientInfo.mPlaybackSession;

        int err = playbackSession.play();
        CheckUtils.checkEqual(err, OK);
    }

    private void onDisplayConnected(int width, int height, int flags, int session) {
        Log.d(TAG, String.format(
                "onDisplayConnected width=%d, height=%d, flags = 0x%08x, session = %d",
                width, height, flags, session));
    }

    private void onDisplayDisconnected() {
        Log.d(TAG, "onDisplayDisconnected");
    }

    private void onDisplayError(int error) {
        Log.d(TAG, "onDisplayError error=" + error, new Exception());
    }

    // sink_audio_list := ("LPCM"|"AAC"|"AC3" HEXDIGIT*8 HEXDIGIT*2)
//                       (", " sink_audio_list)*
    private static void getAudioModes(String s, String prefix, final int[] modes) {
        modes[0] = 0;

        int prefixLen = prefix.length();

        Pattern pattern = Pattern.compile("(\\p{XDigit}{8}) (\\p{XDigit}{2})");

        while (s.charAt(0) != '0') {
            if (s.startsWith(prefix) && s.charAt(prefixLen) == ' ') {
                Matcher m = pattern.matcher(s.substring(prefixLen + 1));
                if (!m.matches()) {
                    modes[0] = 0;
                } else {
                    modes[0] = Integer.parseInt(m.group(1), 16);
                    int latency = Integer.parseInt(m.group(2), 16);
                }

                return;
            }

            int commaPos = s.indexOf(',');
            if (commaPos != -1) {
                s = s.substring(commaPos + 1).trim();
            } else {
                break;
            }
        }
    }

    private static final Random RAND = new Random();

    private static int makeUniquePlaybackSessionID() {
        return Math.abs(RAND.nextInt());
    }

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);

    private static void appendCommonResponse(StringBuilder response, int cseq) {
        appendCommonResponse(response, cseq, -1);
    }

    private static void appendCommonResponse(StringBuilder response, int cseq,
            int playbackSessionID) {
        response.append("Date: ");
        response.append(DATE_FORMAT.format(Date.from(Instant.now())));
        response.append("\r\n");

        response.append("Server: ").append(USER_AGENT).append("\r\n");

        if (cseq >= 0) {
            response.append("CSeq: ").append(cseq).append("\r\n");
        }

        if (playbackSessionID >= 0L) {
            response.append("Session: ").append(playbackSessionID).append(";timeout=")
                    .append(PLAYBACK_SESSION_TIMEOUT_SECS).append("\r\n");
        }
    }
}
