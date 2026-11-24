const { createTimerPreferences } = require('../../utils/timerPreferences');
const { createFeedbackPlayer } = require('../../utils/feedbackPlayer');
const { createBleController } = require('../../utils/bleController');
const { createShutterController } = require('../../utils/shutterController');

const TIMER_PRESETS = [2, 3, 5, 10, 15, 30];
const BURST_COUNT_PRESETS = [2, 3, 5, 10, 20];
const INTERVAL_PRESETS = [1, 2, 3, 5, 10, 30];

function buildBluetoothSvgUri(color) {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16" fill="none">` +
    `<path d="M4.16 4.48L12.16 11.52L8 14.72V1.28L12.16 4.48L4.16 11.52" ` +
    `stroke="${color}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>` +
    `</svg>`;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

function buildSliderSvgUri(color) {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16" fill="none">` +
    `<line x1="1.28" y1="4.8" x2="3.34" y2="4.8" stroke="${color}" stroke-width="1.5" stroke-linecap="round"/>` +
    `<line x1="7.54" y1="4.8" x2="14.72" y2="4.8" stroke="${color}" stroke-width="1.5" stroke-linecap="round"/>` +
    `<circle cx="5.44" cy="4.8" r="2.1" fill="${color}"/>` +
    `<line x1="1.28" y1="11.2" x2="8.46" y2="11.2" stroke="${color}" stroke-width="1.5" stroke-linecap="round"/>` +
    `<line x1="12.66" y1="11.2" x2="14.72" y2="11.2" stroke="${color}" stroke-width="1.5" stroke-linecap="round"/>` +
    `<circle cx="10.56" cy="11.2" r="2.1" fill="${color}"/>` +
    `</svg>`;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

function resolveBluetoothVisual(connectionState) {
  switch (connectionState.type) {
    case 'connected':
      return {
        dotColor: '#34D399',
        iconColor: '#6EE7B7',
        label: `已连接 · ${connectionState.cameraName}`
      };
    case 'connecting':
      return {
        dotColor: '#F59E0B',
        iconColor: '#FCD34D',
        label: `连接中 · ${connectionState.cameraName}`
      };
    case 'pairing':
      return {
        dotColor: '#F59E0B',
        iconColor: '#FCD34D',
        label: '请在相机屏幕确认配对...'
      };
    case 'error':
      return {
        dotColor: '#EF4444',
        iconColor: '#FCA5A5',
        label: connectionState.message || '蓝牙异常'
      };
    default:
      return {
        dotColor: '#6B7280',
        iconColor: '#9CA3AF',
        label: '未连接索尼相机 · 点击配对'
      };
  }
}

function computeSafeLayout() {
  let topSafePx = 56;
  let bottomSafePx = 28;
  try {
    const menuRect =
      typeof wx.getMenuButtonBoundingClientRect === 'function'
        ? wx.getMenuButtonBoundingClientRect()
        : null;
    const winInfo =
      typeof wx.getWindowInfo === 'function'
        ? wx.getWindowInfo()
        : typeof wx.getSystemInfoSync === 'function'
          ? wx.getSystemInfoSync()
          : null;

    if (menuRect && menuRect.bottom) {
      topSafePx = Math.round(menuRect.bottom + 14);
    } else if (winInfo && winInfo.statusBarHeight) {
      topSafePx = Math.round(winInfo.statusBarHeight + 52);
    }

    if (winInfo && winInfo.safeArea && winInfo.screenHeight) {
      const insetBottom = Math.max(0, winInfo.screenHeight - winInfo.safeArea.bottom);
      bottomSafePx = Math.round(insetBottom + 28);
    }
  } catch (_err) {
    // 使用默认安全距离
  }
  return { topSafePx, bottomSafePx };
}

Page({
  data: {
    topSafePx: 56,
    bottomSafePx: 28,

    connectionType: 'disconnected',
    connectedDeviceId: '',
    phaseType: 'idle',
    remainingSeconds: 0,
    totalSeconds: 1,
    currentShot: 1,
    totalShots: 1,
    isInterval: false,

    configuredSeconds: 5,
    burstEnabled: false,
    burstCount: 3,
    burstIntervalSeconds: 2,

    showCameraPicker: false,
    showTimerSheet: false,
    discoveredCameras: [],
    isScanning: false,
    statusHint: '',

    btDotColor: '#6B7280',
    btLabel: '未连接索尼相机 · 点击配对',
    btIconSrc: buildBluetoothSvgUri('#9CA3AF'),

    configBarEnabled: true,
    configLabel: '单拍 · 5s',
    configBorderColor: 'rgba(255, 255, 255, 0.08)',
    sliderIconSrc: buildSliderSvgUri('#9CA3AF'),

    timerPresets: TIMER_PRESETS,
    burstCountPresets: BURST_COUNT_PRESETS,
    intervalPresets: INTERVAL_PRESETS
  },

  onLoad() {
    const { topSafePx, bottomSafePx } = computeSafeLayout();
    this.setData({ topSafePx, bottomSafePx });

    const preferences = createTimerPreferences(wx);
    const feedbackPlayer = createFeedbackPlayer(wx);

    let controller = null;
    const bleController = createBleController({
      wxApi: wx,
      preferences,
      onStateChange: (state) => {
        if (controller) controller.handleBleStateChange(state);
      },
      onCamerasChange: (cameras) => {
        if (controller) controller.handleCamerasChange(cameras);
      },
      onScanningChange: (scanning) => {
        if (controller) controller.handleScanningChange(scanning);
      }
    });

    controller = createShutterController({
      bleController,
      feedbackPlayer,
      preferences,
      setKeepScreenOn: (keep) => {
        try {
          if (typeof wx.setKeepScreenOn === 'function') {
            wx.setKeepScreenOn({ keepScreenOn: Boolean(keep) });
          }
        } catch (_err) {
          // 忽略
        }
      },
      onUiStateChange: (uiState) => this._syncUiState(uiState)
    });

    this._controller = controller;
    controller.emit();
  },

  onUnload() {
    if (this._controller) {
      this._controller.destroy();
      this._controller = null;
    }
  },

  _syncUiState(uiState) {
    const btVisual = resolveBluetoothVisual(uiState.connectionState);
    const configBarEnabled =
      uiState.phase.type !== 'counting_down' &&
      uiState.phase.type !== 'triggering';

    const configLabel = uiState.burstEnabled
      ? `连拍 · ${uiState.configuredSeconds}s · ${uiState.burstCount}张/${uiState.burstIntervalSeconds}s`
      : `单拍 · ${uiState.configuredSeconds}s`;

    const sliderBaseColor = uiState.burstEnabled ? '#FCA5A5' : '#9CA3AF';
    const sliderAlpha = configBarEnabled ? 1 : 0.45;
    const sliderColor =
      sliderAlpha < 1
        ? uiState.burstEnabled
          ? 'rgba(252, 165, 165, 0.45)'
          : 'rgba(156, 163, 175, 0.45)'
        : sliderBaseColor;

    const configBorderColor =
      uiState.burstEnabled && configBarEnabled
        ? 'rgba(248, 113, 113, 0.28)'
        : 'rgba(255, 255, 255, 0.08)';

    this.setData({
      connectionType: uiState.connectionState.type,
      connectedDeviceId: uiState.connectionState.deviceId || '',
      phaseType: uiState.phase.type,
      remainingSeconds: uiState.phase.remainingSeconds || 0,
      totalSeconds: uiState.phase.totalSeconds || 1,
      currentShot: uiState.phase.currentShot || 1,
      totalShots: uiState.phase.totalShots || 1,
      isInterval: Boolean(uiState.phase.isInterval),

      configuredSeconds: uiState.configuredSeconds,
      burstEnabled: uiState.burstEnabled,
      burstCount: uiState.burstCount,
      burstIntervalSeconds: uiState.burstIntervalSeconds,

      showCameraPicker: uiState.showCameraPicker,
      showTimerSheet: uiState.showTimerSheet,
      discoveredCameras: uiState.discoveredCameras,
      isScanning: uiState.isScanning,
      statusHint: uiState.statusHint || '',

      btDotColor: btVisual.dotColor,
      btLabel: btVisual.label,
      btIconSrc: buildBluetoothSvgUri(btVisual.iconColor),

      configBarEnabled,
      configLabel,
      configBorderColor,
      sliderIconSrc: buildSliderSvgUri(sliderColor)
    });
  },

  noop() {},

  onMainButtonTap() {
    this._controller && this._controller.onMainButtonClick();
  },

  onOpenCameraPicker() {
    this._controller && this._controller.openCameraPicker();
  },

  onDismissCameraPicker() {
    this._controller && this._controller.dismissCameraPicker();
  },

  onRefreshScan() {
    this._controller && this._controller.refreshScan();
  },

  onSelectCamera(e) {
    const idx = Number(e.currentTarget.dataset.index);
    const cam = this.data.discoveredCameras[idx];
    if (cam && this._controller) {
      this._controller.onSelectCamera(cam);
    }
  },

  onDisconnectCamera() {
    this._controller && this._controller.onDisconnectCamera();
  },

  onOpenTimerSheet() {
    this._controller && this._controller.openTimerSheet();
  },

  onDismissTimerSheet() {
    this._controller && this._controller.dismissTimerSheet();
  },

  onStepTimer(e) {
    const delta = Number(e.currentTarget.dataset.delta) || 0;
    if (!this._controller) return;
    this._controller.updateTimerSeconds(this.data.configuredSeconds + delta);
  },

  onSelectTimerPreset(e) {
    const val = Number(e.currentTarget.dataset.value);
    if (this._controller && Number.isFinite(val)) {
      this._controller.updateTimerSeconds(val);
    }
  },

  onToggleBurst() {
    if (!this._controller) return;
    this._controller.updateBurstEnabled(!this.data.burstEnabled);
  },

  onStepBurstCount(e) {
    const delta = Number(e.currentTarget.dataset.delta) || 0;
    if (!this._controller) return;
    this._controller.updateBurstCount(this.data.burstCount + delta);
  },

  onSelectBurstCountPreset(e) {
    const val = Number(e.currentTarget.dataset.value);
    if (this._controller && Number.isFinite(val)) {
      this._controller.updateBurstCount(val);
    }
  },

  onStepBurstInterval(e) {
    const delta = Number(e.currentTarget.dataset.delta) || 0;
    if (!this._controller) return;
    this._controller.updateBurstIntervalSeconds(
      this.data.burstIntervalSeconds + delta
    );
  },

  onSelectIntervalPreset(e) {
    const val = Number(e.currentTarget.dataset.value);
    if (this._controller && Number.isFinite(val)) {
      this._controller.updateBurstIntervalSeconds(val);
    }
  }
});
