package com.pinguard.screenoff;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_VPN = 9001;
    private static final String ADGUARD_DNS = "dns.adguard-dns.com";
    private static final String GOOGLE_DNS = "dns.google";

    private LinearLayout rootList;
    private TextView statusBadge;
    private TextView batteryInfoText;
    private TextView permBadge;
    private TextView toggleBtn;
    private TextView dnsStatusText;
    private TextView dnsToggleBtn;
    private TextView fwStatusText;
    private TextView fwToggleBtn;
    private TextView fwPickBtn;
    private BroadcastReceiver updateReceiver;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm dd/MM", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        if (SessionStore.isMonitorEnabled(this)) {
            startMonitorService();
        }
        // Tu dong go cac goi UID < 10000 neu lo tich tu ban cu
        PackageManager pm = getPackageManager();
        boolean cleaned = false;
        for (String pkg : SessionStore.getBlockedPackages(this)) {
            try {
                if (pm.getApplicationInfo(pkg, 0).uid < 10000) {
                    SessionStore.toggleBlockedPackage(this, pkg);
                    cleaned = true;
                }
            } catch (Exception ignored) {}
        }
        if (cleaned) applyFirewallService();

        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshAll();
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(updateReceiver, new IntentFilter(ScreenMonitorService.ACTION_SESSION_UPDATED), Context.RECEIVER_NOT_EXPORTED);
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(updateReceiver); } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                SessionStore.setFirewallEnabled(this, true);
                applyFirewallService();
                Toast.makeText(this, "Đã bật chặn mạng cho app đã chọn", Toast.LENGTH_SHORT).show();
            } else {
                SessionStore.setFirewallEnabled(this, false);
                Toast.makeText(this, "Chưa cấp quyền VPN để chặn mạng", Toast.LENGTH_SHORT).show();
            }
            refreshAll();
        }
    }

    private void startMonitorService() {
        try { startForegroundService(new Intent(this, ScreenMonitorService.class)); } catch (Exception ignored) {}
    }

    private void stopMonitorService() {
        try { stopService(new Intent(this, ScreenMonitorService.class)); } catch (Exception ignored) {}
    }

    private void applyFirewallService() {
        boolean on = SessionStore.isFirewallEnabled(this);
        Set<String> blocked = SessionStore.getBlockedPackages(this);
        if (!on || blocked.isEmpty()) {
            Intent stop = new Intent(this, BlockVpnService.class);
            stop.setAction(BlockVpnService.ACTION_STOP);
            startService(stop);
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
        } else {
            Intent upd = new Intent(this, BlockVpnService.class);
            upd.setAction(BlockVpnService.ACTION_UPDATE);
            startService(upd);
        }
    }

    private void toggleAppNetworkBlock(String pkg, String label) {
        try {
            if (getPackageManager().getApplicationInfo(pkg, 0).uid < 10000) {
                Toast.makeText(this, "Không chặn " + label + " (dùng chung UID hệ thống)", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception ignored) {}
        boolean nowBlocked = SessionStore.toggleBlockedPackage(this, pkg);
        if (nowBlocked && !SessionStore.isFirewallEnabled(this)) {
            SessionStore.setFirewallEnabled(this, true);
        }
        applyFirewallService();
        Toast.makeText(this, (nowBlocked ? "Đã chặn mạng: " : "Đã mở mạng: ") + label, Toast.LENGTH_SHORT).show();
        refreshAll();
    }

    private void toggleAdguardDns() {
        String curMode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
        String curHost = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        boolean isAdguard = "hostname".equals(curMode) && ADGUARD_DNS.equalsIgnoreCase(curHost);
        String nextHost = isAdguard ? GOOGLE_DNS : ADGUARD_DNS;

        try {
            Settings.Global.putString(getContentResolver(), "private_dns_mode", "hostname");
            Settings.Global.putString(getContentResolver(), "private_dns_specifier", nextHost);
            Toast.makeText(this, isAdguard ? "Đã chuyển về Google DNS" : "Đã bật chặn QC toàn máy (AdGuard DNS)", Toast.LENGTH_SHORT).show();
            refreshAll();
        } catch (SecurityException se) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("DNS", nextHost));
            }
            Toast.makeText(this, "Đã copy " + nextHost + " — dán vào mục Private DNS", Toast.LENGTH_LONG).show();
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#0B1120"));
        scroll.setFillViewport(true);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, dp(42), pad, dp(28));
        scroll.addView(container);

        // Header Row
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("PinGuard v1.2");
        title.setTextSize(23);
        title.setTextColor(Color.parseColor("#F8FAFC"));
        title.setTypeface(null, Typeface.BOLD);
        titleBox.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Theo dõi pin tắt màn hình & chặn quảng cáo");
        sub.setTextSize(12);
        sub.setTextColor(Color.parseColor("#94A3B8"));
        titleBox.addView(sub);

        statusBadge = new TextView(this);
        statusBadge.setTextSize(12);
        statusBadge.setTypeface(null, Typeface.BOLD);
        statusBadge.setPadding(dp(10), dp(5), dp(10), dp(5));

        header.addView(titleBox);
        header.addView(statusBadge);
        container.addView(header);

        // Live Battery Card
        LinearLayout batCard = makeCard("#1E293B");
        LinearLayout.LayoutParams bcp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bcp.topMargin = dp(12);
        batCard.setLayoutParams(bcp);

        TextView batHeader = new TextView(this);
        batHeader.setText("Trạng thái pin hiện tại");
        batHeader.setTextSize(13);
        batHeader.setTextColor(Color.parseColor("#94A3B8"));
        batHeader.setTypeface(null, Typeface.BOLD);
        batCard.addView(batHeader);

        batteryInfoText = new TextView(this);
        batteryInfoText.setTextSize(15);
        batteryInfoText.setTextColor(Color.parseColor("#F1F5F9"));
        batteryInfoText.setPadding(0, dp(5), 0, dp(7));
        batCard.addView(batteryInfoText);

        permBadge = new TextView(this);
        permBadge.setTextSize(12);
        permBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        permBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!DrainAnalyzer.hasUsageStatsPermission(MainActivity.this)) {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                }
            }
        });
        batCard.addView(permBadge);
        container.addView(batCard);

        // AdBlock & Firewall Card
        LinearLayout shieldCard = makeCard("#1E293B");
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scp.topMargin = dp(10);
        shieldCard.setLayoutParams(scp);

        TextView shHeader = new TextView(this);
        shHeader.setText("Chống quảng cáo & Chặn mạng (0% hao pin)");
        shHeader.setTextSize(13);
        shHeader.setTextColor(Color.parseColor("#94A3B8"));
        shHeader.setTypeface(null, Typeface.BOLD);
        shieldCard.addView(shHeader);

        // Row 1: AdGuard DNS
        LinearLayout dnsRow = new LinearLayout(this);
        dnsRow.setOrientation(LinearLayout.HORIZONTAL);
        dnsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams drp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        drp.topMargin = dp(8);
        dnsRow.setLayoutParams(drp);

        dnsStatusText = new TextView(this);
        dnsStatusText.setTextSize(13);
        dnsStatusText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        dnsToggleBtn = makeButton("Bật QC DNS", "#065F46", "#A7F3D0");
        dnsToggleBtn.setTextSize(12);
        dnsToggleBtn.setPadding(dp(10), dp(7), dp(10), dp(7));
        dnsToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAdguardDns();
            }
        });

        dnsRow.addView(dnsStatusText);
        dnsRow.addView(dnsToggleBtn);
        shieldCard.addView(dnsRow);

        // Row 2: Per-app Blackhole Firewall
        LinearLayout fwRow = new LinearLayout(this);
        fwRow.setOrientation(LinearLayout.HORIZONTAL);
        fwRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams frp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        frp.topMargin = dp(8);
        fwRow.setLayoutParams(frp);

        fwStatusText = new TextView(this);
        fwStatusText.setTextSize(13);
        fwStatusText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        fwPickBtn = makeButton("Chọn app", "#334155", "#E2E8F0");
        fwPickBtn.setTextSize(12);
        fwPickBtn.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams fpp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fpp.rightMargin = dp(6);
        fwPickBtn.setLayoutParams(fpp);
        fwPickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppBlockPicker();
            }
        });

        fwToggleBtn = makeButton("Bật", "#1D4ED8", "#FFFFFF");
        fwToggleBtn.setTextSize(12);
        fwToggleBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        fwToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !SessionStore.isFirewallEnabled(MainActivity.this);
                if (next && SessionStore.getBlockedPackages(MainActivity.this).isEmpty()) {
                    Toast.makeText(MainActivity.this, "Hãy bấm \"Chọn app\" để chọn app cần chặn mạng trước", Toast.LENGTH_SHORT).show();
                    openAppBlockPicker();
                    return;
                }
                SessionStore.setFirewallEnabled(MainActivity.this, next);
                applyFirewallService();
                refreshAll();
            }
        });

        fwRow.addView(fwStatusText);
        fwRow.addView(fwPickBtn);
        fwRow.addView(fwToggleBtn);
        shieldCard.addView(fwRow);
        container.addView(shieldCard);

        // Action Buttons Row
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(10);
        actions.setLayoutParams(ap);

        toggleBtn = makeButton("Tạm dừng", "#334155", "#F8FAFC");
        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp1.rightMargin = dp(8);
        toggleBtn.setLayoutParams(bp1);
        toggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !SessionStore.isMonitorEnabled(MainActivity.this);
                SessionStore.setMonitorEnabled(MainActivity.this, next);
                if (next) startMonitorService();
                else stopMonitorService();
                refreshAll();
            }
        });

        TextView scanNowBtn = makeButton("Quét 24 giờ qua", "#2563EB", "#FFFFFF");
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp2.rightMargin = dp(8);
        scanNowBtn.setLayoutParams(bp2);
        scanNowBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runManualScan();
            }
        });

        TextView clearBtn = makeButton("Xóa", "#3F1D2E", "#FDA4AF");
        clearBtn.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SessionStore.clearSessions(MainActivity.this);
                refreshAll();
                Toast.makeText(MainActivity.this, "Đã xóa lịch sử", Toast.LENGTH_SHORT).show();
            }
        });

        actions.addView(toggleBtn);
        actions.addView(scanNowBtn);
        actions.addView(clearBtn);
        container.addView(actions);

        // Section title
        TextView secTitle = new TextView(this);
        secTitle.setText("Lịch sử phiên tắt màn hình & quét 24 giờ");
        secTitle.setTextSize(14);
        secTitle.setTextColor(Color.parseColor("#CBD5E1"));
        secTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(16);
        sp.bottomMargin = dp(8);
        secTitle.setLayoutParams(sp);
        container.addView(secTitle);

        // Sessions container
        rootList = new LinearLayout(this);
        rootList.setOrientation(LinearLayout.VERTICAL);
        container.addView(rootList);

        setContentView(scroll);
    }

    private static class SimpleApp {
        String pkg;
        String label;
        boolean blocked;
    }

    private void openAppBlockPicker() {
        PackageManager pm = getPackageManager();
        Set<String> blocked = SessionStore.getBlockedPackages(this);
        String self = getPackageName();
        final List<SimpleApp> allApps = new ArrayList<>();

        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        for (ApplicationInfo ai : installed) {
            if (ai.packageName.equals(self)) continue;
            if (ai.uid < 10000) continue; // Bao ve cac goi loi he thong UID < 10000
            boolean isUser = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            boolean isLaunchable = pm.getLaunchIntentForPackage(ai.packageName) != null;
            if (!isUser && !isLaunchable && !blocked.contains(ai.packageName)) continue;

            SimpleApp sa = new SimpleApp();
            sa.pkg = ai.packageName;
            CharSequence lbl = pm.getApplicationLabel(ai);
            sa.label = (lbl != null && lbl.length() > 0) ? lbl.toString() : ai.packageName;
            sa.blocked = blocked.contains(ai.packageName);
            allApps.add(sa);
        }

        Collections.sort(allApps, new Comparator<SimpleApp>() {
            @Override
            public int compare(SimpleApp a, SimpleApp b) {
                if (a.blocked != b.blocked) return a.blocked ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        LinearLayout dlgRoot = new LinearLayout(this);
        dlgRoot.setOrientation(LinearLayout.VERTICAL);
        dlgRoot.setPadding(dp(16), dp(12), dp(16), dp(8));

        final EditText search = new EditText(this);
        search.setHint("Tìm app (vd: File, Quản lý tệp, Video...)");
        search.setSingleLine(true);
        dlgRoot.addView(search);

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360));
        svp.topMargin = dp(8);
        sv.setLayoutParams(svp);

        final LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        dlgRoot.addView(sv);

        final Runnable renderList = new Runnable() {
            @Override
            public void run() {
                String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
                listBox.removeAllViews();
                for (final SimpleApp sa : allApps) {
                    if (!q.isEmpty()
                            && !sa.label.toLowerCase(Locale.getDefault()).contains(q)
                            && !sa.pkg.toLowerCase(Locale.getDefault()).contains(q)) {
                        continue;
                    }
                    CheckBox cb = new CheckBox(MainActivity.this);
                    cb.setText(sa.label + "\n" + sa.pkg);
                    cb.setTextSize(13);
                    cb.setChecked(sa.blocked);
                    cb.setPadding(dp(4), dp(8), dp(4), dp(8));
                    cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            sa.blocked = isChecked;
                            boolean cur = SessionStore.isBlocked(MainActivity.this, sa.pkg);
                            if (cur != isChecked) {
                                SessionStore.toggleBlockedPackage(MainActivity.this, sa.pkg);
                            }
                        }
                    });
                    listBox.addView(cb);
                }
            }
        };

        renderList.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderList.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        new AlertDialog.Builder(this)
                .setTitle("Chọn app muốn chặn mạng")
                .setView(dlgRoot)
                .setPositiveButton("Áp dụng", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!SessionStore.getBlockedPackages(MainActivity.this).isEmpty()) {
                            SessionStore.setFirewallEnabled(MainActivity.this, true);
                        } else {
                            SessionStore.setFirewallEnabled(MainActivity.this, false);
                        }
                        applyFirewallService();
                        refreshAll();
                    }
                })
                .setNegativeButton("Đóng", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        applyFirewallService();
                        refreshAll();
                    }
                })
                .show();
    }

    private void runManualScan() {
        if (!DrainAnalyzer.hasUsageStatsPermission(this)) {
            Toast.makeText(this, "Chưa có quyền Usage Stats!", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Đang quét 24 giờ gần nhất...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                long end = System.currentTimeMillis();
                long start = end - (24L * 3600_000L);
                DrainAnalyzer.BatterySnapshot now = DrainAnalyzer.captureBattery(MainActivity.this);
                SessionStore.SessionRecord rec = new SessionStore.SessionRecord();
                rec.startMs = start;
                rec.endMs = end;
                rec.startLevel = now.level;
                rec.endLevel = now.level;
                rec.startMah = now.chargeCounterMah;
                rec.endMah = now.chargeCounterMah;
                rec.startTemp = now.tempC;
                rec.endTemp = now.tempC;
                rec.isManualScan = true;
                rec.wasCharging = now.isCharging;
                rec.apps = DrainAnalyzer.analyzeWindow(MainActivity.this, start, end);
                SessionStore.addSession(MainActivity.this, rec);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshAll();
                    }
                });
            }
        }).start();
    }

    private void refreshAll() {
        boolean enabled = SessionStore.isMonitorEnabled(this);
        statusBadge.setText(enabled ? "● Đang bật" : "○ Đã tắt");
        statusBadge.setTextColor(Color.parseColor(enabled ? "#4ADE80" : "#94A3B8"));
        statusBadge.setBackground(makeRounded(enabled ? "#14532D" : "#334155", 20));
        toggleBtn.setText(enabled ? "Tạm dừng" : "Bật theo dõi");

        DrainAnalyzer.BatterySnapshot bat = DrainAnalyzer.captureBattery(this);
        String chgStr = bat.isCharging ? "Đang sạc" : "Đang dùng pin";
        String info = String.format(Locale.getDefault(),
                "%d%%  •  %d mAh  •  %.1f°C  •  %d mV\nTrạng thái: %s",
                bat.level, bat.chargeCounterMah, bat.tempC, bat.voltageMv, chgStr);
        batteryInfoText.setText(info);

        boolean hasPerm = DrainAnalyzer.hasUsageStatsPermission(this);
        if (hasPerm) {
            permBadge.setText("✓ Đã cấp quyền đọc hoạt động nền & mạng");
            permBadge.setTextColor(Color.parseColor("#86EFAC"));
            permBadge.setBackground(makeRounded("#14532D", 10));
        } else {
            permBadge.setText("⚠ Chưa có quyền Usage Stats (bấm để cấp)");
            permBadge.setTextColor(Color.parseColor("#FDE68A"));
            permBadge.setBackground(makeRounded("#78350F", 10));
        }

        // Refresh AdGuard DNS status
        String curMode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
        String curHost = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        boolean isAdguard = "hostname".equals(curMode) && ADGUARD_DNS.equalsIgnoreCase(curHost);
        dnsStatusText.setText(isAdguard ? "DNS chặn QC: ĐANG BẬT (AdGuard)" : ("DNS chặn QC: Tắt (" + (curHost == null ? "mặc định" : curHost) + ")"));
        dnsStatusText.setTextColor(Color.parseColor(isAdguard ? "#86EFAC" : "#CBD5E1"));
        dnsToggleBtn.setText(isAdguard ? "Tắt DNS QC" : "Bật DNS QC");
        dnsToggleBtn.setBackground(makeRounded(isAdguard ? "#334155" : "#065F46", 10));

        // Refresh Firewall status
        boolean fwOn = SessionStore.isFirewallEnabled(this);
        int blkCount = SessionStore.getBlockedPackages(this).size();
        fwStatusText.setText("Chặn mạng app: " + (fwOn && blkCount > 0 ? ("ĐANG CHẶN " + blkCount + " app") : ("Tắt (" + blkCount + " app đã chọn)")));
        fwStatusText.setTextColor(Color.parseColor(fwOn && blkCount > 0 ? "#FCA5A5" : "#CBD5E1"));
        fwToggleBtn.setText(fwOn && blkCount > 0 ? "Tắt" : "Bật");
        fwToggleBtn.setBackground(makeRounded(fwOn && blkCount > 0 ? "#7F1D1D" : "#1D4ED8", 10));

        rootList.removeAllViews();
        List<SessionStore.SessionRecord> sessions = SessionStore.loadSessions(this);
        if (sessions.isEmpty()) {
            LinearLayout empty = makeCard("#1E293B");
            TextView tv = new TextView(this);
            tv.setText("Chưa có phiên tắt màn hình nào được ghi nhận.\n\n• Tắt màn hình tối thiểu 1 phút rồi mở lại để xem thống kê tự động.\n• Hoặc bấm nút \"Quét 24 giờ qua\" ở trên để kiểm tra ngay.");
            tv.setTextSize(13);
            tv.setTextColor(Color.parseColor("#94A3B8"));
            empty.addView(tv);
            rootList.addView(empty);
            return;
        }

        for (SessionStore.SessionRecord rec : sessions) {
            rootList.addView(buildSessionCard(rec));
        }
    }

    private View buildSessionCard(SessionStore.SessionRecord rec) {
        LinearLayout card = makeCard("#1E293B");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        long durMin = Math.max(1, (rec.endMs - rec.startMs) / 60000L);
        String durStr = (durMin >= 60) ? ((durMin / 60) + "g " + (durMin % 60) + "p") : (durMin + " phút");
        String headerTxt = (rec.isManualScan ? "[Quét 24 giờ] " : "[Tắt màn hình] ")
                + timeFmt.format(new Date(rec.endMs)) + "  •  " + durStr;

        TextView head = new TextView(this);
        head.setText(headerTxt);
        head.setTextSize(13);
        head.setTextColor(Color.parseColor("#94A3B8"));
        head.setTypeface(null, Typeface.BOLD);
        card.addView(head);

        if (!rec.isManualScan) {
            int dropPct = rec.startLevel - rec.endLevel;
            int dropMah = rec.startMah - rec.endMah;
            double hrs = Math.max(0.02, (rec.endMs - rec.startMs) / 3600000.0);
            double pctPerHr = dropPct / hrs;
            String statLine;
            String colorHex;
            if (rec.wasCharging) {
                statLine = String.format(Locale.getDefault(),
                        "Đang sạc: %d%% → %d%%  (Nhiệt: %.1f°C → %.1f°C)",
                        rec.startLevel, rec.endLevel, rec.startTemp, rec.endTemp);
                colorHex = "#38BDF8";
            } else {
                statLine = String.format(Locale.getDefault(),
                        "Pin: %d%% → %d%%  (-%d%% | -%d mAh | %.1f%%/giờ)",
                        rec.startLevel, rec.endLevel, Math.max(0, dropPct), Math.max(0, dropMah), Math.max(0, pctPerHr));
                colorHex = (pctPerHr >= 2.5) ? "#F87171" : (pctPerHr >= 1.2 ? "#FBBF24" : "#4ADE80");
            }
            TextView st = new TextView(this);
            st.setText(statLine);
            st.setTextSize(14);
            st.setTypeface(null, Typeface.BOLD);
            st.setTextColor(Color.parseColor(colorHex));
            st.setPadding(0, dp(4), 0, dp(8));
            card.addView(st);
        } else {
            TextView st = new TextView(this);
            st.setText("Tất cả ứng dụng hoạt động nền & dùng mạng trong 24 giờ qua (" + rec.apps.size() + " app)");
            st.setTextSize(13);
            st.setTextColor(Color.parseColor("#60A5FA"));
            st.setPadding(0, dp(4), 0, dp(8));
            card.addView(st);
        }

        if (rec.apps.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("✓ Máy ngủ sạch, không phát hiện app chạy nền đáng kể.");
            none.setTextSize(13);
            none.setTextColor(Color.parseColor("#86EFAC"));
            card.addView(none);
            return card;
        }

        for (final SessionStore.AppDrainItem app : rec.apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(makeRounded("#0F172A", 10));
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(6);
            row.setLayoutParams(rp);

            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView name = new TextView(this);
            name.setText(app.appLabel);
            name.setTextSize(14);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextColor(Color.parseColor("#F8FAFC"));
            left.addView(name);

            StringBuilder detail = new StringBuilder();
            if (app.mobileBytes > 0) detail.append("4G/5G: ").append(fmtBytes(app.mobileBytes)).append("  ");
            if (app.wifiBytes > 0) detail.append("Wi-Fi: ").append(fmtBytes(app.wifiBytes)).append("  ");
            if (app.fgsSeconds > 0) detail.append("Chạy nền: ").append(fmtSec(app.fgsSeconds)).append("  ");
            if (app.eventCount > 0) detail.append("Đánh thức: ").append(app.eventCount).append("x");

            TextView det = new TextView(this);
            det.setText(detail.toString().trim());
            det.setTextSize(11);
            det.setTextColor(Color.parseColor(app.mobileBytes > 200_000 || app.fgsSeconds > 120 ? "#FCA5A5" : "#94A3B8"));
            left.addView(det);

            boolean isBlk = SessionStore.isBlocked(this, app.packageName);
            TextView blkBtn = makeButton(isBlk ? "Đang chặn" : "Chặn mạng",
                    isBlk ? "#991B1B" : "#334155",
                    isBlk ? "#FECACA" : "#E2E8F0");
            blkBtn.setTextSize(11);
            blkBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout.LayoutParams bbp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bbp.rightMargin = dp(5);
            blkBtn.setLayoutParams(bbp);
            blkBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleAppNetworkBlock(app.packageName, app.appLabel);
                }
            });

            TextView stopBtn = makeButton("Tắt", "#7F1D1D", "#FECACA");
            stopBtn.setTextSize(11);
            stopBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
            stopBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + app.packageName));
                    startActivity(i);
                }
            });

            row.addView(left);
            row.addView(blkBtn);
            row.addView(stopBtn);
            card.addView(row);
        }

        return card;
    }

    private String fmtBytes(long b) {
        if (b >= 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", b / (1024.0 * 1024.0));
        return Math.max(1, b / 1024) + " KB";
    }

    private String fmtSec(long s) {
        if (s >= 60) return (s / 60) + "p " + (s % 60) + "s";
        return s + "s";
    }

    private LinearLayout makeCard(String bgHex) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackground(makeRounded(bgHex, 14));
        int p = dp(14);
        ll.setPadding(p, p, p, p);
        return ll;
    }

    private TextView makeButton(String text, String bgHex, String textHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.parseColor(textHex));
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(makeRounded(bgHex, 10));
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        return tv;
    }

    private GradientDrawable makeRounded(String hex, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(hex));
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
