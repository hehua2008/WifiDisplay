/*
 * Copyright (C) 2012 The Android Open Source Project
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
 * A class representing a Wi-Fi P2p group list
 *
 * {@see WifiP2pManager}
 *
 * @hide
 */
public class WifiP2pGroupList implements Parcelable {
    public interface GroupDeleteListener {
        public void onDeleteGroup(int netId);
    }

    /** @hide */
    public WifiP2pGroupList() {
        this(null, null);
    }

    /** @hide */
    public WifiP2pGroupList(WifiP2pGroupList source, GroupDeleteListener listener) {
    }

    /**
     * Return the list of p2p group.
     *
     * @return the list of p2p group.
     */
    public Collection<WifiP2pGroup> getGroupList() {
        return null;
    }

    /**
     * Add the specified group to this group list.
     *
     * @hide
     */
    public void add(WifiP2pGroup group) {
    }

    /**
     * Remove the group with the specified network id from this group list.
     *
     * @hide
     */
    public void remove(int netId) {
    }

    /**
     * Remove the group with the specified device address from this group list.
     */
    void remove(String deviceAddress) {
    }

    /**
     * Clear the group.
     *
     * @hide
     */
    public boolean clear() {
        return false;
    }

    /**
     * Return the network id of the group owner profile with the specified p2p device
     * address.
     * If more than one persistent group of the same address is present in the list,
     * return the first one.
     *
     * @param deviceAddress p2p device address.
     * @return the network id. if not found, return -1.
     * @hide
     */
    public int getNetworkId(String deviceAddress) {
        return -1;
    }

    /**
     * Return the network id of the group with the specified p2p device address
     * and the ssid.
     *
     * @param deviceAddress p2p device address.
     * @param ssid          ssid.
     * @return the network id. if not found, return -1.
     * @hide
     */
    public int getNetworkId(String deviceAddress, String ssid) {
        return -1;
    }

    /**
     * Return the group owner address of the group with the specified network id
     *
     * @param netId network id.
     * @return the address. if not found, return null.
     * @hide
     */
    public String getOwnerAddr(int netId) {
        return null;
    }

    /**
     * Return true if this group list contains the specified network id.
     * This function does NOT update LRU information.
     * It means the internal queue is NOT reordered.
     *
     * @param netId network id.
     * @return true if the specified network id is present in this group list.
     * @hide
     */
    public boolean contains(int netId) {
        return false;
    }

    public String toString() {
        return null;
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiP2pGroupList> CREATOR = null;
}
