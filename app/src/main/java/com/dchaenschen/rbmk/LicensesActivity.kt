package com.dchaenschen.rbmk

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 开源许可页。
 *
 * 本项目是基于 Andy Wang 的 reactor-audio-visualizer（MIT 许可）的衍生作品。
 * MIT 要求：再分发时必须附带原始版权声明与许可全文 —— 这一页就是干这个的。
 */
class LicensesActivity : Activity() {

    private val mitText = """
MIT License

Copyright (c) 2026 Andy Wang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
""".trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0a0d0b"))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "开源许可"
            setTextColor(Color.parseColor("#e6ece0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        })

        root.addView(TextView(this).apply {
            text = "本应用是 Andy Wang 的 reactor-audio-visualizer 的衍生作品，" +
                    "原始项目以 MIT 许可证发布。控制棒的几何与投影算法移植自原项目，"
                    "在此基础上做了 Android 移植（动态壁纸、音频回采、歌名读取）与金属配色调整。"
            setTextColor(Color.parseColor("#8e998a"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, p, 0, p)
        })

        root.addView(TextView(this).apply {
            text = mitText
            setTextColor(Color.parseColor("#c9d1c9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
        })

        root.addView(TextView(this).apply {
            text = "\n原作者：Andy Wang" +
                    "\n原项目：https://github.com/wzk112/reactor-audio-visualizer" +
                    "\n\n本应用在其基础上增加了 Android 动态壁纸实现、" +
                    "系统音频采集（AudioPlaybackCapture）、" +
                    "播放信息读取（NotificationListenerService）与金属配色。"
            setTextColor(Color.parseColor("#7f8a7c"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })

        setContentView(scroll)
    }
}
