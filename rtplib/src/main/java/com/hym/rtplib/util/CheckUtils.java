package com.hym.rtplib.util;

public class CheckUtils {
    private CheckUtils() {
    }

    public static void check(boolean condition) {
        if (!condition) {
            throw new RuntimeException();
        }
    }

    public static <T extends Comparable<T>> void checkLessOrEqual(T left, T right) {
        if (left.compareTo(right) > 0) {
            throw new RuntimeException();
        }
    }

    public static <T extends Comparable<T>> void checkLessThan(T left, T right) {
        if (left.compareTo(right) >= 0) {
            throw new RuntimeException();
        }
    }

    public static <T extends Comparable<T>> void checkGreaterOrEqual(T left, T right) {
        if (left.compareTo(right) < 0) {
            throw new RuntimeException();
        }
    }

    public static <T extends Comparable<T>> void checkGreaterThan(T left, T right) {
        if (left.compareTo(right) <= 0) {
            throw new RuntimeException();
        }
    }

    public static <T extends Comparable<T>> void checkEqual(T left, T right) {
        if (left.compareTo(right) != 0) {
            throw new RuntimeException();
        }
    }

    public static <T extends Comparable<T>> void checkNotEqual(T left, T right) {
        if (left.compareTo(right) == 0) {
            throw new RuntimeException();
        }
    }
}
