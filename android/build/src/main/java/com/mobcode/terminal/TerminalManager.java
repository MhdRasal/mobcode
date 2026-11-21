package com.mobcode.terminal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.shared.view.ExtraKeysView;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;

import java.io.FileWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates native Android Terminal sessions, overlay UI, soft keyboard,
 * Termux ExtraKeys toolbar, and Godot bridge.
 */
public class TerminalManager implements TerminalSessionClient {
    private static TerminalManager sInstance;

    private static final String PTY_HOST = "127.0.0.1";
    private static final int PTY_PORT = 8765;
    public static final int REQUEST_CODE_SPEECH = 4201;

    private Activity mActivity;
    private MobCodeTerminalPlugin mPlugin;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // Native Overlay UI
    private LinearLayout mTerminalOverlay;
    private TextView mTitleView;
    private TerminalView mTerminalView;
    private ExtraKeysView mExtraKeysView;

    // Persistent Sessions Map: sessionId -> TerminalSession
    private final Map<String, TerminalSession> mSessions = new ConcurrentHashMap<>();
    private String mActiveSessionId = null;

    private int mLastKeyboardPadding = -1;

    public static synchronized TerminalManager getInstance() {
        if (sInstance == null) {
            sInstance = new TerminalManager();
        }
        return sInstance;
    }

    private TerminalManager() {}

    public void init(Activity activity, MobCodeTerminalPlugin plugin) {
        mActivity = activity;
        mPlugin = plugin;

        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());
        } catch (Exception ignored) {}

        mActivity.runOnUiThread(() -> {
            setupOverlayUI();
            mActivity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            ensureDaemonRunning();
        });
    }

    public void ensureDaemonRunning() {
        new Thread(() -> {
            boolean isRunning = false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(PTY_HOST, PTY_PORT), 300);
                isRunning = true;
            } catch (Exception ignored) {}

            if (!isRunning) {
                autoStartTermuxEngine();
            }
        }).start();
    }

    public void autoStartTermuxEngine() {
        if (mActivity == null) return;
        try {
            // Write trigger file so daemon watcher or .bashrc can see it
            try (FileWriter fw = new FileWriter("/sdcard/.mobcode_start_daemon")) {
                fw.write(String.valueOf(System.currentTimeMillis() / 1000));
            } catch (Exception ignored) {}

            // Method 1: Send com.termux.RUN_COMMAND intent to background RunCommandService
            Intent intent = new Intent();
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", "nohup bash /sdcard/godot/mobcode/backend/mobcode_daemon.sh > /dev/null 2>&1 &"});
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    mActivity.startForegroundService(intent);
                } else {
                    mActivity.startService(intent);
                }
            } catch (Throwable t) {
                // Method 2: If service intent is restricted by Android, launch Termux activity
                try {
                    Intent launch = mActivity.getPackageManager().getLaunchIntentForPackage("com.termux");
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mActivity.startActivity(launch);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void setupOverlayUI() {
        if (mTerminalOverlay != null || mActivity == null) return;

        // Root Container: LinearLayout (Vertical)
        mTerminalOverlay = new LinearLayout(mActivity);
        mTerminalOverlay.setOrientation(LinearLayout.VERTICAL);
        mTerminalOverlay.setBackgroundColor(0xFF09090B);
        mTerminalOverlay.setVisibility(View.GONE);

        // 1. Top Bar: LinearLayout (Horizontal)
        LinearLayout topBar = new LinearLayout(mActivity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(0xFF111113);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        int statusBarHeight = 0;
        int resId = mActivity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            statusBarHeight = mActivity.getResources().getDimensionPixelSize(resId);
        }
        int initialTop = Math.max(statusBarHeight, dpToPx(32));
        topBar.setPadding(dpToPx(12), initialTop + dpToPx(6), dpToPx(12), dpToPx(8));

        // Back Button with comfortable 48dp touch target
        Button backBtn = new Button(mActivity);
        backBtn.setText("← Back");
        backBtn.setAllCaps(false);
        backBtn.setTextColor(0xFFC4B5FD);
        backBtn.setTextSize(14f);
        backBtn.setTypeface(Typeface.DEFAULT_BOLD);
        backBtn.setBackgroundColor(0x00000000);
        backBtn.setMinimumWidth(dpToPx(64));
        backBtn.setMinimumHeight(dpToPx(48));
        backBtn.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        backBtn.setFocusable(false);
        backBtn.setFocusableInTouchMode(false);
        backBtn.setOnClickListener(v -> hideTerminal());
        topBar.addView(backBtn);

        // Session Title
        mTitleView = new TextView(mActivity);
        mTitleView.setText("MobCode");
        mTitleView.setTextColor(0xFFFAFAFA);
        mTitleView.setTextSize(15f);
        mTitleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTitleView.setSingleLine(true);
        mTitleView.setPadding(dpToPx(12), 0, dpToPx(8), 0);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        mTitleView.setLayoutParams(titleParams);
        topBar.addView(mTitleView);

        // Voice Input Button with minimum 48x48dp target
        Button micBtn = new Button(mActivity);
        micBtn.setText("Mic");
        micBtn.setAllCaps(false);
        micBtn.setTextColor(0xFFA1A1AA);
        micBtn.setTextSize(12f);
        micBtn.setMinimumWidth(dpToPx(48));
        micBtn.setMinimumHeight(dpToPx(48));
        micBtn.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        micBtn.setBackgroundColor(0x00000000);
        micBtn.setFocusable(false);
        micBtn.setFocusableInTouchMode(false);
        micBtn.setOnClickListener(v -> startVoiceRecognition());
        topBar.addView(micBtn);

        // Keyboard Toggle Button with minimum 48x48dp target
        Button kbdBtn = new Button(mActivity);
        kbdBtn.setText("Keys");
        kbdBtn.setAllCaps(false);
        kbdBtn.setTextColor(0xFFA1A1AA);
        kbdBtn.setTextSize(12f);
        kbdBtn.setMinimumWidth(dpToPx(48));
        kbdBtn.setMinimumHeight(dpToPx(48));
        kbdBtn.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        kbdBtn.setBackgroundColor(0x00000000);
        kbdBtn.setFocusable(false);
        kbdBtn.setFocusableInTouchMode(false);
        kbdBtn.setOnClickListener(v -> {
            if (mExtraKeysView != null) {
                mExtraKeysView.toggleKeyboard();
            }
        });
        topBar.addView(kbdBtn);

        mTerminalOverlay.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Window Insets listener for dynamic status bar & cutout adjustments
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mTerminalOverlay.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout()
                );
                int left = Math.max(dpToPx(12), dpToPx(12) + bars.left);
                int top = Math.max(dpToPx(32), bars.top) + dpToPx(8);
                int right = Math.max(dpToPx(12), dpToPx(12) + bars.right);
                topBar.setPadding(left, top, right, dpToPx(8));
                return insets;
            });
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mTerminalOverlay.setOnApplyWindowInsetsListener((v, insets) -> {
                int left = Math.max(dpToPx(12), dpToPx(12) + insets.getSystemWindowInsetLeft());
                int top = Math.max(dpToPx(32), insets.getSystemWindowInsetTop()) + dpToPx(8);
                int right = Math.max(dpToPx(12), dpToPx(12) + insets.getSystemWindowInsetRight());
                topBar.setPadding(left, top, right, dpToPx(8));
                return insets;
            });
        }

        // 2. TerminalView: Interactive Canvas terminal attached to PTY
        mTerminalView = new TerminalView(mActivity);
        LinearLayout.LayoutParams termParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        mTerminalOverlay.addView(mTerminalView, termParams);

        // 3. ExtraKeysView: Termux two-row toolbar pinned directly above keyboard
        mExtraKeysView = new ExtraKeysView(mActivity);
        mExtraKeysView.setTerminalView(mTerminalView);
        mTerminalOverlay.addView(mExtraKeysView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Attach overlay to Window DecorView
        ViewGroup decorView = (ViewGroup) mActivity.getWindow().getDecorView();
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        decorView.addView(mTerminalOverlay, overlayParams);

        // Setup dynamic keyboard listener so shortcuts stay directly on top of the keyboard
        setupKeyboardPositioningListener();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            mTerminalOverlay.requestApplyInsets();
        }
    }

    private void setupKeyboardPositioningListener() {
        if (mTerminalOverlay == null) return;

        // Universal Display Frame Observer: measures exact visible screen bounds
        mTerminalOverlay.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (mTerminalOverlay == null || mTerminalOverlay.getVisibility() != View.VISIBLE) return;

                Rect r = new Rect();
                mTerminalOverlay.getWindowVisibleDisplayFrame(r);
                View root = mTerminalOverlay.getRootView();
                int screenHeight = (root != null) ? root.getHeight() : mTerminalOverlay.getHeight();
                int keypadHeight = screenHeight - r.bottom;

                // If keypadHeight is > 15% of screen height, the soft keyboard is open
                int bottomPadding = (keypadHeight > screenHeight * 0.15) ? keypadHeight : 0;
                if (bottomPadding != mLastKeyboardPadding) {
                    mLastKeyboardPadding = bottomPadding;
                    mTerminalOverlay.setPadding(0, 0, 0, bottomPadding);
                    mTerminalOverlay.requestLayout();
                }
            }
        });
    }

    public void showTerminal(String sessionId, String agentType) {
        ensureDaemonRunning();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "shell-1";
        }
        final String finalSessionId = sessionId;
        final String finalAgentType = (agentType == null || agentType.isEmpty()) ? "shell" : agentType;

        mMainHandler.post(() -> {
            setupOverlayUI();
            if (mTerminalOverlay == null) return;

            mActivity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            // Fetch or create persistent session
            TerminalSession session = mSessions.get(finalSessionId);
            if (session == null) {
                int cols = Math.max(80, (int) (mTerminalOverlay.getWidth() > 0 ? mTerminalOverlay.getWidth() / 20 : 80));
                int rows = Math.max(24, (int) (mTerminalOverlay.getHeight() > 0 ? mTerminalOverlay.getHeight() / 40 : 24));
                session = new TerminalSession(finalSessionId, finalAgentType, cols, rows, this);
                session.connect(PTY_HOST, PTY_PORT);
                mSessions.put(finalSessionId, session);
            }

            mActiveSessionId = finalSessionId;
            mTerminalView.attachSession(session);
            mTitleView.setText(finalSessionId + " [" + finalAgentType + "]");

            mTerminalOverlay.setVisibility(View.VISIBLE);
            mTerminalOverlay.bringToFront();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                mTerminalOverlay.requestApplyInsets();
            }

            // Request focus and open soft keyboard
            mTerminalView.post(() -> {
                mTerminalView.requestFocus();
                mTerminalView.requestFocusFromTouch();
                InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(mTerminalView, InputMethodManager.SHOW_FORCED);
                }
            });
        });
    }

    public void closeSession(String sessionId) {
        if (sessionId == null) return;
        mMainHandler.post(() -> {
            TerminalSession session = mSessions.remove(sessionId);
            if (session != null) {
                session.close();
            }
            if (sessionId.equals(mActiveSessionId)) {
                mActiveSessionId = null;
                hideTerminal();
            }
        });
    }

    public void hideTerminal() {
        mMainHandler.post(() -> {
            if (mTerminalOverlay != null) {
                mTerminalOverlay.setVisibility(View.GONE);
                mTerminalOverlay.setPadding(0, 0, 0, 0);
                mLastKeyboardPadding = -1;
                InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(mTerminalOverlay.getWindowToken(), 0);
                }
            }
            if (mPlugin != null) {
                mPlugin.notifyTerminalClosed();
            }
        });
    }

    public void switchSession(String sessionId, String agentType) {
        showTerminal(sessionId, agentType);
    }

    public boolean isTerminalVisible() {
        return mTerminalOverlay != null && mTerminalOverlay.getVisibility() == View.VISIBLE;
    }

    public String getActiveSessionId() {
        return mActiveSessionId;
    }

    public void sendInput(String sessionId, String text) {
        TerminalSession session = mSessions.get(sessionId);
        if (session != null && text != null) {
            session.write(text);
        }
    }

    public void sendVoiceText(String text) {
        if (mActiveSessionId != null && text != null) {
            sendInput(mActiveSessionId, text);
        }
    }

    private void startVoiceRecognition() {
        if (mActivity == null) return;
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak terminal command...");
            mActivity.startActivityForResult(intent, REQUEST_CODE_SPEECH);
        } catch (Exception e) {
            Toast.makeText(mActivity, "Voice recognition not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_SPEECH && resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) {
                String spoken = matches.get(0);
                sendVoiceText(spoken + " ");
            }
        }
    }

    private int dpToPx(int dp) {
        if (mActivity == null) return dp;
        float density = mActivity.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // TerminalSessionClient implementation
    @Override
    public void onTextChanged(TerminalSession session) {
        if (mTerminalView != null && mTerminalView.getCurrentSession() == session) {
            mTerminalView.invalidate();
        }
    }

    @Override
    public void onTitleChanged(TerminalSession session, String newTitle) {
        if (session != null && session.mSessionId.equals(mActiveSessionId) && mTitleView != null) {
            mMainHandler.post(() -> mTitleView.setText(session.mSessionId + " - " + newTitle));
        }
    }

    @Override
    public void onSessionFinished(TerminalSession session) {
        if (session != null) {
            mSessions.remove(session.mSessionId);
            // Do not immediately force-hide the terminal overlay so user can read final output or errors.
            // User can press Back when ready.
            if (mPlugin != null) {
                mPlugin.notifySessionFinished(session.mSessionId);
            }
        }
    }

    @Override
    public void onClipboardCopy(TerminalSession session, String text) {}

    @Override
    public void onBell(TerminalSession session) {}
}