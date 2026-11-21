package com.mobcode.terminal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;
import org.godotengine.godot.plugin.UsedByGodot;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Godot 4 Android Plugin exposing native TerminalManager to GDScript.
 */
public class MobCodeTerminalPlugin extends GodotPlugin {
    public static final String PLUGIN_NAME = "MobCodeTerminal";

    public MobCodeTerminalPlugin(Godot godot) {
        super(godot);
        Activity activity = getActivity();
        if (activity != null) {
            TerminalManager.getInstance().init(activity, this);
        }
    }

    @Override
    public String getPluginName() {
        return PLUGIN_NAME;
    }

    @Override
    public Set<SignalInfo> getPluginSignals() {
        Set<SignalInfo> signals = new HashSet<>();
        signals.add(new SignalInfo("terminal_closed"));
        signals.add(new SignalInfo("session_finished", String.class));
        return signals;
    }

    @UsedByGodot
    public void openTerminal(String sessionId, String agentType) {
        TerminalManager.getInstance().showTerminal(sessionId, agentType);
    }

    @UsedByGodot
    public void closeTerminal() {
        TerminalManager.getInstance().hideTerminal();
    }

    @UsedByGodot
    public void closeSession(String sessionId) {
        TerminalManager.getInstance().closeSession(sessionId);
    }

    @UsedByGodot
    public void switchSession(String sessionId, String agentType) {
        TerminalManager.getInstance().switchSession(sessionId, agentType);
    }

    @UsedByGodot
    public boolean isTerminalVisible() {
        return TerminalManager.getInstance().isTerminalVisible();
    }

    @UsedByGodot
    public String getActiveSessionId() {
        return TerminalManager.getInstance().getActiveSessionId();
    }

    @UsedByGodot
    public void sendInput(String sessionId, String text) {
        TerminalManager.getInstance().sendInput(sessionId, text);
    }

    @UsedByGodot
    public void sendVoiceText(String text) {
        TerminalManager.getInstance().sendVoiceText(text);
    }

    @UsedByGodot
    public void startEngine() {
        TerminalManager.getInstance().ensureDaemonRunning();
    }

    @UsedByGodot
    public void setSessionSummary(int count, String summary) {
        SessionStatusService.updateSummary(count, summary);
    }

    /**
     * Returns safe-area insets in density-independent pixels. Godot runs
     * edge-to-edge, so the GDScript layer needs the real status bar, cutout
     * and navigation-bar sizes instead of guessing a fixed top padding.
     */
    @UsedByGodot
    public String getSafeAreaInsets() {
        Activity activity = getActivity();
        if (activity == null) {
            return "";
        }

        View decor = activity.getWindow().getDecorView();
        WindowInsets insets = decor.getRootWindowInsets();
        int left = 0;
        int top = 0;
        int right = 0;
        int bottom = 0;

        if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
            );
            left = bars.left;
            top = bars.top;
            right = bars.right;
            bottom = bars.bottom;
        } else if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
                left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
            }
        }

        // Fall back to system dimensions when insets have not been dispatched
        // yet (for example during the first Godot frame).
        if (top <= 0) {
            top = systemDimension(activity, "status_bar_height");
        }
        if (bottom <= 0) {
            bottom = systemDimension(activity, "navigation_bar_height");
        }

        float density = activity.getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }

        return String.format(
                Locale.US,
                "{\"top\":%.1f,\"right\":%.1f,\"bottom\":%.1f,\"left\":%.1f}",
                top / density,
                right / density,
                bottom / density,
                left / density
        );
    }

    private int systemDimension(Activity activity, String name) {
        int resId = activity.getResources().getIdentifier(name, "dimen", "android");
        return resId > 0 ? activity.getResources().getDimensionPixelSize(resId) : 0;
    }

    @UsedByGodot
    public boolean canDrawOverlays() {
        Activity activity = getActivity();
        return FloatingStatusOverlay.canDraw(activity);
    }

    @UsedByGodot
    public boolean isFloatingOverlayAttached() {
        return FloatingStatusOverlay.isAttached();
    }

    @UsedByGodot
    public void requestOverlayPermission() {
        FloatingStatusOverlay.requestPermission(getActivity());
    }

    @UsedByGodot
    public boolean checkAndRequestOverlayPermission() {
        Activity act = getActivity();
        if (act == null) return false;
        if (FloatingStatusOverlay.canDraw(act)) {
            android.util.Log.i("MobCodeOverlay", "Overlay permission already granted");
            return true;
        }
        act.runOnUiThread(() -> {
            android.widget.Toast.makeText(
                act,
                "Allow 'Display over other apps' to show the MobCode floating button",
                android.widget.Toast.LENGTH_LONG
            ).show();
            FloatingStatusOverlay.requestPermission(act);
        });
        return false;
    }

    @UsedByGodot
    public void updateFloatingSessions(String sessionsJson) {
        FloatingStatusOverlay.updateFloatingSessionsJson(sessionsJson);
        Context ctx = getActivity();
        if (ctx == null && getGodot() != null) {
            ctx = getGodot().getContext();
        }
        if (ctx == null) return;
        if (FloatingStatusOverlay.hasFloatingSessions() && FloatingStatusOverlay.canDraw(ctx)) {
            FloatingStatusOverlay.show(ctx);
            SessionStatusService.startOverlay(ctx);
        } else if (!FloatingStatusOverlay.hasFloatingSessions()) {
            FloatingStatusOverlay.hide();
        } else {
            FloatingStatusOverlay.hide();
            android.util.Log.w("MobCodeOverlay", "Pinned sessions exist but overlay permission is missing");
        }
    }

    @UsedByGodot
    public void showFloatingStatus() {
        Activity act = getActivity();
        if (act != null) {
            FloatingStatusOverlay.show(act);
        }
    }

    @UsedByGodot
    public void hideFloatingStatus() {
        FloatingStatusOverlay.hide();
    }

    @UsedByGodot
    public void hideFloatingOverlay() {
        FloatingStatusOverlay.hide();
    }

    public void notifyTerminalClosed() {
        emitSignal("terminal_closed");
    }

    public void notifySessionFinished(String sessionId) {
        emitSignal("session_finished", sessionId);
    }

    @Override
    public void onMainActivityResult(int requestCode, int resultCode, Intent data) {
        super.onMainActivityResult(requestCode, resultCode, data);
        TerminalManager.getInstance().onActivityResult(requestCode, resultCode, data);
    }
}