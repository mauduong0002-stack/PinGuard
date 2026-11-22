package com.pinguard.screenoff;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.util.Set;

public class BlockVpnService extends VpnService {
    public static final String ACTION_UPDATE = "com.pinguard.screenoff.VPN_UPDATE";
    public static final String ACTION_STOP = "com.pinguard.screenoff.VPN_STOP";

    private ParcelFileDescriptor tunInterface;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : ACTION_UPDATE;
        if (ACTION_STOP.equals(action) || !SessionStore.isFirewallEnabled(this)) {
            closeTun();
            stopSelf();
            return START_NOT_STICKY;
        }
        applyRules();
        return START_STICKY;
    }

    private void applyRules() {
        Set<String> blocked = SessionStore.getBlockedPackages(this);
        if (blocked.isEmpty()) {
            closeTun();
            stopSelf();
            return;
        }

        Builder b = new Builder();
        b.setSession("PinGuard Chặn mạng (" + blocked.size() + " app)");
        b.addAddress("10.255.255.1", 32);
        b.addRoute("0.0.0.0", 0);
        try {
            b.addAddress("fd00:255::1", 128);
            b.addRoute("::", 0);
        } catch (Exception ignored) {}

        int added = 0;
        android.content.pm.PackageManager pm = getPackageManager();
        for (String pkg : blocked) {
            if (pkg.equals(getPackageName())) continue;
            try {
                // Khong bao gio chan goi dung chung UID he thong (< 10000, vd UID 1000)
                if (pm.getApplicationInfo(pkg, 0).uid < 10000) continue;
                b.addAllowedApplication(pkg);
                added++;
            } catch (Exception ignored) {}
        }

        if (added == 0) {
            closeTun();
            stopSelf();
            return;
        }

        ParcelFileDescriptor newTun = null;
        try {
            newTun = b.establish();
        } catch (Exception ignored) {}

        closeTun();
        tunInterface = newTun;
        if (tunInterface == null) {
            stopSelf();
        }
    }

    private void closeTun() {
        if (tunInterface != null) {
            try { tunInterface.close(); } catch (Exception ignored) {}
            tunInterface = null;
        }
    }

    @Override
    public void onRevoke() {
        closeTun();
        SessionStore.setFirewallEnabled(this, false);
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        closeTun();
        super.onDestroy();
    }
}
