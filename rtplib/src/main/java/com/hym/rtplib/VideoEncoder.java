package com.hym.rtplib;

import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.util.ImageUtils;
import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class VideoEncoder implements MediaEncoder, MediaConstants {
    private static final String TAG = VideoEncoder.class.getSimpleName();
    private static final boolean DEBUG = true;
    private static final boolean DEBUG_TIMESTAMP = true;

    private static final String DISPLAY_NAME = TAG;

    private static final int MAX_IMAGES = 4;
    private static final int IMAGE_FORMAT = PixelFormat.RGBA_8888;

    private final MediaProjection mMediaProjection;
    private final DisplayMetrics mDisplayMetrics;

    private final Semaphore mAvailableWriterImageCount = new Semaphore(MAX_IMAGES);
    private final HandlerThread mListenerThread = new HandlerThread("ImageListenerThread",
            Process.THREAD_PRIORITY_DISPLAY);

    private MediaCodec.Callback mCallback;
    private Handler mCallbackHandler;
    private MediaCodec mEncoder;
    private Surface mEncoderSurface;
    private Surface mImageReaderSurface;
    private ImageWriter mImageWriter;
    private ImageReader mImageReader;

    private VirtualDisplay mVirtualDisplay;
    private int mWidth;
    private int mHeight;

    public VideoEncoder(MediaProjection mediaProjection, DisplayMetrics displayMetrics) {
        mMediaProjection = mediaProjection;
        mDisplayMetrics = displayMetrics;
    }

    @Override
    public void setCallback(final OutputCallback callback, Handler handler) {
        Log.d(TAG, "setCallback: " + callback + ", " + handler);
        MediaCodec.Callback realCallback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                // Ignore
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

    @SuppressLint({"NewApi", "WrongConstant"})
    @Override
    public void configure(String mimeType, MediaFormat format) throws IOException {
        checkFormat(format);
        Log.w(TAG, "encoder format " + format);
        mEncoder = MediaCodec.createEncoderByType(mimeType);
        Log.w(TAG, "selected encoder " + mEncoder.getName());
        mEncoder.setCallback(mCallback, mCallbackHandler);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // VirtualDisplay -> ImageReader -> ImageWriter -> Encoder
        mEncoderSurface = mEncoder.createInputSurface();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mImageWriter = ImageWriter.newInstance(mEncoderSurface, MAX_IMAGES, IMAGE_FORMAT);
        } else {
            mImageWriter = ImageWriter.newInstance(mEncoderSurface, MAX_IMAGES);
        }
        mImageReader = ImageReader.newInstance(mWidth, mHeight, IMAGE_FORMAT, MAX_IMAGES);
        mImageReaderSurface = mImageReader.getSurface();
        mListenerThread.start();
        Handler handler = new Handler(mListenerThread.getLooper());
        mImageWriter.setOnImageReleasedListener(this::onImageReleased, handler);
        mImageReader.setOnImageAvailableListener(this::onImageAvailable, handler);
    }

    private void checkFormat(MediaFormat format) {
        mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);

        int displayWidth = mDisplayMetrics.widthPixels;
        int displayHeight = mDisplayMetrics.heightPixels;

        if (mWidth > displayWidth || mHeight > displayHeight) {
            int newWidth;
            int newHeight;
            if (mWidth * displayHeight > displayWidth * mHeight) { // mWidth is much bigger
                newWidth = displayWidth;
                newHeight = mHeight * newWidth / mWidth;
                if ((newHeight & 1) == 1) {
                    newHeight -= 1;
                }
            } else if (mWidth * displayHeight < displayWidth * mHeight) { // mHeight is much bigger
                newHeight = displayHeight;
                newWidth = mWidth * newHeight / mHeight;
                if ((newWidth & 1) == 1) {
                    newWidth -= 1;
                }
            } else {
                newWidth = displayWidth;
                newHeight = displayHeight;
            }
            mWidth = newWidth;
            mHeight = newHeight;
        }

        format.setInteger(MediaFormat.KEY_WIDTH, mWidth);
        format.setInteger(MediaFormat.KEY_HEIGHT, mHeight);

        int bitRate = format.getInteger(MediaFormat.KEY_BIT_RATE);
        bitRate = Math.max(VIDEO_BIT_RATE_MIN, Math.min(bitRate, VIDEO_BIT_RATE_MAX));
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);

        mFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
        mFrameRate = Math.max(FRAME_RATE_MIN, Math.min(mFrameRate, FRAME_RATE_MAX));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, (int) mFrameRate);
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, (int) mFrameRate);
        //format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000 / mFrameRate);

        int iFrameInterval = format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL);
        iFrameInterval = Math.max(I_FRAME_INTERVAL_MIN,
                Math.min(iFrameInterval, I_FRAME_INTERVAL_MAX));
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    }

    @Override
    public MediaFormat getOutputFormat() {
        return mEncoder.getOutputFormat();
    }

    @Override
    public boolean start() {
        Log.d(TAG, "start");
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(DISPLAY_NAME,
                mWidth, mHeight, 160, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReaderSurface, null, null);
        if (mVirtualDisplay == null) {
            Log.e(TAG, "createVirtualDisplay failed");
            return false;
        }
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
        mListenerThread.quit();
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        mMediaProjection.stop();
        if (mImageReader != null) {
            mImageReader.close(); // This will release mImageReaderSurface.
            mImageReader = null;
        }
        if (mImageWriter != null) {
            mImageWriter.close();
            mImageWriter = null;
        }
        if (mEncoderSurface != null) {
            mEncoderSurface.release();
            mEncoderSurface = null;
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

    private float mFrameRate;
    private final AtomicReference<Float> mNewFrameRate = new AtomicReference<>(null);
    private long mStartTimeNs = -1L;
    private long mNextTimeNs = 0L;
    private int mFrameCount = 0;

    public float getFrameRate() {
        return mFrameRate;
    }

    public void setFrameRate(float frameRate) {
        mNewFrameRate.set(frameRate);
    }

    private void checkFrameRateUpdate() {
        Float newFrameRate = mNewFrameRate.getAndSet(null);
        if (newFrameRate == null) {
            return;
        }
        if (mStartTimeNs >= 0L) {
            mStartTimeNs = mStartTimeNs + (long) (mFrameCount * (1_000_000_000L / mFrameRate));
            mFrameCount = 0;
        }
        Log.w(TAG, "mFrameRate changed from " + mFrameRate + " to " + newFrameRate);
        mFrameRate = newFrameRate;
    }

    private void sleepPull() throws InterruptedException {
        long nowNs = TimeUtils.getMonotonicNanoTime();
        long delayNs = mNextTimeNs - nowNs;

        if (delayNs > 0L) {
            TimeUnit.NANOSECONDS.sleep(delayNs);
        }
    }

    private void onImageReleased(ImageWriter writer) {
        mAvailableWriterImageCount.release();
    }

    private void onImageAvailable(ImageReader reader) {
        Image newImage = mImageReader.acquireNextImage();
        if (newImage == null) {
            Log.w(TAG, "Why acruire next reader image returned null ???");
        } else {
            Image oldImage = mReaderImageQueue.offer(newImage);
            if (oldImage != null) {
                oldImage.close();
                //Log.w(TAG, "drop one frame!");
            }
            mTimestampMap.put(newImage, TimeUtils.getMonotonicMilliTime());
        }
    }

    private final SyncQueue<Image> mWriterImageQueue = new SyncQueue<>(2);
    private final SyncQueue<Image> mReaderImageQueue = new SyncQueue<>(1);
    private Image mRepeatReaderImage;

    final Map<Image, Long> mTimestampMap = new ArrayMap<>();
    final Map<Image, Image> mWriterToReaderMap = new ArrayMap<>();
    final List<Image> mWriterList = new ArrayList<>();

    @Override
    public int doPull() {
        try {
            sleepPull();

            mAvailableWriterImageCount.acquire();
            Image writerImage = mImageWriter.dequeueInputImage();

            if (mRepeatReaderImage == null) {
                mRepeatReaderImage = mReaderImageQueue.take();
            } else {
                Image newReaderImage = mReaderImageQueue.poll();
                if (newReaderImage != null) {
                    mRepeatReaderImage.close();
                    mRepeatReaderImage = newReaderImage;
                }
            }

            // FIXME: This copy operation will cost 5ms ~ 50ms time!!!
            ImageUtils.imageCopy(mRepeatReaderImage, writerImage);

            checkFrameRateUpdate();
            if (mStartTimeNs < 0L) {
                mStartTimeNs = TimeUtils.getMonotonicNanoTime();
                mNextTimeNs = mStartTimeNs;
            } else {
                mNextTimeNs = mStartTimeNs + (long) (mFrameCount * (1_000_000_000L / mFrameRate));
            }
            mFrameCount++;

            // Otherwise: GraphicBufferSource: Dropping frame that's going backward in time
            writerImage.setTimestamp(mNextTimeNs);

            Image oldWriterImage = mWriterImageQueue.offer(writerImage);
            if (oldWriterImage != null) {
                oldWriterImage.close();
                Log.w(TAG, "drop one image!");
            }

            mWriterToReaderMap.put(writerImage, mRepeatReaderImage);

            return OK;
        } catch (InterruptedException e) {
            return -EINTR;
        }
    }

    @Override
    public int doEncode() {
        Image writerImage = null;
        while ((writerImage = mWriterImageQueue.poll()) != null) {
            mImageWriter.queueInputImage(writerImage);
            mWriterList.add(writerImage);
            mTimestampMap.put(writerImage, TimeUtils.getMonotonicMilliTime());
        }
        return (writerImage != null) ? OK : -ENODATA;
    }

    @Override
    public void setParameters(Bundle params) {
        Log.d(TAG, "setParameters " + params);
        mEncoder.setParameters(params);
    }

    public void setVideoBitrate(int bitRate) {
        if (DEBUG) {
            Log.d(TAG, "setVideoBitrate " + bitRate);
        }
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
        mEncoder.setParameters(params);
    }

    public void requestIDRFrame() {
        Log.d(TAG, "requesting IDR frame");
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        mEncoder.setParameters(params);
    }

    public void suspend(boolean suspend) {
        Log.d(TAG, "suspend " + suspend);
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_SUSPEND, suspend ? 1 : 0);
        mEncoder.setParameters(params);
    }

    private static class SyncQueue<T> {
        private final int mCapacity;
        private final Deque<T> mDeque;

        private SyncQueue(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity " + capacity + " is not positive!");
            }
            mCapacity = capacity;
            mDeque = new LinkedList<>();
        }

        /**
         * @return the first element if it is full
         */
        public T offer(T e) {
            synchronized (mDeque) {
                T first = null;
                if (isFull()) {
                    first = mDeque.removeFirst();
                }
                mDeque.addLast(e);
                mDeque.notifyAll();
                return first;
            }
        }

        public T poll() {
            synchronized (mDeque) {
                return mDeque.pollFirst();
            }
        }

        public T take() throws InterruptedException {
            synchronized (mDeque) {
                while (isEmpty()) {
                    mDeque.wait();
                }
                return mDeque.removeFirst();
            }
        }

        public boolean isEmpty() {
            synchronized (mDeque) {
                return mDeque.size() == 0;
            }
        }

        public boolean isFull() {
            synchronized (mDeque) {
                return mDeque.size() == mCapacity;
            }
        }
    }
}
