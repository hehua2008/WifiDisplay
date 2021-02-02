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

import java.util.Collection;

/**
 * A class representing a Wi-Fi P2p group. A p2p group consists of a single group
 * owner and one or more clients. In the case of a group with only two devices, one
 * will be the group owner and the other will be a group client.
 *
 * {@see WifiP2pManager}
 */
public class WifiP2pGroup implements Parcelable {
    /**
     * The temporary network id.
     * {@hide}
     */
    public static final int TEMPORARY_NET_ID = -1;

    /**
     * The persistent network id.
     * If a matching persistent profile is found, use it.
     * Otherwise, create a new persistent profile.
     * {@hide}
     */
    public static final int PERSISTENT_NET_ID = -2;

    public WifiP2pGroup() {
    }

    /**
     * @param supplicantEvent formats supported include
     *
     *                        P2P-GROUP-STARTED p2p-wlan0-0 [client|GO] ssid="DIRECT-W8" freq=2437
     *                        [psk=2182b2e50e53f260d04f3c7b25ef33c965a3291b9b36b455a82d77fd82ca15bc|
     *                        passphrase="fKG4jMe3"] go_dev_addr=fa:7b:7a:42:02:13 [PERSISTENT]
     *
     *                        P2P-GROUP-REMOVED p2p-wlan0-0 [client|GO] reason=REQUESTED
     *
     *                        P2P-INVITATION-RECEIVED sa=fa:7b:7a:42:02:13
     *                        go_dev_addr=f8:7b:7a:42:02:13
     *                        bssid=fa:7b:7a:42:82:13 unknown-network
     *
     *                        P2P-INVITATION-RECEIVED sa=b8:f9:34:2a:c7:9d persistent=0
     *
     *                        Note: The events formats can be looked up in the wpa_supplicant code
     * @hide
     */
    public WifiP2pGroup(String supplicantEvent) throws IllegalArgumentException {
    }

    /** @hide */
    public void setNetworkName(String networkName) {
    }

    /**
     * Get the network name (SSID) of the group. Legacy Wi-Fi clients will discover
     * the p2p group using the network name.
     */
    public String getNetworkName() {
        return null;
    }

    /** @hide */
    public void setIsGroupOwner(boolean isGo) {
    }

    /** Check whether this device is the group owner of the created p2p group */
    public boolean isGroupOwner() {
        return false;
    }

    /** @hide */
    public void setOwner(WifiP2pDevice device) {
    }

    /** Get the details of the group owner as a {@link WifiP2pDevice} object */
    public WifiP2pDevice getOwner() {
        return null;
    }

    /** @hide */
    public void addClient(String address) {
    }

    /** @hide */
    public void addClient(WifiP2pDevice device) {
    }

    /** @hide */
    public boolean removeClient(String address) {
        return false;
    }

    /** @hide */
    public boolean removeClient(WifiP2pDevice device) {
        return false;
    }

    /** @hide */
    public boolean isClientListEmpty() {
        return false;
    }

    /** @hide Returns {@code true} if the device is part of the group */
    public boolean contains(WifiP2pDevice device) {
        return false;
    }

    /** Get the list of clients currently part of the p2p group */
    public Collection<WifiP2pDevice> getClientList() {
        return null;
    }

    /** @hide */
    public void setPassphrase(String passphrase) {
    }

    /**
     * Get the passphrase of the group. This function will return a valid passphrase only
     * at the group owner. Legacy Wi-Fi clients will need this passphrase alongside
     * network name obtained from {@link #getNetworkName()} to join the group
     */
    public String getPassphrase() {
        return null;
    }

    /** @hide */
    public void setInterface(String intf) {
    }

    /** Get the interface name on which the group is created */
    public String getInterface() {
        return null;
    }

    /** @hide */
    public int getNetworkId() {
        return -1;
    }

    /** @hide */
    public void setNetworkId(int netId) {
    }

    public String toString() {
        return null;
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** copy constructor */
    public WifiP2pGroup(WifiP2pGroup source) {
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pGroup> CREATOR = null;
}
