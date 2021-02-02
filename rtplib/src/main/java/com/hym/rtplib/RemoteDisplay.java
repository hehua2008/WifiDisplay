package com.hym.rtplib;

import android.media.projection.MediaProjection;
import android.os.HandlerThread;
import android.util.DisplayMetrics;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.net.ANetworkSession;

public class RemoteDisplay implements Errno {
    private final HandlerThread mHandlerThread = new HandlerThread("WFD_THREAD");
    private final ANetworkSession mNetSession;
    private final WifiDisplaySource mSource;

    public RemoteDisplay(MediaProjection mediaProjection, DisplayMetrics displayMetrics,
            String iface) {
        mNetSession = new ANetworkSession();
        mHandlerThread.start();
        mSource = new WifiDisplaySource(mHandlerThread.getLooper(), mNetSession, mediaProjection,
                displayMetrics, null);
        mNetSession.start();
        mSource.start(iface);
    }

    public int pause() {
        return mSource.pause();
    }

    public int resume() {
        return mSource.resume();
    }

    public int dispose() {
        mSource.stop();
        mHandlerThread.quit();
        mNetSession.stop();

        return OK;
    }
}
