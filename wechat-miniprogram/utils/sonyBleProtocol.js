/**
 * 索尼 Alpha / ZV / FX 系列相机 BLE 遥控协议定义（适配 A7C II、A7M4 等同协议机型）。
 * 对应原 Kotlin 实现：SonyBleProtocol.kt
 */

const SONY_MANUFACTURER_ID = 0x012d;
const SERVICE_REMOTE_CONTROL_UUID = '8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF';
const CHAR_REMOTE_COMMAND_UUID = '0000FF01-0000-1000-8000-00805F9B34FB';
const CHAR_REMOTE_COMMAND_SHORT = 'FF01';
const CHAR_REMOTE_NOTIFY_UUID = '0000FF02-0000-1000-8000-00805F9B34FB';
const CHAR_REMOTE_NOTIFY_SHORT = 'FF02';

const CMD_SHUTTER_HALF_DOWN = [0x01, 0x07];
const CMD_SHUTTER_FULL_DOWN = [0x01, 0x09];
const CMD_SHUTTER_FULL_UP = [0x01, 0x08];
const CMD_SHUTTER_HALF_UP = [0x01, 0x06];

function bytesToArrayBuffer(bytes) {
  const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const buffer = new ArrayBuffer(arr.length);
  new Uint8Array(buffer).set(arr);
  return buffer;
}

function arrayBufferToBytes(input) {
  if (!input) return new Uint8Array(0);
  if (input instanceof Uint8Array) return input;
  if (input instanceof ArrayBuffer) return new Uint8Array(input);
  if (ArrayBuffer.isView(input)) {
    return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
  }
  if (Array.isArray(input)) return new Uint8Array(input);
  return new Uint8Array(0);
}

/**
 * 兼容 iOS（可能返回 16-bit 短 UUID 如 "FF01"）与 Android（128-bit 完整 UUID）的大小写无关匹配。
 */
function matchesUuid(uuid, fullUuid, shortHex) {
  if (!uuid || typeof uuid !== 'string') return false;
  const upper = uuid.trim().toUpperCase();
  if (fullUuid && upper === fullUuid.toUpperCase()) return true;
  if (shortHex) {
    const shortUpper = shortHex.toUpperCase();
    if (upper === shortUpper) return true;
    if (upper === `0000${shortUpper}-0000-1000-8000-00805F9B34FB`) return true;
  }
  return false;
}

/**
 * 解析索尼 BLE Manufacturer Data 负载（0x012D 厂商 ID 之后的字节）：
 * 前两字节为 0x03 0x00 表示相机设备；包含 0x22 0xEF 0x00 表示相机处于配对模式。
 */
function parseManufacturerPayload(input) {
  const bytes = arrayBufferToBytes(input);
  if (bytes.length < 2) return null;

  const isCamera = bytes[0] === 0x03 && bytes[1] === 0x00;
  if (!isCamera) {
    return { isCamera: false, isPairingMode: false };
  }

  let isPairingMode = false;
  for (let i = 0; i <= bytes.length - 3; i += 1) {
    if (bytes[i] === 0x22 && bytes[i + 1] === 0xef && bytes[i + 2] === 0x00) {
      isPairingMode = true;
      break;
    }
  }

  return { isCamera: true, isPairingMode };
}

/**
 * 解析微信小程序 onBluetoothDeviceFound 返回的 advertisData（ArrayBuffer）。
 * 前两字节为小端序厂商 ID：0x2D 0x01（即 0x012D）。
 */
function parseAdvertisData(advertisData) {
  const bytes = arrayBufferToBytes(advertisData);
  if (bytes.length < 4) return null;
  // Little-endian 0x012D -> [0x2D, 0x01]
  if (bytes[0] !== 0x2d || bytes[1] !== 0x01) return null;
  return parseManufacturerPayload(bytes.subarray(2));
}

/**
 * 解析 0xFF02 相机状态通知。
 */
function parseNotify(input) {
  const bytes = arrayBufferToBytes(input);
  if (bytes.length >= 3 && bytes[0] === 0x02) {
    const type = bytes[1];
    const state = bytes[2];
    if (type === 0x3f) {
      return {
        type: state === 0x20 ? 'focus_acquired' : 'focus_lost',
        raw: bytes
      };
    }
    if (type === 0xa0) {
      return {
        type: state === 0x20 ? 'shutter_active' : 'shutter_ready',
        raw: bytes
      };
    }
  }
  return { type: 'unknown', raw: bytes };
}

function isLikelySonyCameraName(name) {
  if (!name || typeof name !== 'string') return false;
  const upper = name.toUpperCase();
  return (
    upper.includes('ILCE') ||
    upper.includes('ILME') ||
    upper.includes('SONY') ||
    upper.includes('A7') ||
    upper.includes('ZV-') ||
    upper.includes('DSC-') ||
    upper.includes('FX')
  );
}

function formatDeviceId(deviceId) {
  if (!deviceId || typeof deviceId !== 'string') return '';
  if (deviceId.length <= 20) return deviceId;
  return `${deviceId.slice(0, 8)}...${deviceId.slice(-6)}`;
}

module.exports = {
  SONY_MANUFACTURER_ID,
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
  arrayBufferToBytes,
  matchesUuid,
  parseManufacturerPayload,
  parseAdvertisData,
  parseNotify,
  isLikelySonyCameraName,
  formatDeviceId
};
