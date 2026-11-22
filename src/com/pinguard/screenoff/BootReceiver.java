package com.pinguard.screenoff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (SessionStore.isMonitorEnabled(context)) {
                context.startForegroundService(new Intent(context, ScreenMonitorService.class));
            }
            if (SessionStore.isFirewallEnabled(context)
                    && !SessionStore.getBlockedPackages(context).isEmpty()
                    && VpnService.prepare(context) == null) {
                Intent vpn = new Intent(context, BlockVpnService.class);
                vpn.setAction(BlockVpnService.ACTION_UPDATE);
                context.startService(vpn);
            }
        }
    }
}
