package com.hym.rtplib.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RTPUtils {
    private RTPUtils() {
    }

    public static final InetAddress INET_ANY;

    static {
        try {
            INET_ANY = InetAddress.getByName("0.0.0.0");
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    public static short U16_AT(byte[] bytes, int start) {
        return (short) ((bytes[start] & 0xFF) << 8 | (bytes[start + 1] & 0xFF));
    }

    public static short U16_AT(ByteBuffer data, int start) {
        CheckUtils.check(data.order() == ByteOrder.BIG_ENDIAN);
        return data.getShort(start);
    }

    public static int U32_AT(byte[] bytes, int start) {
        return (bytes[start] & 0xFF) << 24 | (bytes[start + 1] & 0xFF) << 16
                | (bytes[start + 2] & 0xFF) << 8 | (bytes[start + 3] & 0xFF);
    }

    public static int U32_AT(ByteBuffer data, int start) {
        CheckUtils.check(data.order() == ByteOrder.BIG_ENDIAN);
        return data.getInt(start);
    }

    public static long U64_AT(byte[] bytes, int start) {
        int h = U32_AT(bytes, start);
        int l = U32_AT(bytes, start + 4);
        return (((long) h) << 32) | ((long) l) & 0xFFFFFFFFL;
    }

    public static long U64_AT(ByteBuffer data, int start) {
        CheckUtils.check(data.order() == ByteOrder.BIG_ENDIAN);
        return data.getLong(start);
    }
}
