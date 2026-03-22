package com.radiacode.ble

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * Unified Settings hub. Replaces the old in-Activity settings panel.
 * Each row navigates to an existing specialist settings activity.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Wire up each row to its destination
        wireRow(R.id.rowUnits, "Display Units") {
            showUnitsDialog()
        }
        wireRow(R.id.rowPollInterval, "Poll Interval") {
            showPollIntervalDialog()
        }
        updatePollIntervalSubtitle()
        wireRow(R.id.rowAlerts, "Smart Alerts") {
            startActivity(Intent(this, AlertConfigActivity::class.java))
        }
        wireRow(R.id.rowNotifications, "Notifications") {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
        wireRow(R.id.rowSound, "Sound & Geiger") {
            startActivity(Intent(this, SoundSettingsActivity::class.java))
        }
        wireRow(R.id.rowIntelligence, "VEGA Intelligence") {
            startActivity(Intent(this, VegaStatisticalSettingsActivity::class.java))
        }
        wireRow(R.id.rowSpectrogram, "Spectrogram") {
            startActivity(Intent(this, com.radiacode.ble.spectrogram.VegaSpectralAnalysisActivity::class.java))
        }
        wireRow(R.id.rowWidgets, "Widgets") {
            startActivity(Intent(this, WidgetGalleryActivity::class.java))
        }
        wireRow(R.id.rowHelp, "Help Center") {
            startActivity(Intent(this, HelpCenterActivity::class.java))
        }
    }

    private fun wireRow(id: Int, label: String, action: () -> Unit) {
        val row: View = findViewById(id) ?: return
        row.setOnClickListener { action() }
    }

    private fun showUnitsDialog() {
        val ctx = this
        val doseUnits = arrayOf("\u00B5Sv/h", "nSv/h")
        val countUnits = arrayOf("CPS", "CPM")
        val currentDose = if (Prefs.isDoseNanoMode(ctx)) 1 else 0
        val currentCount = if (Prefs.isCountCpmMode(ctx)) 1 else 0

        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
            .setTitle("Dose Unit")
            .setSingleChoiceItems(doseUnits, currentDose) { dialog, which ->
                val unit = if (which == 1) Prefs.DoseUnit.NSV_H else Prefs.DoseUnit.USV_H
                Prefs.setDoseUnit(ctx, unit)
                dialog.dismiss()
                // Then show count unit dialog
                showCountUnitDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCountUnitDialog() {
        val ctx = this
        val countUnits = arrayOf("CPS", "CPM")
        val currentCount = if (Prefs.isCountCpmMode(ctx)) 1 else 0

        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
            .setTitle("Count Unit")
            .setSingleChoiceItems(countUnits, currentCount) { dialog, which ->
                val unit = if (which == 1) Prefs.CountUnit.CPM else Prefs.CountUnit.CPS
                Prefs.setCountUnit(ctx, unit)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePollIntervalSubtitle() {
        val ms = Prefs.getPollIntervalMs(this, 1000L)
        findViewById<TextView>(R.id.pollIntervalSub)?.text = "Currently ${ms}ms -- how often to read from device"
    }

    private fun showPollIntervalDialog() {
        val ctx = this
        val currentMs = Prefs.getPollIntervalMs(ctx, 1000L)

        val input = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentMs.toString())
            setSelection(text.length)
            hint = "250 - 60000"
            setTextColor(resources.getColor(R.color.pro_text_primary, null))
            setHintTextColor(resources.getColor(R.color.pro_text_muted, null))
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 0)
            addView(TextView(ctx).apply {
                text = "Enter the polling interval in milliseconds.\n\n" +
                    "The RadiaCode device accumulates data internally and the app reads " +
                    "it at this interval. Faster polling (e.g. 250-500ms) gives more " +
                    "responsive updates but uses slightly more battery. Slower polling " +
                    "(e.g. 2000-5000ms) saves battery.\n\n" +
                    "Note: The device's own firmware controls the averaging window for " +
                    "dose rate and CPS calculations. Changing the poll rate changes how " +
                    "often you SEE updates, not the measurement accuracy.\n\n" +
                    "Range: 250ms - 60000ms (1 minute)\n" +
                    "Default: 1000ms"
                setTextColor(resources.getColor(R.color.pro_text_secondary, null))
                textSize = 12f
            })
            addView(input)
        }

        androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
            .setTitle("Device Poll Interval")
            .setView(container)
            .setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().toLongOrNull()
                if (value != null && value in 250..60000) {
                    Prefs.setPollIntervalMs(ctx, value)
                    updatePollIntervalSubtitle()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
