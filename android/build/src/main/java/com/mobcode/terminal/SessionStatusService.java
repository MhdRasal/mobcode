package com.mobcode.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.godot.game.R;

/**
 * Keeps a low-importance foreground notification alive while MobCode is
 * backgrounded with active terminal sessions and owns the floating overlay
 * lifecycle when the activity is not visible.
 */
public class SessionStatusService extends Service {
    private static final String TAG = "MobCodeOverlay";
    private static final String CHANNEL_ID = "mobcode_sessions";
    private static final String CHANNEL_NAME = "Active sessions";
    private static final int NOTIFICATION_ID = 4242;
    private static final long REFRESH_MS = 2000L;

    private static volatile int sSessionCount = 0;
    private static volatile String sSummary = "";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (sSessionCount <= 0 && !FloatingStatusOverlay.hasFloatingSessions()) {
                Log.i(TAG, "No sessions left; stopping status service");
                stopSelf();
                return;
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
            if (FloatingStatusOverlay.hasFloatingSessions()
                    && FloatingStatusOverlay.canDraw(SessionStatusService.this)) {
                FloatingStatusOverlay.show(SessionStatusService.this);
            }
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    public static void updateSummary(int count, String summary) {
        sSessionCount = Math.max(0, count);
        sSummary = summary == null ? "" : summary.trim();
    }

    public static boolean hasActiveSessions() {
        return sSessionCount > 0;
    }

    public static int getSessionCount() {
        return sSessionCount;
    }

    public static String getSummary() {
        return sSummary;
    }

    public static void startIfActive(Context context) {
        if (context == null) return;
        if (sSessionCount <= 0 && !FloatingStatusOverlay.hasFloatingSessions()) {
            return;
        }
        startForegroundService(context);
    }

    public static void startOverlay(Context context) {
        if (context == null) return;
        if (!FloatingStatusOverlay.hasFloatingSessions()) {
            Log.i(TAG, "Overlay service not started: no pinned sessions");
            return;
        }
        if (!FloatingStatusOverlay.canDraw(context)) {
            Log.w(TAG, "Overlay service not started: permission missing");
            return;
        }
        startForegroundService(context);
    }

    private static void startForegroundService(Context context) {
        Intent intent = new Intent(context, SessionStatusService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "Starting session status service");
        } catch (Throwable e) {
            Log.e(TAG, "Unable to start session status service", e);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        context.stopService(new Intent(context, SessionStatusService.class));
        Log.i(TAG, "Stopping session status service requested");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Log.i(TAG, "Session status service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat(buildNotification());
        FloatingStatusOverlay.restoreSessions(this);

        if (sSessionCount <= 0 && !FloatingStatusOverlay.hasFloatingSessions()) {
            Log.i(TAG, "Service started without sessions; stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (FloatingStatusOverlay.hasFloatingSessions()
                && FloatingStatusOverlay.canDraw(this)) {
            FloatingStatusOverlay.show(this);
        } else {
            FloatingStatusOverlay.hide();
        }

        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, REFRESH_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Session status service destroyed");
        mHandler.removeCallbacksAndMessages(null);
        if (!FloatingStatusOverlay.hasFloatingSessions()
                || !FloatingStatusOverlay.canDraw(this)) {
            FloatingStatusOverlay.hide();
        }
        stopForegroundCompat();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Unable to enter foreground service mode", e);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows which coding sessions are still running in the background");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        int count = Math.max(1, sSessionCount);
        String title = count == 1 ? "1 session active" : count + " sessions active";
        String text = sSummary.isEmpty()
                ? "MobCode is keeping your terminal sessions alive"
                : sSummary;

        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = null;
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            contentIntent = PendingIntent.getActivity(this, 0, launch, piFlags);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mobcode)
                .setColor(0xFF8B5CF6)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent);
        }
        return builder.build();
    }
}
