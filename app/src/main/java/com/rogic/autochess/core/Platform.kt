package com.rogic.autochess.core

/**
 * 宿主无关平台接口（抽核手术·刀 1）
 *
 * APK 宿主: AndroidScheduler（Handler/mainHandler 实现）
 * 沙箱宿主: VirtualScheduler（虚拟时钟——可暂停/快进/单步，回放器驱动）
 *
 * 铁律: core 内禁止 import android.*（编译期由 lint 兜底）。
 */
interface Clock {
    /** 单调毫秒（APK=SystemClock.elapsedRealtime / 沙箱=虚拟时钟） */
    fun now(): Long
}

interface Scheduler {
    /** 主线程/驱动线程异步执行（APK=Handler.post / 沙箱=立即或队列） */
    fun post(action: () -> Unit)
    /** 延迟执行（APK=Handler.postDelayed / 沙箱=虚拟时间轴调度） */
    fun postDelayed(ms: Long, action: () -> Unit)
}

/** 平台能力注入容器 */
class Platform(val clock: Clock, val scheduler: Scheduler)
