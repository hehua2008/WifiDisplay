package com.hym.rtplib.foundation;

import com.hym.rtplib.util.CheckUtils;

import java.nio.ByteBuffer;

public class ABitReader {
    protected final ByteBuffer mData;
    protected int mDataIndex;
    protected int mSize;

    protected int mReservoir;  // left-aligned bits
    protected int mNumBitsLeft;
    protected boolean mOverRead;

    public ABitReader(ByteBuffer data, int size) {
        mData = data.slice();
        mDataIndex = 0;
        mSize = size;
        mReservoir = 0;
        mNumBitsLeft = 0;
        mOverRead = false;
    }

    // Tries to get |n| bits. If not successful, returns |fallback|. Otherwise, returns result.
    // Reading 0 bits will always succeed and return 0.
    public int getBitsWithFallback(int n, int fallback) {
        int[] ret = {fallback};
        getBitsGraceful(n, ret);
        return ret[0];
    }

    // Tries to get |n| bits. If not successful, returns false. Otherwise, stores result in |out|
    // and returns true. Use !overRead() to determine if this call was successful. Reading 0 bits
    // will always succeed and write 0 in |out|.
    public boolean getBitsGraceful(int n, final int[] out) {
        if (n > 32) {
            return false;
        }

        int result = 0;
        while (n > 0) {
            if (mNumBitsLeft == 0) {
                if (!fillReservoir()) {
                    return false;
                }
            }

            int m = n;
            if (m > mNumBitsLeft) {
                m = mNumBitsLeft;
            }

            result = (result << m) | (mReservoir >>> (32 - m));
            mReservoir <<= m;
            mNumBitsLeft -= m;

            n -= m;
        }

        out[0] = result;
        return true;
    }

    // Gets |n| bits and returns result. ABORTS if unsuccessful. Reading 0 bits will always
    // succeed.
    public int getBits(int n) {
        int[] ret = new int[1];
        CheckUtils.check(getBitsGraceful(n, ret));
        return ret[0];
    }

    // Tries to skip |n| bits. Returns true iff successful. Skipping 0 bits will always succeed.
    public boolean skipBits(int n) {
        int[] dummy = new int[1];
        while (n > 32) {
            if (!getBitsGraceful(32, dummy)) {
                return false;
            }
            n -= 32;
        }

        if (n > 0) {
            return getBitsGraceful(n, dummy);
        }
        return true;
    }

    // "Puts" |n| bits with the value |x| back virtually into the bit stream. The put-back bits
    // are not actually written into the data, but are tracked in a separate buffer that can
    // store at most 32 bits. This is a no-op if the stream has already been over-read.
    public void putBits(int x, int n) {
        if (mOverRead) {
            return;
        }

        CheckUtils.checkLessThan(n, 32);

        while (mNumBitsLeft + n > 32) {
            mNumBitsLeft -= 8;
            --mDataIndex;
            ++mSize;
        }

        mReservoir = (mReservoir >>> n) | (x << (32 - n));
        mNumBitsLeft += n;
    }

    public int numBitsLeft() {
        return mSize * 8 + mNumBitsLeft;
    }

    public ByteBuffer data() {
        return ((ByteBuffer) mData.duplicate().position(
                mDataIndex - (mNumBitsLeft + 7) / 8)).slice();
    }

    // Returns true if the stream was over-read (e.g. any getBits operation has been unsuccessful
    // due to overRead (and not trying to read >32 bits).)
    public boolean overRead() {
        return mOverRead;
    }

    protected boolean fillReservoir() {
        if (mSize == 0) {
            mOverRead = true;
            return false;
        }

        mReservoir = 0;
        int i;
        for (i = 0; mSize > 0 && i < 4; ++i) {
            mReservoir = (mReservoir << 8) | (mData.get(mDataIndex) & 0xFF);

            ++mDataIndex;
            --mSize;
        }

        mNumBitsLeft = 8 * i;
        mReservoir <<= 32 - mNumBitsLeft;
        return true;
    }
}
