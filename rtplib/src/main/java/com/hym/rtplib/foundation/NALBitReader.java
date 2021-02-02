package com.hym.rtplib.foundation;

import java.nio.ByteBuffer;

public class NALBitReader extends ABitReader {
    private int mNumZeros;

    public NALBitReader(ByteBuffer data, int size) {
        super(data, size);
        mNumZeros = 0;
    }

    public boolean atLeastNumBitsLeft(int n) {
        // check against raw size and reservoir bits first
        int numBits = numBitsLeft();
        if (n > numBits) {
            return false;
        }

        int numBitsRemaining = n - mNumBitsLeft;

        int size = mSize;
        ByteBuffer data = mData.slice();
        int dataIndex = 0;
        int numZeros = mNumZeros;
        while (size > 0 && numBitsRemaining > 0) {
            int content = data.get(dataIndex) & 0xFF;
            boolean isEmulationPreventionByte = (numZeros >= 2 && content == 3);

            if (content == 0) {
                ++numZeros;
            } else {
                numZeros = 0;
            }

            if (!isEmulationPreventionByte) {
                numBitsRemaining -= 8;
            }

            ++dataIndex;
            --size;
        }

        return (numBitsRemaining <= 0);
    }

    protected boolean fillReservoir() {
        if (mSize == 0) {
            mOverRead = true;
            return false;
        }

        mReservoir = 0;
        int i = 0;
        while (mSize > 0 && i < 4) {
            int content = mData.get(mDataIndex) & 0xFF;
            boolean isEmulationPreventionByte = (mNumZeros >= 2 && content == 3);

            if (content == 0) {
                ++mNumZeros;
            } else {
                mNumZeros = 0;
            }

            // skip emulation_prevention_three_byte
            if (!isEmulationPreventionByte) {
                mReservoir = (mReservoir << 8) | content;
                ++i;
            }

            ++mDataIndex;
            --mSize;
        }

        mNumBitsLeft = 8 * i;
        mReservoir <<= 32 - mNumBitsLeft;
        return true;
    }
}
