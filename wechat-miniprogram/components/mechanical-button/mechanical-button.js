function hexToRgba(hex, alpha) {
  const clean = hex.replace('#', '');
  const r = parseInt(clean.slice(0, 2), 16);
  const g = parseInt(clean.slice(2, 4), 16);
  const b = parseInt(clean.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

Component({
  properties: {
    connectionType: {
      type: String,
      value: 'disconnected'
    },
    phaseType: {
      type: String,
      value: 'idle'
    },
    remainingSeconds: {
      type: Number,
      value: 0
    },
    totalSeconds: {
      type: Number,
      value: 1
    },
    currentShot: {
      type: Number,
      value: 1
    },
    totalShots: {
      type: Number,
      value: 1
    },
    isInterval: {
      type: Boolean,
      value: false
    },
    configuredSeconds: {
      type: Number,
      value: 5
    }
  },

  data: {
    isFingerDown: false,
    isPressed: false,
    sweepDeg: 0,
    ledArcGradient: 'none',
    ledGlowGradient: 'none',
    labelText: '5S',
    labelSizeClass: 'mech-label-idle',
    labelColor: 'rgba(253, 232, 232, 0.72)',
    subLabelText: '',
    subLabelColor: 'rgba(253, 232, 232, 0.72)'
  },

  observers: {
    'connectionType, phaseType, remainingSeconds, totalSeconds, currentShot, totalShots, isInterval, configuredSeconds'() {
      this._syncView();
    }
  },

  lifetimes: {
    attached() {
      this._syncView();
    }
  },

  methods: {
    _resolveLedColor() {
      const { phaseType, isInterval, connectionType } = this.data;
      if (phaseType === 'shot_success') return '#4ADE80';
      if (phaseType === 'triggering') return '#FFB020';
      if (phaseType === 'counting_down' && isInterval) return '#38BDF8';
      if (phaseType === 'counting_down') return '#FF5A36';
      if (connectionType === 'connected') return '#34D399';
      if (connectionType === 'connecting' || connectionType === 'pairing') {
        return '#F59E0B';
      }
      return '#4B5563';
    },

    _computeSweepFraction() {
      const { phaseType, remainingSeconds, totalSeconds, connectionType } = this.data;
      if (phaseType === 'counting_down') {
        const denom = Math.max(1, totalSeconds || 1);
        return Math.max(0, Math.min(1, remainingSeconds / denom));
      }
      if (phaseType === 'triggering') return 0;
      if (phaseType === 'shot_success') return 1;
      return connectionType === 'connected' ? 1 : 0;
    },

    _syncView() {
      const {
        phaseType,
        remainingSeconds,
        currentShot,
        totalShots,
        configuredSeconds,
        isFingerDown
      } = this.data;

      const isPressed = Boolean(isFingerDown || phaseType === 'triggering');
      const sweepFraction = this._computeSweepFraction();
      const sweepDeg = Math.round(sweepFraction * 360);
      const ledColor = this._resolveLedColor();
      const glowColor = hexToRgba(ledColor, 0.28);

      const ledArcGradient =
        sweepDeg >= 360
          ? ledColor
          : sweepDeg > 0
            ? `conic-gradient(from 0deg, ${ledColor} 0deg ${sweepDeg}deg, transparent ${sweepDeg}deg 360deg)`
            : 'none';

      const ledGlowGradient =
        sweepDeg >= 360
          ? glowColor
          : sweepDeg > 0
            ? `conic-gradient(from 0deg, ${glowColor} 0deg ${sweepDeg}deg, transparent ${sweepDeg}deg 360deg)`
            : 'none';

      let labelText = `${configuredSeconds}S`;
      let labelSizeClass = 'mech-label-idle';
      let labelColor = 'rgba(253, 232, 232, 0.72)';
      let subLabelText = '';
      let subLabelColor = 'rgba(253, 232, 232, 0.65)';

      if (phaseType === 'counting_down') {
        labelText = String(remainingSeconds);
        labelSizeClass =
          totalShots > 1 ? 'mech-label-count-burst' : 'mech-label-count';
        labelColor = '#FFF5EB';
        if (totalShots > 1) {
          subLabelText = `${currentShot}/${totalShots}`;
          subLabelColor = 'rgba(255, 245, 235, 0.9)';
        }
      } else if (phaseType === 'triggering') {
        labelText = '●';
        labelSizeClass = 'mech-label-symbol';
        labelColor = 'rgba(253, 232, 232, 0.72)';
        if (totalShots > 1) {
          subLabelText = `${currentShot}/${totalShots}`;
          subLabelColor = 'rgba(253, 232, 232, 0.65)';
        }
      } else if (phaseType === 'shot_success') {
        labelText = '✓';
        labelSizeClass = 'mech-label-symbol';
        labelColor = '#D1FAE5';
        if (totalShots > 1) {
          subLabelText = `${totalShots}/${totalShots}`;
          subLabelColor = 'rgba(209, 250, 229, 0.9)';
        }
      }

      this.setData({
        isPressed,
        sweepDeg,
        ledArcGradient,
        ledGlowGradient,
        labelText,
        labelSizeClass,
        labelColor,
        subLabelText,
        subLabelColor
      });
    },

    onTouchStart() {
      this.setData({
        isFingerDown: true,
        isPressed: true
      });
    },

    onTouchEnd() {
      this.setData({
        isFingerDown: false,
        isPressed: this.data.phaseType === 'triggering'
      });
    },

    onTouchCancel() {
      this.setData({
        isFingerDown: false,
        isPressed: this.data.phaseType === 'triggering'
      });
    },

    onTap() {
      this.triggerEvent('press');
    }
  }
});
