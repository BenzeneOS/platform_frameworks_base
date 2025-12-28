/*
 * Copyright (C) 2025 Amaan Qureshi <contact@amaanq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.pipeline.mobile.ui.drawable

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * A custom drawable that renders text in the same style as the network type vector icons
 * (4G, 5G, LTE, etc.). This allows for dynamic custom network type text that matches
 * the native icon style perfectly.
 */
class NetworkTypeTextDrawable(
    private val text: String,
    private val density: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val textBounds = Rect()

    // Match the vector icon dimensions: 22dp x 16dp
    private val intrinsicWidthDp = 22f
    private val intrinsicHeightDp = 16f

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || text.isEmpty()) return

        // Calculate text size to fit within bounds while maintaining aspect ratio
        // The text should fill most of the height (leaving small padding)
        val targetHeight = bounds.height() * 0.85f
        paint.textSize = targetHeight

        // Measure the text and adjust if it's too wide
        paint.getTextBounds(text, 0, text.length, textBounds)
        val textWidth = paint.measureText(text)
        val maxWidth = bounds.width() * 0.95f

        if (textWidth > maxWidth) {
            // Scale down text size to fit width
            paint.textSize = targetHeight * (maxWidth / textWidth)
            paint.getTextBounds(text, 0, text.length, textBounds)
        }

        // Draw text centered in bounds
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY() + (textBounds.height() / 2f) - textBounds.bottom
        canvas.drawText(text, x, y, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = (intrinsicWidthDp * density).toInt()

    override fun getIntrinsicHeight(): Int = (intrinsicHeightDp * density).toInt()

    override fun setTint(tintColor: Int) {
        paint.color = tintColor
        invalidateSelf()
    }
}
