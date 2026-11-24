const SOUND_PATHS = {
  mechanicalClick: '/assets/sounds/mechanical_click.wav',
  tickNormal: '/assets/sounds/tick_normal.wav',
  tickFinal: '/assets/sounds/tick_final.wav',
  shutterTrigger: '/assets/sounds/shutter_trigger.wav',
  cancel: '/assets/sounds/cancel.wav'
};

function createAudioClip(wxApi, src) {
  if (!wxApi || typeof wxApi.createInnerAudioContext !== 'function') {
    return null;
  }
  try {
    const ctx = wxApi.createInnerAudioContext();
    ctx.src = src;
    if ('obeyMuteSwitch' in ctx) {
      ctx.obeyMuteSwitch = false;
    }
    if (typeof ctx.onError === 'function') {
      ctx.onError(() => {});
    }
    return ctx;
  } catch (_err) {
    return null;
  }
}

function replayClip(clip) {
  if (!clip) return;
  try {
    if (typeof clip.stop === 'function') {
      clip.stop();
    }
    if (typeof clip.seek === 'function') {
      clip.seek(0);
    }
    if (typeof clip.play === 'function') {
      clip.play();
    }
  } catch (_err) {
    // 忽略模拟器或快速连续触发时的播放异常
  }
}

function vibrate(wxApi, type) {
  if (!wxApi || typeof wxApi.vibrateShort !== 'function') return;
  try {
    wxApi.vibrateShort({
      type,
      fail: () => {}
    });
  } catch (_err) {
    // 忽略不支持震动的设备
  }
}

function createFeedbackPlayer(wxApi = typeof wx !== 'undefined' ? wx : null) {
  const clips = {
    mechanicalClick: createAudioClip(wxApi, SOUND_PATHS.mechanicalClick),
    tickNormal: createAudioClip(wxApi, SOUND_PATHS.tickNormal),
    tickFinal: createAudioClip(wxApi, SOUND_PATHS.tickFinal),
    shutterTrigger: createAudioClip(wxApi, SOUND_PATHS.shutterTrigger),
    cancel: createAudioClip(wxApi, SOUND_PATHS.cancel)
  };

  return {
    playMechanicalPress() {
      replayClip(clips.mechanicalClick);
      vibrate(wxApi, 'heavy');
    },
    playCountdownTick(finalTick) {
      replayClip(finalTick ? clips.tickFinal : clips.tickNormal);
      vibrate(wxApi, finalTick ? 'heavy' : 'light');
    },
    playShutterTrigger() {
      replayClip(clips.shutterTrigger);
      vibrate(wxApi, 'heavy');
    },
    playCancel() {
      replayClip(clips.cancel);
      vibrate(wxApi, 'light');
    },
    playLongPressDetent() {
      vibrate(wxApi, 'heavy');
    },
    release() {
      Object.keys(clips).forEach((key) => {
        const clip = clips[key];
        if (clip && typeof clip.destroy === 'function') {
          try {
            clip.destroy();
          } catch (_err) {
            // 忽略销毁异常
          }
        }
        clips[key] = null;
      });
    }
  };
}

module.exports = {
  SOUND_PATHS,
  createFeedbackPlayer
};
