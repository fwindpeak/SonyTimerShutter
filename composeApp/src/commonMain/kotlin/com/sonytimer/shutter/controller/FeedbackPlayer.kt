package com.sonytimer.shutter.controller

interface FeedbackPlayer {
    /** 物理机械按钮按下瞬间的咔哒声 + 震动 */
    fun playMechanicalPress()

    /** 每秒一次的倒计时电子滴声；finalTick 为最后 1 秒的高音提示 */
    fun playCountdownTick(finalTick: Boolean)

    /** 归零发送快门指令时的确认音效与触感 */
    fun playShutterTrigger()

    /** 中途取消倒计时时的低音提示 */
    fun playCancel()

    /** 长按呼出隐藏秒数面板时的轻微触感反馈 */
    fun playLongPressDetent()

    fun release()
}

interface TimerPreferences {
    fun getTimerSeconds(defaultValue: Int = 5): Int
    fun setTimerSeconds(seconds: Int)
}
