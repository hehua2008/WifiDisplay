package com.hym.rtplib;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;

import com.hym.rtplib.constant.Errno;

import java.io.IOException;

interface MediaEncoder extends Errno {
    public interface OutputCallback {
        /**
         * Called when an output buffer becomes available.
         *
         * @param codec The MediaCodec object.
         * @param index The index of the available output buffer.
         * @param info  Info regarding the available output buffer {@link MediaCodec.BufferInfo}.
         */
        public abstract void onOutputBufferAvailable(
                MediaCodec codec, int index, MediaCodec.BufferInfo info);

        /**
         * Called when the output format has changed
         *
         * @param codec  The MediaCodec object.
         * @param format The new output format.
         */
        public abstract void onOutputFormatChanged(MediaCodec codec, MediaFormat format);
    }

    public abstract void setCallback(OutputCallback callback, Handler handler);

    public abstract void configure(String mimeType, MediaFormat format) throws IOException;

    public abstract MediaFormat getOutputFormat();

    public abstract void setParameters(Bundle params);

    public abstract boolean start();

    public abstract void stop();

    public abstract void release();

    public abstract int doPull();

    public abstract int doEncode();
}
