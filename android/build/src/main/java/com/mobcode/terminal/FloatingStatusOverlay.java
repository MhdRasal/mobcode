package com.mobcode.terminal;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Floating MobCode control rendered above other apps. The collapsed button
 * expands into a compact active-session switcher. All state is driven by the
 * sessions supplied by the Godot layer and by the shared status file.
 */
public final class FloatingStatusOverlay {
    private static final String TAG = "MobCodeOverlay";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long REFRESH_MS = 2000L;
    private static final String STATUS_FILE_PATH = "/sdcard/.mobcode_status.json";
    private static final String PREFS_NAME = "mobcode_overlay";
    private static final String PREF_SESSIONS = "sessions_json";
    private static final String PREF_X = "position_x";
    private static final String PREF_Y = "position_y";

    public static final String EXTRA_OPEN_SESSION_ID = "EXTRA_OPEN_SESSION_ID";

    public static class FloatingSession {
        public String id;
        public String type = "shell";
        public String status = "idle";
        public boolean pinned = true;
        public long lastSeenMs = System.currentTimeMillis();

        public FloatingSession(String id, String type, String status, boolean pinned) {
            this.id = id;
            this.type = type != null ? type : "shell";
            this.status = status != null ? status : "idle";
            this.pinned = pinned;
        }
    }

    private static final Map<String, FloatingSession> sSessions = new ConcurrentHashMap<>();
    private static FloatingStatusOverlay sInstance;
    private static SharedPreferences sPrefs;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final SharedPreferences mPrefs;
    private final LinearLayout mRoot;
    private final FrameLayout mButton;
    private final TextView mButtonIcon;
    private final View mButtonBadge;
    private final LinearLayout mPanel;
    private final LinearLayout mPanelRows;
    private final ScrollView mPanelScroll;
    private final int mButtonSize;
    private final int mTouchSlop;

    private WindowManager.LayoutParams mParams;
    private boolean mAttached = false;
    private boolean mPanelOpen = false;
    private boolean mDragging = false;
    private int mDragStartX;
    private int mDragStartY;
    private float mTouchStartX;
    private float mTouchStartY;

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mAttached) return;
            if (!canDraw(mContext)) {
                Log.w(TAG, "Overlay permission revoked; detaching");
                detach();
                return;
            }
            pollStatusFile();
            MAIN.postDelayed(this, REFRESH_MS);
        }
    };

    private FloatingStatusOverlay(Context context) {
        mContext = context.getApplicationContext();
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mPrefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mButtonSize = dp(54);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();

        mRoot = new LinearLayout(mContext);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setGravity(Gravity.START);
        mRoot.setClipChildren(false);
        mRoot.setClipToPadding(false);
        mRoot.setElevation(dp(8));

        mButton = new FrameLayout(mContext);
        GradientDrawable buttonBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF7C3AED, 0xFFA78BFA}
        );
        buttonBg.setCornerRadius(dp(17));
        buttonBg.setStroke(dp(1), 0xFFC4B5FD);
        mButton.setBackground(buttonBg);
        mButton.setElevation(dp(8));
        mButton.setClickable(true);
        mButton.setContentDescription("MobCode floating sessions");

        mButtonIcon = new TextView(mContext);
        mButtonIcon.setText(">_");
        mButtonIcon.setTextColor(Color.WHITE);
        mButtonIcon.setTextSize(16f);
        mButtonIcon.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        mButtonIcon.setGravity(Gravity.CENTER);
        mButtonIcon.setIncludeFontPadding(false);
        mButton.addView(mButtonIcon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        mButtonBadge = new View(mContext);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(0xFF22C55E);
        badgeBg.setStroke(dp(1), 0xFF111113);
        mButtonBadge.setBackground(badgeBg);
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(10), dp(10));
        badgeParams.gravity = Gravity.TOP | Gravity.END;
        badgeParams.topMargin = dp(2);
        badgeParams.rightMargin = dp(2);
        mButton.addView(mButtonBadge, badgeParams);

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(mButtonSize, mButtonSize);
        buttonParams.bottomMargin = dp(8);
        mRoot.addView(mButton, buttonParams);

        mPanelRows = new LinearLayout(mContext);
        mPanelRows.setOrientation(LinearLayout.VERTICAL);

        mPanelScroll = new ScrollView(mContext);
        mPanelScroll.setFillViewport(false);
        mPanelScroll.setVerticalScrollBarEnabled(false);
        mPanelScroll.addView(mPanelRows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        mPanel = new LinearLayout(mContext);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        mPanel.setElevation(dp(12));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xF5111113);
        panelBg.setCornerRadius(dp(18));
        panelBg.setStroke(dp(1), 0xFF3F3F46);
        mPanel.setBackground(panelBg);
        mPanel.setVisibility(View.GONE);

        LinearLayout panelHeader = new LinearLayout(mContext);
        panelHeader.setOrientation(LinearLayout.HORIZONTAL);
        panelHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(mContext);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(mContext);
        title.setText("MobCode sessions");
        title.setTextColor(0xFFFAFAFA);
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subtitle = new TextView(mContext);
        subtitle.setText("Active terminals");
        subtitle.setTextColor(0xFF71717A);
        subtitle.setTextSize(10f);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        panelHeader.addView(titleBlock, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView close = new TextView(mContext);
        close.setText("×");
        close.setTextColor(0xFFA1A1AA);
        close.setTextSize(19f);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close session panel");
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(0x1AFFFFFF);
        closeBg.setCornerRadius(dp(12));
        close.setBackground(closeBg);
        close.setOnClickListener(v -> closePanel());
        panelHeader.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));

        mPanel.addView(panelHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
        );
        scrollParams.topMargin = dp(8);
        mPanel.addView(mPanelScroll, scrollParams);

        TextView footer = new TextView(mContext);
        footer.setText("Open MobCode");
        footer.setTextColor(0xFFA78BFA);
        footer.setTextSize(11f);
        footer.setTypeface(Typeface.DEFAULT_BOLD);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(8), 0, 0);
        footer.setOnClickListener(v -> openAppForSession(null));
        mPanel.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                panelWidth(),
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelParams.bottomMargin = dp(8);
        mRoot.addView(mPanel, panelParams);

        mButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleButtonTouch(event);
            }
        });
        mRoot.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (!mDragging) {
                MAIN.post(FloatingStatusOverlay.this::clampToScreen);
            }
        });
    }

    private boolean handleButtonTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mParams == null) return false;
                mDragStartX = mParams.x;
                mDragStartY = mParams.y;
                mTouchStartX = event.getRawX();
                mTouchStartY = event.getRawY();
                mDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mParams == null || mWindowManager == null || !mAttached) return true;
                int dx = (int) (event.getRawX() - mTouchStartX);
                int dy = (int) (event.getRawY() - mTouchStartY);
                if (Math.abs(dx) > mTouchSlop || Math.abs(dy) > mTouchSlop) {
                    mDragging = true;
                }
                if (mDragging) {
                    mParams.x = clamp(mDragStartX + dx, 0, screenWidth() - mRoot.getWidth());
                    mParams.y = clamp(mDragStartY + dy, 0, screenHeight() - mRoot.getHeight());
                    try {
                        mWindowManager.updateViewLayout(mRoot, mParams);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to move overlay", e);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!mDragging) {
                    togglePanel();
                } else {
                    savePosition();
                }
                mDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                return true;
            default:
                return false;
        }
    }

    private void togglePanel() {
        if (!hasPinnedSessions()) {
            Log.w(TAG, "Ignoring panel toggle: no pinned sessions");
            return;
        }
        mPanelOpen = !mPanelOpen;
        if (mPanelOpen) {
            buildPanelContents();
            mPanel.setVisibility(View.VISIBLE);
            mRoot.post(this::clampToScreen);
            Log.i(TAG, "Session panel opened");
        } else {
            closePanel();
        }
    }

    private void closePanel() {
        mPanelOpen = false;
        mPanel.setVisibility(View.GONE);
        Log.i(TAG, "Session panel closed");
    }

    private void buildPanelContents() {
        mPanelRows.removeAllViews();
        List<FloatingSession> list = getSortedSessions();
        if (list.isEmpty()) {
            TextView empty = new TextView(mContext);
            empty.setText("No active sessions");
            empty.setTextColor(0xFF71717A);
            empty.setTextSize(11f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(18), dp(8), dp(18));
            mPanelRows.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            mPanelScroll.getLayoutParams().height = dp(56);
            return;
        }

        for (final FloatingSession session : list) {
            LinearLayout row = new LinearLayout(mContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(7), dp(8), dp(7));
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setColor(session.pinned ? 0x24A78BFA : 0x0FFFFFFF);
            rowBg.setCornerRadius(dp(11));
            row.setBackground(rowBg);
            row.setClickable(true);
            row.setContentDescription(session.id + ", " + statusText(session.status));

            View dot = new View(mContext);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(statusColor(session.status));
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotParams.rightMargin = dp(8);
            row.addView(dot, dotParams);

            LinearLayout textBlock = new LinearLayout(mContext);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(mContext);
            name.setText(session.id);
            name.setTextColor(0xFFFAFAFA);
            name.setTextSize(12f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            TextView meta = new TextView(mContext);
            meta.setText(typeLabel(session.type) + " · " + statusText(session.status));
            meta.setTextColor(0xFFA1A1AA);
            meta.setTextSize(10f);
            textBlock.addView(name);
            textBlock.addView(meta);
            row.addView(textBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView type = new TextView(mContext);
            type.setText(typeLabel(session.type));
            type.setTextColor(typeColor(session.type));
            type.setTextSize(9f);
            type.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable typeBg = new GradientDrawable();
            typeBg.setColor(typeBgColor(session.type));
            typeBg.setCornerRadius(dp(6));
            type.setBackground(typeBg);
            type.setPadding(dp(5), dp(2), dp(5), dp(2));
            row.addView(type, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));

            row.setOnClickListener(v -> {
                closePanel();
                openAppForSession(session.id);
            });

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.bottomMargin = dp(5);
            mPanelRows.addView(row, rowParams);
        }

        int visibleRows = Math.min(list.size(), 5);
        mPanelScroll.getLayoutParams().height = dp(visibleRows * 54 + 2);
        mPanelRows.requestLayout();
    }

    private static List<FloatingSession> getSortedSessions() {
        List<FloatingSession> list = new ArrayList<>(sSessions.values());
        Collections.sort(list, new Comparator<FloatingSession>() {
            @Override
            public int compare(FloatingSession a, FloatingSession b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return a.id.compareToIgnoreCase(b.id);
            }
        });
        return list;
    }

    private void refreshUi() {
        if (!hasPinnedSessions()) {
            closePanel();
            detach();
            return;
        }
        updateBadge();
        if (mPanelOpen) {
            buildPanelContents();
        }
    }

    private void updateBadge() {
        int color = overallStatusColor();
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(color);
        badge.setStroke(dp(1), 0xFF111113);
        mButtonBadge.setBackground(badge);
    }

    private int overallStatusColor() {
        boolean working = false;
        boolean stopped = false;
        for (FloatingSession session : sSessions.values()) {
            if (!session.pinned) continue;
            if (isAttention(session.status)) return 0xFFF59E0B;
            if (isWorking(session.status)) working = true;
            if ("stopped".equals(session.status)) stopped = true;
        }
        if (working) return 0xFF22C55E;
        if (stopped) return 0xFFF97316;
        return 0xFF8B5CF6;
    }

    private static boolean isWorking(String status) {
        return "running".equals(status) || "working".equals(status) || "active".equals(status);
    }

    private static boolean isAttention(String status) {
        return "needs_attention".equals(status) || "waiting".equals(status) || "input".equals(status);
    }

    private static void persistSessions() {
        if (sPrefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (FloatingSession session : sSessions.values()) {
                JSONObject obj = new JSONObject();
                obj.put("id", session.id);
                obj.put("type", session.type);
                obj.put("status", session.status);
                obj.put("pinned", session.pinned);
                arr.put(obj);
            }
            sPrefs.edit().putString(PREF_SESSIONS, arr.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Unable to persist floating sessions", e);
        }
    }

    public static boolean canDraw(Context context) {
        return context != null && Settings.canDrawOverlays(context);
    }

    public static void requestPermission(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "Overlay permission is not required below Android 6");
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "Opened overlay permission settings for " + context.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Package overlay settings unavailable; opening general settings", e);
            try {
                Intent fallback = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {
                Log.e(TAG, "Unable to open overlay permission settings", ignored);
            }
        }
    }

    public static boolean hasFloatingSessions() {
        return hasPinnedSessions();
    }

    public static boolean hasPinnedSessions() {
        for (FloatingSession session : sSessions.values()) {
            if (session.pinned) return true;
        }
        return false;
    }

    public static boolean isAttached() {
        return sInstance != null && sInstance.mAttached;
    }

    public static String getSessionType(String sessionId) {
        if (sessionId == null) return "shell";
        FloatingSession fs = sSessions.get(sessionId);
        return fs != null ? fs.type : "shell";
    }

    public static void updateFloatingSessionsJson(String jsonStr) {
        if (jsonStr == null) return;
        try {
            JSONArray arr = new JSONArray(jsonStr);
            Set<String> activeKeys = new HashSet<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id");
                if (id == null || id.isEmpty()) continue;
                String type = obj.optString("type", "shell");
                String status = obj.optString("status", "idle");
                boolean pinned = obj.optBoolean("pinned", true);
                activeKeys.add(id);
                FloatingSession existing = sSessions.get(id);
                if (existing == null) {
                    existing = new FloatingSession(id, type, status, pinned);
                }
                existing.type = type;
                existing.status = status;
                existing.pinned = pinned;
                existing.lastSeenMs = now;
                sSessions.put(id, existing);
            }
            sSessions.keySet().retainAll(activeKeys);
            persistSessions();
            MAIN.post(() -> {
                if (sInstance != null) {
                    sInstance.refreshUi();
                }
                if (!hasPinnedSessions()) {
                    hide();
                }
            });
            Log.i(TAG, "Updated floating sessions: " + sSessions.size() + ", pinned=" + hasPinnedSessions());
        } catch (Exception e) {
            Log.e(TAG, "Unable to parse floating sessions", e);
        }
    }

    public static void restoreSessions(Context context) {
        if (context == null) return;
        sPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!sSessions.isEmpty()) return;
        String saved = sPrefs.getString(PREF_SESSIONS, null);
        if (saved == null || saved.isEmpty()) return;
        Log.i(TAG, "Restoring persisted floating sessions");
        updateFloatingSessionsJson(saved);
    }

    public static void show(Context context) {
        if (context == null) {
            Log.w(TAG, "Cannot show overlay: null context");
            return;
        }
        if (!hasPinnedSessions()) {
            Log.i(TAG, "Cannot show overlay: no pinned sessions");
            return;
        }
        if (!canDraw(context)) {
            Log.w(TAG, "Cannot show overlay: permission not granted");
            return;
        }
        final Context appContext = context.getApplicationContext();
        sPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        persistSessions();
        MAIN.post(() -> {
            if (!hasPinnedSessions() || !canDraw(appContext)) return;
            if (sInstance == null) {
                sInstance = new FloatingStatusOverlay(appContext);
            }
            sInstance.attach();
            sInstance.refreshUi();
        });
    }

    public static void hide() {
        MAIN.post(() -> {
            if (sInstance != null) {
                sInstance.closePanel();
                sInstance.detach();
            }
        });
    }

    private void attach() {
        if (mAttached || mWindowManager == null) return;
        if (!canDraw(mContext)) {
            Log.w(TAG, "Overlay attach blocked: permission missing");
            return;
        }

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
        );
        mParams.gravity = Gravity.TOP | Gravity.START;
        mParams.x = mPrefs.getInt(PREF_X, dp(16));
        mParams.y = mPrefs.getInt(PREF_Y, statusBarHeight() + dp(16));

        updateBadge();
        if (mPanelOpen) {
            buildPanelContents();
        }

        try {
            mWindowManager.addView(mRoot, mParams);
            mAttached = true;
            mRoot.post(this::clampToScreen);
            MAIN.removeCallbacks(mRefreshRunnable);
            MAIN.postDelayed(mRefreshRunnable, REFRESH_MS);
            Log.i(TAG, "Overlay attached at x=" + mParams.x + " y=" + mParams.y
                    + " type=" + type + " sessions=" + sSessions.size());
        } catch (Exception e) {
            mAttached = false;
            Log.e(TAG, "WindowManager failed to attach overlay", e);
        }
    }

    private void detach() {
        mAttached = false;
        mPanelOpen = false;
        mPanel.setVisibility(View.GONE);
        MAIN.removeCallbacks(mRefreshRunnable);
        if (mWindowManager != null && mRoot.isAttachedToWindow()) {
            try {
                mWindowManager.removeView(mRoot);
                Log.i(TAG, "Overlay detached");
            } catch (Exception e) {
                Log.e(TAG, "Failed to detach overlay", e);
            }
        }
    }

    private void pollStatusFile() {
        try {
            File file = new File(STATUS_FILE_PATH);
            if (!file.exists()) return;
            int length = (int) Math.min(file.length(), 65536);
            if (length <= 0) return;
            byte[] buffer = new byte[length];
            try (FileInputStream stream = new FileInputStream(file)) {
                int read = stream.read(buffer);
                if (read <= 0) return;
                length = read;
            }
            String content = new String(buffer, 0, length, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(content);
            if (!json.has("sessions")) return;

            JSONArray arr = json.getJSONArray("sessions");
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id");
                FloatingSession session = sSessions.get(id);
                if (session == null) continue;
                String status = obj.optString("status", session.status);
                String type = obj.optString("type", session.type);
                if (!status.equals(session.status) || !type.equals(session.type)) {
                    session.status = status;
                    session.type = type;
                    changed = true;
                }
            }
            if (changed) {
                updateBadge();
                if (mPanelOpen) {
                    buildPanelContents();
                }
                Log.i(TAG, "Overlay status refreshed from " + STATUS_FILE_PATH);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to poll overlay status file", e);
        }
    }

    private void clampToScreen() {
        if (!mAttached || mParams == null || mRoot.getWidth() == 0 || mRoot.getHeight() == 0) return;
        int minX = dp(8);
        int minY = statusBarHeight() + dp(8);
        int maxX = screenWidth() - mRoot.getWidth() - dp(8);
        int maxY = screenHeight() - navigationBarHeight() - mRoot.getHeight() - dp(8);
        int x = clamp(mParams.x, minX, Math.max(minX, maxX));
        int y = clamp(mParams.y, minY, Math.max(minY, maxY));
        if (x == mParams.x && y == mParams.y) return;
        mParams.x = x;
        mParams.y = y;
        try {
            mWindowManager.updateViewLayout(mRoot, mParams);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clamp overlay position", e);
        }
    }

    private void savePosition() {
        if (mParams == null) return;
        mPrefs.edit().putInt(PREF_X, mParams.x).putInt(PREF_Y, mParams.y).apply();
        Log.i(TAG, "Saved overlay position x=" + mParams.x + " y=" + mParams.y);
    }

    private int panelWidth() {
        int width = Math.min(dp(292), screenWidth() - dp(24));
        return Math.max(dp(220), width);
    }

    private int screenWidth() {
        Rect bounds = screenBounds();
        return bounds.width();
    }

    private int screenHeight() {
        Rect bounds = screenBounds();
        return bounds.height();
    }

    private Rect screenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mWindowManager != null) {
            return mWindowManager.getCurrentWindowMetrics().getBounds();
        }
        DisplayMetrics metrics = new DisplayMetrics();
        if (mWindowManager != null && mWindowManager.getDefaultDisplay() != null) {
            mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
        }
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private int statusBarHeight() {
        int id = mContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? mContext.getResources().getDimensionPixelSize(id) : 0;
    }

    private int navigationBarHeight() {
        int id = mContext.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? mContext.getResources().getDimensionPixelSize(id) : 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private int statusColor(String status) {
        if (status == null) return 0xFF71717A;
        switch (status) {
            case "running":
            case "working":
            case "active":
                return 0xFF22C55E;
            case "needs_attention":
            case "waiting":
            case "input":
                return 0xFFF59E0B;
            case "completed":
            case "done":
                return 0xFF3B82F6;
            case "stopped":
                return 0xFFF97316;
            case "paused":
                return 0xFF8B5CF6;
            case "pending":
            case "starting":
                return 0xFFF59E0B;
            case "error":
            case "failed":
                return 0xFFEF4444;
            default:
                return 0xFF71717A;
        }
    }

    private String statusText(String status) {
        if (status == null) return "Idle";
        switch (status) {
            case "running":
            case "working":
            case "active":
                return "Working";
            case "needs_attention":
            case "waiting":
            case "input":
                return "Needs approval";
            case "completed":
            case "done":
                return "Completed";
            case "stopped":
                return "Stopped";
            case "paused":
                return "Paused";
            case "pending":
            case "starting":
                return "Starting";
            case "error":
            case "failed":
                return "Error";
            default:
                return "Idle";
        }
    }

    private String typeLabel(String type) {
        if (type == null) return "SH";
        switch (type.toLowerCase()) {
            case "opencode":
                return "CODE";
            case "agy":
                return "AGY";
            case "claude":
                return "CLAUDE";
            default:
                return "SH";
        }
    }

    private int typeColor(String type) {
        if (type == null) return 0xFF34D399;
        switch (type.toLowerCase()) {
            case "opencode":
                return 0xFFA78BFA;
            case "agy":
                return 0xFF38BDF8;
            case "claude":
                return 0xFFFB923C;
            default:
                return 0xFF34D399;
        }
    }

    private int typeBgColor(String type) {
        if (type == null) return 0x2E34D399;
        switch (type.toLowerCase()) {
            case "opencode":
                return 0x2EA78BFA;
            case "agy":
                return 0x2E38BDF8;
            case "claude":
                return 0x2EFB923C;
            default:
                return 0x2E34D399;
        }
    }

    private void openAppForSession(String sessionId) {
        Intent launch = mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
        if (launch == null) {
            Log.e(TAG, "Unable to find MobCode launch intent");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (sessionId != null && !sessionId.isEmpty()) {
            launch.putExtra(EXTRA_OPEN_SESSION_ID, sessionId);
        }
        try {
            mContext.startActivity(launch);
            Log.i(TAG, "Opened MobCode for session " + sessionId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open MobCode", e);
        }
    }

    private int dp(int value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }
}
