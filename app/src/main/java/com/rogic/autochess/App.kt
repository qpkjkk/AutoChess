package com.rogic.autochess

import android.app.Application
import android.util.Log
import java.io.File

/**
 * 全局崩溃记录器：崩溃栈写入内部与外部应用目录，便于定位线上问题。
 * 文件位置：Android/data/com.rogic.autochess/files/crash/crash_<时间戳>.txt
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            installCrashHandler()
        } catch (_: Throwable) {
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stamp = System.currentTimeMillis()
                val content = buildString {
                    append("app: AutoChess\n")
                    append("time: ").append(stamp).append('\n')
                    append("thread: ").append(t.name).append('\n')
                    append(Log.getStackTraceString(e))
                }
                // 双写：内部目录 + 外部应用专属目录（文件管理器可见，无需权限）
                for (base in listOf(filesDir, getExternalFilesDir(null))) {
                    if (base == null) continue
                    try {
                        val dir = File(base, "crash")
                        if (!dir.exists()) dir.mkdirs()
                        File(dir, "crash_$stamp.txt").writeText(content)
                        // 只保留最近 5 个
                        dir.listFiles()
                            ?.sortedByDescending { it.name }
                            ?.drop(5)
                            ?.forEach { it.delete() }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
    }
}
