# 耳机弹窗 · EarbudsPopup2

Android 系统级蓝牙耳机弹窗（Kotlin 版）。

- 蓝牙耳机连接时，以 `TYPE_APPLICATION_OVERLAY` 系统级圆角弹窗展示**真实**左右耳 / 充电仓电量
- 支持自定义图片 / GIF、圆角毛玻璃面板、宽高与位置可调、横竖屏两套布局、多种出入场动画
- 双前台服务互拉 + 开机/心跳/静态广播兜底保活，通知渠道可静默
- 通过 **Stellar / Shizuku**（类 Shizuku ADB 权限代理）实现一键授权

构建：GitHub Actions 自动编译 Debug / Release APK，见 Actions 页面的 artifacts。
