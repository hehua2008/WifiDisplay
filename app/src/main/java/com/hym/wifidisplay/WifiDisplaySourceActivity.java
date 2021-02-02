package com.hym.wifidisplay;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import com.hym.rtplib.RemoteDisplay;

public class WifiDisplaySourceActivity extends Activity {
    private MediaProjection mMediaProjection;
    private DisplayMetrics mDisplayMetrics;
    private String mIFace;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_display_source);

        Intent intent = getIntent();
        Intent projData = intent.getParcelableExtra(WfdConstants.PROJECTION_DATA);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        mMediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, projData);
        String host = intent.getStringExtra(WfdConstants.SOURCE_HOST);
        int port = intent.getIntExtra(WfdConstants.SOURCE_PORT, -1);
        mIFace = host + ':' + port;
        mDisplayMetrics = new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(mDisplayMetrics);
    }

    public void onClick(View v) {
        new RemoteDisplay(mMediaProjection, mDisplayMetrics, mIFace);
    }
}