package com.hym.rtplib;

import android.util.Log;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.util.CheckUtils;

import java.nio.ByteBuffer;
import java.util.List;

public abstract class RTPAssembler implements MediaConstants, Errno {
    private static final String TAG = RTPAssembler.class.getSimpleName();

    private final AMessage mNotify;

    public RTPAssembler(AMessage notify) {
        mNotify = notify;
    }

    public abstract void signalDiscontinuity();

    public abstract int processPacket(ABuffer packet);

    protected void postAccessUnit(ABuffer accessUnit, boolean followsDiscontinuity) {
        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, RTPReceiver.WHAT_ACCESS_UNIT);
        notify.set(ACCESS_UNIT, accessUnit);
        notify.setBoolean(FOLLOWS_DISCONTINUITY, followsDiscontinuity);
    }

    public static class TSAssembler extends RTPAssembler {
        private boolean mSawDiscontinuity;

        public TSAssembler(AMessage notify) {
            super(notify);
            mSawDiscontinuity = false;
        }

        @Override
        public void signalDiscontinuity() {
            mSawDiscontinuity = true;
        }

        @Override
        public int processPacket(ABuffer packet) {
            int rtpTime = packet.meta().getInt(RTP_TIME);

            packet.meta().setLong(TIME_US, rtpTime * 100 / 9);

            postAccessUnit(packet, mSawDiscontinuity);

            if (mSawDiscontinuity) {
                mSawDiscontinuity = false;
            }

            return OK;
        }
    }

    public static class H264Assembler extends RTPAssembler {
        private int mState;

        private int mIndicator;
        private int mNALType;

        private ABuffer mAccumulator;

        private List<ABuffer> mNALUnits;

        private int mAccessUnitRTPTime;

        public H264Assembler(AMessage notify) {
            super(notify);
            mState = 0;
            mIndicator = 0;
            mNALType = 0;
            mAccessUnitRTPTime = 0;
        }

        @Override
        public void signalDiscontinuity() {
            reset();
        }

        @Override
        public int processPacket(ABuffer packet) {
            int err = internalProcessPacket(packet);

            if (err != OK) {
                reset();
            }

            return err;
        }

        private int internalProcessPacket(ABuffer packet) {
            ByteBuffer packetData = packet.data();
            int size = packet.size();

            switch (mState) {
                case 0: {
                    int data0 = packetData.get(0) & 0xff;
                    if (size < 1 || (data0 & 0x80) != 0) {
                        Log.w(TAG, "Malformed H264 RTP packet (empty or F-bit set)");
                        return ERROR_MALFORMED;
                    }

                    int nalType = data0 & 0x1f;
                    if (nalType >= 1 && nalType <= 23) {
                        addSingleNALUnit(packet);
                        Log.d(TAG, "added single NAL packet");
                    } else if (nalType == 28) {
                        // FU-A
                        int indicator = data0;
                        CheckUtils.check((indicator & 0x1f) == 28);

                        if (size < 2) {
                            Log.w(TAG, "Malformed H264 FU-A packet (single byte)");
                            return ERROR_MALFORMED;
                        }

                        int data1 = packetData.get(1) & 0xff;
                        if ((data1 & 0x80) == 0) {
                            Log.w(TAG, "Malformed H264 FU-A packet (no start bit)");
                            return ERROR_MALFORMED;
                        }

                        mIndicator = data0;
                        mNALType = data1 & 0x1f;
                        int nri = (data0 >>> 5) & 3;

                        clearAccumulator();

                        byte info = (byte) (mNALType | (nri << 5));
                        appendToAccumulator(ByteBuffer.wrap(new byte[]{info}), 1);
                        appendToAccumulator(((ByteBuffer) packetData.position(2)).slice(),
                                size - 2);

                        int rtpTime = packet.meta().getInt(RTP_TIME);
                        mAccumulator.meta().setInt(RTP_TIME, rtpTime);

                        if ((data1 & 0x40) != 0) {
                            // Huh? End bit also set on the first buffer.
                            addSingleNALUnit(mAccumulator);
                            clearAccumulator();

                            Log.d(TAG, "added FU-A");
                            break;
                        }

                        mState = 1;
                    } else if (nalType == 24) {
                        // STAP-A

                        int err = addSingleTimeAggregationPacket(packet);
                        if (err != OK) {
                            return err;
                        }
                    } else {
                        Log.w(TAG, "Malformed H264 packet: unknown type " + nalType);
                        return ERROR_UNSUPPORTED;
                    }
                    break;
                }

                case 1: {
                    int data0 = packetData.get(0) & 0xff;
                    int data1 = packetData.get(1) & 0xff;
                    if (size < 2
                            || data0 != mIndicator
                            || (data1 & 0x1f) != mNALType
                            || ((data1 & 0x80) != 0)) {
                        Log.w(TAG, "Malformed H264 FU-A packet (indicator, "
                                + "type or start bit mismatch)");

                        return ERROR_MALFORMED;
                    }

                    appendToAccumulator(((ByteBuffer) packetData.position(2)).slice(), size - 2);

                    if ((data1 & 0x40) != 0) {
                        addSingleNALUnit(mAccumulator);

                        clearAccumulator();
                        mState = 0;

                        Log.d(TAG, "added FU-A");
                    }
                    break;
                }

                default:
                    throw new RuntimeException("TRESPASS");
            }

            int marker = packet.meta().getInt(MARKER);

            if (marker != 0) {
                flushAccessUnit();
            }

            return OK;
        }

        private void addSingleNALUnit(ABuffer packet) {
            if (mNALUnits.isEmpty()) {
                int rtpTime = mAccumulator.meta().getInt(RTP_TIME);

                mAccessUnitRTPTime = rtpTime;
            }

            mNALUnits.add(packet);
        }

        private int addSingleTimeAggregationPacket(ABuffer packet) {
            ByteBuffer packetData = packet.data();
            int size = packet.size();

            if (size < 3) {
                Log.w(TAG, "Malformed H264 STAP-A packet (too small)");
                return ERROR_MALFORMED;
            }

            int rtpTime = packet.meta().getInt(RTP_TIME);

            packetData = ((ByteBuffer) packetData.position(1)).slice();
            --size;
            while (size >= 2) {
                int data0 = packetData.get(0) & 0xff;
                int data1 = packetData.get(1) & 0xff;
                int nalSize = (data0 << 8) | data1;

                if (size < nalSize + 2) {
                    Log.w(TAG, "Malformed H264 STAP-A packet (incomplete NAL unit)");
                    return ERROR_MALFORMED;
                }

                ABuffer unit = new ABuffer(nalSize);
                packetData.position(2).limit(2 + nalSize);
                unit.data().put(packetData);

                unit.meta().setInt(RTP_TIME, rtpTime);

                addSingleNALUnit(unit);

                size -= 2 + nalSize;
            }

            if (size != 0) {
                Log.w(TAG, "Unexpected padding at end of STAP-A packet");
            }

            Log.d(TAG, "added STAP-A");

            return OK;
        }

        private void flushAccessUnit() {
            if (mNALUnits.isEmpty()) {
                return;
            }

            int totalSize = 0;
            for (ABuffer unit : mNALUnits) {
                totalSize += 4 + unit.size();
            }

            ABuffer accessUnit = new ABuffer(totalSize);
            ByteBuffer dstData = accessUnit.data();
            for (ABuffer unit : mNALUnits) {
                dstData.put(NAL_START_BUFFER);
                ByteBuffer srcData = unit.data();
                srcData.limit(unit.size());
                dstData.put(srcData);
            }

            mNALUnits.clear();

            accessUnit.meta().setLong(TIME_US, mAccessUnitRTPTime * 100 / 9);
            postAccessUnit(accessUnit, false);
        }

        private void clearAccumulator() {
            if (mAccumulator != null) {
                mAccumulator = null;
            }
        }

        private void appendToAccumulator(ByteBuffer srcData, int size) {
            if (mAccumulator == null) {
                mAccumulator = new ABuffer(size);
                srcData.rewind().limit(size);
                mAccumulator.data().put(srcData);
                return;
            }

            if (mAccumulator.size() + size > mAccumulator.capacity()) {
                ABuffer buf = new ABuffer(mAccumulator.size() + size);
                ByteBuffer oriData = mAccumulator.data();
                oriData.limit(mAccumulator.size());
                buf.data().put(oriData);
                buf.setRange(0, mAccumulator.size());

                int rtpTime = mAccumulator.meta().getInt(RTP_TIME, Integer.MIN_VALUE);
                if (rtpTime != Integer.MIN_VALUE) {
                    buf.meta().setInt(RTP_TIME, rtpTime);
                }

                mAccumulator = buf;
            }

            srcData.rewind().limit(size);
            ByteBuffer dstData = mAccumulator.data();
            dstData.position(mAccumulator.size());
            dstData.put(srcData);
            mAccumulator.setRange(0, mAccumulator.size() + size);
        }

        private void reset() {
            mNALUnits.clear();

            clearAccumulator();
            mState = 0;
        }
    }
}
