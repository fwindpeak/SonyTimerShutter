import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

const protocol = require('../utils/sonyBleProtocol.js');
const { createTimerPreferences } = require('../utils/timerPreferences.js');
const { createBleController } = require('../utils/bleController.js');
const { createShutterController } = require('../utils/shutterController.js');

function createMemoryStorage() {
  const map = new Map();
  return {
    get(key) {
      return map.has(key) ? map.get(key) : undefined;
    },
    set(key, val) {
      map.set(key, val);
    }
  };
}

function createNoopFeedback() {
  const calls = [];
  return {
    calls,
    playMechanicalPress: () => calls.push('press'),
    playCountdownTick: (finalTick) => calls.push(`tick:${finalTick}`),
    playShutterTrigger: () => calls.push('trigger'),
    playCancel: () => calls.push('cancel'),
    playLongPressDetent: () => calls.push('detent'),
    release: () => calls.push('release')
  };
}

function createMockWx({
  platform = 'ios',
  shortUuids = false,
  failOnPacket = null,
  triggerFocusOnHalfDown = false
} = {}) {
  const listeners = {
    found: [],
    connState: [],
    charChange: [],
    adapterState: []
  };
  const writtenPackets = [];
  let connectedDeviceId = null;

  const svcUuid = protocol.SERVICE_REMOTE_CONTROL_UUID;
  const cmdUuid = shortUuids
    ? protocol.CHAR_REMOTE_COMMAND_SHORT
    : protocol.CHAR_REMOTE_COMMAND_UUID;
  const notifyUuid = shortUuids
    ? protocol.CHAR_REMOTE_NOTIFY_SHORT
    : protocol.CHAR_REMOTE_NOTIFY_UUID;

  const wxApi = {
    writtenPackets,
    listeners,
    getDeviceInfo: () => ({ platform }),
    openBluetoothAdapter: ({ success }) => success && success({}),
    closeBluetoothAdapter: ({ success }) => success && success({}),
    startBluetoothDevicesDiscovery: ({ success }) => success && success({}),
    stopBluetoothDevicesDiscovery: ({ success }) => success && success({}),
    getConnectedBluetoothDevices: ({ success }) =>
      success && success({ devices: [] }),
    isBluetoothDevicePaired: ({ success }) =>
      success && success({ isPaired: true }),
    onBluetoothDeviceFound: (cb) => listeners.found.push(cb),
    offBluetoothDeviceFound: (cb) => {
      listeners.found = listeners.found.filter((fn) => fn !== cb);
    },
    onBLEConnectionStateChange: (cb) => listeners.connState.push(cb),
    offBLEConnectionStateChange: (cb) => {
      listeners.connState = listeners.connState.filter((fn) => fn !== cb);
    },
    onBLECharacteristicValueChange: (cb) => listeners.charChange.push(cb),
    offBLECharacteristicValueChange: (cb) => {
      listeners.charChange = listeners.charChange.filter((fn) => fn !== cb);
    },
    onBluetoothAdapterStateChange: (cb) => listeners.adapterState.push(cb),
    offBluetoothAdapterStateChange: (cb) => {
      listeners.adapterState = listeners.adapterState.filter((fn) => fn !== cb);
    },
    createBLEConnection: ({ deviceId, success }) => {
      connectedDeviceId = deviceId;
      success && success({});
    },
    closeBLEConnection: ({ success }) => {
      connectedDeviceId = null;
      success && success({});
    },
    getBLEDeviceServices: ({ success }) => {
      success &&
        success({
          services: [{ uuid: svcUuid.toLowerCase(), isPrimary: true }]
        });
    },
    getBLEDeviceCharacteristics: ({ success }) => {
      success &&
        success({
          characteristics: [
            { uuid: cmdUuid, properties: { write: true } },
            { uuid: notifyUuid, properties: { notify: true } }
          ]
        });
    },
    notifyBLECharacteristicValueChange: ({ success }) => {
      success && success({});
    },
    writeBLECharacteristicValue: ({ value, success, fail }) => {
      const bytes = Array.from(new Uint8Array(value));
      writtenPackets.push(bytes);

      if (
        failOnPacket &&
        bytes.length === failOnPacket.length &&
        bytes.every((b, idx) => b === failOnPacket[idx])
      ) {
        fail && fail({ errMsg: 'mock write failed' });
        return;
      }

      if (
        triggerFocusOnHalfDown &&
        bytes[0] === 0x01 &&
        bytes[1] === 0x07
      ) {
        setTimeout(() => {
          const focusPayload = protocol.bytesToArrayBuffer([0x02, 0x3f, 0x20]);
          for (const cb of listeners.charChange) {
            cb({
              deviceId: connectedDeviceId,
              serviceId: svcUuid,
              characteristicId: notifyUuid,
              value: focusPayload
            });
          }
        }, 10);
      }

      success && success({});
    },
    emitFoundDevices(devices) {
      for (const cb of listeners.found) {
        cb({ devices });
      }
    }
  };

  return wxApi;
}

test('sonyBleProtocol: parseAdvertisData parses camera and pairing flags', () => {
  // Little endian Sony 0x012D -> [0x2D, 0x01], camera [0x03, 0x00], pairing [0x22, 0xEF, 0x00]
  const pairingBuf = protocol.bytesToArrayBuffer([
    0x2d, 0x01, 0x03, 0x00, 0x11, 0x22, 0xef, 0x00
  ]);
  assert.deepEqual(protocol.parseAdvertisData(pairingBuf), {
    isCamera: true,
    isPairingMode: true
  });

  // Sony camera not in pairing mode
  const normalBuf = protocol.bytesToArrayBuffer([
    0x2d, 0x01, 0x03, 0x00, 0x00, 0x00
  ]);
  assert.deepEqual(protocol.parseAdvertisData(normalBuf), {
    isCamera: true,
    isPairingMode: false
  });

  // Non-camera Sony device (e.g. headphones)
  const nonCameraBuf = protocol.bytesToArrayBuffer([0x2d, 0x01, 0x07, 0x00]);
  assert.deepEqual(protocol.parseAdvertisData(nonCameraBuf), {
    isCamera: false,
    isPairingMode: false
  });

  // Non-Sony manufacturer ID
  const otherVendor = protocol.bytesToArrayBuffer([0x4c, 0x00, 0x03, 0x00]);
  assert.equal(protocol.parseAdvertisData(otherVendor), null);

  // Truncated buffer
  assert.equal(
    protocol.parseAdvertisData(protocol.bytesToArrayBuffer([0x2d, 0x01, 0x03])),
    null
  );
});

test('sonyBleProtocol: parseNotify, matchesUuid, and camera name heuristics', () => {
  assert.equal(protocol.parseNotify([0x02, 0x3f, 0x20]).type, 'focus_acquired');
  assert.equal(protocol.parseNotify([0x02, 0x3f, 0x00]).type, 'focus_lost');
  assert.equal(protocol.parseNotify([0x02, 0xa0, 0x20]).type, 'shutter_active');
  assert.equal(protocol.parseNotify([0x02, 0xa0, 0x00]).type, 'shutter_ready');
  assert.equal(protocol.parseNotify([0x01, 0x02]).type, 'unknown');

  assert.equal(
    protocol.matchesUuid(
      'ff01',
      protocol.CHAR_REMOTE_COMMAND_UUID,
      protocol.CHAR_REMOTE_COMMAND_SHORT
    ),
    true
  );
  assert.equal(
    protocol.matchesUuid(
      '0000ff02-0000-1000-8000-00805f9b34fb',
      protocol.CHAR_REMOTE_NOTIFY_UUID,
      protocol.CHAR_REMOTE_NOTIFY_SHORT
    ),
    true
  );
  assert.equal(
    protocol.matchesUuid(
      '8000ff00-ff00-ffff-ffff-ffffffffffff',
      protocol.SERVICE_REMOTE_CONTROL_UUID
    ),
    true
  );

  assert.equal(protocol.isLikelySonyCameraName('ILCE-7CM2'), true);
  assert.equal(protocol.isLikelySonyCameraName('ZV-E10'), true);
  assert.equal(protocol.isLikelySonyCameraName('AirPods Pro'), false);
});

test('bleController: scan sorting, iOS short-UUID connect, and 4-step shutter sequence', async () => {
  const prefs = createTimerPreferences(createMemoryStorage());
  prefs.setLastCamera({ name: 'Cached A7C2', deviceId: 'OLD-UUID-1234567890' });

  const wxApi = createMockWx({
    platform: 'ios',
    shortUuids: true,
    triggerFocusOnHalfDown: true
  });

  let latestState = null;
  let latestCameras = [];

  const ble = createBleController({
    wxApi,
    preferences: prefs,
    onStateChange: (s) => {
      latestState = s;
    },
    onCamerasChange: (cams) => {
      latestCameras = cams;
    }
  });

  await ble.startScan();
  wxApi.emitFoundDevices([
    {
      deviceId: 'CAM-NORMAL',
      localName: 'ILCE-7M4',
      RSSI: -52,
      advertisData: protocol.bytesToArrayBuffer([0x2d, 0x01, 0x03, 0x00, 0x00])
    },
    {
      deviceId: 'CAM-PAIRING',
      localName: 'ILCE-7CM2',
      RSSI: -65,
      advertisData: protocol.bytesToArrayBuffer([
        0x2d, 0x01, 0x03, 0x00, 0x22, 0xef, 0x00
      ])
    },
    {
      deviceId: 'OTHER-DEV',
      localName: 'Mi Band',
      RSSI: -40,
      advertisData: protocol.bytesToArrayBuffer([0x57, 0x01, 0x03, 0x00])
    }
  ]);

  assert.equal(latestCameras.length, 3);
  assert.equal(latestCameras[0].deviceId, 'CAM-PAIRING');
  assert.equal(latestCameras[1].deviceId, 'OLD-UUID-1234567890');
  assert.equal(latestCameras[2].deviceId, 'CAM-NORMAL');

  await ble.connect(latestCameras[0]);
  assert.equal(latestState.type, 'connected');
  assert.equal(latestState.cameraName, 'ILCE-7CM2');
  assert.deepEqual(prefs.getLastCamera(), {
    name: 'ILCE-7CM2',
    deviceId: 'CAM-PAIRING'
  });

  const startMs = Date.now();
  const shutterRes = await ble.triggerShutter();
  const elapsedMs = Date.now() - startMs;

  assert.equal(shutterRes.ok, true);
  assert.ok(elapsedMs < 400, `Expected focus notify early wakeup, took ${elapsedMs}ms`);
  assert.deepEqual(wxApi.writtenPackets, [
    [0x01, 0x07],
    [0x01, 0x09],
    [0x01, 0x08],
    [0x01, 0x06]
  ]);

  await ble.release();
});

test('bleController: always sends FULL_UP and HALF_UP release packets even when FULL_DOWN fails', async () => {
  const prefs = createTimerPreferences(createMemoryStorage());
  const wxApi = createMockWx({
    platform: 'android',
    shortUuids: false,
    failOnPacket: [0x01, 0x09]
  });

  const ble = createBleController({
    wxApi,
    preferences: prefs
  });

  await ble.connect({
    name: 'ILCE-7CM2',
    deviceId: 'AA:BB:CC:DD:EE:FF',
    isPairingMode: false
  });

  const res = await ble.triggerShutter();
  assert.equal(res.ok, false);
  assert.deepEqual(wxApi.writtenPackets, [
    [0x01, 0x07],
    [0x01, 0x09],
    [0x01, 0x08],
    [0x01, 0x06]
  ]);

  await ble.release();
});

test('shutterController: single shot, cancel mid-countdown, burst mode, and keepScreenOn', async () => {
  const prefs = createTimerPreferences(createMemoryStorage());
  const feedback = createNoopFeedback();
  const keepScreenCalls = [];
  let triggerCount = 0;

  let connState = { type: 'connected', cameraName: 'ILCE-7CM2', deviceId: 'D1' };
  const fakeBle = {
    getConnectionState: () => connState,
    startScan: () => {},
    stopScan: () => {},
    connect: () => {},
    disconnect: () => {
      connState = { type: 'disconnected' };
    },
    triggerShutter: async () => {
      triggerCount += 1;
      return { ok: true };
    },
    release: () => {}
  };

  const observedPhases = [];
  const controller = createShutterController({
    bleController: fakeBle,
    feedbackPlayer: feedback,
    preferences: prefs,
    setKeepScreenOn: (val) => keepScreenCalls.push(val),
    onUiStateChange: (s) => observedPhases.push(s.phase.type),
    delayFn: () => Promise.resolve()
  });

  // Verify clamping
  controller.updateTimerSeconds(99);
  assert.equal(controller.getState().configuredSeconds, 60);
  controller.updateTimerSeconds(2);
  assert.equal(controller.getState().configuredSeconds, 2);
  controller.updateBurstCount(1);
  assert.equal(controller.getState().burstCount, 2);

  // Single-shot run
  controller.onMainButtonClick();
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(triggerCount, 1);
  assert.equal(controller.getState().phase.type, 'idle');
  assert.deepEqual(keepScreenCalls, [true, false]);

  // Burst mode (2 shots)
  triggerCount = 0;
  controller.updateBurstEnabled(true);
  controller.updateBurstCount(2);
  controller.updateBurstIntervalSeconds(1);
  controller.onMainButtonClick();
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(triggerCount, 2);
  assert.ok(observedPhases.includes('shot_success'));

  // Mid-countdown cancel
  let resumeDelay;
  const slowController = createShutterController({
    bleController: fakeBle,
    feedbackPlayer: feedback,
    preferences: prefs,
    setKeepScreenOn: (val) => keepScreenCalls.push(val),
    delayFn: () =>
      new Promise((resolve) => {
        resumeDelay = resolve;
      })
  });
  slowController.updateBurstEnabled(false);
  slowController.updateTimerSeconds(5);
  slowController.onMainButtonClick();
  assert.equal(slowController.getState().phase.type, 'counting_down');
  slowController.onMainButtonClick();
  assert.equal(slowController.getState().phase.type, 'idle');
  assert.equal(slowController.getState().statusHint, '已取消倒计时');
  if (resumeDelay) resumeDelay();

  controller.destroy();
  slowController.destroy();
});
