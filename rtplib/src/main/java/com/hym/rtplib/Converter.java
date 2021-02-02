package com.hym.rtplib;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.foundation.AHandler;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.util.CheckUtils;
import com.hym.rtplib.util.HexDump;
import com.hym.rtplib.util.MediaFormatUtils;
import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;
import java.nio.ByteBuffer;

public class Converter extends AHandler implements MediaConstants, Errno {
    private static final String TAG = Converter.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int WHAT_ACCESS_UNIT = 0;
    public static final int WHAT_EOS = 1;
    public static final int WHAT_ERROR = 2;
    public static final int WHAT_SHUTDOWN_COMPLETED = 3;

    private static final int WHAT_DO_MORE_WORK = 0;
    private static final int WHAT_REQUEST_IDR_FRAME = 1;
    private static final int WHAT_SUSPEND_ENCODING = 2;
    private static final int WHAT_SHUTDOWN = 3;
    private static final int WHAT_DROP_A_FRAME = 4;

    private final AMessage mNotify;
    private final Looper mConverterLooper;
    private final MediaFormat mOutputFormat;
    private final String mMIME;
    private boolean mIsVideo;
    private boolean mIsH264;
    private boolean mIsPCMAudio;
    private boolean mNeedToManuallyPrependSPSPPS;
    private MediaEncoder mEncoder;
    //private ABuffer mCSD;
    private ABuffer mPartialAudioAU;
    private int mPrevVideoBitrate;
    private int mNumFramesToDrop;
    private boolean mEncodingSuspended;

    private final HandlerThread mOutputThread = new HandlerThread("OutputThread",
            Process.THREAD_PRIORITY_DISPLAY);

    Converter(AMessage notify, Looper converterLooper, MediaFormat outputFormat) {
        super(converterLooper);
        mNotify = notify;
        mConverterLooper = converterLooper;
        mOutputFormat = outputFormat;
        mMIME = outputFormat.getString(MediaFormat.KEY_MIME);
        mIsVideo = false;
        mIsH264 = false;
        mIsPCMAudio = false;
        mNeedToManuallyPrependSPSPPS = false;
        mPrevVideoBitrate = -1;
        mNumFramesToDrop = 0;
        mEncodingSuspended = false;

        if (mMIME.toLowerCase().startsWith("video/")) {
            mIsVideo = true;
            mIsH264 = MediaFormat.MIMETYPE_VIDEO_AVC.equals(mMIME);
        } else if (MediaFormat.MIMETYPE_AUDIO_RAW.equals(mMIME)) {
            mIsPCMAudio = true;
        }
    }

    public int init(MediaProjection mediaProjection, DisplayMetrics displayMetrics) {
        int err = initEncoder(mediaProjection, displayMetrics);

        if (err != OK) {
            releaseEncoder();
        }

        return err;
    }

    public MediaEncoder getMediaEncoder() {
        return mEncoder;
    }

    public MediaFormat getOutputFormat() {
        return mOutputFormat;
    }

    public boolean needToManuallyPrependSPSPPS() {
        return mNeedToManuallyPrependSPSPPS;
    }

    public void requestIDRFrame() {
        AMessage.obtain(WHAT_REQUEST_IDR_FRAME, this).post();
    }

    public void dropAFrame() {
        AMessage.obtain(WHAT_DROP_A_FRAME, this).post();
    }

    public void suspendEncoding(boolean suspend) {
        AMessage msg = AMessage.obtain(WHAT_SUSPEND_ENCODING, this);
        msg.setBoolean(SUSPEND, suspend);
        msg.post();
    }

    public void shutdownAsync() {
        Log.d(TAG, "shutdown");
        AMessage.obtain(WHAT_SHUTDOWN, this).post();
    }

    public int getVideoBitrate() {
        return mPrevVideoBitrate;
    }

    public void setVideoBitrate(int bitRate) {
        if (mIsVideo && mEncoder != null && bitRate != mPrevVideoBitrate) {
            ((VideoEncoder) mEncoder).setVideoBitrate(bitRate);
            mPrevVideoBitrate = bitRate;
        }
    }

    public float getVideoFrameRate() {
        if (mIsVideo && mEncoder != null) {
            return ((VideoEncoder) mEncoder).getFrameRate();
        }
        return 0;
    }

    public void setVideoFrameRate(float frameRate) {
        if (mIsVideo && mEncoder != null) {
            ((VideoEncoder) mEncoder).setFrameRate(frameRate);
        }
    }

    // MUST not conflict with private enums below.
    public static final int WHAT_MEDIA_PULLER_NOTIFY = 'p' << 24 | 'u' << 16 | 'l' << 8 | 'N';

    protected void onMessageReceived(AMessage msg) {
        switch (msg.getWhat()) {
            case WHAT_MEDIA_PULLER_NOTIFY: {
                int what = msg.getInt(WHAT);
                CheckUtils.checkEqual(what, MediaPuller.WHAT_PULL_SUCCESS);

                if (!mIsPCMAudio && mEncoder == null) {
                    Log.d(TAG, "got msg '" + msg + "' after encoder shutdown");
                    break;
                }

                if (mNumFramesToDrop > 0 || mEncodingSuspended) {
                    if (mNumFramesToDrop > 0) {
                        --mNumFramesToDrop;
                        Log.d(TAG, "dropping frame");
                    }

                    break;
                }

                int ret = mEncoder.doEncode();
                break;
            }

            case WHAT_REQUEST_IDR_FRAME: {
                if (mEncoder == null) {
                    break;
                }

                if (mIsVideo) {
                    ((VideoEncoder) mEncoder).requestIDRFrame();
                }
                break;
            }

            case WHAT_SHUTDOWN: {
                Log.d(TAG, String.format("shutting down %s encoder", mIsVideo ? "video" : "audio"));

                releaseEncoder();

                Log.d(TAG, String.format("encoder (%s) shut down.", mMIME));

                AMessage notify = mNotify.dup();
                notify.setInt(WHAT, WHAT_SHUTDOWN_COMPLETED);
                notify.post();

                mOutputThread.quit();
                mConverterLooper.quit();
                break;
            }

            case WHAT_DROP_A_FRAME: {
                ++mNumFramesToDrop;
                break;
            }

            case WHAT_SUSPEND_ENCODING: {
                mEncodingSuspended = msg.getBoolean(SUSPEND, false);
                ((VideoEncoder) mEncoder).suspend(mEncodingSuspended);
                break;
            }

            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private int initEncoder(MediaProjection mediaProjection, DisplayMetrics displayMetrics) {
        if (mIsPCMAudio) {
            return OK;
        }

        if (mIsVideo) {
            mEncoder = new VideoEncoder(mediaProjection, displayMetrics);
        } else {
            mEncoder = new AudioEncoder();
        }

        //int audioBitrate = 128_000;
        //int videoBitrate = 8_000_000;
        //mPrevVideoBitrate = videoBitrate;
        //Log.d(TAG, String.format("using audio bitrate of %d bps, video bitrate of %d bps",
        //        audioBitrate, videoBitrate));
        if (mIsVideo) {
            int width;
            int height;
            int fps;
            try {
                width = mOutputFormat.getInteger(MediaFormat.KEY_WIDTH);
                height = mOutputFormat.getInteger(MediaFormat.KEY_HEIGHT);
            } catch (RuntimeException e) {
                return ERROR_UNSUPPORTED;
            }

            if (mOutputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                fps = mOutputFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
            } else {
                mOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                fps = 30;
            }

            mPrevVideoBitrate = 5_000_000;
            mOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE, mPrevVideoBitrate);
            mOutputFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            // Iframes every 10 secs
            mOutputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        } else {
            mOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        }

        Log.d(TAG, "output format is " + mOutputFormat.toString());

        mOutputThread.start();
        Handler handler = new Handler(mOutputThread.getLooper());
        mEncoder.setCallback(new OutputCallback(), handler);

        mNeedToManuallyPrependSPSPPS = false;

        int err = NO_INIT;

        /*
        if (mIsVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaFormat tmp = new MediaFormat();
            try {
                @SuppressWarnings("JavaReflectionMemberAccess")
                @SuppressLint("DiscouragedPrivateApi")
                Method method = MediaFormat.class.getDeclaredMethod("getMap");
                method.setAccessible(true);
                Map<String, Object> sourceMap = (Map<String, Object>) method.invoke(mOutputFormat);
                Map<String, Object> targetMap = (Map<String, Object>) method.invoke(tmp);
                targetMap.putAll(sourceMap);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            tmp.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);

            try {
                mEncoder.configure(mMIME, tmp);
                // Encoder supported prepending SPS/PPS, we don't need to emulate it.
                mOutputFormat = mEncoder.getOutputFormat();
                err = OK;
            } catch (IOException e) {
                mNeedToManuallyPrependSPSPPS = true;

                Log.d(TAG, "We going to manually prepend SPS and PPS to IDR frames");
                err = -EIO;
            }
        }
        */
        mNeedToManuallyPrependSPSPPS = true;

        if (err != OK) {
            // We'll get here for audio or if we failed to configure the encoder
            // to automatically prepend SPS/PPS in the case of video.

            try {
                mEncoder.configure(mMIME, mOutputFormat);
                MediaFormatUtils.set(mEncoder.getOutputFormat(), mOutputFormat, true);

                err = OK;
            } catch (IOException e) {
                Log.e(TAG, mEncoder + " configure " + mOutputFormat + " failed", e);
                err = -EIO;
                return err;
            }
        }

        err = mEncoder.start() ? OK : -EIO;

        if (err != OK) {
            return err;
        }

        return OK;
    }

    private void releaseEncoder() {
        mEncoder.release();
    }

    private void notifyError(int err) {
        AMessage notify = mNotify.dup();
        notify.setInt(WHAT, WHAT_ERROR);
        notify.setInt(ERR, err);
        notify.post();
    }

    /*
    // Packetizes raw PCM audio data available in mInputBufferQueue
    // into a format suitable for transport stream inclusion and
    // notifies the observer.
    private int feedRawAudioInputBuffers() {
        // Split incoming PCM audio into buffers of 6 AUs of 80 audio frames each
        // and add a 4 byte header according to the wifi display specs.

        while (!mInputBufferQueue.isEmpty()) {
            ABuffer buffer = mInputBufferQueue.remove(0);
            ByteBuffer bufferData = buffer.data();
            // The order of a newly-created byte buffer is always ByteOrder.BIG_ENDIAN.
            ShortBuffer shortBuf = ((ByteBuffer) bufferData.limit(buffer.size())).asShortBuffer();

            final int kFrameSize = 2 * 2;  // sizeof(int16_t) stereo
            final int kFramesPerAU = 80;
            final int kNumAUsPerPESPacket = 6;

            if (mPartialAudioAU != null) {
                int bytesMissingForFullAU =
                        kNumAUsPerPESPacket * kFramesPerAU * kFrameSize
                                - mPartialAudioAU.size() + 4;

                int copy = buffer.size();
                if (copy > bytesMissingForFullAU) {
                    copy = bytesMissingForFullAU;
                }

                bufferData.rewind().limit(copy);
                ((ByteBuffer) mPartialAudioAU.data().position(mPartialAudioAU.size()))
                        .put(bufferData);

                mPartialAudioAU.setRange(0, mPartialAudioAU.size() + copy);

                buffer.setRange(buffer.offset() + copy, buffer.size() - copy);

                long timeUs = buffer.meta().getLong(TIME_US);

                long copyUs = (long) ((copy / kFrameSize) * 1E6 / 48000.0);
                timeUs += copyUs;
                buffer.meta().setLong(TIME_US, timeUs);

                if (bytesMissingForFullAU == copy) {
                    AMessage notify = mNotify.dup();
                    notify.setInt(WHAT, WHAT_ACCESS_UNIT);
                    notify.set(ACCESS_UNIT, mPartialAudioAU);
                    notify.post();

                    mPartialAudioAU = null;
                }
            }

            while (buffer.size() > 0) {
                ABuffer partialAudioAU =
                        new ABuffer(4 + kNumAUsPerPESPacket * kFrameSize * kFramesPerAU);

                ByteBuffer pauData = partialAudioAU.data();
                pauData.put((byte) 0xa0);  // 10100000b
                pauData.put((byte) kNumAUsPerPESPacket);
                pauData.put((byte) 0);  // reserved, audio _emphasis_flag = 0

                final int kQuantizationWordLength = 0;  // 16-bit
                final int kAudioSamplingFrequency = 2;  // 48Khz
                final int kNumberOfAudioChannels = 1;  // stereo

                pauData.put((byte) ((kQuantizationWordLength << 6)
                        | (kAudioSamplingFrequency << 3)
                        | kNumberOfAudioChannels));

                int copy = buffer.size();
                if (copy > partialAudioAU.size() - 4) {
                    copy = partialAudioAU.size() - 4;
                }

                pauData.put((ByteBuffer) bufferData.rewind().limit(copy));

                partialAudioAU.setRange(0, 4 + copy);
                buffer.setRange(buffer.offset() + copy, buffer.size() - copy);

                long timeUs = buffer.meta().getLong(TIME_US);

                partialAudioAU.meta().setLong(TIME_US, timeUs);

                long copyUs = (long) ((copy / kFrameSize) * 1E6 / 48000.0);
                timeUs += copyUs;
                buffer.meta().setLong(TIME_US, timeUs);

                if (copy == partialAudioAU.capacity() - 4) {
                    AMessage notify = mNotify.dup();
                    notify.setInt(WHAT, WHAT_ACCESS_UNIT);
                    notify.set(ACCESS_UNIT, partialAudioAU);
                    notify.post();

                    partialAudioAU = null;
                    continue;
                }

                mPartialAudioAU = partialAudioAU;
            }
        }

        return OK;
    }
    */

    /*
    private ABuffer prependCSD(ABuffer accessUnit) {
        CheckUtils.check(mCSD != null);

        ABuffer buffer = new ABuffer(accessUnit.size() + mCSD.size());
        ByteBuffer unitData = (ByteBuffer) accessUnit.data().limit(accessUnit.size());
        buffer.data().put(mCSD.data()).put(unitData);

        long timeUs = accessUnit.meta().getLong(TIME_US);

        buffer.meta().setLong(TIME_US, timeUs);

        return buffer;
    }
    */

    private class OutputCallback implements MediaEncoder.OutputCallback {
        private int mOutputCount = 0;

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index,
                MediaCodec.BufferInfo info) {
            try {
                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                // It's usually necessary to adjust the ByteBuffer values to match BufferInfo.
                outputBuffer.position(info.offset);
                outputBuffer.limit(info.offset + info.size);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.w(TAG, "reach BUFFER_FLAG_END_OF_STREAM");
                    AMessage notify = mNotify.dup();
                    notify.setInt(WHAT, WHAT_EOS);
                    notify.post();
                    return;
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    Log.w(TAG, "reach BUFFER_FLAG_CODEC_CONFIG");
                    //mCSD = new ABuffer(outputBuffer.remaining());
                    //mCSD.data().put(outputBuffer);
                } else {
                    VideoEncoder encoder = (VideoEncoder) mEncoder;
                    Image writerImage = encoder.mWriterList.get(mOutputCount++);
                    long inputTimeStamp = encoder.mTimestampMap.get(writerImage);
                    Image readerImage = encoder.mWriterToReaderMap.get(writerImage);
                    long frameTimeStamp = encoder.mTimestampMap.get(readerImage);
                    long outputTimestamp = TimeUtils.getMonotonicMilliTime();
                    Log.w(TAG, "encodeTime=" + (outputTimestamp - inputTimeStamp)
                            + " queueTime=" + (inputTimeStamp - frameTimeStamp));

                    boolean isIDR = ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    if (isIDR) {
                        Log.w(TAG, "reach BUFFER_FLAG_KEY_FRAME");
                    }
                    ABuffer buffer = new ABuffer(info.size);
                    buffer.meta().set(IS_IDR, isIDR);
                    buffer.meta().setLong(TIME_US, info.presentationTimeUs);
                    ByteBuffer bufData = buffer.data();
                    bufData.put(outputBuffer);
                    AMessage notify = mNotify.dup();
                    notify.setInt(WHAT, WHAT_ACCESS_UNIT);
                    notify.set(ACCESS_UNIT, buffer);
                    notify.post();
                }
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            Log.w(TAG, "mEncoder output format changed: " + format);
            MediaFormatUtils.set(format, mOutputFormat, true);
            ByteBuffer csd0 = format.getByteBuffer("csd-0").duplicate();
            if (csd0 != null) {
                byte[] bytes = new byte[csd0.remaining()];
                csd0.get(bytes);
                Log.w(TAG, "csd-0 :" + HexDump.dumpHexString(bytes));
                int offset = 0;
                while (offset < bytes.length - 1 && bytes[offset++] != (byte) 0x01) {
                }
                String sps = Base64.encodeToString(bytes, offset, bytes.length - offset,
                        Base64.NO_WRAP);
                Log.w(TAG, "SPS : " + sps);
            }
            ByteBuffer csd1 = format.getByteBuffer("csd-1").duplicate();
            if (csd1 != null) {
                byte[] bytes = new byte[csd1.remaining()];
                csd1.get(bytes);
                Log.w(TAG, "csd-1 :" + HexDump.dumpHexString(bytes));
                int offset = 0;
                while (offset < bytes.length - 1 && bytes[offset++] != (byte) 0x01) {
                }
                String pps = Base64.encodeToString(bytes, offset, bytes.length - offset,
                        Base64.NO_WRAP);
                Log.w(TAG, "PPS : " + pps);
            }
        }
    }
}
