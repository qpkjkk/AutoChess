package com.rogic.autochess.chess

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import com.caverock.androidsvg.SVG

/**
 * 棋子素材：优先从 SVG 矢量渲染（任意分辨率、完美透明），
 * 失败时回退到 PNG（已做透明处理）。
 */
object PieceAssets {

    const val SET_DEFAULT = "cburnett"
    val SETS = listOf("cburnett", "merida", "alpha")

    private val cache = HashMap<String, Bitmap>()
    private var loadedSet: String? = null

    @Volatile
    var currentSet: String = SET_DEFAULT

    val pieceKeys = listOf(
        "wK", "wQ", "wR", "wB", "wN", "wP",
        "bK", "bQ", "bR", "bB", "bN", "bP"
    )

    fun ensureLoaded(context: Context, set: String = currentSet, sizePx: Int = 288) {
        if (loadedSet == set) return
        synchronized(this) {
            if (loadedSet == set) return
            cache.clear()
            val am = context.assets
            for (key in pieceKeys) {
                val bmp = renderSvg(am, set, key, sizePx)
                    ?: renderPng(am, set, key)
                    ?: continue
                cache[key] = bmp
            }
            loadedSet = set
        }
    }

    private fun renderSvg(am: android.content.res.AssetManager, set: String, key: String, sizePx: Int): Bitmap? {
        return try {
            val svg = SVG.getFromInputStream(am.open("pieces/$set/$key.svg"))
            svg.setDocumentWidth(sizePx.toFloat())
            svg.setDocumentHeight(sizePx.toFloat())
            val picture: Picture = svg.renderToPicture()
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawPicture(picture)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun renderPng(am: android.content.res.AssetManager, set: String, key: String): Bitmap? {
        return try {
            android.graphics.BitmapFactory.decodeStream(am.open("pieces/$set/png/$key.png"))
        } catch (_: Exception) {
            null
        }
    }

    fun get(key: String): Bitmap? = cache[key]

    fun bitmap(pieceCode: String): Bitmap? = get(pieceCode)
}
