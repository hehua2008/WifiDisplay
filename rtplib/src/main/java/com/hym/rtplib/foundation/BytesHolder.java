package com.hym.rtplib.foundation;

import android.util.Log;

import java.nio.ByteBuffer;

public final class BytesHolder {
    private static final String TAG = BytesHolder.class.getSimpleName();

    private ByteBuffer mByteBuffer;

    public enum Position {
        ZERO,
        POSITION,
        LIMIT,
        CAPACITY
    }

    private int getIndex(Position copyPosition) {
        switch (copyPosition) {
            default:
            case ZERO:
                return 0;
            case POSITION:
                return mByteBuffer.position();
            case LIMIT:
                return mByteBuffer.limit();
            case CAPACITY:
                return mByteBuffer.capacity();
        }
    }

    public BytesHolder(int capacity) {
        resizeTo(capacity);
    }

    public void resizeTo(int newCapacity) {
        resizeTo(newCapacity, 0, 0);
    }

    public void resizeTo(int newCapacity, Position copyStart, Position copyEnd) {
        resizeTo(newCapacity, getIndex(copyStart), getIndex(copyEnd));
    }

    public void resizeTo(int newCapacity, int copyStart, int copyEnd) {
        if (mByteBuffer != null) {
            if (copyStart < 0 || copyEnd > mByteBuffer.capacity() || copyStart > copyEnd) {
                throw new IllegalArgumentException("mByteBuffer.capacity=" + mByteBuffer.capacity()
                        + ", copyStart=" + copyStart + ", copyEnd=" + copyEnd);
            }
            if (newCapacity <= mByteBuffer.capacity()) {
                return;
            }
        }
        Log.d(TAG, "required newCapacity=" + newCapacity);
        int n = newCapacity - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        newCapacity = n + 1;
        ByteBuffer newByteBuffer = ByteBuffer.wrap(new byte[newCapacity]);
        if (mByteBuffer != null) {
            Log.d(TAG, this + " resize " + mByteBuffer.capacity() + " -> " + newCapacity);
            if (copyStart < copyEnd) {
                mByteBuffer.position(copyStart).limit(copyEnd);
                newByteBuffer.put(mByteBuffer);
            }
        }
        mByteBuffer = newByteBuffer;
    }

    public byte[] getByteArray() {
        return mByteBuffer.array();
    }

    public ByteBuffer getByteBuffer() {
        return mByteBuffer;
    }
}
