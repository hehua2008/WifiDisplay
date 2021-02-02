package com.hym.rtplib.foundation;

import com.hym.rtplib.util.CheckUtils;

import java.nio.ByteBuffer;

public class ABuffer {
    private final ByteBuffer mData;
    private final int mCapacity;
    private final boolean mOwnsData;

    private int mRangeOffset;
    private int mRangeLength;

    private AMessage mMeta;
    private int mInt32Data;

    public ABuffer(int capacity) {
        this(capacity, false);
    }

    public ABuffer(int capacity, boolean direct) {
        mRangeOffset = 0;
        mInt32Data = 0;
        mOwnsData = true;
        mData = direct ? ByteBuffer.allocateDirect(capacity) : ByteBuffer.allocate(capacity);
        mCapacity = capacity;
        mRangeLength = capacity;
    }

    public ABuffer(ByteBuffer data, int capacity) {
        mData = data.slice();
        mCapacity = capacity;
        mRangeOffset = 0;
        mRangeLength = capacity;
        mInt32Data = 0;
        mOwnsData = false;
    }

    public void reset() {
        mRangeOffset = 0;
        mInt32Data = 0;
        mRangeLength = mCapacity;
        // TODO: set all bytes to zero ?
        mData.clear();
        if (mMeta != null) {
            mMeta.recycle();
            mMeta = null;
        }
    }

    public ByteBuffer base() {
        return (ByteBuffer) mData.duplicate().rewind();
    }

    public ByteBuffer data() {
        return ((ByteBuffer) mData.duplicate().position(mRangeOffset)).slice();
    }

    public int capacity() {
        return mCapacity;
    }

    public int size() {
        return mRangeLength;
    }

    public int offset() {
        return mRangeOffset;
    }

    public void setRange(int offset, int size) {
        CheckUtils.checkLessOrEqual(offset, mCapacity);
        CheckUtils.checkLessOrEqual(offset + size, mCapacity);

        mRangeOffset = offset;
        mRangeLength = size;
    }

    public AMessage meta() {
        if (mMeta == null) {
            synchronized (this) {
                if (mMeta == null) {
                    mMeta = AMessage.obtain();
                }
            }
        }
        return mMeta;
    }

    public static ABuffer createAsCopy(ByteBuffer data, int capacity) {
        ABuffer res = new ABuffer(capacity);
        data = (ByteBuffer) data.slice().limit(capacity);
        res.data().put(data);
        return res;
    }

    public void setInt32Data(int data) {
        mInt32Data = data;
    }

    public int getInt32Data() {
        return mInt32Data;
    }
}
