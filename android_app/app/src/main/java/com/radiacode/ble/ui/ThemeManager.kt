package com.radiacode.ble.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.radiacode.ble.R

/**
 * Theme Manager for Dark/OLED Black Theme Toggle
 * 
 * Provides color schemes for different themes:
 * - Pro Dark: Current default
 * - OLED Black: True black for AMOLED screens (saves battery)
 * - High Contrast: Maximum readability
 */
class ThemeManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
    
    var currentTheme: Theme
        get() = Theme.valueOf(prefs.getString("current_theme", Theme.PRO_DARK.name) ?: Theme.PRO_DARK.name)
        set(value) {
            prefs.edit().putString("current_theme", value.name).apply()
            onThemeChanged?.invoke(value)
        }
    
    var onThemeChanged: ((Theme) -> Unit)? = null

    /**
     * Get colors for the current theme.
     */
    fun getColors(): ThemeColors {
        return when (currentTheme) {
            Theme.PRO_DARK -> ThemeColors(
                background = ContextCompat.getColor(context, R.color.pro_background),
                surface = ContextCompat.getColor(context, R.color.pro_surface),
                border = ContextCompat.getColor(context, R.color.pro_border),
                textPrimary = Color.WHITE,
                textSecondary = ContextCompat.getColor(context, R.color.pro_text_secondary),
                muted = ContextCompat.getColor(context, R.color.pro_text_muted),
                cyan = ContextCompat.getColor(context, R.color.pro_cyan),
                magenta = ContextCompat.getColor(context, R.color.pro_magenta),
                green = ContextCompat.getColor(context, R.color.pro_green),
                red = ContextCompat.getColor(context, R.color.pro_red),
                yellow = ContextCompat.getColor(context, R.color.pro_yellow)
            )
            
            Theme.OLED_BLACK -> ThemeColors(
                background = Color.BLACK,
                surface = Color.rgb(10, 10, 10),
                border = Color.rgb(30, 30, 30),
                textPrimary = Color.WHITE,
                textSecondary = Color.rgb(180, 180, 180),
                muted = Color.rgb(100, 100, 100),
                cyan = Color.rgb(0, 255, 255),       // Brighter cyan
                magenta = Color.rgb(255, 0, 255),    // Brighter magenta
                green = Color.rgb(0, 255, 128),      // Brighter green
                red = Color.rgb(255, 64, 64),        // Brighter red
                yellow = Color.rgb(255, 230, 0)      // Brighter yellow
            )
            
            Theme.HIGH_CONTRAST -> ThemeColors(
                background = Color.BLACK,
                surface = Color.rgb(20, 20, 20),
                border = Color.WHITE,
                textPrimary = Color.WHITE,
                textSecondary = Color.rgb(200, 200, 200),
                muted = Color.rgb(150, 150, 150),
                cyan = Color.CYAN,
                magenta = Color.MAGENTA,
                green = Color.GREEN,
                red = Color.RED,
                yellow = Color.YELLOW
            )
            
            Theme.NIGHT_VISION -> ThemeColors(
                background = Color.BLACK,
                surface = Color.rgb(5, 10, 5),
                border = Color.rgb(20, 40, 20),
                textPrimary = Color.rgb(100, 255, 100),
                textSecondary = Color.rgb(70, 180, 70),
                muted = Color.rgb(50, 100, 50),
                cyan = Color.rgb(80, 255, 180),
                magenta = Color.rgb(180, 100, 180),
                green = Color.rgb(100, 255, 100),
                red = Color.rgb(255, 100, 100),
                yellow = Color.rgb(200, 255, 100)
            )
        }
    }

    /**
     * Apply theme colors to a view hierarchy.
     * Can be used with ViewBinding or manual application.
     */
    fun applyTheme(applicator: ThemeApplicator) {
        val colors = getColors()
        applicator.apply(colors)
    }

    /**
     * Get CSS-style color string for WebView or HTML content.
     */
    fun getCssColors(): String {
        val colors = getColors()
        return """
            :root {
                --background: ${colors.background.toCssColor()};
                --surface: ${colors.surface.toCssColor()};
                --border: ${colors.border.toCssColor()};
                --text-primary: ${colors.textPrimary.toCssColor()};
                --text-secondary: ${colors.textSecondary.toCssColor()};
                --muted: ${colors.muted.toCssColor()};
                --cyan: ${colors.cyan.toCssColor()};
                --magenta: ${colors.magenta.toCssColor()};
                --green: ${colors.green.toCssColor()};
                --red: ${colors.red.toCssColor()};
                --yellow: ${colors.yellow.toCssColor()};
            }
        """.trimIndent()
    }

    private fun Int.toCssColor(): String {
        return "rgb(${Color.red(this)}, ${Color.green(this)}, ${Color.blue(this)})"
    }

    enum class Theme(val displayName: String, val description: String) {
        PRO_DARK("Pro Dark", "Default professional dark theme"),
        OLED_BLACK("OLED Black", "True black for AMOLED screens"),
        HIGH_CONTRAST("High Contrast", "Maximum readability"),
        NIGHT_VISION("Night Vision", "Red-shifted to preserve night vision")
    }

    data class ThemeColors(
        val background: Int,
        val surface: Int,
        val border: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val muted: Int,
        val cyan: Int,
        val magenta: Int,
        val green: Int,
        val red: Int,
        val yellow: Int
    ) {
        fun getColorStateList(colorGetter: (ThemeColors) -> Int): ColorStateList {
            return ColorStateList.valueOf(colorGetter(this))
        }
    }

    /**
     * Interface for applying theme colors to views.
     */
    fun interface ThemeApplicator {
        fun apply(colors: ThemeColors)
    }

    companion object {
        @Volatile
        private var instance: ThemeManager? = null
        
        fun getInstance(context: Context): ThemeManager {
            return instance ?: synchronized(this) {
                instance ?: ThemeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
