package com.hym.rtplib;

import java.util.Random;

public interface RTPBase {
    enum PacketizationMode {
        PACKETIZATION_TRANSPORT_STREAM,
        PACKETIZATION_H264,
        PACKETIZATION_AAC,
        PACKETIZATION_NONE,
    }

    enum TransportMode {
        TRANSPORT_UNDEFINED,
        TRANSPORT_NONE,
        TRANSPORT_UDP,
        TRANSPORT_TCP,
        TRANSPORT_TCP_INTERLEAVED,
    }

    // Really UDP _payload_ size
    int MAX_UDP_PACKET_SIZE = 1472;   // 1472 good, 1473 bad on Android@Home

    static int pickRandomRTPPort() {
        // Pick an even integer in range [1024, 65534)
        int range = (65534 - 1024) / 2;
        Random rand = new Random(System.currentTimeMillis());
        return ((int) ((range + 1) * rand.nextFloat())) * 2 + 1024;
    }
}
