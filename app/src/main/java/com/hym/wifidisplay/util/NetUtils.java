package com.hym.wifidisplay.util;

import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class NetUtils {
    private static final String TAG = NetUtils.class.getSimpleName();

    private NetUtils() {
    }

    public static List<NetworkInterface> getNetworkInterfaces() {
        try {
            Enumeration<NetworkInterface> enumIfs = NetworkInterface.getNetworkInterfaces();
            if (enumIfs == null) {
                Log.w(TAG, "NetworkInterface.getNetworkInterfaces() returned null");
                return Collections.emptyList();
            }
            return Collections.list(enumIfs);
        } catch (SocketException e) {
            Log.e(TAG, "getNetworkInterfaces failed", e);
        }
        return Collections.emptyList();
    }

    public static NetworkInterface getNetworkInterfaceByName(String interfaceName) {
        interfaceName = interfaceName.toLowerCase();
        List<NetworkInterface> interfaces = getNetworkInterfaces();
        for (NetworkInterface netIf : interfaces) {
            if (netIf != null && netIf.getName().toLowerCase().startsWith(interfaceName)) {
                return netIf;
            }
        }
        Log.w(TAG, "getNetworkInterfaceByName " + interfaceName + " failed");
        return null;
    }

    public static NetworkInterface getP2pNetworkInterface() {
        NetworkInterface netIf = null;
        try {
            // "p2p-wlan", "p2p-eth0", "p2p-p2p0",
            if ((netIf = getNetworkInterfaceByName("p2p")) != null) {
                return netIf;
            }
            if ((netIf = getNetworkInterfaceByName("wlan")) != null) {
                return netIf;
            }
            if ((netIf = getNetworkInterfaceByName("eth")) != null) {
                return netIf;
            }
            return netIf;
        } finally {
            Log.d(TAG, "getP2pNetworkInterface result: " + netIf);
        }
    }

    public static String getP2pIpAddress() {
        NetworkInterface p2pNetIf = getP2pNetworkInterface();
        if (p2pNetIf == null) {
            Log.w(TAG, "getP2pNetworkInterface returned null");
            return "";
        }
        List<InetAddress> addrs = Collections.list(p2pNetIf.getInetAddresses());
        for (InetAddress addr : addrs) {
            if (addr.isLoopbackAddress()) {
                continue;
            }
            String ipAddr = addr.getHostAddress();
            if (ipAddr.contains("192.168.49.")) {
                Log.d(TAG, "getP2pIpAddress ip=" + ipAddr);
                return ipAddr;
            }
        }
        return "";
    }

    public static String getMacAddress(NetworkInterface netIf) {
        try {
            byte[] mac = netIf.getHardwareAddress();
            return convertMacArrayToString(mac);
        } catch (SocketException e) {
            Log.e(TAG, "getMacAddress", e);
            return "";
        }
    }

    public static String getMacAddress(String interfaceName) {
        try {
            List<NetworkInterface> interfaces = getNetworkInterfaces();
            for (NetworkInterface netIf : interfaces) {
                if (!netIf.getName().equalsIgnoreCase(interfaceName)) {
                    continue;
                }
                byte[] mac = netIf.getHardwareAddress();
                return convertMacArrayToString(mac);
            }
        } catch (SocketException e) {
            Log.e(TAG, "getMacAddress", e);
        }
        return "";
    }

    public static String convertMacArrayToString(byte[] array) {
        if (array == null) {
            Log.w(TAG, "convertMacArrayToString: array is null");
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < array.length; idx++) {
            sb.append(String.format("%02X:", array[idx]));
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    public static String getWfdTypeString(int type) {
        switch (type) {
            case 0: // WifiP2pWfdInfo.WFD_SOURCE
                return "SOURCE";
            case 1: // WifiP2pWfdInfo.PRIMARY_SINK
                return "PRIMARY_SINK";
            case 2: // WifiP2pWfdInfo.SECONDARY_SINK
                return "SECONDARY_SINK";
            case 3: // WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK
                return "SOURCE_OR_SINK";
            default:
                return "UNKNOWN";
        }
    }
}
