package com.hym.rtplib;

import android.util.ArrayMap;

import com.hym.rtplib.constant.Errno;

public class Parameters implements Errno {
    public static Parameters parse(String data) {
        Parameters params = new Parameters();
        int err = params.internalParse(data);

        if (err != OK) {
            return null;
        }

        return params;
    }

    public String getParameter(String name) {
        String key = name.toLowerCase();

        int index = mDict.indexOfKey(key);

        if (index < 0) {
            return null;
        }

        String value = mDict.valueAt(index);
        return value;
    }

    private final ArrayMap<String, String> mDict = new ArrayMap<>();

    private Parameters() {
    }

    private int internalParse(String data) {
        int i = 0;
        int size = data.length();
        while (i < size) {
            int nameStart = i;
            while (i < size && data.charAt(i) != ':') {
                ++i;
            }

            if (i == size || i == nameStart) {
                return ERROR_MALFORMED;
            }

            String name = data.substring(nameStart, i);
            name.trim();
            name.toLowerCase();

            ++i;

            int valueStart = i;

            while (i + 1 < size && (data.charAt(i) != '\r' || data.charAt(i + 1) != '\n')) {
                ++i;
            }

            String value = data.substring(valueStart, i).trim();

            mDict.put(name, value);

            while (i + 1 < size && data.charAt(i) == '\r' && data.charAt(i + 1) == '\n') {
                i += 2;
            }
        }

        return OK;
    }
}
