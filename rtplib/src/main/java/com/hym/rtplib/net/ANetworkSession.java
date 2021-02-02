package com.hym.rtplib.net;

import android.os.Process;
import android.util.Log;
import android.util.SparseArray;

import com.hym.rtplib.constant.Errno;
import com.hym.rtplib.constant.MediaConstants;
import com.hym.rtplib.foundation.ABuffer;
import com.hym.rtplib.foundation.AMessage;
import com.hym.rtplib.foundation.BytesHolder;
import com.hym.rtplib.util.CheckUtils;
import com.hym.rtplib.util.HexDump;
import com.hym.rtplib.util.RTPUtils;
import com.hym.rtplib.util.StringUtils;
import com.hym.rtplib.util.TimeUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

// Helper class to manage a number of live sockets (datagram and stream-based)
// on a single thread. Clients are notified about activity through AMessages.
public class ANetworkSession implements MediaConstants, Errno {
    private static final String TAG = ANetworkSession.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int MAX_UDP_SIZE = 1500;
    public static final int MAX_UDP_RETRIES = 200;

    private final Object mLock = new Object();
    private final SparseArray<Session<? extends SelectableChannel>> mSessions = new SparseArray<>();

    private Thread mThread;

    private int mNextSessionID;

    private enum Mode {
        MODE_CREATE_UDP_SESSION,
        MODE_CREATE_TCP_DATAGRAM_SESSION_PASSIVE,
        MODE_CREATE_TCP_DATAGRAM_SESSION_ACTIVE,
        MODE_CREATE_RTSP_SERVER,
        MODE_CREATE_RTSP_CLIENT,
    }

    private int createClientOrServer(Mode mode, InetAddress localAddr, int port,
            String remoteHost, int remotePort, AMessage notify) throws IOException {
        Log.d(TAG, "createClientOrServer mode=" + mode + " localAddr=" + localAddr
                + " port=" + port + " remoteHost=" + remoteHost + " remotePort=" + remotePort);
        synchronized (mLock) {
            final boolean isUDP = (mode == Mode.MODE_CREATE_UDP_SESSION);

            final boolean isServer = (mode == Mode.MODE_CREATE_RTSP_SERVER
                    || mode == Mode.MODE_CREATE_TCP_DATAGRAM_SESSION_PASSIVE);

            final boolean isClient = (mode == Mode.MODE_CREATE_RTSP_CLIENT
                    || mode == Mode.MODE_CREATE_TCP_DATAGRAM_SESSION_ACTIVE);

            SelectableChannel selectableChannel = null;
            DatagramChannel dgChannel = null;
            ServerSocketChannel serverSocketChannel = null;
            SocketChannel clientSocketChannel = null;

            if (isUDP) {
                selectableChannel = dgChannel = DatagramChannel.open(StandardProtocolFamily.INET);
            } else if (isServer) {
                selectableChannel = serverSocketChannel = ServerSocketChannel.open();
            } else if (isClient) {
                selectableChannel = clientSocketChannel = SocketChannel.open();
            }

            if (isServer) {
                serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            }

            if (isUDP) {
                int size = 256 * 1024;
                dgChannel.setOption(StandardSocketOptions.SO_RCVBUF, size);
                dgChannel.setOption(StandardSocketOptions.SO_SNDBUF, size);
            } else if (mode == Mode.MODE_CREATE_TCP_DATAGRAM_SESSION_ACTIVE) {
                clientSocketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                int tos = 224;  // VOICE
                clientSocketChannel.setOption(StandardSocketOptions.IP_TOS, tos);
            }

            makeSocketNonBlocking(selectableChannel);

            SocketAddress localSocketAddr = new InetSocketAddress((localAddr != null) ?
                    localAddr : RTPUtils.INET_ANY, port);

            if (isClient) {
                SocketAddress remoteSocketAddr = new InetSocketAddress(remoteHost, remotePort);
                Log.d(TAG, String.format("connecting socket %s to %s",
                        clientSocketChannel, remoteSocketAddr));
                clientSocketChannel.connect(remoteSocketAddr);
            } else if (isServer) {
                serverSocketChannel.bind(localSocketAddr, 4);
            } else if (isUDP) {
                dgChannel.bind(localSocketAddr);

                if (remoteHost != null) {
                    SocketAddress remoteSocketAddr = new InetSocketAddress(remoteHost, remotePort);
                    dgChannel.connect(remoteSocketAddr);
                }
            }

            Session.State state;
            switch (mode) {
                case MODE_CREATE_RTSP_CLIENT:
                    state = Session.State.CONNECTING;
                    break;

                case MODE_CREATE_TCP_DATAGRAM_SESSION_ACTIVE:
                    //noinspection DuplicateBranchesInSwitch
                    state = Session.State.CONNECTING;
                    break;

                case MODE_CREATE_TCP_DATAGRAM_SESSION_PASSIVE:
                    state = Session.State.LISTENING_TCP_DGRAMS;
                    break;

                case MODE_CREATE_RTSP_SERVER:
                    state = Session.State.LISTENING_RTSP;
                    break;

                default:
                    CheckUtils.checkEqual(mode, Mode.MODE_CREATE_UDP_SESSION);
                    state = Session.State.DATAGRAM;
                    break;
            }

            Session session = new Session(mNextSessionID++, state, selectableChannel, notify);

            if (mode == Mode.MODE_CREATE_TCP_DATAGRAM_SESSION_ACTIVE) {
                session.setMode(Session.Mode.MODE_DATAGRAM);
            } else if (mode == Mode.MODE_CREATE_RTSP_CLIENT) {
                session.setMode(Session.Mode.MODE_RTSP);
            }

            addSession(session);

            int sessionID = session.getSessionID();
            return sessionID;
        }
    }

    private final Selector mSelector;

    {
        try {
            mSelector = Selector.open();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Can't use Thread.interrupt instead, since socket will be interrupted too !
     */
    private void wakeUp() {
        mSelector.wakeup();
    }

    public int addSession(Session session) {
        int sessionID = session.getSessionID();
        Log.d(TAG, "addSession[" + sessionID + ']');

        synchronized (mLock) {
            mSessions.put(sessionID, session);
        }

        wakeUp();

        return OK;
    }

    /**
     * This method must be run on the same thread as mSelector.select() !
     */
    private void updateSelectionKeys() {
        synchronized (mLock) {
            for (int i = 0; i < mSessions.size(); ++i) {
                Session session = mSessions.valueAt(i);
                SelectableChannel channel = session.getSelectableChannel();

                if (channel == null || !channel.isOpen()) {
                    continue;
                }

                try {
                    int ops = 0;

                    if (session.wantsToRead()) {
                        ops |= (channel instanceof ServerSocketChannel)
                                ? SelectionKey.OP_ACCEPT : SelectionKey.OP_READ;
                    }

                    if (session.wantsToWrite()) {
                        ops |= SelectionKey.OP_WRITE;
                    }

                    channel.register(mSelector, ops, session);
                } catch (IOException e) {
                    Log.e(TAG, "session[" + session.getSessionID() + "] register " + channel
                            + " failed!", e);
                }
            }
        }
    }

    private void threadLoop() {
        while (!mStopped) {
            updateSelectionKeys();

            final Set<SelectionKey> selectedKeys;

            try {
                mSelector.select();
                selectedKeys = mSelector.selectedKeys();
                if (selectedKeys.isEmpty()) {
                    continue;
                }
            } catch (IOException e) {
                Log.w(TAG, "select failed", e);
                continue;
            }

            synchronized (mLock) {
                List<Session> sessionsToAdd = new LinkedList<>();

                for (SelectionKey selectedKey : selectedKeys) {
                    Session session = (Session) selectedKey.attachment();
                    //SelectableChannel channel = session.getSelectableChannel();
                    SelectableChannel channel = selectedKey.channel();

                    if (selectedKey.isAcceptable()) {
                        CheckUtils.check(session.isRTSPServer() || session.isTCPDatagramServer());
                        ServerSocketChannel serverChannel = (ServerSocketChannel) channel;

                        try {
                            SocketChannel clientChannel = serverChannel.accept();
                            Log.w(TAG, serverChannel + " accept");
                            makeSocketNonBlocking(clientChannel);

                            Log.d(TAG, String.format("incoming connection from %s (socket %s)",
                                    clientChannel.getRemoteAddress(), clientChannel));

                            Session clientSession =
                                    new Session(mNextSessionID++, Session.State.CONNECTED,
                                            clientChannel, session.getNotificationMessage());

                            clientSession.setMode(session.isRTSPServer()
                                    ? Session.Mode.MODE_RTSP : Session.Mode.MODE_DATAGRAM);

                            sessionsToAdd.add(clientSession);
                        } catch (IOException e) {
                            Log.e(TAG, serverChannel + " accept failed", e);
                        }
                    }

                    if (selectedKey.isReadable()) {
                        int err = session.readMore();
                        if (err != OK) {
                            Log.e(TAG, "readMore on socket " + channel + " error:" + err);
                        }
                    }

                    if (selectedKey.isWritable()) {
                        int err = session.writeMore();
                        if (err != OK) {
                            Log.e(TAG, "writeMore on socket " + channel + " error:" + err);
                        }
                    }
                }

                for (Session session : sessionsToAdd) {
                    addSession(session);
                    Log.d(TAG, "added clientSession " + session.getSessionID());
                }
            }

            // This is important ! Otherwise mSelector.select() (return 0) will loop indefinitely !
            selectedKeys.clear();
        }

        try {
            mSelector.close();
        } catch (IOException e) {
            // ignore
        }
    }

    private static void makeSocketNonBlocking(SelectableChannel channel) throws IOException {
        channel.configureBlocking(false);
    }

    public static class NetworkThread extends Thread {
        private final ANetworkSession mSession;

        public NetworkThread(String name, ANetworkSession session) {
            super(name);
            mSession = session;
        }

        @SuppressWarnings("MethodDoesntCallSuperMethod")
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            mSession.threadLoop();
        }
    }

    public static class Session<T extends SelectableChannel> {
        public enum Mode {
            MODE_RTSP,
            MODE_DATAGRAM,
            MODE_WEBSOCKET,
        }

        public enum State {
            CONNECTING,
            CONNECTED,
            LISTENING_RTSP,
            LISTENING_TCP_DGRAMS,
            DATAGRAM,
        }

        private static final int FRAGMENT_FLAG_TIME_VALID = 1;

        private static class Fragment {
            int mFlags;
            long mTimeUs;
            ABuffer mBuffer;
        }

        private final int mSessionID;
        private State mState;
        private Session.Mode mMode;
        private final T mSelectableChannel;
        private final AMessage mNotify;
        private boolean mSawReceiveFailure, mSawSendFailure;
        private int mUDPRetries;

        private final List<Fragment> mOutFragments = new LinkedList<>();
        private final BytesHolder mInBuffer = new BytesHolder(512);

        private final long mLastStallReportUs;

        public Session(int sessionID, State state, T selectableChannel, AMessage notify) {
            mSessionID = sessionID;
            mState = state;
            mMode = Mode.MODE_DATAGRAM;
            mSelectableChannel = selectableChannel;
            mNotify = notify;
            mSawReceiveFailure = false;
            mSawSendFailure = false;
            mUDPRetries = MAX_UDP_RETRIES;
            mLastStallReportUs = -1L;

            if (mState == State.CONNECTED) {
                InetSocketAddress localSocketAddr = null;
                InetSocketAddress remoteSocketAddr = null;
                try {
                    if (mSelectableChannel instanceof DatagramChannel) {
                        DatagramChannel channel = (DatagramChannel) mSelectableChannel;
                        localSocketAddr = (InetSocketAddress) channel.getLocalAddress();
                        remoteSocketAddr = (InetSocketAddress) channel.getRemoteAddress();
                    } else if (mSelectableChannel instanceof ServerSocketChannel) {
                        ServerSocketChannel channel = (ServerSocketChannel) mSelectableChannel;
                        localSocketAddr = (InetSocketAddress) channel.getLocalAddress();
                    } else if (mSelectableChannel instanceof SocketChannel) {
                        SocketChannel channel = (SocketChannel) mSelectableChannel;
                        localSocketAddr = (InetSocketAddress) channel.getLocalAddress();
                        remoteSocketAddr = (InetSocketAddress) channel.getRemoteAddress();
                    }
                } catch (IOException e) {
                    Log.e(TAG, mSelectableChannel + " getLocal/RemoteAddress exception", e);
                }

                String localAddr = null;
                int localPort = Integer.MIN_VALUE;
                if (localSocketAddr != null) {
                    localAddr = localSocketAddr.getHostString();
                    localPort = localSocketAddr.getPort();
                }

                String remoteAddr = null;
                int remotePort = Integer.MIN_VALUE;
                if (remoteSocketAddr != null) {
                    remoteAddr = remoteSocketAddr.getHostString();
                    remotePort = remoteSocketAddr.getPort();
                }

                AMessage msg = mNotify.dup();
                msg.setInt(SESSION_ID, mSessionID);
                msg.setInt(REASON, WHAT_CLIENT_CONNECTED);
                msg.set(SERVER_IP, localAddr);
                msg.setInt(SERVER_PORT, localPort);
                msg.set(CLIENT_IP, remoteAddr);
                msg.setInt(CLIENT_PORT, remotePort);
                msg.post();
            }
        }

        public int getSessionID() {
            return mSessionID;
        }

        public T getSelectableChannel() {
            return mSelectableChannel;
        }

        public AMessage getNotificationMessage() {
            return mNotify;
        }

        public boolean isRTSPServer() {
            return mState == State.LISTENING_RTSP;
        }

        public boolean isTCPDatagramServer() {
            return mState == State.LISTENING_TCP_DGRAMS;
        }

        public boolean wantsToRead() {
            return !mSawReceiveFailure && mState != State.CONNECTING;
        }

        public boolean wantsToWrite() {
            return !mSawSendFailure
                    && (mState == State.CONNECTING
                    || (mState == State.CONNECTED && !mOutFragments.isEmpty())
                    || (mState == State.DATAGRAM && !mOutFragments.isEmpty()));
        }

        public int readMore() {
            if (mState == State.DATAGRAM) {
                CheckUtils.checkEqual(mMode, Mode.MODE_DATAGRAM);
                DatagramChannel channel = (DatagramChannel) mSelectableChannel;

                int err;
                do {
                    try {
                        ABuffer buf = new ABuffer(MAX_UDP_SIZE);
                        ByteBuffer bufData = buf.data();
                        int n = bufData.position();
                        InetSocketAddress remoteAddr = (InetSocketAddress) channel.receive(bufData);
                        n = (bufData.position() - n);

                        if (remoteAddr == null) {
                            err = -EAGAIN;
                        } else if (n == 0) {
                            // FIXME ?
                            err = -EAGAIN;
                        } else {
                            err = OK;
                            buf.setRange(0, n);

                            long nowUs = TimeUtils.getMonotonicMicroTime();
                            buf.meta().setLong(ARRIVAL_TIME_US, nowUs);

                            AMessage notify = mNotify.dup();
                            notify.setInt(SESSION_ID, mSessionID);
                            notify.setInt(REASON, WHAT_DATAGRAM);
                            notify.set(FROM_ADDR, remoteAddr.getAddress().getHostAddress());
                            notify.setInt(FROM_PORT, remoteAddr.getPort());
                            notify.set(DATA, buf);
                            notify.post();
                        }
                    } catch (IOException e) {
                        Log.w(TAG, channel + " receive failed", e);
                        err = -EIO;
                    }
                } while (err == OK);

                if (err == -EAGAIN) {
                    err = OK;
                }

                if (err != OK) {
                    if (mUDPRetries == 0) {
                        notifyError(false /* send */, err, "Recvfrom failed");
                        mSawReceiveFailure = true;
                    } else {
                        mUDPRetries--;
                        Log.e(TAG, String.format("Recvfrom failed, %d/%d retries left",
                                mUDPRetries, MAX_UDP_RETRIES));
                        err = OK;
                    }
                } else {
                    mUDPRetries = MAX_UDP_RETRIES;
                }

                return err;
            }

            SocketChannel channel = (SocketChannel) mSelectableChannel;

            mInBuffer.resizeTo(mInBuffer.getByteBuffer().position() + 512,
                    BytesHolder.Position.ZERO, BytesHolder.Position.POSITION);
            final ByteBuffer inBuf = mInBuffer.getByteBuffer();
            inBuf.limit(inBuf.position() + 512);
            int n;
            try {
                n = channel.read(mInBuffer.getByteBuffer());
            } catch (IOException e) {
                Log.w(TAG, channel + " read failed", e);
                n = -1;
            }

            int err = OK;

            if (n > 0) {
//#if 0
                if (DEBUG) {
                    int end = inBuf.position();
                    String dump = HexDump.dumpHexString(mInBuffer.getByteArray(), end - n, end);
                    Log.w(TAG, "in:\n" + dump);
                }
//#endif
            } else if (n < 0) {
                err = -EIO;
            } else { // n == 0
                // FIXME ?
                err = -EAGAIN;
            }

            inBuf.limit(inBuf.position()).rewind();
            ByteBuffer sliceBuf = inBuf.slice(); // Mustn't change its limit !!!

            if (mMode == Mode.MODE_DATAGRAM) {
                // TCP stream carrying 16-bit length-prefixed datagrams.

                while (sliceBuf.remaining() >= 2) {
                    int packetSize = RTPUtils.U16_AT(sliceBuf, 0);

                    if (sliceBuf.remaining() < packetSize + 2) {
                        break;
                    }

                    ABuffer packet = new ABuffer(packetSize);
                    ByteBuffer dupBuf = (ByteBuffer) sliceBuf.duplicate().
                            position(2).limit(2 + packetSize);
                    packet.data().put(dupBuf);

                    long nowUs = TimeUtils.getMonotonicMicroTime();
                    packet.meta().setLong(ARRIVAL_TIME_US, nowUs);

                    AMessage notify = mNotify.dup();
                    notify.setInt(SESSION_ID, mSessionID);
                    notify.setInt(REASON, WHAT_DATAGRAM);
                    notify.set(DATA, packet);
                    notify.post();

                    sliceBuf = ((ByteBuffer) sliceBuf.position(packetSize + 2)).slice();
                }
            } else if (mMode == Mode.MODE_RTSP) {
                while (true) {
                    int length;

                    if (sliceBuf.remaining() > 0 && sliceBuf.get(0) == '$') {
                        if (sliceBuf.remaining() < 4) {
                            break;
                        }

                        length = RTPUtils.U16_AT(sliceBuf, 2);

                        if (sliceBuf.remaining() < 4 + length) {
                            break;
                        }

                        AMessage notify = mNotify.dup();
                        notify.setInt(SESSION_ID, mSessionID);
                        notify.setInt(REASON, WHAT_BINARY_DATA);
                        notify.setInt(CHANNEL, sliceBuf.get(1) & 0xFF);

                        ABuffer data = new ABuffer(length);
                        ByteBuffer dupBuf = (ByteBuffer) sliceBuf.duplicate()
                                .position(4).limit(4 + length);
                        data.data().put(dupBuf);

                        long nowUs = TimeUtils.getMonotonicMicroTime();
                        data.meta().setLong(ARRIVAL_TIME_US, nowUs);

                        notify.set(DATA, data);
                        notify.post();

                        sliceBuf = ((ByteBuffer) sliceBuf.position(4 + length)).slice();
                        continue;
                    }

                    String str = StringUtils.newStringFromBytes(sliceBuf.array(),
                            sliceBuf.position() + sliceBuf.arrayOffset(), sliceBuf.remaining());

                    ParsedMessage msg = ParsedMessage.parse(str, str.length(), err != OK);

                    if (msg == null) {
                        break;
                    }
                    length = msg.getLength();

                    AMessage notify = mNotify.dup();
                    notify.setInt(SESSION_ID, mSessionID);
                    notify.setInt(REASON, WHAT_DATA);
                    notify.set(DATA, msg);
                    notify.post();

//#if 1
                    if (true) {
                        // XXX The (old) dongle sends the wrong content length header on a
                        // SET_PARAMETER request that signals a "wfd_idr_request".
                        // (17 instead of 19).
                        String content = msg.getContent();
                        if (content != null
                                && content.startsWith("wfd_idr_request\r\n")
                                && length >= 19
                                && str.charAt(length) == '\r'
                                && str.charAt(length + 1) == '\n') {
                            length += 2;
                        }
                    }
//#endif

                    sliceBuf = ((ByteBuffer) sliceBuf.position(length)).slice();

                    if (err != OK) {
                        break;
                    }
                }
            } else {
                CheckUtils.checkEqual(mMode, Mode.MODE_WEBSOCKET);

                // hexdump(data, mInBuffer.size());

                while (sliceBuf.remaining() >= 2) {
                    int offset = 2;

                    long payloadLen = sliceBuf.get(1) & 0x7f;
                    if (payloadLen == 126) {
                        if (offset + 2 > sliceBuf.remaining()) {
                            break;
                        }

                        payloadLen = RTPUtils.U16_AT(sliceBuf, offset);
                        offset += 2;
                    } else if (payloadLen == 127) {
                        if (offset + 8 > sliceBuf.remaining()) {
                            break;
                        }

                        payloadLen = RTPUtils.U64_AT(sliceBuf, offset);
                        offset += 8;
                    }

                    int mask = 0;
                    if ((sliceBuf.get(1) & 0x80) != 0) {
                        // MASK==1
                        if (offset + 4 > sliceBuf.remaining()) {
                            break;
                        }

                        mask = RTPUtils.U32_AT(sliceBuf, offset);
                        offset += 4;
                    }

                    if (payloadLen > sliceBuf.remaining()
                            || offset > sliceBuf.remaining() - payloadLen) {
                        break;
                    }

                    // We have the full message.

                    CheckUtils.checkLessThan(payloadLen, (long) Integer.MAX_VALUE);
                    ABuffer packet = new ABuffer((int) payloadLen);
                    ByteBuffer dupBuf = (ByteBuffer) sliceBuf.duplicate()
                            .position(offset).limit(offset + (int) payloadLen);
                    ByteBuffer packetData = packet.data();
                    packetData.put(dupBuf);

                    if (mask != 0) {
                        for (int i = 0; i < payloadLen; ++i) {
                            packetData.put(i,
                                    (byte) ((sliceBuf.get(offset + i) & 0xff)
                                            ^ ((mask >>> (8 * (3 - (i % 4)))) & 0xff)));
                        }
                    }

                    AMessage notify = mNotify.dup();
                    notify.setInt(SESSION_ID, mSessionID);
                    notify.setInt(REASON, WHAT_WEB_SOCKET_MESSAGE);
                    notify.set(DATA, packet);
                    notify.setInt(HEADER_BYTE, sliceBuf.get(0) & 0xFF);
                    notify.post();

                    sliceBuf = ((ByteBuffer) sliceBuf.position(offset + (int) payloadLen)).slice();
                }
            }

            if (err != OK) {
                notifyError(false /* send */, err, "Recv failed");
                mSawReceiveFailure = true;
            }

            return err;
        }

        public int writeMore() {
            if (mState == State.DATAGRAM) {
                CheckUtils.check(!mOutFragments.isEmpty());

                int err;
                do {
                    Fragment frag = mOutFragments.get(0);
                    ABuffer datagram = frag.mBuffer;
                    ByteBuffer dgData = datagram.data();
                    dgData.limit(datagram.size());
                    DatagramChannel channel = (DatagramChannel) mSelectableChannel;

                    int n;
                    try {
                        n = channel.send(dgData, channel.getRemoteAddress());
                    } catch (IOException e) {
                        Log.w(TAG, channel + " send failed", e);
                        n = -1;
                    }

                    err = OK;

                    if (n > 0) {
                        if ((frag.mFlags & FRAGMENT_FLAG_TIME_VALID) != 0) {
                            dumpFragmentStats(frag);
                        }

                        mOutFragments.remove(0);
                    } else if (n < 0) {
                        err = -EIO;
                    } else if (n == 0) {
                        // FIXME ?
                        err = -EAGAIN;
                    }
                } while (err == OK && !mOutFragments.isEmpty());

                if (err == -EAGAIN) {
                    if (!mOutFragments.isEmpty()) {
                        Log.d(TAG, mOutFragments.size() + " datagrams remain queued");
                    }
                    err = OK;
                }

                if (err != OK) {
                    if (mUDPRetries == 0) {
                        notifyError(true /* send */, err, "Send datagram failed");
                        mSawSendFailure = true;
                    } else {
                        mUDPRetries--;
                        Log.e(TAG, String.format("Send datagram failed, %d/%d retries left",
                                mUDPRetries, MAX_UDP_RETRIES));
                        err = OK;
                    }
                } else {
                    mUDPRetries = MAX_UDP_RETRIES;
                }

                return err;
            }

            SocketChannel channel = (SocketChannel) mSelectableChannel;

            if (mState == State.CONNECTING) {
                if (!channel.isConnected()) {
                    int err = -EIO;
                    notifyError(false, err, "Connection failed");
                    mSawSendFailure = true;

                    return err;
                }

                mState = State.CONNECTED;
                notify(WHAT_CONNECTED);

                return OK;
            }

            CheckUtils.checkEqual(mState, State.CONNECTED);
            CheckUtils.check(!mOutFragments.isEmpty());

            int n = -1;
            while (!mOutFragments.isEmpty()) {
                Fragment frag = mOutFragments.get(0);
                ByteBuffer fragData = frag.mBuffer.data();
                fragData.limit(frag.mBuffer.size());

                try {
                    n = channel.write(fragData);
                } catch (IOException e) {
                    Log.w(TAG, channel + " write failed", e);
                    n = -1;
                }

                if (n <= 0) {
                    break;
                }

                frag.mBuffer.setRange(
                        frag.mBuffer.offset() + n, frag.mBuffer.size() - n);

                if (frag.mBuffer.size() > 0) {
                    break;
                }

                if ((frag.mFlags & FRAGMENT_FLAG_TIME_VALID) != 0) {
                    dumpFragmentStats(frag);
                }

                mOutFragments.remove(0);
            }

            int err = OK;

            if (n < 0) {
                err = -EIO;
            } else if (n == 0) {
                // FIXME ?
                err = -EAGAIN;
            }

            if (err != OK) {
                notifyError(true /* send */, err, "Send failed");
                mSawSendFailure = true;
            }

/*#if 0
            int numBytesQueued;
            int res = ioctl(mSocket, SIOCOUTQ, & numBytesQueued);
            if (res == 0 && numBytesQueued > 50 * 1024) {
                if (numBytesQueued > 409600) {
                    Log.w(TAG, "!!! numBytesQueued = " + numBytesQueued);
                }

                long nowUs = TimeUtils.getMonotonicMicroTime();

                if (mLastStallReportUs < 0l l
                        || nowUs > mLastStallReportUs + 100000l){
                    AMessage msg = mNotify.dup();
                    msg.setInt(SESSION_ID, mSessionID);
                    msg.setInt(REASON, WHAT_NETWORK_STALL);
                    msg.set(NUM_BYTES_QUEUED, numBytesQueued);
                    msg.post();

                    mLastStallReportUs = nowUs;
                }
            }
#endif*/

            return err;
        }

        private final boolean mUseMask = false;  // Chromium doesn't like it.
        private final Random mRandom = new Random();

        public int sendRequest(ByteBuffer data, int size, boolean timeValid, long timeUs) {
            CheckUtils.check(mState == State.CONNECTED || mState == State.DATAGRAM);

            if (size < 0) {
                size = data.remaining();
            }

            if (size == 0) {
                return OK;
            }

            ABuffer buffer;
            ByteBuffer bufData;

            if (mState == State.CONNECTED && mMode == Mode.MODE_DATAGRAM) {
                CheckUtils.checkLessOrEqual(size, 65535);

                buffer = new ABuffer(size + 2, true);
                bufData = buffer.data();
                bufData.put((byte) (size >>> 8));
                bufData.put((byte) (size & 0xff));
                data.limit(data.position() + size);
                bufData.put(data);
            } else if (mState == State.CONNECTED && mMode == Mode.MODE_WEBSOCKET) {
                int numHeaderBytes = 2 + (mUseMask ? 4 : 0);
                if (size > 65535) {
                    numHeaderBytes += 8;
                } else if (size > 125) {
                    numHeaderBytes += 2;
                }

                buffer = new ABuffer(numHeaderBytes + size, true);
                bufData = buffer.data();
                bufData.put(0, (byte) 0x81);  // FIN==1 | opcode=1 (text)
                int byte1Base = mUseMask ? 0x80 : 0x00;

                if (size > 65535) {
                    bufData.put(1, (byte) (byte1Base | 127));
                    bufData.put(2, (byte) 0x00);
                    bufData.put(3, (byte) 0x00);
                    bufData.put(4, (byte) 0x00);
                    bufData.put(5, (byte) 0x00);
                    bufData.put(6, (byte) ((size >>> 24) & 0xff));
                    bufData.put(7, (byte) ((size >>> 16) & 0xff));
                    bufData.put(8, (byte) ((size >>> 8) & 0xff));
                    bufData.put(9, (byte) (size & 0xff));
                } else if (size > 125) {
                    bufData.put(1, (byte) (byte1Base | 126));
                    bufData.put(2, (byte) ((size >>> 8) & 0xff));
                    bufData.put(3, (byte) (size & 0xff));
                } else {
                    bufData.put(1, (byte) (byte1Base | size));
                }

                if (mUseMask) {
                    int mask = Math.abs(mRandom.nextInt());

                    bufData.put(numHeaderBytes - 4, (byte) ((mask >>> 24) & 0xff));
                    bufData.put(numHeaderBytes - 3, (byte) ((mask >>> 16) & 0xff));
                    bufData.put(numHeaderBytes - 2, (byte) ((mask >>> 8) & 0xff));
                    bufData.put(numHeaderBytes - 1, (byte) (mask & 0xff));

                    for (int i = 0; i < size; ++i) {
                        bufData.put(numHeaderBytes + i,
                                (byte) ((data.get(i) & 0xff)
                                        ^ ((mask >>> (8 * (3 - (i % 4)))) & 0xff)));
                    }
                } else {
                    bufData.position(numHeaderBytes);
                    data.limit(data.position() + size);
                    bufData.put(data);
                }
            } else {
                buffer = new ABuffer(size, true);
                data.limit(data.position() + size);
                buffer.data().put(data);
            }

            Fragment frag = new Fragment();

            frag.mFlags = 0;
            if (timeValid) {
                frag.mFlags = FRAGMENT_FLAG_TIME_VALID;
                frag.mTimeUs = timeUs;
            }

            frag.mBuffer = buffer;

            mOutFragments.add(frag);

            return OK;
        }

        public void setMode(Mode mode) {
            mMode = mode;
        }

        public int switchToWebSocketMode() {
            if (mState != State.CONNECTED || mMode != Mode.MODE_RTSP) {
                return INVALID_OPERATION;
            }

            mMode = Mode.MODE_WEBSOCKET;

            return OK;
        }

        private void notifyError(boolean send, int err, String detail) {
            AMessage msg = mNotify.dup();
            msg.setInt(SESSION_ID, mSessionID);
            msg.setInt(REASON, WHAT_ERROR);
            msg.setBoolean(SEND, send);
            msg.setInt(ERR, err);
            msg.set(DETAIL, detail);
            msg.post();
        }

        private void notify(int reason) {
            AMessage msg = mNotify.dup();
            msg.setInt(SESSION_ID, mSessionID);
            msg.setInt(REASON, reason);
            msg.post();
        }

        private static final long MIN_DELAY_MS = 0L;
        private static final long MAX_DELAY_MS = 300L;
        private static final String K_PATTERN = "########################################";

        private void dumpFragmentStats(Fragment frag) {
//#if 0
            if (DEBUG) {
                long nowUs = TimeUtils.getMonotonicMicroTime();
                long delayMs = (nowUs - frag.mTimeUs) / 1000L;
                Log.d(TAG, "nowUs       =" + nowUs);
                Log.d(TAG, "frag.mTimeUs=" + frag.mTimeUs);

                int patternSize = K_PATTERN.length();

                int n = (int) ((patternSize * (delayMs - MIN_DELAY_MS))
                        / (MAX_DELAY_MS - MIN_DELAY_MS));

                if (n < 0) {
                    n = 0;
                } else if (n > patternSize) {
                    n = patternSize;
                }

                Log.d(TAG, String.format("[%d]: (%4d ms) %s\n",
                        frag.mTimeUs / 1000,
                        delayMs,
                        K_PATTERN.substring(patternSize - n)));
            }
//#endif
        }
    }

    public ANetworkSession() {
        mNextSessionID = 1;
    }

    private volatile boolean mStopped = false;

    public int start() {
        if (mThread != null) {
            return INVALID_OPERATION;
        }

        mStopped = false;
        mThread = new NetworkThread("ANetworkSession", this);
        mThread.start();

        return OK;
    }

    public int stop() {
        if (mThread == null) {
            return INVALID_OPERATION;
        }

        mStopped = true;
        wakeUp();
        try {
            mThread.join();
        } catch (InterruptedException e) {
            Log.w(TAG, "wait " + mThread + " failed!", e);
        }
        mThread = null;

        return OK;
    }

    public int createRTSPClient(String host, int port, AMessage notify) throws IOException {
        return createClientOrServer(
                Mode.MODE_CREATE_RTSP_CLIENT,
                null /* addr */,
                0 /* port */,
                host,
                port,
                notify);
    }

    public int createRTSPServer(InetAddress localAddr, int port, AMessage notify)
            throws IOException {
        return createClientOrServer(
                Mode.MODE_CREATE_RTSP_SERVER,
                localAddr,
                port,
                null /* remoteHost */,
                0 /* remotePort */,
                notify);
    }

    public int createUDPSession(int localPort, AMessage notify) throws IOException {
        return createUDPSession(localPort, null, 0, notify);
    }

    public int createUDPSession(int localPort, String remoteHost, int remotePort, AMessage notify)
            throws IOException {
        return createClientOrServer(
                Mode.MODE_CREATE_UDP_SESSION,
                null /* addr */,
                localPort,
                remoteHost,
                remotePort,
                notify);
    }

    public int connectUDPSession(int sessionID, String remoteHost, int remotePort) {
        synchronized (mLock) {
            int index = mSessions.indexOfKey(sessionID);

            if (index < 0) {
                return -ENOENT;
            }

            Session session = mSessions.valueAt(index);
            DatagramChannel channel = (DatagramChannel) session.getSelectableChannel();
            SocketAddress remoteAddr = new InetSocketAddress(remoteHost, remotePort);

            try {
                channel.connect(remoteAddr);
                return OK;
            } catch (IOException e) {
                Log.e(TAG, channel + " connect to " + remoteAddr + " failed", e);
                return -EIO;
            }
        }
    }

    // passive
    public int createTCPDatagramSession(InetAddress localAddr, int port, AMessage notify)
            throws IOException {
        return createClientOrServer(
                Mode.MODE_CREATE_TCP_DATAGRAM_SESSION_PASSIVE,
                localAddr,
                port,
                null /* remoteHost */,
                0 /* remotePort */,
                notify);
    }

    // active
    public int createTCPDatagramSession(int localPort, String remoteHost, int remotePort,
            AMessage notify) throws IOException {
        return createClientOrServer(
                Mode.MODE_CREATE_TCP_DATAGRAM_SESSION_ACTIVE,
                null /* addr */,
                localPort,
                remoteHost,
                remotePort,
                notify);
    }

    public int destroySession(int sessionID) {
        Log.d(TAG, "destroySession[" + sessionID + ']');

        Session session;

        synchronized (mLock) {
            int index = mSessions.indexOfKey(sessionID);

            if (index < 0) {
                return -ENOENT;
            }

            session = mSessions.valueAt(index);
            mSessions.removeAt(index);
        }

        SelectableChannel channel = session.getSelectableChannel();
        SelectionKey selectionKey = channel.keyFor(mSelector);
        if (selectionKey != null) {
            selectionKey.cancel();
        }
        try {
            channel.close();
        } catch (IOException e) {
            // ignore
        }

        wakeUp();

        return OK;
    }

    public int sendRequest(int sessionID, CharSequence data) {
        return sendRequest(sessionID, data, false, -1L);
    }

    public int sendRequest(int sessionID, CharSequence data, boolean timeValid, long timeUs) {
        Log.d(TAG, "sendRequest[" + sessionID + "] >>>>>>------\n" + data + "------>>>>>>");
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(data));
        return sendRequest(sessionID, buffer, buffer.remaining(), timeValid, timeUs);
    }

    public int sendRequest(int sessionID, byte[] data) {
        return sendRequest(sessionID, data, false, -1L);
    }

    public int sendRequest(int sessionID, byte[] data, boolean timeValid, long timeUs) {
        return sendRequest(sessionID, ByteBuffer.wrap(data), data.length, timeValid, timeUs);
    }

    public int sendRequest(int sessionID, ByteBuffer data, int size) {
        return sendRequest(sessionID, data, size, false, -1L);
    }

    public int sendRequest(int sessionID, ByteBuffer data, int size,
            boolean timeValid, long timeUs) {
        synchronized (mLock) {
            int index = mSessions.indexOfKey(sessionID);

            if (index < 0) {
                return -ENOENT;
            }

            Session session = mSessions.valueAt(index);

            int err = session.sendRequest(data, size, timeValid, timeUs);

            //Log.d(TAG, String.format("sendRequest session[%d] size[%d] result[%d] >>>>>>>>>>>>",
            //        sessionID, size, err));

            wakeUp();

            return err;
        }
    }

    public int switchToWebSocketMode(int sessionID) {
        synchronized (mLock) {
            int index = mSessions.indexOfKey(sessionID);

            if (index < 0) {
                return -ENOENT;
            }

            Session session = mSessions.valueAt(index);
            return session.switchToWebSocketMode();
        }
    }

    public static final int WHAT_ERROR = 0;
    public static final int WHAT_CONNECTED = 1;
    public static final int WHAT_CLIENT_CONNECTED = 2;
    public static final int WHAT_DATA = 3;
    public static final int WHAT_DATAGRAM = 4;
    public static final int WHAT_BINARY_DATA = 5;
    public static final int WHAT_WEB_SOCKET_MESSAGE = 6;
    public static final int WHAT_NETWORK_STALL = 7;
}