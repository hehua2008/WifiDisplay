package com.hym.rtplib;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.Pair;

import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;

public class AudioEncoder implements MediaEncoder {
    private static final String TAG = AudioEncoder.class.getSimpleName();
    private static final boolean DEBUG = true;

    private MediaCodec.Callback mCallback;
    private Handler mCallbackHandler;
    private int mBufferSize;
    private AudioRecord mAudioRecord;
    private MediaCodec mEncoder;

    private final BlockingDeque<Pair<ByteBuffer, Integer>> mInputBuffers =
            new LinkedBlockingDeque<>();

    private final AtomicReference<Pair<ByteBuffer, Integer>> mPendingInputBuffer =
            new AtomicReference<>(null);

    @Override
    public void setCallback(final OutputCallback callback, Handler handler) {
        Log.d(TAG, "setCallback: " + callback + ", " + handler);
        MediaCodec.Callback realCallback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                ByteBuffer buffer = codec.getInputBuffer(index);
                mInputBuffers.addLast(Pair.create(buffer, index));
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int index,
                    MediaCodec.BufferInfo info) {
                callback.onOutputBufferAvailable(codec, index, info);
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                Log.e(TAG, codec + " onError", e);
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec,
                    MediaFormat format) {
                callback.onOutputFormatChanged(codec, format);
            }
        };
        mCallback = realCallback;
        mCallbackHandler = handler;
    }

    @Override
    public void configure(String mimeType, MediaFormat format) throws IOException {
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        mBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * 2;
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, mBufferSize);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mBufferSize);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);

        Log.w(TAG, "encoder format " + format);
        mEncoder = MediaCodec.createEncoderByType(mimeType);
        Log.w(TAG, "selected encoder " + mEncoder.getName());
        mEncoder.setCallback(mCallback, mCallbackHandler);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    }

    @Override
    public MediaFormat getOutputFormat() {
        return mEncoder.getOutputFormat();
    }

    @Override
    public void setParameters(Bundle params) {
        Log.d(TAG, "setParameters " + params);
        mEncoder.setParameters(params);
    }

    @Override
    public boolean start() {
        Log.d(TAG, "start");
        try {
            mEncoder.start();
        } catch (RuntimeException e) {
            Log.e(TAG, "start " + mEncoder + " failed", e);
            return false;
        }
        return true;
    }

    @Override
    public void stop() {
        Log.d(TAG, "stop");
        release();
    }

    @Override
    public void release() {
        Log.d(TAG, "release");
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
        destroyEncoder();
    }

    private void destroyEncoder() {
        if (mEncoder == null) {
            Log.w(TAG, "destroyEncoder mEncoder is null");
            return;
        }
        try {
            mEncoder.stop();
            mEncoder.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "destroyEncoder exception", e);
        }
        Log.w(TAG, mEncoder + " destroyed");
        mEncoder = null;
    }

    @Override
    public int doPull() {
        Pair<ByteBuffer, Integer> pair = mInputBuffers.getFirst();
        if (pair == null) {
            Log.w(TAG, "mInputBuffers is null");
            return -ENODATA;
        }
        pair.first.clear();
        int ret = mAudioRecord.read(pair.first, mBufferSize);
        pair.first.rewind();
        if (ret < 0) {
            Log.w(TAG, mAudioRecord + " read " + ret);
            return -ENODATA;
        } else if (ret == 0) {
            Log.w(TAG, mAudioRecord + " read 0");
            return -ENODATA;
        }
        pair.first.limit(ret);
        mInputBuffers.removeFirst();
        Pair<ByteBuffer, Integer> oldPair = mPendingInputBuffer.getAndSet(pair);
        if (oldPair != null) {
            Log.w(TAG, "drop one buffer!");
            mEncoder.queueInputBuffer(oldPair.second, 0, 0, TimeUtils.getMonotonicMicroTime(), 0);
        }
        return OK;
    }

    @Override
    public int doEncode() {
        Pair<ByteBuffer, Integer> pair = mPendingInputBuffer.getAndSet(null);
        if (pair != null) {
            mEncoder.queueInputBuffer(pair.second, 0, pair.first.remaining(),
                    TimeUtils.getMonotonicMicroTime(), 0);
            return OK;
        }
        return -ENODATA;
    }
}
