package com.hym.wifidisplay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

public class WifiDisplaySinkActivity extends Activity implements WfdConstants,
        SurfaceHolder.Callback {
    private static final String TAG = WifiDisplaySinkActivity.class.getSimpleName();

    private static final int SYSTEM_UI_IMMERSIVE_VISIBILITY =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private static final int SYSTEM_UI_FULLSCREEN_VISIBILITY =
            View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    private boolean mShowingNavBar;
    private SurfaceView mSurfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sink_activity);

        showNavBar(false);
        mSurfaceView = findViewById(R.id.surface_view);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setKeepScreenOn(true);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        Intent intent = getIntent();
        String host = intent.getStringExtra(SOURCE_HOST);
        if (TextUtils.isEmpty(host)) {
            Log.w(TAG, "host is null or empty");
            finish();
            return;
        }
        int port = intent.getIntExtra(SOURCE_PORT, -1);
        if (port == -1) {
            Log.w(TAG, "port is -1");
            finish();
            return;
        }
        Log.d(TAG, "souce " + host + ':' + port);
        WifiDisplaySink.setServer(host, port);
    }

    private void showNavBar(boolean show) {
        mShowingNavBar = show;
        if (show) {
            getWindow().getDecorView().setSystemUiVisibility(SYSTEM_UI_IMMERSIVE_VISIBILITY);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(SYSTEM_UI_FULLSCREEN_VISIBILITY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (MotionEvent.ACTION_DOWN == event.getAction()) {
            showNavBar(!mShowingNavBar);
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");
        startSink(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged format=" + format + " width=" + width + " height=" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");
        stopSink();
    }

    private void startSink(Surface surface) {
        Log.d(TAG, "startSink");
        WifiDisplaySink.setSurface(surface);
        WifiDisplaySink.setVideoConfig(mSurfaceView.getWidth(), mSurfaceView.getHeight(), 30, 1, 1);
        new Thread("WifiDisplaySinkThread") {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                Log.d(TAG, getName() + " start");
                WifiDisplaySink.start();
                Log.d(TAG, getName() + " stop");
            }
        }.start();
    }

    private void stopSink() {
        Log.d(TAG, "stopSink");
        WifiDisplaySink.stop();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop");
        stopSink();
    }
}
