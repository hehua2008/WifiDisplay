package com.hym.rtplib.foundation;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

public class AHandler extends Handler {
    public AHandler() {
        super();
    }

    public AHandler(Looper looper) {
        super(looper);
    }

    @Override
    public final void dispatchMessage(Message msg) {
        if (msg.what == AMessage.WHAT_AMESSAGE && msg.obj instanceof AMessage) {
            onMessageReceived((AMessage) msg.obj);
            ((AMessage) msg.obj).recycle();
        } else {
            super.dispatchMessage(msg);
        }
    }

    protected void onMessageReceived(AMessage msg) {
        // int what = msg.getWhat();
    }
}
