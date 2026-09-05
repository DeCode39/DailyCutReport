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
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "Health Connect permission use"
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 220, 50))
        })
        root.addView(TextView(this).apply {
            text = "Daily Cut Report reads steps, distance, active calories, total calories, and exercise sessions from Health Connect to calculate your daily fitness report and 28-day trends. Optional weight access imports body-weight records; manual weights work without it. Only walking-session summaries are retained—never routes, coordinates, titles, notes, or raw sensor records. Nutrition read access is optional and is used only as a fallback when the local food log is empty. When optional nutrition write access is granted, local food-log changes synchronize automatically and silently to Health Connect on this device. Data stays in app-private storage, Android cloud backup is disabled, and explicit backup files are password-encrypted. The app has no internet permission and uploads nothing. You can revoke Health Connect access in Android Settings at any time."
            textSize = 16f
            setTextColor(Color.rgb(247, 241, 208))
            setPadding(0, dp(16), 0, dp(16))
        })
        root.addView(TextView(this).apply {
            text = "Optional weight write access exports all manual weight recordings and keeps later additions, corrections, and deletions in sync. Imported readings are never written back. Weight read and write permissions are separate and can be revoked independently."
            textSize = 16f
            setTextColor(Color.rgb(247, 241, 208))
        })
        setContentView(android.widget.ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
