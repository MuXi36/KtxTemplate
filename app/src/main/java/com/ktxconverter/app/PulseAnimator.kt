package com.ktxconverter.app

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * 光遇风格按钮脉冲动画 — 模拟 React Bits BorderGlow 呼吸效果
 */
object PulseAnimator {

    /** 对按钮施加呼吸脉冲：scale 1.0 ↔ 1.03，无限循环 */
    fun applyPulse(view: View, minScale: Float = 1.0f, maxScale: Float = 1.03f) {
        val animator = ValueAnimator.ofFloat(minScale, maxScale).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = OvershootInterpolator(0.5f)
            addUpdateListener { anim ->
                val s = anim.animatedValue as Float
                view.scaleX = s
                view.scaleY = s
            }
        }
        animator.start()
    }

    /** 停止脉冲 */
    fun stopPulse(view: View) {
        view.animate().cancel()
        view.scaleX = 1.0f
        view.scaleY = 1.0f
    }

    /** 单次弹跳动画（点击反馈） */
    fun bounce(view: View) {
        view.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }
}