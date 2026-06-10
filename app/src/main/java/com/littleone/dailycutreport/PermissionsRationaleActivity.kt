package com.littleone.dailycutreport

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.math.roundToInt

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(248, 248, 246))
        }
        root.addView(TextView(this).apply {
            text = "Health Connect permission use"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 20, 20))
        })
        root.addView(TextView(this).apply {
            text = "Daily Cut Report reads steps, distance, active calories, total calories, and exercise sessions from Health Connect only to calculate your daily fitness report. Data is stored locally on your device using app private storage. The app does not request internet permission and does not upload data. You can revoke Health Connect access in Android Settings at any time."
            textSize = 16f
            setTextColor(Color.rgb(70, 70, 70))
            setPadding(0, dp(16), 0, dp(16))
        })
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
