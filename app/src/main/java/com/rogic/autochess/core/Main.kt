package com.rogic.autochess.core

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Sandbox driver entry (core extraction surgery - knife 3) - stdin/stdout JSON line protocol.
 * Input lines:
 *   {"cmd":"start"}
 *   {"cmd":"frame","placement":"rnbq...","whiteAtBottom":true,"pieceCount":32}
 *   {"cmd":"tick","nowMs":12345}
 * Output lines:
 *   {"click":{"fx":6,"fy":4,"tx":4,"ty":4}}
 *   {"status":"..."}
 *   {"ok":true}
 */
fun main() {
    val clock = object : Clock {
        var t = 0L
        override fun now(): Long = t
    }
    val scheduler = object : Scheduler {
        override fun post(action: () -> Unit) { action() }
        override fun postDelayed(ms: Long, action: () -> Unit) { action() }
    }
    val writer = System.out.bufferedWriter()
    val out = object : AutoPlayCore.CoreOut {
        override fun onClick(fx: Int, fy: Int, tx: Int, ty: Int) {
            writer.write("{\"click\":{\"fx\":" + fx + ",\"fy\":" + fy + ",\"tx\":" + tx + ",\"ty\":" + ty + "}}")
            writer.newLine(); writer.flush()
        }
        override fun onStatus(text: String) {
            val esc = escapeStatus(text)
            writer.write("{\"status\":\"" + esc + "\"}")
            writer.newLine(); writer.flush()
        }
    }
    val core = AutoPlayCore(Platform(clock, scheduler), out)
    val reader = BufferedReader(InputStreamReader(System.`in`))
    while (true) {
        val line = reader.readLine() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        try {
            when {
                trimmed.contains("start") -> { core.start(); writer.write("{\"ok\":true}"); writer.newLine(); writer.flush() }
                trimmed.contains("frame") -> {
                    val plac = extractStr(trimmed, "placement")
                    val wab = trimmed.contains("whiteAtBottom\":true")
                    val pc = extractInt(trimmed, "pieceCount") ?: 0
                    if (plac != null) {
                        core.onFrame(AutoPlayCore.Frame(plac, wab, pc))
                        writer.write("{\"ok\":true}"); writer.newLine(); writer.flush()
                    }
                }
                trimmed.contains("myMove") -> {
                    val uci = extractStr(trimmed, "uci")
                    if (uci != null) {
                        core.setupVerifyAndClick(uci)
                        writer.write("{\"ok\":true}"); writer.newLine(); writer.flush()
                    }
                }
                trimmed.contains("tick") -> { clock.t = (extractInt(trimmed, "nowMs") ?: 0).toLong() }
            }
        } catch (e: Exception) {
            writer.write("{\"error\":\"" + (e.message ?: e.javaClass.simpleName) + "\"}")
            writer.newLine(); writer.flush()
        }
    }
}

private fun extractStr(json: String, key: String): String? {
    val i = json.indexOf("\"" + key + "\"")
    if (i < 0) return null
    val j = json.indexOf(':', i + key.length + 2)
    if (j < 0) return null
    val k = json.indexOf('"', j + 1)
    if (k < 0) return null
    val m = json.indexOf('"', k + 1)
    if (m < 0) return null
    return json.substring(k + 1, m)
}
private fun extractInt(json: String, key: String): Int? {
    val i = json.indexOf("\"" + key + "\"")
    if (i < 0) return null
    val j = json.indexOf(':', i + key.length + 2)
    if (j < 0) return null
    var m = j + 1
    while (m < json.length && json.get(m) == ' ') m++
    val n = m
    while (m < json.length && json.get(m).isDigit()) m++
    return if (m > n) json.substring(n, m).toIntOrNull() else null
}



private fun escapeStatus(text: String): String {
    val sb = StringBuilder(text.length + 8)
    for (c in text) {
        if (c.code == 92 || c.code == 34) sb.append(92.toChar())  // BS / quote 前面加 BS
        sb.append(c)
    }
    return sb.toString()
}