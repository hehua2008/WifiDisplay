package com.hym.wifidisplay.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public final class PermissionUtils {
    public static final String[] RUNTIME_PERMISSIONS =
            {Manifest.permission.WRITE_EXTERNAL_STORAGE};

    private PermissionUtils() {
    }

    public static boolean checkSelfPermission(Context context, String permission) {
        if (context == null || TextUtils.isEmpty(permission)) {
            return false;
        }
        int result = context.checkSelfPermission(permission);
        return result == PackageManager.PERMISSION_GRANTED;
    }

    public static List<String> checkSelfPermissions(Context context, List<String> permissions) {
        if (context == null || permissions == null || permissions.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> notGrantedPermissions = new LinkedList<>();
        for (String permission : permissions) {
            if (!checkSelfPermission(context, permission)) {
                notGrantedPermissions.add(permission);
            }
        }
        return notGrantedPermissions;
    }

    public static List<String> checkSelfPermissions(Context context, String... permissions) {
        if (context == null || permissions == null || permissions.length == 0) {
            return Collections.emptyList();
        }
        return checkSelfPermissions(context, Arrays.asList(permissions));
    }

    public static void requestPermissions(Activity activity, String... permissions) {
        if (activity == null || permissions == null || permissions.length == 0) {
            return;
        }
        activity.requestPermissions(permissions, 0);
    }

    public static void requestPermissions(Activity activity, List<String> permissions) {
        if (activity == null || permissions == null || permissions.isEmpty()) {
            return;
        }
        requestPermissions(activity, permissions.toArray(new String[permissions.size()]));
    }

    public static void checkAndRequestPermissions(Activity activity, List<String> permissions) {
        if (activity == null || permissions == null || permissions.isEmpty()) {
            return;
        }
        List<String> notGrantedPermissions = checkSelfPermissions(activity, permissions);
        if (!notGrantedPermissions.isEmpty()) {
            requestPermissions(activity, notGrantedPermissions);
        }
    }

    public static void checkAndRequestPermissions(Activity activity, String... permissions) {
        if (activity == null || permissions == null || permissions.length == 0) {
            return;
        }
        checkAndRequestPermissions(activity, Arrays.asList(permissions));
    }
}
