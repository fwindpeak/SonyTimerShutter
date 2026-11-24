# SonyTimerShutter (KMP / Android)

面向索尼相机（适配 **Sony A7C II / ILCE-7CM2** 及同 BLE 遥控协议机型）的极简定时拍照 App，基于 **Kotlin Multiplatform + Compose Multiplatform** 构建。

## 核心交互
- **界面极简**：顶部为克制的**蓝牙状态胶囊**与**实时预览胶囊**，未开启预览时中央为全尺寸**工业感复古机械大按钮**，底部为对称的**拍摄参数胶囊**。
- **Wi-Fi 实时预览（远距离自拍）**：点击顶部「实时预览」胶囊，支持通过 **Wi-Fi Direct** 或 **同一局域网 / 手机热点** 对接相机 **PTP/IP 3.0** 协议拉取实时取景画面（支持「SSH 访问认证」隧道与「访问认证=关」免密直连 15740 两种模式）。预览模式下自动切换为取景框 + 水平「镜像」开关 + 超大倒计时数字 + 紧凑机械快门，且优先通过 Wi-Fi PTP/IP 触发快门；同时完整保留纯蓝牙盲拍模式。
- **定时拍照（默认 5 秒）**：按下主按钮后仅在手机端每秒发出一次电子倒计时滴声（不提前发蓝牙/快门指令），倒计时归零瞬间再向索尼相机发送完整快门序列。
- **中途取消**：倒计时或连拍间隔过程中再次短按主按钮，立即取消或停止后续连拍。
- **拍摄参数调节**：点击底部**参数胶囊**（单拍 / 连拍状态一目了然）呼出设置面板，支持倒计时 `2s / 3s / 5s / 10s / 15s / 30s` 快捷档位与 `1~60s` 逐秒微调；开启**连拍模式**后还可设置连拍张数（`2~99张`）与每张间隔时间（`1~60s`），修改自动持久化。
- **蓝牙配对入口**：点击顶部蓝牙状态条（或未连接时直接点主按钮）弹出索尼相机扫描与连接列表。

## 索尼 A7C II 相机端设置
### 方式 A：纯蓝牙定时快门（盲拍，最省电）
按 [Sony 官方蓝牙遥控支持文档](https://www.sony.com/electronics/support/articles/00266597) 配置：
1. `MENU → 网络 → 蓝牙 → 蓝牙功能 → 开`
2. `MENU → 网络 → 传输/远程 → 蓝牙遥控 → 开`
3. 首次连接时进入 `MENU → 网络 → 蓝牙 → 配对`，手机点选相机后 10 秒内在相机屏幕按「确定」。
4. 若之前开启了手机定位同步，建议先关闭「蓝牙位置信息关联」（索尼部分机型与蓝牙遥控互斥，参考 [Staacks/alpharemote](https://github.com/staacks/alpharemote)）。

### 方式 B：Wi-Fi 实时预览 + 定时快门（远距离自拍推荐，Android 版）
基于索尼 **Camera Control PTP 3.0 over IP** 协议（参考 [jakkuh/sony-alpha-python](https://github.com/jakkuh/sony-alpha-python)）：
1. 确保手机未同时连接官方 Creators' App（否则相机会拒绝第二个远程会话）。
2. 在相机端开启：`MENU → 网络 → 连接/PC 遥控 → PC 遥控功能 → PC 遥控 → 开`。
3. 选择以下任一无线组网方式：
   - **户外无路由（Wi-Fi Direct）**：在 `PC 遥控功能 → Wi-Fi Direct 信息` 查看热点密码并让手机连上相机 Wi-Fi，默认相机 IP 为 `192.168.122.1`。
   - **手机热点 / 路由器局域网**：在 `MENU → 网络 → Wi-Fi → 接入点设置` 让相机连入手机热点或同一路由器，App 内可一键填入网关 IP 或手动输入相机 IP。
4. 鉴权方式二选一：
   - **默认 SSH 安全模式**：保留相机 `MENU → 网络 → 网络选项 → 访问认证设置 → 访问认证 → 开`，在 `访问认证信息` 中查看用户名与密码并填入 App。
   - **免密直连模式**：将 `访问认证` 设为 `关`，在 App 中关闭「SSH 访问认证」开关即可直连端口 `15740`。

## BLE 协议参考
- 主服务 UUID：`8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF`
- 指令特征值：`0xFF01`；通知特征值：`0xFF02`（[HYPOXIC](https://gethypoxic.com/blogs/technical/sony-camera-ble-control-protocol-di-remote-control)）
- 快门四步序列：`0x01 0x07`（半按）→ `0x01 0x09`（全按）→ `0x01 0x08`（松开全按）→ `0x01 0x06`（松开半按）（[coral/freemote](https://github.com/coral/freemote)）

## 打开与运行
1. 解压后使用 Android Studio（Ladybug 或更新版本，JDK 17）直接 Open 根目录。
2. 等待 Gradle Sync 完成（仓库已附带 Gradle Wrapper，可直接使用 `./gradlew` 构建）。
3. 连接安卓真机（需实体蓝牙，模拟器不支持 BLE）运行 `composeApp`。

## GitHub Actions 自动编译与发布 Release
仓库已配置 [`.github/workflows/release.yml`](.github/workflows/release.yml)，支持自动编译可安装的 Release APK 并创建 GitHub Release：

- **方式一：推送版本 Tag 自动发布**
  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```
  Tag 带 `-`（例如 `v1.0.0-beta.1`）时会自动标记为 Pre-release。
- **方式二：手动触发**
  在 GitHub 仓库 `Actions → Release Android APK → Run workflow` 中输入版本号（如 `v1.0.0`）触发构建与发版。

### 正式签名配置（可选）
若未配置签名密钥，CI 会自动回退为 Debug 签名（可直接安装调试）。如需正式签名发布，请在 GitHub 仓库 `Settings → Secrets and variables → Actions` 中添加以下 4 个 Secrets：
- `ANDROID_KEYSTORE_BASE64`：Keystore 文件的 Base64 编码（`base64 -i release.keystore`）
- `ANDROID_KEYSTORE_PASSWORD`：Keystore 密码
- `ANDROID_KEY_ALIAS`：Key Alias
- `ANDROID_KEY_PASSWORD`：Key 密码

## 微信小程序版本
仓库同时提供了零构建依赖的原生微信小程序实现（支持 iOS / Android 微信真机调试），详见 [wechat-miniprogram/README.md](wechat-miniprogram/README.md)。
