package com.hym.rtplib.util;

import android.os.SystemClock;

public class TimeUtils {
    private TimeUtils() {
    }

    public static long getMonotonicMilliTime() {
        return getMonotonicNanoTime() / 1_000_000L;
    }

    public static long getMonotonicMicroTime() {
        return getMonotonicNanoTime() / 1_000L;
    }

    public static long getMonotonicNanoTime() {
        return SystemClock.elapsedRealtimeNanos();
    }
}
