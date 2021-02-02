package com.hym.rtplib.util;

import android.annotation.SuppressLint;
import android.media.MediaFormat;

import java.lang.reflect.Method;
import java.util.Map;

public class MediaFormatUtils {
    private MediaFormatUtils() {
    }

    public static MediaFormat clone(MediaFormat format) {
        MediaFormat tmp = new MediaFormat();
        try {
            @SuppressWarnings("JavaReflectionMemberAccess")
            @SuppressLint("DiscouragedPrivateApi")
            Method method = MediaFormat.class.getDeclaredMethod("getMap");
            method.setAccessible(true);
            Map<String, Object> sourceMap = (Map<String, Object>) method.invoke(format);
            Map<String, Object> targetMap = (Map<String, Object>) method.invoke(tmp);
            targetMap.putAll(sourceMap);
            return tmp;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void set(MediaFormat source, MediaFormat target, boolean cleanTarget) {
        try {
            @SuppressWarnings("JavaReflectionMemberAccess")
            @SuppressLint("DiscouragedPrivateApi")
            Method method = MediaFormat.class.getDeclaredMethod("getMap");
            method.setAccessible(true);
            Map<String, Object> sourceMap = (Map<String, Object>) method.invoke(source);
            Map<String, Object> targetMap = (Map<String, Object>) method.invoke(target);
            if (cleanTarget) {
                targetMap.clear();
            }
            targetMap.putAll(sourceMap);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
