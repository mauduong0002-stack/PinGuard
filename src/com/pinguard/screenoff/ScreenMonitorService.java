package com.pinguard.screenoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

import java.util.List;

public class ScreenMonitorService extends Service {
    public static final String ACTION_SESSION_UPDATED = "com.pinguard.screenoff.SESSION_UPDATED";
    private static final String CHANNEL_ID = "pinguard_monitor";
    private static final int NOTIF_ID = 1001;
    private static final long MIN_SESSION_MS = 60_000L; // Ghi nhận phiên tắt màn hình >= 1 phút

    private BroadcastReceiver screenReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification("Đang chờ phiên tắt màn hình..."));

        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String act = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(act)) {
                    DrainAnalyzer.BatterySnapshot snap = DrainAnalyzer.captureBattery(context);
                    SessionStore.savePendingSnapshot(context, snap);
                } else if (Intent.ACTION_SCREEN_ON.equals(act)) {
                    final DrainAnalyzer.BatterySnapshot start = SessionStore.popPendingSnapshot(context);
                    if (start == null) return;
                    final DrainAnalyzer.BatterySnapshot end = DrainAnalyzer.captureBattery(context);
                    final long durationMs = end.timestampMs - start.timestampMs;
                    if (durationMs < MIN_SESSION_MS) return;

                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            SessionStore.SessionRecord rec = new SessionStore.SessionRecord();
                            rec.startMs = start.timestampMs;
                            rec.endMs = end.timestampMs;
                            rec.startLevel = start.level;
                            rec.endLevel = end.level;
                            rec.startMah = start.chargeCounterMah;
                            rec.endMah = end.chargeCounterMah;
                            rec.startTemp = start.tempC;
                            rec.endTemp = end.tempC;
                            rec.isManualScan = false;
                            rec.wasCharging = start.isCharging || end.isCharging;
                            rec.apps = DrainAnalyzer.analyzeWindow(ScreenMonitorService.this, start.timestampMs, end.timestampMs);
                            SessionStore.addSession(ScreenMonitorService.this, rec);

                            int dropPct = Math.max(0, rec.startLevel - rec.endLevel);
                            long mins = Math.max(1, durationMs / 60000L);
                            String top = rec.apps.isEmpty() ? "Không có app nổi bật" : ("Top: " + rec.apps.get(0).appLabel);
                            String summary = rec.wasCharging
                                    ? ("Phiên " + mins + "p (đang sạc) • " + top)
                                    : ("Phiên " + mins + "p: -" + dropPct + "% • " + top);
                            updateNotification(summary);

                            Intent upd = new Intent(ACTION_SESSION_UPDATED);
                            upd.setPackage(getPackageName());
                            sendBroadcast(upd);
                        }
                    }).start();
                }
            }
        };

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Theo dõi pin tắt màn hình",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("PinGuard đang theo dõi")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }
}
