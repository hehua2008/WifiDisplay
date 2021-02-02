package com.hym.rtplib.net;

import android.util.ArrayMap;
import android.util.Log;

import com.hym.rtplib.util.CheckUtils;

// Encapsulates an "HTTP/RTSP style" response, i.e. a status line,
// key/value pairs making up the headers and an optional body/content.
public class ParsedMessage {
    private static final String TAG = ParsedMessage.class.getSimpleName();

    private static final boolean DEBUG = false;

    private final ArrayMap<String, String> mDict = new ArrayMap<>();

    private String mContent;
    private int mLength;

    private ParsedMessage() {
    }

    public static ParsedMessage parse(String data, int size, boolean noMoreData) {
        if (size <= 0) {
            if (size < 0) {
                Log.w(TAG, "ParsedMessage in size(" + size + " is less than 0 !");
            }
            return null;
        }
        if (DEBUG) {
            Log.w(TAG, "ParsedMessage in:\n" + data);
        }
        ParsedMessage msg = new ParsedMessage();
        int res = msg.internalParse(data, size, noMoreData);

        if (res < 0) {
            Log.w(TAG, "ParsedMessage out: null");
            return null;
        }

        msg.mLength = res;
        if (DEBUG) {
            Log.w(TAG, "ParsedMessage out(" + msg.mLength + "):\n" + msg);
        }
        return msg;
    }

    public String getString(String name) {
        String key = name.toLowerCase();
        String value = mDict.get(key);
        return value;
    }

    public int getInt(String name, int def) {
        String stringValue = getString(name);
        if (stringValue == null) {
            return def;
        }
        return Integer.parseInt(stringValue);
    }

    public String getContent() {
        return mContent;
    }

    public int getLength() {
        return mLength;
    }

    public String getRequestField(int index) {
        String line = getString("_");
        CheckUtils.check(line != null);

        /*int prevOffset = 0;
        int offset = 0;
        for (int i = 0; i <= index; ++i) {
            if (offset >= line.length()) {
                return null;
            }

            int spacePos = line.indexOf(' ', offset);

            if (spacePos < 0) {
                spacePos = line.length();
            }

            prevOffset = offset;
            offset = spacePos + 1;
        }

        String field = line.substring(prevOffset, offset - 1);

        return field;*/

        return line.split("\\s+")[index];
    }

    public int getStatusCode() {
        String statusCodeString = getRequestField(1);
        if (statusCodeString == null) {
            return 0;
        }

        int statusCode = Integer.parseInt(statusCodeString);

        if (statusCode < 100 || statusCode > 999) {
            return 0;
        }

        return statusCode;
    }

    @Override
    public String toString() {
        StringBuilder line = new StringBuilder();
        line.append(getString("_"));
        CheckUtils.check(line.length() != 0);

        line.append('\n');

        for (int i = 0; i < mDict.size(); ++i) {
            String key = mDict.keyAt(i);

            if ("_".equals(key)) {
                continue;
            }

            String value = mDict.valueAt(i);
            line.append(key);
            line.append(": ");
            line.append(value);
            line.append('\n');
        }

        line.append('\n');
        line.append(mContent);

        return line.toString();
    }

    public static String getAttribute(String s, String key) {
        int keyLen = key.length();

        while (true) {
            s = s.trim();

            int colonPos = s.indexOf(';');

            int len = (colonPos == -1) ? s.length() : colonPos;

            if (len >= keyLen + 1 && s.charAt(keyLen) == '=' && s.startsWith(key)) {
                String value = s.substring(keyLen + 1, len);
                return value;
            }

            if (colonPos == -1) {
                return null;
            }

            s = s.substring(colonPos + 1);
        }
    }

    public static int getIntAttribute(String s, String key, int def) {
        String stringValue = getAttribute(s, key);
        if (stringValue == null) {
            return def;
        }

        int value = Integer.parseInt(stringValue);
        return value;
    }

    private int internalParse(String data, int size, boolean noMoreData) {
        if (size == 0) {
            return -1;
        }

        int lastDictIndex = -1;

        int offset = 0;
        boolean headersComplete = false;
        while (offset < size) {
            int lineEndOffset = offset;
            while (lineEndOffset + 1 < size
                    && (data.charAt(lineEndOffset) != '\r'
                    || data.charAt(lineEndOffset + 1) != '\n')) {
                ++lineEndOffset;
            }

            if (lineEndOffset + 1 >= size) {
                return -1;
            }

            String line = data.substring(offset, lineEndOffset);

            if (offset == 0) {
                // Special handling for the request/status line.

                mDict.put("_", line.trim());
                offset = lineEndOffset + 2;

                continue;
            }

            if (lineEndOffset == offset) {
                // An empty line separates headers from body.
                headersComplete = true;
                offset += 2;
                break;
            }

            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                // Support for folded header values.

                if (lastDictIndex >= 0) {
                    // Otherwise it's malformed since the first header line
                    // cannot continue anything...

                    String value = mDict.valueAt(lastDictIndex);
                    value = (value + line).trim();
                    mDict.setValueAt(lastDictIndex, value);
                }

                offset = lineEndOffset + 2;
                continue;
            }

            int colonPos = line.indexOf(':');
            if (colonPos >= 0) {
                String key = line.substring(0, colonPos).trim().toLowerCase();
                String value = line.substring(colonPos + 1).trim();

                mDict.put(key, value);
                lastDictIndex = mDict.indexOfKey(key);
            }

            offset = lineEndOffset + 2;
        }

        if (!headersComplete && (!noMoreData || offset == 0)) {
            // We either saw the empty line separating headers from body
            // or we saw at least the status line and know that no more data
            // is going to follow.
            return -1;
        }

        int contentLength = getInt("content-length", 0);
        if (contentLength < 0) {
            contentLength = 0;
        }

        int totalLength = offset + contentLength;

        if (size < totalLength) {
            return -1;
        }

        mContent = data.substring(offset, offset + contentLength);

        return totalLength;
    }
}
