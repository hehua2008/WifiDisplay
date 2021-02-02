package com.hym.rtplib.foundation;

import android.os.Handler;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Log;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AMessage {
    private static final String TAG = AMessage.class.getSimpleName();

    private static final boolean DEBUG = false;

    public static final int WHAT_AMESSAGE = 'A' << 24 | 'M' << 16 | 'S' << 8 | 'G';

    private static final int POOL_SIZE = 64;

    private static final AtomicReference<AMessage> POOL = new AtomicReference<>(null);

    public static AMessage obtain() {
        while (true) {
            AMessage cur = POOL.get();
            if (cur == null) {
                break;
            }
            if (POOL.compareAndSet(cur, cur.mNext)) {
                cur.mNext = null; // after get
                cur.acquire();
                return cur;
            }
        }
        return new AMessage();
    }

    public static AMessage obtain(int what, Handler handler) {
        AMessage newAMsg = obtain();
        newAMsg.setWhat(what);
        newAMsg.setTarget(handler);
        return newAMsg;
    }

    private final AtomicBoolean mInUse = new AtomicBoolean(false);
    private final AtomicBoolean mWaitingForResponse = new AtomicBoolean(false);

    private final ArrayMap<String, Object> mMap = new ArrayMap<>();

    private final BlockingQueue<AMessage> mResponseQueue = new ArrayBlockingQueue<>(1);

    private int mAWhat = Integer.MIN_VALUE;
    private Message mMsg = null;

    private AMessage mNext = null;

    private AMessage() {
        acquire();
    }

    private boolean acquire() {
        if (DEBUG) {
            Log.v(TAG, this + " acquire", new Exception());
        }
        return mInUse.compareAndSet(false, true);
    }

    public void recycle() {
        if (mWaitingForResponse.get()) {
            Log.w(TAG, this + " is waiting for a response");
            return;
        }
        if (!mInUse.compareAndSet(true, false)) {
            Log.w(TAG, this + " is not in use");
            return;
        }
        if (DEBUG) {
            Log.v(TAG, this + " recycle", new Exception());
        }
        if (!mResponseQueue.isEmpty()) {
            Log.w(TAG, this + " mResponseQueue is not empty!");
        }
        mResponseQueue.clear();
        mMap.clear();
        mAWhat = Integer.MIN_VALUE;
        mMsg = null;
        while (true) {
            AMessage cur = POOL.get();
            this.mNext = cur; // before set
            if (POOL.compareAndSet(cur, this)) {
                break;
            }
        }
    }

    public void setBoolean(String key, boolean value) {
        mMap.put(key, value);
    }

    public void setInt(String key, int value) {
        mMap.put(key, value);
    }

    public void setLong(String key, long value) {
        mMap.put(key, value);
    }

    public void set(String key, Object value) {
        mMap.put(key, value);
    }

    public void setAll(Map<String, Object> map) {
        mMap.putAll(map);
    }

    public boolean getBoolean(String key, boolean def) {
        return get(key, def);
    }

    public boolean getBoolean(String key) throws NoSuchElementException {
        return getThrow(key);
    }

    public int getInt(String key, int def) {
        return get(key, def);
    }

    public int getInt(String key) throws NoSuchElementException {
        return getThrow(key);
    }

    public long getLong(String key, long def) {
        return get(key, def);
    }

    public long getLong(String key) throws NoSuchElementException {
        return getThrow(key);
    }

    public <T> T getNoThrow(String key) {
        return (T) mMap.get(key);
    }

    public <T> T get(String key, T def) {
        T value = getNoThrow(key);
        if (value != null) {
            return value;
        }
        return def;
    }

    public <T> T getThrow(String key) throws NoSuchElementException {
        T value = getNoThrow(key);
        if (value != null) {
            return value;
        }
        throw new NoSuchElementException(key);
    }

    private Message getMessage() {
        if (mMsg == null) {
            mMsg = Message.obtain();
            mMsg.what = WHAT_AMESSAGE;
            mMsg.obj = this;
        }
        return mMsg;
    }

    public void setWhat(int what) {
        mAWhat = what;
    }

    public int getWhat() {
        return mAWhat;
    }

    public void setTarget(Handler handler) {
        getMessage().setTarget(handler);
    }

    public void post() {
        post(0);
    }

    public void post(long delayMillis) {
        Message msg = getMessage();
        Handler handler = msg.getTarget();
        if (handler == null) {
            throw new IllegalStateException(this + " handler has not been set!");
        }
        handler.sendMessageDelayed(msg, delayMillis);
    }

    public AMessage postAndAwaitResponse() throws InterruptedException {
        if (DEBUG) {
            Log.v(TAG, this + " postAndAwaitResponse", new Exception());
        }
        if (mWaitingForResponse.getAndSet(true)) {
            throw new RuntimeException(this + " is already waiting for a response");
        }
        Message msg = getMessage();
        Handler handler = msg.getTarget();
        if (handler == null) {
            throw new IllegalStateException(this + " handler has not been set!");
        } else if (Thread.currentThread() == handler.getLooper().getThread()) {
            throw new IllegalStateException(this
                    + " postAndAwaitResponse caller and handler looper are in the same thread!");
        }
        msg.sendToTarget();
        AMessage response = mResponseQueue.take();
        mWaitingForResponse.getAndSet(false);
        recycle();
        return response;
    }

    public boolean postResponse(AMessage response) {
        if (DEBUG) {
            Log.v(TAG, this + " postResponse(" + response + ')', new Exception());
        }
        boolean ret = mResponseQueue.offer(response);
        if (!ret) {
            Log.e(TAG, this + " postResponse(" + response + ") failed");
        }
        return ret;
    }

    public AMessage dup() {
        AMessage dupMsg = AMessage.obtain();
        dupMsg.mMap.putAll(mMap);
        dupMsg.mAWhat = mAWhat;
        if (mMsg != null) {
            dupMsg.getMessage().setTarget(mMsg.getTarget());
        }
        return dupMsg;
    }
}
