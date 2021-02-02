package com.hym.wifidisplay.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RARP: MAC --> IP
 * cat /proc/net/arp
 * IP address       HW type     Flags       HW address            Mask     Device
 * 192.168.49.208   0x1         0x2         a2:0b:ba:ba:c4:d1     *        p2p-wlan0-8
 */
public class RarpUtils {
    private static String TAG = RarpUtils.class.getSimpleName();

    private static final String ARP_PATH = "/proc/net/arp";

    private static final Pattern ARP_REGEX = Pattern.compile(
            "((?:25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\."
                    + "(?:25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\."
                    + "(?:25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\."
                    + "(?:25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[0-9]))" // IP address
                    + "\\s+"
                    + "(0x[0-9a-fA-F]+)" // HW type
                    + "\\s+"
                    + "(0x[0-9a-fA-F]+)" // Flags
                    + "\\s+"
                    + "((?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})" // HW address
                    + "\\s+"
                    + "(\\S+)" // Mask
                    + "\\s+"
                    + "(\\S+)" // Device
    );

    private RarpUtils() {
    }

    public static class ArpInfo {
        public final String mIpAddress;
        public final String mHwType;
        public final String mFlags;
        public final String mHwAddress;
        public final String mMask;
        public final String mDevice;

        ArpInfo(String ipAddress, String hwType, String flags, String hwAddress, String mask,
                String device) {
            mIpAddress = ipAddress;
            mHwType = hwType;
            mFlags = flags;
            mHwAddress = hwAddress;
            mMask = mask;
            mDevice = device;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("IP address:").append(mIpAddress).append(' ')
                    .append("HW type:").append(mHwType).append(' ')
                    .append("Flags:").append(mFlags).append(' ')
                    .append("HW address:").append(mHwAddress).append(' ')
                    .append("Mask:").append(mMask).append(' ')
                    .append("Device:").append(mDevice);
            return sb.toString();
        }
    }

    public static String execRarp(String ifName, String macAddr) {
        if (ifName == null || ifName.length() == 0 || macAddr == null || macAddr.length() == 0) {
            Log.w(TAG, "execRarp() ifName=" + ifName + " macAddr=" + macAddr + " ???");
            return null;
        }
        if ("00:00:00:00:00:00".equals(macAddr)) {
            Log.w(TAG, "execRarp() macAddr is 00:00:00:00:00:00 !");
            return null;
        }
        List<ArpInfo> arps = getArpTable();
        if (arps == null || arps.isEmpty()) {
            Log.w(TAG, "execRarp() getArpTable() returns null or empty");
            return null;
        }
        ArpInfo arp = searchArp(arps, ifName, macAddr);
        if (arp == null) {
            Log.w(TAG, "execRarp() searchArp( " + ifName + ", " + macAddr + ") Not Found!");
            return null;
        }
        return arp.mIpAddress;
    }

    public static List<ArpInfo> getArpTable() {
        List<String> lines = readFileLines(ARP_PATH);
        if (lines == null || lines.isEmpty()) {
            Log.w(TAG, "getArpTable() readFileLines(" + ARP_PATH + ") returns null or empty");
            return null;
        }
        List<ArpInfo> arps = parseArp(lines);
        if (arps == null || arps.isEmpty()) {
            Log.w(TAG, "getArpTable() parseArp(" + lines + ") returns null or empty");
            return null;
        }
        return arps;
    }

    private static List<String> readFileLines(String path) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(path)), 128)) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            Log.e(TAG, "readFileLines " + path, e);
        }
        return null;
    }

    private static List<ArpInfo> parseArp(List<String> arpLines) {
        if (arpLines == null || arpLines.isEmpty()) {
            return null;
        }
        List<ArpInfo> arps = new ArrayList<>();
        for (String line : arpLines) {
            ArpInfo arp = parseArpLine(line);
            if (arp == null) {
                continue;
            }
            arps.add(arp);
        }
        return arps;
    }

    private static ArpInfo parseArpLine(String line) {
        if (line == null) {
            Log.w(TAG, "parseArpLine() line is null!");
            return null;
        }
        Matcher m = ARP_REGEX.matcher(line);
        if (!m.find()) {
            Log.w(TAG, "ARP line: " + line + " doesn't match ARP_REGEX");
            return null;
        }
        Log.d(TAG, "ARP line: " + line);
        return new ArpInfo(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6));
    }

    private static ArpInfo searchArp(List<ArpInfo> arps, String ifName, String macAddr) {
        if (arps == null || arps.isEmpty()) {
            return null;
        }
        macAddr = macAddr.toLowerCase();
        for (ArpInfo arp : arps) {
            if (arp.mDevice.equalsIgnoreCase(ifName)
                    && !"00:00:00:00:00:00".equals(arp.mHwAddress)) {
                String arpMac = arp.mHwAddress.toLowerCase();
                if (arp.mHwAddress.equals(macAddr)) {
                    return arp;
                } else {
                    final int maxDiffCount = 2;
                    int diffCount = 0;
                    for (int i = 0; i < 17; i++) {
                        if (arpMac.charAt(i) != macAddr.charAt(i)) {
                            diffCount++;
                        }
                    }
                    if (diffCount <= maxDiffCount) {
                        Log.d(TAG, arpMac + " and " + macAddr + " may belong to the same device");
                        return arp;
                    }
                }
            }
        }
        return null;
    }
}
