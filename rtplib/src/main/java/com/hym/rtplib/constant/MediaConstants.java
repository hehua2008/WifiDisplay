package com.hym.rtplib.constant;

import java.nio.ByteBuffer;

public interface MediaConstants {
    String WHAT = "what";
    String ACCESS_UNIT = "accessUnit";
    String TRACK_INDEX = "trackIndex";
    String FOLLOWS_DISCONTINUITY = "followsDiscontinuity";
    String SSRC = "ssrc";
    String RTP_TIME = "rtp-time";
    String TIME_US = "timeUs";
    String MARKER = "M";
    String GENERATION = "generation";
    String PT = "PT";
    String SESSION_ID = "sessionID";
    String REASON = "reason";
    String SEND = "send";
    String ERR = "err";
    String DETAIL = "detail";
    String NUM_BYTES_QUEUED = "numBytesQueued";
    String SERVER_IP = "server-ip";
    String SERVER_PORT = "server-port";
    String CLIENT_IP = "client-ip";
    String ARRIVAL_TIME_US = "arrivalTimeUs";
    String AVG_LATENCY_US = "avgLatencyUs";
    String MAX_LATENCY_US = "maxLatencyUs";
    String FROM_ADDR = "fromAddr";
    String FROM_PORT = "fromPort";
    String DATA = "data";
    String CHANNEL = "channel";
    String HEADER_BYTE = "headerByte";
    String REMOTE_HOST = "remoteHost";
    String REMOTE_PORT = "remotePort";
    String LOCAL_PORT = "localPort";
    String CLIENT_PORT = "client-port";
    String OFFSET = "offset";
    String IFACE = "iface";
    String SUSPEND = "suspend";
    String IS_IDR = "isIDR";

    int VIDEO_BIT_RATE_MIN = 500_000;
    int VIDEO_BIT_RATE_MAX = 10_000_000;

    int FRAME_RATE_MIN = 10;
    int FRAME_RATE_MAX = 30;

    int I_FRAME_INTERVAL_MIN = 1;
    int I_FRAME_INTERVAL_MAX = 60;

    int WIFI_DISPLAY_DEFAULT_PORT = 7236;

    byte[] NAL_START_BYTE = {0x00, 0x00, 0x00, 0x01};

    ByteBuffer NAL_START_BUFFER = ByteBuffer.wrap(NAL_START_BYTE);
}
