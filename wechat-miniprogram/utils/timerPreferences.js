const STORAGE_KEYS = {
  TIMER_SECONDS: 'sony_timer_seconds',
  BURST_ENABLED: 'sony_burst_enabled',
  BURST_COUNT: 'sony_burst_count',
  BURST_INTERVAL_SECONDS: 'sony_burst_interval_seconds',
  LAST_CAMERA: 'sony_last_camera'
};

const LIMITS = {
  MIN_TIMER_SECONDS: 1,
  MAX_TIMER_SECONDS: 60,
  MIN_BURST_COUNT: 2,
  MAX_BURST_COUNT: 99,
  MIN_BURST_INTERVAL_SECONDS: 1,
  MAX_BURST_INTERVAL_SECONDS: 60
};

function clampInt(value, min, max, fallback) {
  const num = Number(value);
  if (!Number.isFinite(num)) return fallback;
  const rounded = Math.round(num);
  return Math.max(min, Math.min(max, rounded));
}

function createWxStorageAdapter(wxApi) {
  return {
    get(key) {
      try {
        if (!wxApi || typeof wxApi.getStorageSync !== 'function') return undefined;
        const val = wxApi.getStorageSync(key);
        return val === '' ? undefined : val;
      } catch (_err) {
        return undefined;
      }
    },
    set(key, value) {
      try {
        if (wxApi && typeof wxApi.setStorageSync === 'function') {
          wxApi.setStorageSync(key, value);
        }
      } catch (_err) {
        // 忽略存储异常
      }
    }
  };
}

function createTimerPreferences(customStorageOrWx) {
  const storage =
    customStorageOrWx &&
    typeof customStorageOrWx.get === 'function' &&
    typeof customStorageOrWx.set === 'function'
      ? customStorageOrWx
      : createWxStorageAdapter(
          customStorageOrWx || (typeof wx !== 'undefined' ? wx : null)
        );

  return {
    getTimerSeconds(defaultValue = 5) {
      const raw = storage.get(STORAGE_KEYS.TIMER_SECONDS);
      return clampInt(
        raw === undefined ? defaultValue : raw,
        LIMITS.MIN_TIMER_SECONDS,
        LIMITS.MAX_TIMER_SECONDS,
        defaultValue
      );
    },
    setTimerSeconds(seconds) {
      const clamped = clampInt(
        seconds,
        LIMITS.MIN_TIMER_SECONDS,
        LIMITS.MAX_TIMER_SECONDS,
        5
      );
      storage.set(STORAGE_KEYS.TIMER_SECONDS, clamped);
      return clamped;
    },

    isBurstEnabled(defaultValue = false) {
      const raw = storage.get(STORAGE_KEYS.BURST_ENABLED);
      if (typeof raw === 'boolean') return raw;
      return defaultValue;
    },
    setBurstEnabled(enabled) {
      const next = Boolean(enabled);
      storage.set(STORAGE_KEYS.BURST_ENABLED, next);
      return next;
    },

    getBurstCount(defaultValue = 3) {
      const raw = storage.get(STORAGE_KEYS.BURST_COUNT);
      return clampInt(
        raw === undefined ? defaultValue : raw,
        LIMITS.MIN_BURST_COUNT,
        LIMITS.MAX_BURST_COUNT,
        defaultValue
      );
    },
    setBurstCount(count) {
      const clamped = clampInt(
        count,
        LIMITS.MIN_BURST_COUNT,
        LIMITS.MAX_BURST_COUNT,
        3
      );
      storage.set(STORAGE_KEYS.BURST_COUNT, clamped);
      return clamped;
    },

    getBurstIntervalSeconds(defaultValue = 2) {
      const raw = storage.get(STORAGE_KEYS.BURST_INTERVAL_SECONDS);
      return clampInt(
        raw === undefined ? defaultValue : raw,
        LIMITS.MIN_BURST_INTERVAL_SECONDS,
        LIMITS.MAX_BURST_INTERVAL_SECONDS,
        defaultValue
      );
    },
    setBurstIntervalSeconds(seconds) {
      const clamped = clampInt(
        seconds,
        LIMITS.MIN_BURST_INTERVAL_SECONDS,
        LIMITS.MAX_BURST_INTERVAL_SECONDS,
        2
      );
      storage.set(STORAGE_KEYS.BURST_INTERVAL_SECONDS, clamped);
      return clamped;
    },

    getLastCamera() {
      const raw = storage.get(STORAGE_KEYS.LAST_CAMERA);
      if (
        raw &&
        typeof raw === 'object' &&
        typeof raw.deviceId === 'string' &&
        raw.deviceId.length > 0
      ) {
        return {
          name: typeof raw.name === 'string' && raw.name ? raw.name : 'Sony Camera',
          deviceId: raw.deviceId
        };
      }
      return null;
    },
    setLastCamera(camera) {
      if (!camera || !camera.deviceId) return;
      storage.set(STORAGE_KEYS.LAST_CAMERA, {
        name: camera.name || 'Sony Camera',
        deviceId: camera.deviceId
      });
    }
  };
}

module.exports = {
  STORAGE_KEYS,
  LIMITS,
  clampInt,
  createTimerPreferences
};
