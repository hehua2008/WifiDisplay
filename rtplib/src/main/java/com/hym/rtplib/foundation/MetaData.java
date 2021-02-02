/*
package com.hym.rtplib.foundation;

import android.graphics.Rect;
import android.util.SparseArray;

import com.hym.rtplib.constant.MediaConstants;

import java.nio.ByteBuffer;

public class MetaData implements MediaConstants {
    //public static final int TYPE_NONE = stringToInt("none");
    //public static final int TYPE_C_STRING = stringToInt("cstr");
    //public static final int TYPE_INT32 = stringToInt("in32");
    //public static final int TYPE_INT64 = stringToInt("in64");
    //public static final int TYPE_FLOAT = stringToInt("floa");
    //public static final int TYPE_POINTER = stringToInt("ptr ");
    //public static final int TYPE_RECT = stringToInt("rect");

    private final SparseArray<Object> mItems;

    public MetaData() {
        mItems = new SparseArray<>();
    }

    public MetaData(MetaData from) {
        mItems = from.mItems.clone();
    }

    public void clear() {
        mItems.clear();
    }

    public boolean remove(int key) {
        int i = mItems.indexOfKey(key);
        if (i < 0) {
            return false;
        }
        mItems.removeAt(i);
        return true;
    }

    public boolean setString(int key, String value) {
        return setData(key, value);
    }

    public boolean setInt(int key, int value) {
        return setData(key, value);
    }

    public boolean setLong(int key, long value) {
        return setData(key, value);
    }

    public boolean setFloat(int key, float value) {
        return setData(key, value);
    }

    public boolean setRect(int key, int left, int top, int right, int bottom) {
        Rect r = new Rect(left, top, right, bottom);
        return setData(key, r);
    }

    public boolean setRect(int key, Rect rect) {
        Rect r = new Rect(rect);
        return setData(key, r);
    }

    public String getString(int key) {
        return getData(key);
    }

    public int getInt(int key, int def) {
        return getData(key, def);
    }

    public long getLong(int key, long def) {
        return getData(key, def);
    }

    public float getFloat(int key, float def) {
        return getData(key, def);
    }

    public Rect getRect(int key) {
        return getData(key);
    }

    public <T> boolean setData(int key, T data) {
        int i = mItems.indexOfKey(key);
        if (i >= 0) {
            mItems.setValueAt(i, data);
            return true;
        } else {
            mItems.put(key, data);
            return false;
        }
    }

    public boolean setByteData(int key, int type, ByteBuffer data, int size, boolean copyBuffer) {
        ByteData byteData = new ByteData();
        byteData.setData(type, data, size, copyBuffer);
        return setData(key, byteData);
    }

    public <T> T getData(int key) {
        return getData(key, null);
    }

    private <T> T getData(int key, T def) {
        return (T) mItems.get(key, def);
    }

    public ByteData getByteData(int key, boolean copyBuffer) {
        ByteData value = getData(key);
        if (value == null) {
            return null;
        }
        // Do not return the original ByteData !!!
        return new ByteData(value, copyBuffer);
    }

    public boolean hasData(int key) {
        return mItems.indexOfKey(key) >= 0;
    }

    public String toString() {
        return "MetaData {" + mItems + '}';
    }

    public static class ByteData {
        private int mType;
        private int mSize;
        private ByteBuffer mData;

        public ByteData() {
            mType = 0;
            mSize = 0;
            mData = null;
        }

        public ByteData(ByteData from, boolean copyBuffer) {
            mType = from.mType;
            mSize = from.mSize;
            ByteBuffer data = from.getData(copyBuffer);
        }

        private void clear() {
            mType = 0;
            mSize = 0;
            mData = null;
        }

        private void setData(int type, ByteBuffer data, int size, boolean copyBuffer) {
            mType = type;
            mSize = size;
            mData = duplicateBuffer(data, size, copyBuffer);
        }

        public int getType() {
            return mType;
        }

        public int getSize() {
            return mSize;
        }

        public ByteBuffer getData(boolean copyBuffer) {
            return duplicateBuffer((ByteBuffer) mData.duplicate().rewind(), mSize, copyBuffer);
        }

        private static ByteBuffer duplicateBuffer(ByteBuffer buffer, int size, boolean copyBuffer) {
            ByteBuffer tmp = (ByteBuffer) buffer.slice().limit(size);
            if (!copyBuffer) {
                return tmp;
            }
            ByteBuffer copy = ByteBuffer.allocateDirect(size);
            copy.put(tmp);
            return copy;
        }
    }
}
*/
