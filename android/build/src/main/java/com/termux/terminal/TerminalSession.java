package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An individual terminal session attached to an interactive PTY subprocess.
 * Uses a background write executor to avoid NetworkOnMainThreadException.
 */
public final class TerminalSession {
    public final String mSessionId;
    public final String mAgentType;
    public final TerminalEmulator mEmulator;

    private TerminalSessionClient mClient;
    private Socket mSocket;
    private InputStream mIn;
    private OutputStream mOut;
    private Thread mReaderThread;
    private volatile boolean mIsRunning = false;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWriteExecutor = Executors.newSingleThreadExecutor();

    public TerminalSession(String sessionId, String agentType, int columns, int rows, TerminalSessionClient client) {
        mSessionId = sessionId;
        mAgentType = agentType;
        mClient = client;
        mEmulator = new TerminalEmulator(columns, rows, 2000); // 2000 lines scrollback
    }

    public void setClient(TerminalSessionClient client) {
        mClient = client;
    }

    public synchronized void connect(String host, int port) {
        if (mIsRunning) return;
        mIsRunning = true;

        new Thread(() -> {
            int attempts = 0;
            while (mIsRunning && attempts < 12) {
                try {
                    mSocket = new Socket(host, port);
                    break;
                } catch (Exception e) {
                    attempts++;
                    try {
                        com.mobcode.terminal.TerminalManager.getInstance().ensureDaemonRunning();
                    } catch (Throwable ignored) {}

                    if (attempts >= 12) {
                        mIsRunning = false;
                        String err = "\r\n\033[1;31m[MobCode] Could not connect to Termux PTY daemon (127.0.0.1:" + port + ").\033[0m\r\n"
                                + "\033[1;33mEnsure Termux is running and execute:\033[0m\r\n"
                                + "  \033[1;32mbash /sdcard/godot/mobcode/backend/mobcode_daemon.sh\033[0m\r\n\r\n"
                                + "\033[1;30m(Press Back button in top-left to return)\033[0m\r\n";
                        byte[] errBytes = err.getBytes(StandardCharsets.UTF_8);
                        synchronized (mEmulator) {
                            mEmulator.append(errBytes, errBytes.length);
                        }
                        notifyTextChanged();
                        notifySessionFinished();
                        return;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {}
                }
            }

            try {
                if (mSocket == null) {
                    mIsRunning = false;
                    notifySessionFinished();
                    return;
                }
                mIn = mSocket.getInputStream();
                mOut = mSocket.getOutputStream();

                // Handshake protocol: CONNECT:<session_id>:<agent_type>:<rows>:<cols>\n
                String handshake = String.format("CONNECT:%s:%s:%d:%d\n", mSessionId, mAgentType, mEmulator.mRows, mEmulator.mColumns);
                mOut.write(handshake.getBytes(StandardCharsets.UTF_8));
                mOut.flush();

                // Reader thread
                mReaderThread = new Thread(this::readLoop, "TerminalReader-" + mSessionId);
                mReaderThread.start();
            } catch (Exception e) {
                mIsRunning = false;
                notifySessionFinished();
            }
        }, "TerminalConnect-" + mSessionId).start();
    }

    private void readLoop() {
        byte[] buffer = new byte[8192];
        try {
            while (mIsRunning && mIn != null) {
                int read = mIn.read(buffer);
                if (read <= 0) break;
                synchronized (mEmulator) {
                    mEmulator.append(buffer, read);
                }
                notifyTextChanged();
            }
        } catch (Exception ignored) {
        } finally {
            mIsRunning = false;
            String exitMsg = "\r\n\033[1;33m[Session finished - press Back to return]\033[0m\r\n";
            byte[] exitBytes = exitMsg.getBytes(StandardCharsets.UTF_8);
            synchronized (mEmulator) {
                mEmulator.append(exitBytes, exitBytes.length);
            }
            notifyTextChanged();
            notifySessionFinished();
        }
    }

    public void write(byte[] data, int offset, int count) {
        if (!mIsRunning || data == null || count <= 0) return;
        final byte[] copy = Arrays.copyOfRange(data, offset, offset + count);
        mWriteExecutor.execute(() -> {
            try {
                if (mOut != null) {
                    mOut.write(copy);
                    mOut.flush();
                }
            } catch (Exception e) {
                mIsRunning = false;
            }
        });
    }

    public void write(byte[] data) {
        if (data != null && data.length > 0) {
            write(data, 0, data.length);
        }
    }

    public void write(String text) {
        if (text != null && !text.isEmpty()) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            write(bytes, 0, bytes.length);
        }
    }

    public void updateSize(int columns, int rows) {
        mEmulator.resize(columns, rows);
        mWriteExecutor.execute(() -> {
            if (mIsRunning && mOut != null) {
                try {
                    String resizeCmd = String.format("RESIZE:%d:%d\n", rows, columns);
                    mOut.write(resizeCmd.getBytes(StandardCharsets.UTF_8));
                    mOut.flush();
                } catch (Exception ignored) {
                }
            }
        });
        notifyTextChanged();
    }

    public synchronized void close() {
        mIsRunning = false;
        try {
            mWriteExecutor.shutdownNow();
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {}
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    private void notifyTextChanged() {
        mMainHandler.post(() -> {
            if (mClient != null) {
                mClient.onTextChanged(this);
            }
        });
    }

    private void notifySessionFinished() {
        mMainHandler.post(() -> {
            if (mClient != null) {
                mClient.onSessionFinished(this);
            }
        });
    }
}