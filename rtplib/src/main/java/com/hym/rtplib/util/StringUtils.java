package com.hym.rtplib.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class StringUtils {
    private static final char REPLACEMENT_CHAR = (char) 0xfffd;

    private static final int[] TABLE_UTF8_NEEDED = new int[]{
            //0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f
            0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 0xc0 - 0xcf
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, // 0xd0 - 0xdf
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, // 0xe0 - 0xef
            3, 3, 3, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // 0xf0 - 0xff
    };

    private static final ThreadLocal<StringBuilder> TMP_STRING_BUILDER
            = new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
            return new StringBuilder();
        }
    };

    private StringUtils() {
    }

    public static String newStringFromBytes(byte[] data) {
        return newStringFromBytes(data, 0, data.length);
    }

    public static String newStringFromBytes(byte[] data, int offset, int byteCount) {
        return newStringFromBytes(data, offset, byteCount, StandardCharsets.UTF_8);
    }

    public static String newStringFromBytes(byte[] data, int offset, int byteCount,
            Charset charset) {
        if ((offset | byteCount) < 0 || byteCount > data.length - offset) {
            throw new IndexOutOfBoundsException("length=" + data.length + "; regionStart=" + offset
                    + "; regionLength=" + byteCount);
        }

        char[] value;
        int length;

        // We inline UTF-8, ISO-8859-1, and US-ASCII decoders for speed.
        String canonicalCharsetName = charset.name();
        if (canonicalCharsetName.equals("UTF-8")) {
            /*
            This code converts a UTF-8 byte sequence to a Java String (UTF-16).
            It implements the W3C recommended UTF-8 decoder.
            https://www.w3.org/TR/encoding/#utf-8-decoder

            Unicode 3.2 Well-Formed UTF-8 Byte Sequences
            Code Points        First  Second Third Fourth
            U+0000..U+007F     00..7F
            U+0080..U+07FF     C2..DF 80..BF
            U+0800..U+0FFF     E0     A0..BF 80..BF
            U+1000..U+CFFF     E1..EC 80..BF 80..BF
            U+D000..U+D7FF     ED     80..9F 80..BF
            U+E000..U+FFFF     EE..EF 80..BF 80..BF
            U+10000..U+3FFFF   F0     90..BF 80..BF 80..BF
            U+40000..U+FFFFF   F1..F3 80..BF 80..BF 80..BF
            U+100000..U+10FFFF F4     80..8F 80..BF 80..BF

            Please refer to Unicode as the authority.
            p.126 Table 3-7 in http://www.unicode.org/versions/Unicode10.0.0/ch03.pdf

            Handling Malformed Input
            The maximal subpart should be replaced by a single U+FFFD. Maximal subpart is
            the longest code unit subsequence starting at an unconvertible offset that is either
            1) the initial subsequence of a well-formed code unit sequence, or
            2) a subsequence of length one:
            One U+FFFD should be emitted for every sequence of bytes that is an incomplete prefix
            of a valid sequence, and with the conversion to restart after the incomplete sequence.

            For example, in byte sequence "41 C0 AF 41 F4 80 80 41", the maximal subparts are
            "C0", "AF", and "F4 80 80". "F4 80 80" can be the initial subsequence of "F4 80 80 80",
            but "C0" can't be the initial subsequence of any well-formed code unit sequence.
            Thus, the output should be "A\ufffd\ufffdA\ufffdA".

            Please refer to section "Best Practices for Using U+FFFD." in
            http://www.unicode.org/versions/Unicode10.0.0/ch03.pdf
            */
            byte[] d = data;
            char[] v = new char[byteCount];

            int idx = offset;
            int last = offset + byteCount;
            int s = 0;

            int codePoint = 0;
            int utf8BytesSeen = 0;
            int utf8BytesNeeded = 0;
            int lowerBound = 0x80;
            int upperBound = 0xbf;

            while (idx < last) {
                int b = d[idx++] & 0xff;
                if (utf8BytesNeeded == 0) {
                    if ((b & 0x80) == 0) { // ASCII char. 0xxxxxxx
                        v[s++] = (char) b;
                        continue;
                    }

                    if ((b & 0x40) == 0) { // 10xxxxxx is illegal as first byte
                        v[s++] = REPLACEMENT_CHAR;
                        continue;
                    }

                    // 11xxxxxx
                    int tableLookupIndex = b & 0x3f;
                    utf8BytesNeeded = TABLE_UTF8_NEEDED[tableLookupIndex];
                    if (utf8BytesNeeded == 0) {
                        v[s++] = REPLACEMENT_CHAR;
                        continue;
                    }

                    // utf8BytesNeeded
                    // 1: b & 0x1f
                    // 2: b & 0x0f
                    // 3: b & 0x07
                    codePoint = b & (0x3f >> utf8BytesNeeded);
                    if (b == 0xe0) {
                        lowerBound = 0xa0;
                    } else if (b == 0xed) {
                        upperBound = 0x9f;
                    } else if (b == 0xf0) {
                        lowerBound = 0x90;
                    } else if (b == 0xf4) {
                        upperBound = 0x8f;
                    }
                } else {
                    if (b < lowerBound || b > upperBound) {
                        // The bytes seen are ill-formed. Substitute them with U+FFFD
                        v[s++] = REPLACEMENT_CHAR;
                        codePoint = 0;
                        utf8BytesNeeded = 0;
                        utf8BytesSeen = 0;
                        lowerBound = 0x80;
                        upperBound = 0xbf;
                        /*
                         * According to the Unicode Standard,
                         * "a UTF-8 conversion process is required to never consume well-formed
                         * subsequences as part of its error handling for ill-formed subsequences"
                         * The current byte could be part of well-formed subsequences. Reduce the
                         * index by 1 to parse it in next loop.
                         */
                        idx--;
                        continue;
                    }

                    lowerBound = 0x80;
                    upperBound = 0xbf;
                    codePoint = (codePoint << 6) | (b & 0x3f);
                    utf8BytesSeen++;
                    if (utf8BytesNeeded != utf8BytesSeen) {
                        continue;
                    }

                    // Encode chars from U+10000 up as surrogate pairs
                    if (codePoint < 0x10000) {
                        v[s++] = (char) codePoint;
                    } else {
                        v[s++] = (char) ((codePoint >> 10) + 0xd7c0);
                        v[s++] = (char) ((codePoint & 0x3ff) + 0xdc00);
                    }

                    utf8BytesSeen = 0;
                    utf8BytesNeeded = 0;
                    codePoint = 0;
                }
            }

            // The bytes seen are ill-formed. Substitute them by U+FFFD
            if (utf8BytesNeeded != 0) {
                v[s++] = REPLACEMENT_CHAR;
            }

            if (s == byteCount) {
                // We guessed right, so we can use our temporary array as-is.
                value = v;
                length = s;
            } else {
                // Our temporary array was too big, so reallocate and copy.
                value = new char[s];
                length = s;
                System.arraycopy(v, 0, value, 0, s);
            }
        } else {
            CharBuffer cb = charset.decode(ByteBuffer.wrap(data, offset, byteCount));
            length = cb.length();
            // The call to newStringFromChars below will copy length bytes out of value, so it does
            // not matter that cb.array().length may be > cb.length() or that a Charset could keep a
            // reference to the CharBuffer it returns and later mutate it.
            value = cb.array();
        }

        StringBuilder tmpSb = TMP_STRING_BUILDER.get();
        tmpSb.delete(0, tmpSb.length());
        tmpSb.append(value, 0, length);
        return tmpSb.toString();
    }

   /* public static void intToByteArray(int intValue, byte[] bytes) {
        bytes[0] = (byte) ((intValue >> 24) & 0xFF);
        bytes[1] = (byte) ((intValue >> 16) & 0xFF);
        bytes[2] = (byte) ((intValue >> 8) & 0xFF);
        bytes[3] = (byte) (intValue & 0xFF);
    }

    public static int byteArrayToInt(byte[] bytes) {
        return byteArrayToInt(bytes, 0);

    }

    public static int byteArrayToInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24
                | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8
                | bytes[offset + 3] & 0xFF;
    }

    public static void longToByteArray(int longValue, byte[] bytes) {
        bytes[0] = (byte) ((longValue >> 56) & 0xFF);
        bytes[1] = (byte) ((longValue >> 48) & 0xFF);
        bytes[2] = (byte) ((longValue >> 40) & 0xFF);
        bytes[3] = (byte) ((longValue >> 32) & 0xFF);
        bytes[4] = (byte) ((longValue >> 24) & 0xFF);
        bytes[5] = (byte) ((longValue >> 16) & 0xFF);
        bytes[6] = (byte) ((longValue >> 8) & 0xFF);
        bytes[7] = (byte) (longValue & 0xFF);
    }

    public static long byteArrayToLong(byte[] bytes) {
        return byteArrayToLong(bytes, 0);

    }

    public static long byteArrayToLong(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xFF) << 56
                | ((long) bytes[offset + 1] & 0xFF) << 48
                | ((long) bytes[offset + 2] & 0xFF) << 40
                | ((long) bytes[offset + 3] & 0xFF) << 32
                | ((long) bytes[offset + 4] & 0xFF) << 24
                | ((long) bytes[offset + 5] & 0xFF) << 16
                | ((long) bytes[offset + 6] & 0xFF) << 8
                | (long) bytes[offset + 7] & 0xFF;
    }*/
}
