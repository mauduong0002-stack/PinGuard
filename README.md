# PinGuard

Ứng dụng Android siêu nhẹ (~32 KB, viết thuần Android SDK Java, 0 quảng cáo, 0 theo dõi) giúp:
- **Theo dõi hao pin khi tắt màn hình:** Tự động chụp mốc pin (`%`, `mAh`, nhiệt độ) lúc khóa/mở màn hình và liệt kê các app chạy nền, dùng 4G/5G/Wi-Fi, đánh thức máy trong phiên tắt màn hình.
- **Quét nhanh 24 giờ gần nhất:** Thống kê tới 50 app hoạt động nền & tiêu thụ mạng để quản lý và tắt nhanh.
- **Chặn mạng riêng từng app (Blackhole VPN — 0% hao pin):** Chỉ đẩy những app được chọn vào hố đen nội bộ (`10.255.255.1`), toàn bộ app còn lại đi mạng thẳng 100%; tự bảo vệ các gói lõi hệ thống `UID < 10000`.
- **Hỗ trợ bật/tắt AdGuard DNS (`dns.adguard-dns.com` ↔ `dns.google`)** để chặn quảng cáo toàn máy.

## Cấp quyền 1 lần qua ADB
```bash
adb shell appops set com.pinguard.screenoff ACCESS_RESTRICTED_SETTINGS allow
adb shell appops set com.pinguard.screenoff GET_USAGE_STATS allow
adb shell dumpsys deviceidle whitelist +com.pinguard.screenoff
```
