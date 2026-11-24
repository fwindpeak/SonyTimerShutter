# SonyTimerShutter (KMP / Android)

面向索尼相机（适配 **Sony A7C II / ILCE-7CM2** 及同 BLE 遥控协议机型）的极简定时拍照 App，基于 **Kotlin Multiplatform + Compose Multiplatform** 构建。

## 核心交互
- **界面极简**：全屏仅保留中央的**工业感复古机械大按钮** + 顶部克制的**蓝牙连接状态图标与文字提醒**。
- **定时拍照（默认 5 秒）**：按下按钮后仅在手机端每秒发出一次电子倒计时滴声（不提前发蓝牙指令），倒计时归零瞬间再通过 BLE 向索尼相机发送完整快门序列。
- **中途取消**：倒计时过程中再次短按主按钮，立即取消倒计时。
- **隐藏式修改秒数**：**长按主按钮**（约 0.6 秒）呼出底部秒数设定面板，支持 `2s / 3s / 5s / 10s / 15s / 30s` 快捷档位与 `1~60s` 逐秒微调，修改自动持久化。
- **蓝牙配对入口**：点击顶部蓝牙状态条（或未连接时直接点主按钮）弹出索尼相机扫描与连接列表。

## 索尼 A7C II 相机端设置
按 [Sony 官方蓝牙遥控支持文档](https://www.sony.com/electronics/support/articles/00266597) 配置：
1. `MENU → 网络 → 蓝牙 → 蓝牙功能 → 开`
2. `MENU → 网络 → 传输/远程 → 蓝牙遥控 → 开`
3. 首次连接时进入 `MENU → 网络 → 蓝牙 → 配对`，手机点选相机后 10 秒内在相机屏幕按「确定」。
4. 若之前开启了手机定位同步，建议先关闭「蓝牙位置信息关联」（索尼部分机型与蓝牙遥控互斥，参考 [Staacks/alpharemote](https://github.com/staacks/alpharemote)）。

## BLE 协议参考
- 主服务 UUID：`8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF`
- 指令特征值：`0xFF01`；通知特征值：`0xFF02`（[HYPOXIC](https://gethypoxic.com/blogs/technical/sony-camera-ble-control-protocol-di-remote-control)）
- 快门四步序列：`0x01 0x07`（半按）→ `0x01 0x09`（全按）→ `0x01 0x08`（松开全按）→ `0x01 0x06`（松开半按）（[coral/freemote](https://github.com/coral/freemote)）

## 打开与运行
1. 解压后使用 Android Studio（Ladybug 或更新版本，JDK 17）直接 Open 根目录。
2. 等待 Gradle Sync 完成（工程未附带 `gradle-wrapper.jar` 二进制，Android Studio 会自动使用本地 Gradle / Wrapper 同步）。
3. 连接安卓真机（需实体蓝牙，模拟器不支持 BLE）运行 `composeApp`。
