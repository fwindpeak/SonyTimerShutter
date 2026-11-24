App({
  onLaunch() {
    try {
      if (typeof wx !== 'undefined' && typeof wx.setInnerAudioOption === 'function') {
        wx.setInnerAudioOption({
          obeyMuteSwitch: false
        });
      }
    } catch (_err) {
      // 忽略低版本基础库不支持 setInnerAudioOption 的情况
    }
  }
});
