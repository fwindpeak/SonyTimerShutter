const {
  SERVICE_REMOTE_CONTROL_UUID,
  CHAR_REMOTE_COMMAND_UUID,
  CHAR_REMOTE_COMMAND_SHORT,
  CHAR_REMOTE_NOTIFY_UUID,
  CHAR_REMOTE_NOTIFY_SHORT,
  CMD_SHUTTER_HALF_DOWN,
  CMD_SHUTTER_FULL_DOWN,
  CMD_SHUTTER_FULL_UP,
  CMD_SHUTTER_HALF_UP,
  bytesToArrayBuffer,
  matchesUuid,
  parseAdvertisData,
  parseNotify,
  isLikelySonyCameraName,
  formatDeviceId
} = require('./sonyBleProtocol');

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function wxCall(wxApi, method, options = {}) {
  return new Promise((resolve, reject) => {
    if (!wxApi || typeof wxApi[method] !== 'function') {
      reject(new Error(`wx.${method} 不可用`));
      return;
    }
    wxApi[method]({
      ...options,
      success: (res) => resolve(res),
      fail: (err) => reject(err)
    });
  });
}

function getPlatform(wxApi) {
  try {
    if (wxApi && typeof wxApi.getDeviceInfo === 'function') {
      const info = wxApi.getDeviceInfo();
      if (info && info.platform) return String(info.platform).toLowerCase();
    }
    if (wxApi && typeof wxApi.getSystemInfoSync === 'function') {
      const info = wxApi.getSystemInfoSync();
      if (info && info.platform) return String(info.platform).toLowerCase();
    }
  } catch (_err) {
    // 忽略
  }
  return 'unknown';
}

function translateAdapterError(err) {
  const code = err && (err.errCode !== undefined ? err.errCode : err.errno);
  const msg = (err && err.errMsg ? String(err.errMsg) : '').toLowerCase();
  if (code === 10001 || msg.includes('not available')) {
    return '请先打开手机蓝牙';
  }
  if (msg.includes('auth') || msg.includes('permission') || msg.includes('denied')) {
    return '请授予微信蓝牙权限（安卓需开启定位）';
  }
  if (msg.includes('location')) {
    return '安卓扫描蓝牙需开启系统定位开关';
  }
  return (err && err.errMsg) || '蓝牙初始化失败';
}

function createBleController({
  wxApi = typeof wx !== 'undefined' ? wx : null,
  preferences,
  onStateChange = () => {},
  onCamerasChange = () => {},
  onScanningChange = () => {}
}) {
  let connectionState = { type: 'disconnected' };
  let isScanning = false;
  let released = false;
  let adapterOpened = false;
  let listenersBound = false;

  const cameraCache = new Map();

  let activeDeviceId = null;
  let activeCameraName = null;
  let activeServiceId = null;
  let commandCharId = null;
  let notifyCharId = null;
  let writeQueue = Promise.resolve();
  let focusWaiterResolve = null;

  function setConnectionState(next) {
    connectionState = next;
    onStateChange(next);
  }

  function setScanning(next) {
    if (isScanning === next) return;
    isScanning = next;
    onScanningChange(next);
  }

  function publishCameras() {
    const list = Array.from(cameraCache.values()).sort((a, b) => {
      if (a.isPairingMode !== b.isPairingMode) return a.isPairingMode ? -1 : 1;
      if (a.isBonded !== b.isBonded) return a.isBonded ? -1 : 1;
      if (a.isCached !== b.isCached) return a.isCached ? -1 : 1;
      return (b.rssi || -127) - (a.rssi || -127);
    });
    onCamerasChange(list);
  }

  function clearActiveHandles() {
    if (focusWaiterResolve) {
      const resolve = focusWaiterResolve;
      focusWaiterResolve = null;
      resolve(false);
    }
    activeDeviceId = null;
    activeCameraName = null;
    activeServiceId = null;
    commandCharId = null;
    notifyCharId = null;
  }

  async function closeActiveConnection() {
    const targetId = activeDeviceId;
    clearActiveHandles();
    if (targetId && wxApi && typeof wxApi.closeBLEConnection === 'function') {
      try {
        await wxCall(wxApi, 'closeBLEConnection', { deviceId: targetId });
      } catch (_err) {
        // 忽略断开旧连接失败
      }
    }
  }

  const handleDeviceFound = (res) => {
    if (released || !res || !Array.isArray(res.devices)) return;
    let changed = false;

    for (const dev of res.devices) {
      if (!dev || !dev.deviceId) continue;
      const advInfo = parseAdvertisData(dev.advertisData);
      const rawName = (dev.localName || dev.name || '').trim();
      const matched =
        (advInfo && advInfo.isCamera) || isLikelySonyCameraName(rawName);
      if (!matched) continue;

      const existing = cameraCache.get(dev.deviceId);
      const name =
        rawName || (existing && existing.name) || 'Sony Camera';
      const entry = {
        name,
        deviceId: dev.deviceId,
        displayId: formatDeviceId(dev.deviceId),
        rssi: typeof dev.RSSI === 'number' ? dev.RSSI : -70,
        isBonded: Boolean(existing && existing.isBonded),
        isPairingMode: Boolean(
          (advInfo && advInfo.isPairingMode) ||
            (existing && existing.isPairingMode)
        ),
        isCached: false
      };
      cameraCache.set(dev.deviceId, entry);
      changed = true;
    }

    if (changed) {
      publishCameras();
    }
  };

  const handleConnectionStateChange = (res) => {
    if (released || !res) return;
    if (!res.connected && activeDeviceId && res.deviceId === activeDeviceId) {
      clearActiveHandles();
      if (
        connectionState.type === 'connected' ||
        connectionState.type === 'connecting' ||
        connectionState.type === 'pairing'
      ) {
        setConnectionState({ type: 'disconnected' });
      }
    }
  };

  const handleCharacteristicValueChange = (res) => {
    if (released || !res || !res.value) return;
    if (
      notifyCharId &&
      res.characteristicId &&
      !matchesUuid(
        res.characteristicId,
        CHAR_REMOTE_NOTIFY_UUID,
        CHAR_REMOTE_NOTIFY_SHORT
      )
    ) {
      return;
    }
    const event = parseNotify(res.value);
    if (event.type === 'focus_acquired' && focusWaiterResolve) {
      const resolve = focusWaiterResolve;
      focusWaiterResolve = null;
      resolve(true);
    }
  };

  const handleAdapterStateChange = (res) => {
    if (released || !res) return;
    if (!res.available) {
      adapterOpened = false;
      setScanning(false);
      clearActiveHandles();
      setConnectionState({ type: 'error', message: '请先打开手机蓝牙' });
    } else if (!res.discovering && isScanning) {
      setScanning(false);
    }
  };

  function bindListenersOnce() {
    if (listenersBound || !wxApi) return;
    if (typeof wxApi.onBluetoothDeviceFound === 'function') {
      wxApi.onBluetoothDeviceFound(handleDeviceFound);
    }
    if (typeof wxApi.onBLEConnectionStateChange === 'function') {
      wxApi.onBLEConnectionStateChange(handleConnectionStateChange);
    }
    if (typeof wxApi.onBLECharacteristicValueChange === 'function') {
      wxApi.onBLECharacteristicValueChange(handleCharacteristicValueChange);
    }
    if (typeof wxApi.onBluetoothAdapterStateChange === 'function') {
      wxApi.onBluetoothAdapterStateChange(handleAdapterStateChange);
    }
    listenersBound = true;
  }

  function unbindListeners() {
    if (!listenersBound || !wxApi) return;
    if (typeof wxApi.offBluetoothDeviceFound === 'function') {
      wxApi.offBluetoothDeviceFound(handleDeviceFound);
    }
    if (typeof wxApi.offBLEConnectionStateChange === 'function') {
      wxApi.offBLEConnectionStateChange(handleConnectionStateChange);
    }
    if (typeof wxApi.offBLECharacteristicValueChange === 'function') {
      wxApi.offBLECharacteristicValueChange(handleCharacteristicValueChange);
    }
    if (typeof wxApi.offBluetoothAdapterStateChange === 'function') {
      wxApi.offBluetoothAdapterStateChange(handleAdapterStateChange);
    }
    listenersBound = false;
  }

  async function ensureAdapterOpen() {
    bindListenersOnce();
    if (adapterOpened) return true;
    try {
      await wxCall(wxApi, 'openBluetoothAdapter');
      adapterOpened = true;
      return true;
    } catch (err) {
      adapterOpened = false;
      setConnectionState({
        type: 'error',
        message: translateAdapterError(err)
      });
      return false;
    }
  }

  async function startScan() {
    if (released) return;
    const ready = await ensureAdapterOpen();
    if (!ready || released) return;

    cameraCache.clear();
    const lastCam = preferences && preferences.getLastCamera
      ? preferences.getLastCamera()
      : null;
    if (lastCam && lastCam.deviceId) {
      cameraCache.set(lastCam.deviceId, {
        name: lastCam.name,
        deviceId: lastCam.deviceId,
        displayId: formatDeviceId(lastCam.deviceId),
        rssi: -100,
        isBonded: false,
        isPairingMode: false,
        isCached: true
      });
    }

    if (typeof wxApi.getConnectedBluetoothDevices === 'function') {
      try {
        const connectedRes = await wxCall(wxApi, 'getConnectedBluetoothDevices', {
          services: [SERVICE_REMOTE_CONTROL_UUID]
        });
        const devices = (connectedRes && connectedRes.devices) || [];
        for (const dev of devices) {
          if (!dev || !dev.deviceId) continue;
          cameraCache.set(dev.deviceId, {
            name: dev.name || 'Sony Camera',
            deviceId: dev.deviceId,
            displayId: formatDeviceId(dev.deviceId),
            rssi: 0,
            isBonded: true,
            isPairingMode: false,
            isCached: false
          });
        }
      } catch (_err) {
        // 忽略系统查询异常
      }
    }

    publishCameras();

    try {
      setScanning(true);
      await wxCall(wxApi, 'startBluetoothDevicesDiscovery', {
        allowDuplicatesKey: true,
        interval: 500
      });
    } catch (err) {
      setScanning(false);
      setConnectionState({
        type: 'error',
        message: translateAdapterError(err)
      });
    }
  }

  async function stopScan() {
    if (!isScanning) return;
    setScanning(false);
    if (!wxApi || typeof wxApi.stopBluetoothDevicesDiscovery !== 'function') {
      return;
    }
    try {
      await wxCall(wxApi, 'stopBluetoothDevicesDiscovery');
    } catch (_err) {
      // 忽略停止扫描错误
    }
  }

  async function checkAndroidPaired(deviceId) {
    if (!wxApi || typeof wxApi.isBluetoothDevicePaired !== 'function') {
      return null;
    }
    if (getPlatform(wxApi) !== 'android') return null;
    try {
      const res = await wxCall(wxApi, 'isBluetoothDevicePaired', { deviceId });
      return Boolean(res && res.isPaired);
    } catch (_err) {
      return null;
    }
  }

  async function writeCharacteristicPacket(bytes) {
    if (!activeDeviceId || !activeServiceId || !commandCharId) {
      return false;
    }
    const value = bytesToArrayBuffer(bytes);
    try {
      await wxCall(wxApi, 'writeBLECharacteristicValue', {
        deviceId: activeDeviceId,
        serviceId: activeServiceId,
        characteristicId: commandCharId,
        value,
        writeType: 'write'
      });
      return true;
    } catch (err) {
      const msg = (err && err.errMsg ? String(err.errMsg) : '').toLowerCase();
      const isWriteTypeUnsupported =
        msg.includes('writetype') ||
        msg.includes('param') ||
        msg.includes('support') ||
        msg.includes('invalid');
      if (!isWriteTypeUnsupported) {
        return false;
      }
      // 某些基础库或机型不支持显式 writeType，回退默认参数再试一次
      try {
        await wxCall(wxApi, 'writeBLECharacteristicValue', {
          deviceId: activeDeviceId,
          serviceId: activeServiceId,
          characteristicId: commandCharId,
          value
        });
        return true;
      } catch (_fallbackErr) {
        return false;
      }
    }
  }

  async function connect(camera) {
    if (released || !camera || !camera.deviceId) return;
    const ready = await ensureAdapterOpen();
    if (!ready || released) return;

    await stopScan();
    await closeActiveConnection();

    const camName = camera.name || 'Sony Camera';
    const androidPaired = await checkAndroidPaired(camera.deviceId);
    const needPairPrompt = camera.isPairingMode || androidPaired === false;

    setConnectionState(
      needPairPrompt
        ? { type: 'pairing', cameraName: camName }
        : { type: 'connecting', cameraName: camName }
    );

    activeDeviceId = camera.deviceId;
    activeCameraName = camName;

    try {
      await wxCall(wxApi, 'createBLEConnection', {
        deviceId: camera.deviceId,
        timeout: 15000
      });
      if (released || activeDeviceId !== camera.deviceId) return;

      const platform = getPlatform(wxApi);
      if (platform === 'android') {
        await delay(300);
        if (released || activeDeviceId !== camera.deviceId) return;
      }

      const servicesRes = await wxCall(wxApi, 'getBLEDeviceServices', {
        deviceId: camera.deviceId
      });
      const services = (servicesRes && servicesRes.services) || [];
      const remoteService = services.find((svc) =>
        matchesUuid(svc.uuid, SERVICE_REMOTE_CONTROL_UUID)
      );

      if (!remoteService) {
        await closeActiveConnection();
        setConnectionState({
          type: 'error',
          message: '未找到索尼遥控服务，请开启相机「蓝牙遥控」'
        });
        return;
      }

      activeServiceId = remoteService.uuid;

      const charsRes = await wxCall(wxApi, 'getBLEDeviceCharacteristics', {
        deviceId: camera.deviceId,
        serviceId: activeServiceId
      });
      const chars = (charsRes && charsRes.characteristics) || [];
      const cmdChar = chars.find((c) =>
        matchesUuid(c.uuid, CHAR_REMOTE_COMMAND_UUID, CHAR_REMOTE_COMMAND_SHORT)
      );
      const notifyChar = chars.find((c) =>
        matchesUuid(c.uuid, CHAR_REMOTE_NOTIFY_UUID, CHAR_REMOTE_NOTIFY_SHORT)
      );

      if (!cmdChar) {
        await closeActiveConnection();
        setConnectionState({
          type: 'error',
          message: '未找到快门指令通道，请确认相机固件支持蓝牙遥控'
        });
        return;
      }

      commandCharId = cmdChar.uuid;
      notifyCharId = notifyChar ? notifyChar.uuid : null;

      if (notifyCharId) {
        try {
          await wxCall(wxApi, 'notifyBLECharacteristicValueChange', {
            deviceId: camera.deviceId,
            serviceId: activeServiceId,
            characteristicId: notifyCharId,
            state: true
          });
        } catch (_notifyErr) {
          await closeActiveConnection();
          setConnectionState({
            type: 'error',
            message: '配对未完成，请在相机「蓝牙 → 配对」确认或先在系统蓝牙配对'
          });
          return;
        }
      }

      if (preferences && typeof preferences.setLastCamera === 'function') {
        preferences.setLastCamera({
          name: camName,
          deviceId: camera.deviceId
        });
      }

      const cachedEntry = cameraCache.get(camera.deviceId);
      if (cachedEntry) {
        cachedEntry.isBonded = true;
        cachedEntry.isCached = false;
        publishCameras();
      }

      setConnectionState({
        type: 'connected',
        cameraName: camName,
        deviceId: camera.deviceId
      });
    } catch (err) {
      await closeActiveConnection();
      const errMsg = (err && err.errMsg ? String(err.errMsg) : '').toLowerCase();
      if (errMsg.includes('auth') || errMsg.includes('pair') || errMsg.includes('bond')) {
        setConnectionState({
          type: 'error',
          message: '配对未完成，请在相机屏幕确认配对'
        });
      } else {
        setConnectionState({
          type: 'error',
          message: '连接失败，请确认相机开机并处于蓝牙有效范围'
        });
      }
    }
  }

  async function disconnect() {
    await closeActiveConnection();
    setConnectionState({ type: 'disconnected' });
  }

  function runSerialized(task) {
    const next = writeQueue.then(task, task);
    writeQueue = next.catch(() => {});
    return next;
  }

  function triggerShutter() {
    if (connectionState.type !== 'connected') {
      return Promise.resolve({ ok: false, error: '未连接到索尼相机' });
    }
    if (!activeDeviceId || !activeServiceId || !commandCharId) {
      return Promise.resolve({ ok: false, error: '蓝牙通道未就绪' });
    }

    return runSerialized(async () => {
      if (connectionState.type !== 'connected' || !activeDeviceId || !commandCharId) {
        return { ok: false, error: '蓝牙连接已断开' };
      }

      const focusPromise = new Promise((resolve) => {
        focusWaiterResolve = resolve;
      });
      const focusTimeout = delay(450).then(() => false);

      try {
        // 1. 半按对焦 (0x01 0x07)
        const halfOk = await writeCharacteristicPacket(CMD_SHUTTER_HALF_DOWN);
        if (!halfOk) {
          return { ok: false, error: '半按对焦指令写入失败' };
        }

        // 等待相机 0xFF02 返回对焦成功，MF 或超时 450ms 直接继续触发快门
        await Promise.race([focusPromise, focusTimeout]);

        // 2. 全按快门 (0x01 0x09)
        const fullOk = await writeCharacteristicPacket(CMD_SHUTTER_FULL_DOWN);
        if (!fullOk) {
          return { ok: false, error: '快门触发指令写入失败' };
        }

        await delay(120);
        return { ok: true };
      } finally {
        if (focusWaiterResolve) {
          const resolve = focusWaiterResolve;
          focusWaiterResolve = null;
          resolve(false);
        }
        // 3 & 4. 成对释放全按与半按，防止索尼相机锁死在半按状态
        await writeCharacteristicPacket(CMD_SHUTTER_FULL_UP).catch(() => false);
        await delay(60);
        await writeCharacteristicPacket(CMD_SHUTTER_HALF_UP).catch(() => false);
      }
    });
  }

  async function release() {
    released = true;
    await stopScan();
    await closeActiveConnection();
    unbindListeners();
    if (adapterOpened && wxApi && typeof wxApi.closeBluetoothAdapter === 'function') {
      try {
        await wxCall(wxApi, 'closeBluetoothAdapter');
      } catch (_err) {
        // 忽略关闭适配器错误
      }
    }
    adapterOpened = false;
  }

  return {
    getConnectionState: () => connectionState,
    getActiveCameraName: () => activeCameraName,
    startScan,
    stopScan,
    connect,
    disconnect,
    triggerShutter,
    release
  };
}

module.exports = {
  createBleController
};
