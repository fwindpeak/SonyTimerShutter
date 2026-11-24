# SonyTimerShutter 微信小程序

基于原生微信小程序（零构建依赖，Canvas 2D + 微信小程序 BLE API）实现的索尼相机定时/连拍快门遥控器，完整复刻了 [SonyTimerShutter Android 版](../README.md) 的视觉设计、声学/触觉反馈与蓝牙遥控协议。

## 目录结构

```text
wechat-miniprogram/
├── app.js / app.json / app.wxss / project.config.json / sitemap.json
├── assets/sounds/                  # 实时合成导出的 5 个低延迟 WAV 音效
├── components/mechanical-button/   # Canvas 2D 工业复古机械大按钮组件
├── pages/index/                    # 主页面（蓝牙状态条、机械快门、参数弹窗、相机列表）
├── utils/
│   ├── sonyBleProtocol.js          # 索尼 BLE 广播解析、UUID 匹配、快门/通知指令
│   ├── bleController.js            # 微信小程序 BLE 扫描、配对连接、四步快门时序控制
│   ├── shutterController.js        # 倒计时/连拍状态机与亮屏保活管理
│   ├── timerPreferences.js         # wx.setStorageSync 本地参数与最近相机持久化
│   └── feedbackPlayer.js           # InnerAudioContext 音效 + vibrateShort 震动反馈
└── tests/unit.test.mjs             # Node.js 单元测试
```

## 在微信开发者工具中运行

1. 打开 **微信开发者工具** → 选择「导入项目」。
2. 项目目录选择本仓库下的 `wechat-miniprogram/` 目录。
3. 测试阶段可使用 `project.config.json` 中预设的 `touristappid`（或替换为自己的正式 AppID）。
4. **必须使用真机调试**：微信开发者工具的电脑模拟器不支持 BLE 蓝牙外设扫描与 GATT 通信，请点击工具栏 **「真机调试」** 或 **「预览」** 在手机微信中运行。

## 索尼 A7C II 相机端设置

1. `MENU → 网络 → 蓝牙 → 蓝牙功能 → 开`
2. `MENU → 网络 → 传输/远程 → 蓝牙遥控 → 开`
3. 首次连接时进入 `MENU → 网络 → 蓝牙 → 配对`，在小程序里点击对应相机后，于 10 秒内在相机屏幕按「确定」。
4. 如曾开启手机定位同步，建议关闭「蓝牙位置信息关联」（部分索尼机型与蓝牙遥控互斥）。

## 微信小程序双端使用说明

- **iOS 首次配对**：iOS 不支持 `wx.makeBluetoothPair`，当小程序连接并开启 `0xFF02` 通知时，iOS 系统会自动弹出配对确认框；如弹窗前出现失败提示，请确认相机停留在「蓝牙 → 配对」界面后重试一次。
- **Android 蓝牙扫描**：部分安卓机型在微信小程序内扫描 BLE 需要同时开启 **系统定位（GPS）开关** 与微信位置权限。若个别安卓 ROM 无法通过小程序弹窗完成首次配对，可先在「手机系统设置 → 蓝牙」中与相机配对一次，再回到小程序连接。
- **屏幕常亮限制**：微信小程序切后台或熄屏后 JS 定时器会被挂起，因此本小程序在倒计时和连拍过程中会自动调用 `wx.setKeepScreenOn` 保持屏幕常亮。请勿在长间隔连拍期间切出微信或手动锁屏。

## 运行单元测试

在项目根目录执行（需要 Node.js 20+，无需额外依赖）：

```bash
node --test wechat-miniprogram/tests/unit.test.mjs
```
