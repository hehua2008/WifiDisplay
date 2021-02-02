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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import com.hym.wifidisplay.util.NetUtils;
import com.hym.wifidisplay.util.PermissionUtils;

import java.util.Collection;

/*
 * Displays Wi-fi p2p settings UI
 */
public class WifiP2pSettingsActivity extends PreferenceActivity {
    private static final String TAG = WifiP2pSettingsActivity.class.getSimpleName();
    private static final boolean DBG = true;

    private static final String SAVE_DIALOG_PEER = "PEER_STATE";
    private static final String SAVE_DEVICE_NAME = "DEV_NAME";

    private static final long ARP_RETRY_INTERVAL = 1000L;
    private static final int MAX_ARP_RETRY_COUNT = 60;

    private static final int MENU_ID_SEARCH = Menu.FIRST;
    private static final int MENU_ID_RENAME = Menu.FIRST + 1;

    private static final int DIALOG_DISCONNECT = 1;
    private static final int DIALOG_CANCEL_CONNECT = 2;
    private static final int DIALOG_RENAME = 3;

    private static final int REQUEST_MEDIA_PROJECTION = 1;

    private final IntentFilter mIntentFilter = new IntentFilter();

    {
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
    }

    private MediaProjectionManager mProjectionManager;
    private WifiManager mWifiManager;
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mChannel;
    private WifiP2pPeer mSelectedWifiPeer;
    private EditText mDeviceNameText;
    private String mSavedDeviceName;
    private Intent mProjectionData;

    private boolean mWifiP2pEnabled;
    private boolean mWifiP2pSearching;
    private int mConnectedDevices;

    private Preference mThisDevicePreference;
    private PreferenceCategory mPeerCategory;

    private WifiP2pDevice mThisDevice;

    private Runnable mRetryStartWfdSinkRunnable;
    private final Handler mHandler = new Handler();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        private boolean mLastGroupFormed;

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive " + intent);

            String action = intent.getAction();
            switch (action) {
                case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                    handleP2pStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                    break;
                case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                    WifiP2pDeviceList peers = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_P2P_DEVICE_LIST);
                    handlePeersChanged(peers);
                    break;
                case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                    NetworkInfo networkInfo = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO);
                    WifiP2pInfo wifiP2pinfo = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    WifiP2pGroup group = intent.getParcelableExtra
                            (WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                    if (networkInfo.isConnected()) {
                        onDeviceConnected(networkInfo, wifiP2pinfo, group);
                    } else if (!mLastGroupFormed) {
                        //start a search when we are disconnected
                        //but not on group removed broadcast event
                        startSearch();
                    }
                    mLastGroupFormed = wifiP2pinfo.groupFormed;
                    break;
                case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                    WifiP2pDevice thisDevice = intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                    updateThisDevice(thisDevice);
                    break;
                case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:
                    int discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE,
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                    if (DBG) {
                        Log.d(TAG, "Discovery state changed: " + discoveryState);
                    }
                    boolean searching = discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                    updateSearchMenu(searching);
                    break;
            }
        }
    };

    private void handleP2pStateChanged(boolean enabled) {
        Log.d(TAG, "handleP2pStateChanged enabled=" + enabled);
        mWifiP2pEnabled = enabled;
        updateSearchMenu(false);
        mThisDevicePreference.setEnabled(mWifiP2pEnabled);
        mPeerCategory.setEnabled(mWifiP2pEnabled);
        if (mWifiP2pEnabled) {
            initializeP2p();
        }
    }

    private void updateSearchMenu(boolean searching) {
        Log.d(TAG, "updateSearchMenu searching=" + searching);
        mWifiP2pSearching = searching;
        invalidateOptionsMenu();
    }

    private void onDeviceConnected(NetworkInfo networkInfo, WifiP2pInfo wifiP2pInfo,
            WifiP2pGroup group) {
        Log.d(TAG, "onDeviceConnected networkInfo=" + networkInfo + " wifiP2pInfo=" + wifiP2pInfo
                + " group=" + group);
        if (mRetryStartWfdSinkRunnable != null) {
            mHandler.removeCallbacks(mRetryStartWfdSinkRunnable);
        }
        mRetryStartWfdSinkRunnable = new RetryStartWfdSinkRunnable(group,
                wifiP2pInfo.groupOwnerAddress.getHostAddress());
        mHandler.post(mRetryStartWfdSinkRunnable);
    }

    private class RetryStartWfdSinkRunnable implements Runnable {
        private final WifiP2pGroup mGroup;
        private final String mGroupOwnerIp;
        private int mRetryCount;

        public RetryStartWfdSinkRunnable(WifiP2pGroup group, String groupOwnerIp) {
            mGroup = group;
            mGroupOwnerIp = groupOwnerIp;
        }

        @Override
        public void run() {
            String sourceMac = "00:00:00:00:00:00";
            String sourceIp = null; // should be "192.168.49.*"
            int controlPort = 7236;

            Collection<WifiP2pDevice> p2pDevs = mGroup.getClientList();
            for (WifiP2pDevice dev : p2pDevs) {
                WifiP2pWfdInfo wfd = dev.wfdInfo;
                if (wfd != null && wfd.isWfdEnabled()) {
                    int type = wfd.getDeviceType();
                    if (type == WifiP2pWfdInfo.WFD_SOURCE
                            || type == WifiP2pWfdInfo.SOURCE_OR_PRIMARY_SINK) {
                        sourceMac = dev.deviceAddress;
                        Log.d(TAG, "onConnectionChanged source mac=" + sourceMac);
                        controlPort = wfd.getControlPort();
                        Log.d(TAG, "onConnectionChanged source port=" + controlPort);
                        break;
                    }
                } else {
                    continue;
                }
            }

            if (mGroup.isGroupOwner()) {
                Log.d(TAG, "This device is the p2p group owner");
                sourceIp = mGroupOwnerIp;
            } else {
                Log.d(TAG, "This device is a p2p group client");
                sourceIp = NetUtils.getP2pIpAddress();
            }
            Log.d(TAG, "onConnectionChanged source IP=" + sourceIp);
            startWifiDisplaySourceActivity(sourceIp, controlPort);
            mRetryStartWfdSinkRunnable = null;
        }
    }

    private void startSearch() {
        Log.d(TAG, "startSearch");
        if (!mWifiP2pSearching) {
            mWifiP2pManager.discoverPeers(mChannel, new MyActionListener("discover peers"));
        }
    }

    private void updateThisDevice(WifiP2pDevice thisDevice) {
        Log.d(TAG, "updateThisDevice thisDevice=\n" + thisDevice);
        mThisDevice = thisDevice;
        if (mThisDevicePreference != null && mThisDevice != null) {
            mThisDevicePreference.setTitle(getDeviceName(mThisDevice));
        }
    }

    private boolean checkP2pFuture() {
        boolean result = getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
        if (!result) {
            Log.w(TAG, "This package does not support P2P Feature !!!");
        }
        return result;
    }

    private boolean checkWifiState() {
        if (!mWifiManager.isWifiEnabled() && !mWifiManager.setWifiEnabled(true)) {
            Log.w(TAG, "Can not enable wifi");
            return false;
        }
        return true;
    }

    //@Override
    public void onChannelDisconnected() {
        Log.w(TAG, "onChannelDisconnected");
        // TODO
    }

    private boolean initializeP2p() {
        Log.d(TAG, "initializeP2p");
        mChannel = mWifiP2pManager.initialize(getApplicationContext(), getMainLooper(),
                this::onChannelDisconnected);

        if (mChannel == null) {
            Log.e(TAG, "Failed to initialize Wifi p2p");
            return false;
        }

        mWifiP2pManager.setDeviceName(mChannel, getString(R.string.p2p_device_name),
                new MyActionListener("set device name"));

        mWifiP2pManager.setMiracastMode(WifiP2pManager.MIRACAST_SOURCE);

        WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
        wfdInfo.setWfdEnabled(true);
        wfdInfo.setDeviceType(WifiP2pWfdInfo.WFD_SOURCE);
        wfdInfo.setSessionAvailable(true);
        wfdInfo.setControlPort(7236);
        wfdInfo.setMaxThroughput(50);

        mWifiP2pManager.setWFDInfo(mChannel, wfdInfo, new MyActionListener("set WFD info"));

        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PermissionUtils.requestPermissions(this, PermissionUtils.RUNTIME_PERMISSIONS);
            } catch (RuntimeException e) {
                Log.e(TAG, "requestPermissions failed: " + e);
                finish();
                return;
            }
        }

        addPreferencesFromResource(R.xml.wifi_p2p_settings);
        PreferenceScreen prefScr = getPreferenceScreen();
        mThisDevicePreference = (Preference) prefScr.findPreference("p2p_this_device");
        mPeerCategory = (PreferenceCategory) prefScr.findPreference("p2p_peer_devices");

        if (!checkP2pFuture()) {
            finish();
            return;
        }

        mWifiManager = getSystemService(WifiManager.class);
        if (!checkWifiState()) {
            finish();
            return;
        }

        mWifiP2pManager = getSystemService(WifiP2pManager.class);
        if (mWifiP2pManager == null) {
            Log.e(TAG, "mWifiP2pManager is null!");
            finish();
            return;
        } else {
            if (!initializeP2p()) {
                //Failure to set up connection
                Log.e(TAG, "Failed to set up connection with wifi p2p service");
                finish();
                return;
            }
        }

        if (savedInstanceState != null && savedInstanceState.containsKey(SAVE_DIALOG_PEER)) {
            WifiP2pDevice device = savedInstanceState.getParcelable(SAVE_DIALOG_PEER);
            mSelectedWifiPeer = new WifiP2pPeer(getApplicationContext(), device);
        }
        if (savedInstanceState != null && savedInstanceState.containsKey(SAVE_DEVICE_NAME)) {
            mSavedDeviceName = savedInstanceState.getString(SAVE_DEVICE_NAME);
        }

        mProjectionManager = getSystemService(MediaProjectionManager.class);
        Log.d(TAG, "requesting projection confirmation");
        // This initiates a prompt dialog for the user to confirm screen projection.
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (mSelectedWifiPeer != null) {
            outState.putParcelable(SAVE_DIALOG_PEER, mSelectedWifiPeer.device);
        }
        if (mDeviceNameText != null) {
            outState.putString(SAVE_DEVICE_NAME, mDeviceNameText.getText().toString());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "User cancelled");
            } else {
                Log.d(TAG, "User confirmed");
                mProjectionData = data;
            }
        }
    }

    //@Override
    public void onPeersAvailable(WifiP2pDeviceList peers) {
        if (DBG) {
            Log.d(TAG, "Requested peers are available");
        }
        handlePeersChanged(peers);
    }

    private void handlePeersChanged(WifiP2pDeviceList peers) {
        Collection<WifiP2pDevice> peerList = peers.getDeviceList();
        Log.d(TAG, "handlePeersChanged peers size=" + peerList.size());
        removeAllChildren(mPeerCategory);

        mConnectedDevices = 0;
        if (DBG) {
            Log.d(TAG, "List of available peers");
        }
        for (WifiP2pDevice peer : peerList) {
            if (DBG) {
                Log.d(TAG, "->->->->->->\n" + peer);
            }
            addChild(mPeerCategory, new WifiP2pPeer(getApplicationContext(), peer));
            if (peer.status == WifiP2pDevice.CONNECTED) {
                mConnectedDevices++;
            }
        }
        if (DBG) {
            Log.d(TAG, "mConnectedDevices " + mConnectedDevices);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        registerReceiver(mReceiver, mIntentFilter);
        mWifiP2pManager.requestPeers(mChannel, this::onPeersAvailable);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        mWifiP2pManager.stopPeerDiscovery(mChannel, new MyActionListener("stop peer discovery"));
        unregisterReceiver(mReceiver);
    }

    private int getSearchStringId() {
        return mWifiP2pSearching ? R.string.wifi_p2p_menu_searching : R.string.wifi_p2p_menu_search;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_ID_SEARCH, 0, getSearchStringId())
                .setEnabled(mWifiP2pEnabled)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(Menu.NONE, MENU_ID_RENAME, 0, R.string.wifi_p2p_menu_rename)
                .setEnabled(mWifiP2pEnabled)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem searchMenu = menu.findItem(MENU_ID_SEARCH);
        MenuItem renameMenu = menu.findItem(MENU_ID_RENAME);
        if (mWifiP2pEnabled) {
            searchMenu.setEnabled(true);
            renameMenu.setEnabled(true);
        } else {
            searchMenu.setEnabled(false);
            renameMenu.setEnabled(false);
        }
        searchMenu.setTitle(getSearchStringId());
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_ID_SEARCH:
                startSearch();
                return true;
            case MENU_ID_RENAME:
                showDialog(DIALOG_RENAME);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference instanceof WifiP2pPeer) {
            mSelectedWifiPeer = (WifiP2pPeer) preference;
            if (mSelectedWifiPeer.device.status == WifiP2pDevice.CONNECTED) {
                showDialog(DIALOG_DISCONNECT);
            } else if (mSelectedWifiPeer.device.status == WifiP2pDevice.INVITED) {
                showDialog(DIALOG_CANCEL_CONNECT);
            } else {
                WifiP2pConfig config = new WifiP2pConfig();
                config.deviceAddress = mSelectedWifiPeer.device.deviceAddress;
                int forceWps = SystemProperties.getInt("wifidirect.wps", -1);

                if (forceWps != -1) {
                    config.wps.setup = forceWps;
                } else {
                    if (mSelectedWifiPeer.device.wpsPbcSupported()) {
                        config.wps.setup = WpsInfo.PBC;
                    } else if (mSelectedWifiPeer.device.wpsKeypadSupported()) {
                        config.wps.setup = WpsInfo.KEYPAD;
                    } else {
                        config.wps.setup = WpsInfo.DISPLAY;
                    }
                }

                mWifiP2pManager.connect(mChannel, config, new MyActionListener("connect") {
                    @Override
                    public void onFailure(int reason) {
                        Log.e(TAG, "connect fail " + reason);
                        Toast.makeText(WifiP2pSettingsActivity.this,
                                R.string.wifi_p2p_failed_connect_message, Toast.LENGTH_SHORT)
                                .show();
                    }
                });
            }
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_DISCONNECT: {
                String deviceName = getDeviceName(mSelectedWifiPeer.device);
                String msg;
                if (mConnectedDevices > 1) {
                    msg = getString(R.string.wifi_p2p_disconnect_multiple_message,
                            deviceName, mConnectedDevices - 1);
                } else {
                    msg = getString(R.string.wifi_p2p_disconnect_message, deviceName);
                }
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.wifi_p2p_disconnect_title)
                        .setMessage(msg)
                        .setPositiveButton(getString(R.string.dlg_ok), (dl, which) -> {
                            mWifiP2pManager.removeGroup(mChannel,
                                    new MyActionListener("remove group"));
                        })
                        .setNegativeButton(getString(R.string.dlg_cancel), null)
                        .create();
                return dialog;
            }
            case DIALOG_CANCEL_CONNECT: {
                String deviceName = getDeviceName(mSelectedWifiPeer.device);
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.wifi_p2p_cancel_connect_title)
                        .setMessage(getString(R.string.wifi_p2p_cancel_connect_message, deviceName))
                        .setPositiveButton(getString(R.string.dlg_ok), (dl, which) -> {
                            mWifiP2pManager.cancelConnect(mChannel,
                                    new MyActionListener("cancel connect"));
                        })
                        .setNegativeButton(getString(R.string.dlg_cancel), null)
                        .create();
                return dialog;
            }
            case DIALOG_RENAME: {
                mDeviceNameText = new EditText(this);
                mDeviceNameText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
                if (mSavedDeviceName != null) {
                    mDeviceNameText.setText(mSavedDeviceName);
                    mDeviceNameText.setSelection(mSavedDeviceName.length());
                } else if (mThisDevice != null && !TextUtils.isEmpty(mThisDevice.deviceName)) {
                    mDeviceNameText.setText(mThisDevice.deviceName);
                    mDeviceNameText.setSelection(0, mThisDevice.deviceName.length());
                }
                mSavedDeviceName = null;
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.wifi_p2p_menu_rename)
                        .setView(mDeviceNameText)
                        .setPositiveButton(getString(R.string.dlg_ok), (dl, which) -> {
                            String name = mDeviceNameText.getText().toString();
                            if (!checkDeviceName(name)) {
                                Toast.makeText(WifiP2pSettingsActivity.this,
                                        R.string.wifi_p2p_failed_rename_message, Toast.LENGTH_LONG)
                                        .show();
                                return;
                            }
                            mWifiP2pManager.setDeviceName(mChannel, name,
                                    new MyActionListener("rename device") {
                                        @Override
                                        public void onFailure(int reason) {
                                            Toast.makeText(WifiP2pSettingsActivity.this,
                                                    R.string.wifi_p2p_failed_rename_message,
                                                    Toast.LENGTH_LONG).show();
                                        }
                                    });
                        })
                        .setNegativeButton(getString(R.string.dlg_cancel), null)
                        .create();
                return dialog;
            }
            default:
                return null;
        }
    }

    private void startWifiDisplaySinkActivity(String sourceIp, int sourcePort) {
        Log.d(TAG, "startWifiDisplaySinkActivity " + sourceIp + ':' + sourcePort);
        Intent intent = new Intent(this, WifiDisplaySinkActivity.class);
        intent.putExtra(WfdConstants.SOURCE_HOST, sourceIp);
        intent.putExtra(WfdConstants.SOURCE_PORT, sourcePort);
        startActivity(intent);
        finish();
    }

    private void startWifiDisplaySourceActivity(String sourceIp, int sourcePort) {
        Log.d(TAG, "startWifiDisplaySourceActivity " + sourceIp + ':' + sourcePort);
        Intent intent = new Intent(this, WifiDisplaySourceActivity.class);
        intent.putExtra(WfdConstants.PROJECTION_DATA, mProjectionData);
        intent.putExtra(WfdConstants.SOURCE_HOST, sourceIp);
        intent.putExtra(WfdConstants.SOURCE_PORT, sourcePort);
        startActivity(intent);
        finish();
    }

    private static boolean checkDeviceName(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char cur = name.charAt(i);
            if (!Character.isDigit(cur) && !Character.isLetter(cur)
                    && cur != '-' && cur != '_' && cur != ' ') {
                return false;
            }
        }
        return true;
    }

    private static String getDeviceName(WifiP2pDevice device) {
        if (TextUtils.isEmpty(device.deviceName)) {
            return device.deviceAddress;
        }
        return device.deviceName;
    }

    private static void removeAllChildren(PreferenceGroup group) {
        if (group != null) {
            group.removeAll();
            //group.setVisible(false);
        }
    }

    private static void addChild(PreferenceGroup group, Preference child) {
        if (group != null) {
            group.addPreference(child);
            //group.setVisible(true);
        }
    }

    private static class MyActionListener implements WifiP2pManager.ActionListener {
        private final String mActionName;

        public MyActionListener(String actionName) {
            mActionName = actionName;
        }

        @Override
        public void onSuccess() {
            Log.d(TAG, mActionName + " success");
        }

        @Override
        public void onFailure(int reason) {
            if (DBG) {
                Log.d(TAG, mActionName + " failed " + reason);
            }
        }
    }
}
