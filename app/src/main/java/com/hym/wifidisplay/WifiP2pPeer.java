/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hym.wifidisplay;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.preference.Preference;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;

import com.hym.wifidisplay.util.NetUtils;
import com.hym.wifidisplay.util.RarpUtils;

import java.net.NetworkInterface;

public class WifiP2pPeer extends Preference {
    private static final int SIGNAL_LEVELS = 4;

    private final int mRssi;

    public WifiP2pDevice device;
    private ImageView mSignal;

    public WifiP2pPeer(Context context, WifiP2pDevice dev) {
        super(context);
        device = dev;
        setWidgetLayoutResource(R.layout.preference_widget_wifi_signal);
        mRssi = 60; //TODO: fix
        if (TextUtils.isEmpty(device.deviceName)) {
            setTitle(device.deviceAddress);
        } else {
            setTitle(device.deviceName);
        }
        String[] statusArray = context.getResources().getStringArray(R.array.wifi_p2p_status);
        StringBuilder sb = new StringBuilder(statusArray[device.status]);
        sb.append("\nMAC: ").append(device.deviceAddress);
        String ipAddr = getIpAddress();
        if (ipAddr != null) {
            sb.append("\nIP: " + ipAddr);
        }
        if (device.wfdInfo != null) {
            String typeString = NetUtils.getWfdTypeString(device.wfdInfo.getDeviceType());
            sb.append("\nWFD Type: ").append(typeString);
        }
        setSummary(sb);
    }

    @Override
    public void onBindView(View view) {
        super.onBindView(view);
        mSignal = (ImageView) view.findViewById(R.id.signal);
        if (mRssi == Integer.MAX_VALUE) {
            mSignal.setImageDrawable(null);
        } else {
            mSignal.setImageResource(R.drawable.wifi_signal);
        }
        mSignal.setImageLevel(getLevel());
    }

    @Override
    public int compareTo(Preference preference) {
        if (!(preference instanceof WifiP2pPeer)) {
            return 1;
        }
        WifiP2pPeer other = (WifiP2pPeer) preference;

        // devices go in the order of the status
        if (device.status != other.device.status) {
            return device.status < other.device.status ? -1 : 1;
        }

        // Sort by name/address
        if (device.deviceName != null) {
            return device.deviceName.compareToIgnoreCase(other.device.deviceName);
        }

        return device.deviceAddress.compareToIgnoreCase(other.device.deviceAddress);
    }

    private int getLevel() {
        if (mRssi == Integer.MAX_VALUE) {
            return -1;
        }
        return WifiManager.calculateSignalLevel(mRssi, SIGNAL_LEVELS);
    }

    public int getWfdDeviceType() {
        if (device.wfdInfo == null) {
            return -1;
        }
        return device.wfdInfo.getDeviceType();
    }

    public String getIpAddress() {
        NetworkInterface p2pNetIf = NetUtils.getP2pNetworkInterface();
        if (p2pNetIf == null) {
            return null;
        }
        String ipAddr = RarpUtils.execRarp(p2pNetIf.getName(), device.deviceAddress);
        return ipAddr;
    }
}
