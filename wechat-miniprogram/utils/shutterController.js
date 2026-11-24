const { LIMITS, clampInt } = require('./timerPreferences');

function waitMs(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function createShutterController({
  bleController,
  feedbackPlayer,
  preferences,
  setKeepScreenOn = () => {},
  onUiStateChange = () => {},
  delayFn = waitMs
}) {
  let activeRunId = 0;
  let hintTimer = null;
  let cancelBurstAfterCurrentShot = false;
  let destroyed = false;

  const uiState = {
    connectionState: bleController.getConnectionState
      ? bleController.getConnectionState()
      : { type: 'disconnected' },
    phase: { type: 'idle' },
    configuredSeconds: preferences.getTimerSeconds(5),
    burstEnabled: preferences.isBurstEnabled(false),
    burstCount: preferences.getBurstCount(3),
    burstIntervalSeconds: preferences.getBurstIntervalSeconds(2),
    showCameraPicker: false,
    showTimerSheet: false,
    discoveredCameras: [],
    isScanning: false,
    statusHint: null
  };

  function emit() {
    if (destroyed) return;
    onUiStateChange({
      ...uiState,
      connectionState: { ...uiState.connectionState },
      phase: { ...uiState.phase },
      discoveredCameras: uiState.discoveredCameras.slice()
    });
  }

  function showTemporaryHint(message) {
    if (hintTimer) {
      clearTimeout(hintTimer);
      hintTimer = null;
    }
    uiState.statusHint = message;
    emit();
    hintTimer = setTimeout(() => {
      hintTimer = null;
      if (destroyed) return;
      uiState.statusHint = null;
      emit();
    }, 2200);
  }

  function isBusyPhase(phase = uiState.phase) {
    return phase.type === 'counting_down' || phase.type === 'triggering';
  }

  function cancelCountdown(silent = false) {
    const prevPhase = uiState.phase;
    activeRunId += 1;
    cancelBurstAfterCurrentShot = false;
    uiState.phase = { type: 'idle' };
    setKeepScreenOn(false);
    emit();

    if (!silent) {
      feedbackPlayer.playCancel();
      if (prevPhase.type === 'counting_down' && prevPhase.isInterval) {
        const completed = Math.max(0, (prevPhase.currentShot || 1) - 1);
        showTemporaryHint(`已停止连拍 (${completed}/${prevPhase.totalShots})`);
      } else {
        showTemporaryHint('已取消倒计时');
      }
    }
  }

  async function startCountdown() {
    const initialDelay = uiState.configuredSeconds;
    const totalShots = uiState.burstEnabled ? uiState.burstCount : 1;
    const intervalSeconds = uiState.burstIntervalSeconds;

    activeRunId += 1;
    const myRunId = activeRunId;
    cancelBurstAfterCurrentShot = false;

    feedbackPlayer.playMechanicalPress();
    setKeepScreenOn(true);

    try {
      for (let remaining = initialDelay; remaining >= 1; remaining -= 1) {
        if (destroyed || activeRunId !== myRunId) return;
        uiState.phase = {
          type: 'counting_down',
          remainingSeconds: remaining,
          totalSeconds: initialDelay,
          currentShot: 1,
          totalShots,
          isInterval: false
        };
        emit();
        feedbackPlayer.playCountdownTick(remaining === 1);
        await delayFn(1000);
        if (destroyed || activeRunId !== myRunId) return;
      }

      for (let shot = 1; shot <= totalShots; shot += 1) {
        if (destroyed || activeRunId !== myRunId) return;

        if (shot > 1) {
          for (let remaining = intervalSeconds; remaining >= 1; remaining -= 1) {
            if (destroyed || activeRunId !== myRunId) return;
            uiState.phase = {
              type: 'counting_down',
              remainingSeconds: remaining,
              totalSeconds: intervalSeconds,
              currentShot: shot,
              totalShots,
              isInterval: true
            };
            emit();
            feedbackPlayer.playCountdownTick(remaining === 1);
            await delayFn(1000);
            if (destroyed || activeRunId !== myRunId) return;
          }
        }

        uiState.phase = {
          type: 'triggering',
          currentShot: shot,
          totalShots
        };
        emit();
        feedbackPlayer.playShutterTrigger();

        const result = await bleController.triggerShutter();
        if (destroyed || activeRunId !== myRunId) return;

        if (!result || !result.ok) {
          const reason =
            (result && result.error) || '快门发送失败，请检查相机蓝牙遥控开关';
          showTemporaryHint(
            totalShots > 1 ? `第 ${shot}/${totalShots} 张失败：${reason}` : reason
          );
          uiState.phase = { type: 'idle' };
          setKeepScreenOn(false);
          emit();
          return;
        }

        if (cancelBurstAfterCurrentShot && shot < totalShots) {
          feedbackPlayer.playCancel();
          showTemporaryHint(`已停止连拍 (${shot}/${totalShots})`);
          uiState.phase = { type: 'idle' };
          setKeepScreenOn(false);
          emit();
          return;
        }
      }

      uiState.phase = { type: 'shot_success', totalShots };
      setKeepScreenOn(false);
      emit();
      showTemporaryHint(
        totalShots > 1 ? `连拍完成 (${totalShots}张)` : '快门已触发'
      );

      await delayFn(650);
      if (destroyed || activeRunId !== myRunId) return;
      uiState.phase = { type: 'idle' };
      emit();
    } catch (_err) {
      if (!destroyed && activeRunId === myRunId) {
        uiState.phase = { type: 'idle' };
        setKeepScreenOn(false);
        emit();
      }
    }
  }

  function openCameraPicker() {
    uiState.showCameraPicker = true;
    emit();
    bleController.startScan();
  }

  function dismissCameraPicker() {
    uiState.showCameraPicker = false;
    emit();
    bleController.stopScan();
  }

  function refreshScan() {
    Promise.resolve(bleController.stopScan()).then(() => {
      if (!destroyed) {
        bleController.startScan();
      }
    });
  }

  function onSelectCamera(camera) {
    bleController.stopScan();
    uiState.showCameraPicker = false;
    emit();
    bleController.connect(camera);
  }

  function onDisconnectCamera() {
    cancelCountdown(true);
    bleController.disconnect();
    uiState.showCameraPicker = false;
    emit();
  }

  function openTimerSheet() {
    if (isBusyPhase()) return;
    uiState.showTimerSheet = true;
    emit();
  }

  function dismissTimerSheet() {
    uiState.showTimerSheet = false;
    emit();
  }

  function updateTimerSeconds(seconds) {
    const clamped = clampInt(
      seconds,
      LIMITS.MIN_TIMER_SECONDS,
      LIMITS.MAX_TIMER_SECONDS,
      5
    );
    uiState.configuredSeconds = clamped;
    preferences.setTimerSeconds(clamped);
    emit();
  }

  function updateBurstEnabled(enabled) {
    const next = Boolean(enabled);
    uiState.burstEnabled = next;
    preferences.setBurstEnabled(next);
    emit();
  }

  function updateBurstCount(count) {
    const clamped = clampInt(
      count,
      LIMITS.MIN_BURST_COUNT,
      LIMITS.MAX_BURST_COUNT,
      3
    );
    uiState.burstCount = clamped;
    preferences.setBurstCount(clamped);
    emit();
  }

  function updateBurstIntervalSeconds(seconds) {
    const clamped = clampInt(
      seconds,
      LIMITS.MIN_BURST_INTERVAL_SECONDS,
      LIMITS.MAX_BURST_INTERVAL_SECONDS,
      2
    );
    uiState.burstIntervalSeconds = clamped;
    preferences.setBurstIntervalSeconds(clamped);
    emit();
  }

  function onMainButtonClick() {
    const phase = uiState.phase;
    if (phase.type === 'counting_down') {
      cancelCountdown(false);
      return;
    }
    if (phase.type === 'triggering') {
      if (phase.totalShots > 1 && phase.currentShot < phase.totalShots) {
        cancelBurstAfterCurrentShot = true;
        showTemporaryHint('正在停止连拍...');
      }
      return;
    }

    if (uiState.connectionState.type !== 'connected') {
      feedbackPlayer.playMechanicalPress();
      openCameraPicker();
      return;
    }

    startCountdown();
  }

  function handleBleStateChange(nextConnState) {
    uiState.connectionState = nextConnState;
    if (nextConnState.type !== 'connected' && isBusyPhase()) {
      cancelCountdown(true);
      if (nextConnState.type === 'error' && nextConnState.message) {
        showTemporaryHint(nextConnState.message);
      }
      return;
    }
    emit();
  }

  function handleCamerasChange(cameras) {
    uiState.discoveredCameras = cameras;
    emit();
  }

  function handleScanningChange(scanning) {
    uiState.isScanning = scanning;
    emit();
  }

  function destroy() {
    destroyed = true;
    activeRunId += 1;
    if (hintTimer) {
      clearTimeout(hintTimer);
      hintTimer = null;
    }
    setKeepScreenOn(false);
    bleController.release();
    feedbackPlayer.release();
  }

  return {
    getState: () => ({ ...uiState }),
    emit,
    onMainButtonClick,
    openCameraPicker,
    dismissCameraPicker,
    refreshScan,
    onSelectCamera,
    onDisconnectCamera,
    openTimerSheet,
    dismissTimerSheet,
    updateTimerSeconds,
    updateBurstEnabled,
    updateBurstCount,
    updateBurstIntervalSeconds,
    handleBleStateChange,
    handleCamerasChange,
    handleScanningChange,
    destroy
  };
}

module.exports = {
  createShutterController
};
