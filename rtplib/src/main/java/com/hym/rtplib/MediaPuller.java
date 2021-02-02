package com.hym.rtplib;

import android.media.MediaFormat;
import android.os.Looper;
import android.util.Log;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.AHandler;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.util.CheckUtils;

public class MediaPuller extends AHandler implements MediaConstants, Errno {
    private static final String TAG = MediaPuller.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int WHAT_PULL_SUCCESS = 1;

    private static final int WHAT_START = 0;
    private static final int WHAT_STOP = 1;
    private static final int WHAT_PULL = 2;
    private static final int WHAT_PAUSE = 3;
    private static final int WHAT_RESUME = 4;

    private final MediaEncoder mSource;
    private final Looper mLooper;
    private final AMessage mNotify;
    private int mPullGeneration;
    private boolean mIsAudio;
    private boolean mPaused;

    public MediaPuller(MediaEncoder source, Looper looper, AMessage notify) {
        super(looper);
        mSource = source;
        mLooper = looper;
        mNotify = notify;
        mPullGeneration = 0;
        mIsAudio = false;
        mPaused = false;

        MediaFormat format = source.getOutputFormat();
        String mime = format.getString(MediaFormat.KEY_MIME);
        CheckUtils.check(mime != null);
        mIsAudio = mime.toLowerCase().startsWith("audio/");
    }

    public int start() {
        return postSynchronouslyAndReturnError(AMessage.obtain(WHAT_START, this));
    }

    public void stopAsync(AMessage notify) {
        AMessage msg = AMessage.obtain(WHAT_STOP, this);
        msg.set("notify", notify);
        msg.post();
    }

    public void pause() {
        AMessage.obtain(WHAT_PAUSE, this).post();
    }

    public void resume() {
        AMessage.obtain(WHAT_RESUME, this).post();
    }

    protected void onMessageReceived(AMessage msg) {
        switch (msg.getWhat()) {
            case WHAT_START: {
                schedulePull();

                AMessage response = AMessage.obtain();
                response.setInt(ERR, OK);
                msg.postResponse(response);

                break;
            }

            case WHAT_STOP: {
                Log.d(TAG, this + " stopping");
                mSource.stop();
                Log.d(TAG, this + " stopped");
                ++mPullGeneration;

                AMessage notify = msg.getThrow("notify");
                notify.post();

                mLooper.quit();
                break;
            }

            case WHAT_PULL: {
                int generation = msg.getInt(GENERATION);

                if (generation != mPullGeneration) {
                    break;
                }

                int err = mSource.doPull();

                if (mPaused) {
                    schedulePull();
                    break;
                }

                if (err != OK) {
                    Log.e(TAG, "error " + err + " reading stream");
                } else {
                    AMessage notify = mNotify.dup();
                    notify.setInt(WHAT, WHAT_PULL_SUCCESS);
                    notify.post();

                    schedulePull();
                }
                break;
            }

            case WHAT_PAUSE: {
                mPaused = true;
                break;
            }

            case WHAT_RESUME: {
                mPaused = false;
                break;
            }

            default:
                throw new RuntimeException("TRESPASS");
        }
    }

    private int postSynchronouslyAndReturnError(AMessage msg) {
        int err;
        try {
            AMessage response = msg.postAndAwaitResponse();
            err = response.getInt(ERR, OK);
        } catch (InterruptedException e) {
            err = -EINTR;
        }

        return err;
    }

    private void schedulePull() {
        AMessage msg = AMessage.obtain(WHAT_PULL, this);
        msg.setInt(GENERATION, mPullGeneration);
        msg.post();
    }
}
