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

package android.net.wifi.p2p;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class representing a Wi-Fi p2p device
 *
 * Note that the operations are not thread safe
 * {@see WifiP2pManager}
 */
public class WifiP2pDevice implements Parcelable {
    /**
     * The device name is a user friendly string to identify a Wi-Fi p2p device
     */
    public String deviceName = "";

    /**
     * The device MAC address uniquely identifies a Wi-Fi p2p device
     */
    public String deviceAddress = "";

    /**
     * Primary device type identifies the type of device. For example, an application
     * could filter the devices discovered to only display printers if the purpose is to
     * enable a printing action from the user. See the Wi-Fi Direct technical specification
     * for the full list of standard device types supported.
     */
    public String primaryDeviceType;

    /**
     * Secondary device type is an optional attribute that can be provided by a device in
     * addition to the primary device type.
     */
    public String secondaryDeviceType;

    /**
     * WPS config methods supported
     *
     * @hide
     */
    public int wpsConfigMethodsSupported;

    /**
     * Device capability
     *
     * @hide
     */
    public int deviceCapability;

    /**
     * Group capability
     *
     * @hide
     */
    public int groupCapability;

    public static final int CONNECTED = 0;
    public static final int INVITED = 1;
    public static final int FAILED = 2;
    public static final int AVAILABLE = 3;
    public static final int UNAVAILABLE = 4;

    /** Device connection status */
    public int status = UNAVAILABLE;

    /** @hide */
    public WifiP2pWfdInfo wfdInfo;

    public WifiP2pDevice() {
    }

    /**
     * @param string formats supported include
     *               P2P-DEVICE-FOUND fa:7b:7a:42:02:13 p2p_dev_addr=fa:7b:7a:42:02:13
     *               pri_dev_type=1-0050F204-1 name='p2p-TEST1' config_methods=0x188 dev_capab=0x27
     *               group_capab=0x0 wfd_dev_info=000006015d022a0032
     *
     *               P2P-DEVICE-LOST p2p_dev_addr=fa:7b:7a:42:02:13
     *
     *               AP-STA-CONNECTED 42:fc:89:a8:96:09 [p2p_dev_addr=02:90:4c:a0:92:54]
     *
     *               AP-STA-DISCONNECTED 42:fc:89:a8:96:09 [p2p_dev_addr=02:90:4c:a0:92:54]
     *
     *               fa:7b:7a:42:02:13
     *
     *               Note: The events formats can be looked up in the wpa_supplicant code
     * @hide
     */
    public WifiP2pDevice(String string) throws IllegalArgumentException {
    }

    /** Returns true if WPS push button configuration is supported */
    public boolean wpsPbcSupported() {
        return false;
    }

    /** Returns true if WPS keypad configuration is supported */
    public boolean wpsKeypadSupported() {
        return false;
    }

    /** Returns true if WPS display configuration is supported */
    public boolean wpsDisplaySupported() {
        return false;
    }

    /** Returns true if the device is capable of service discovery */
    public boolean isServiceDiscoveryCapable() {
        return false;
    }

    /** Returns true if the device is capable of invitation {@hide} */
    public boolean isInvitationCapable() {
        return false;
    }

    /** Returns true if the device reaches the limit. {@hide} */
    public boolean isDeviceLimit() {
        return false;
    }

    /** Returns true if the device is a group owner */
    public boolean isGroupOwner() {
        return false;
    }

    /** Returns true if the group reaches the limit. {@hide} */
    public boolean isGroupLimit() {
        return false;
    }

    /**
     * Update device details. This will be throw an exception if the device address
     * does not match.
     *
     * @param device to be updated
     * @throws IllegalArgumentException if the device is null or device address does not match
     * @hide
     */
    public void update(WifiP2pDevice device) {
    }

    /** Updates details obtained from supplicant @hide */
    public void updateSupplicantDetails(WifiP2pDevice device) {
    }

    @Override
    public boolean equals(Object obj) {
        return false;
    }

    @Override
    public String toString() {
        return null;
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** copy constructor */
    public WifiP2pDevice(WifiP2pDevice source) {
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pDevice> CREATOR = null;
}
