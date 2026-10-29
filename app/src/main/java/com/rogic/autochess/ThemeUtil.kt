package com.rogic.autochess

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 运行时主题（v1.6.1）：设置→界面主题 深色/白色/蓝白/蓝色/粉色。
 * 布局硬编码 @color/bg_dark|bg_panel|bg_card（深色系）——浅色主题需把
 * 已知深色背景映射为主题色，文字色同步遍历。弹窗保持深色系统样式（不处理）。
 */
object ThemeUtil {

    data class Palette(
        val bg: Int, val panel: Int, val card: Int, val text: Int, val textSub: Int
    )

    val palettes = mapOf(
        "default" to Palette(0xFF141821.toInt(), 0xFF1D2330.toInt(), 0xFF232B3B.toInt(), 0xFFECEFF6.toInt(), 0xFF9AA6BC.toInt()),
        "white" to Palette(0xFFF7F8FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFF0F2F6.toInt(), 0xFF1A2233.toInt(), 0xFF5A6B85.toInt()),
        "bluewhite" to Palette(0xFFEAF1FB.toInt(), 0xFFF8FAFF.toInt(), 0xFFE4ECF8.toInt(), 0xFF15233D.toInt(), 0xFF4C6489.toInt()),
        "blue" to Palette(0xFF0E1F42.toInt(), 0xFF152A55.toInt(), 0xFF1B3468.toInt(), 0xFFE3ECFF.toInt(), 0xFF8FA7D6.toInt()),
        "pink" to Palette(0xFFF9E8EF.toInt(), 0xFFFDF3F7.toInt(), 0xFFF5DCE8.toInt(), 0xFF3D2130.toInt(), 0xFF8C6478.toInt())
    )

    fun current(themeName: String?): Palette = palettes[themeName ?: "default"] ?: palettes["default"]!!

    // 深色系原色 → 主题色映射（布局硬编码色值）
    private val mapBgDark = 0xFF141821.toInt()
    private val mapPanel = 0xFF1D2330.toInt()
    private val mapCard = 0xFF232B3B.toInt()

    /** 主界面应用：状态栏 + 全树背景色映射 + 文字色遍历 */
    fun applyActivity(activity: Activity, root: View, themeName: String?) {
        if (themeName == null || themeName == "default") return   // 深色=布局默认
        val p = current(themeName)
        try {
            activity.window.statusBarColor = p.bg
            activity.window.navigationBarColor = p.bg
        } catch (_: Throwable) {}
        walk(root, p)
    }

    private fun walk(v: View, p: Palette) {
        // 背景色映射（只改纯色背景，图片/selector 不动）
        try {
            val bg = v.background
            if (bg is ColorDrawable) {
                when (bg.color) {
                    mapBgDark -> v.setBackgroundColor(p.bg)
                    mapPanel -> v.setBackgroundColor(p.panel)
                    mapCard -> v.setBackgroundColor(p.card)
                }
            }
        } catch (_: Throwable) {}
        if (v is TextView) {
            try {
                val c = v.currentTextColor
                v.setTextColor(
                    when (c) {
                        0xFFECEFF6.toInt() -> p.text       // text_primary
                        0xFF9AA6BC.toInt() -> p.textSub     // text_secondary
                        else -> v.currentTextColor
                    }
                )
            } catch (_: Throwable) {}
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), p)
        }
    }
}
