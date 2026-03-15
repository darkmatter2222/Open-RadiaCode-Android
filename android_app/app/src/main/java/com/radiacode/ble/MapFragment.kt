package com.radiacode.ble

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.radiacode.ble.location.LocationController
import com.radiacode.ble.ui.MapCardView
import java.io.File

/**
 * Full-screen Map tab with GPS tier selector, route recording, and export.
 * Implements Points 3, 11, 12, 13, 14, 15, 16.
 */
class MapFragment : Fragment() {

    // Map
    private lateinit var mapCard: MapCardView

    // GPS tier chips
    private lateinit var chipGpsPassive: TextView
    private lateinit var chipGpsBalanced: TextView
    private lateinit var chipGpsHigh: TextView
    private lateinit var gpsAccuracyLabel: TextView

    // Bottom bar controls
    private lateinit var btnExportData: MaterialButton
    private lateinit var btnSessions: MaterialButton
    private lateinit var btnClearMapData: MaterialButton
    private lateinit var btnMapTheme: ImageButton

    // Route recording
    private lateinit var routeRecordingBar: View
    private lateinit var routeRecordingLabel: TextView
    private lateinit var routeRecordingTime: TextView

    private val mainActivity: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupGpsTierSelector()
        setupMapControls()
        setupBottomBar()
        loadMap()
    }

    private fun bindViews(view: View) {
        mapCard = view.findViewById(R.id.mapCard)
        chipGpsPassive = view.findViewById(R.id.chipGpsPassive)
        chipGpsBalanced = view.findViewById(R.id.chipGpsBalanced)
        chipGpsHigh = view.findViewById(R.id.chipGpsHigh)
        gpsAccuracyLabel = view.findViewById(R.id.gpsAccuracyLabel)
        btnExportData = view.findViewById(R.id.btnExportData)
        btnSessions = view.findViewById(R.id.btnSessions)
        btnClearMapData = view.findViewById(R.id.btnClearMapData)
        btnMapTheme = view.findViewById(R.id.btnMapTheme)
        routeRecordingBar = view.findViewById(R.id.routeRecordingBar)
        routeRecordingLabel = view.findViewById(R.id.routeRecordingLabel)
        routeRecordingTime = view.findViewById(R.id.routeRecordingTime)
    }

    private fun setupGpsTierSelector() {
        val ctx = requireContext()
        // Read current GPS tier from prefs
        val currentTier = Prefs.getGpsTier(ctx)
        updateGpsTierHighlight(currentTier)

        // Always enable GPS in passive mode by default (Point 11 - zero battery cost)
        if (!Prefs.isGpsTrackingEnabled(ctx)) {
            // Auto-enable passive GPS - no warning needed
            Prefs.setGpsTrackingEnabled(ctx, true)
            Prefs.setGpsTier(ctx, Prefs.GpsTier.PASSIVE)
        }

        chipGpsPassive.setOnClickListener {
            setGpsTier(Prefs.GpsTier.PASSIVE)
        }
        chipGpsBalanced.setOnClickListener {
            setGpsTier(Prefs.GpsTier.BALANCED)
        }
        chipGpsHigh.setOnClickListener {
            // Show battery warning only for high precision (Point 12)
            AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
                .setTitle("High Precision GPS")
                .setMessage("High precision mode uses the GPS radio for ~1-3m accuracy. This will increase battery drain significantly.\n\nContinue?")
                .setPositiveButton("Enable") { _, _ ->
                    setGpsTier(Prefs.GpsTier.HIGH)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setGpsTier(tier: Prefs.GpsTier) {
        val ctx = requireContext()
        Prefs.setGpsTier(ctx, tier)
        Prefs.setGpsTrackingEnabled(ctx, true)
        updateGpsTierHighlight(tier)
        mapCard.updateGpsTrackingState()
        // Notify location controller about mode change
        LocationController.getInstance(ctx).setGpsTier(tier)
        RadiaCodeForegroundService.reloadDevices(ctx)
    }

    private fun updateGpsTierHighlight(tier: Prefs.GpsTier) {
        val ctx = requireContext()
        val active = ContextCompat.getColor(ctx, R.color.pro_green)
        val inactive = ContextCompat.getColor(ctx, R.color.pro_text_muted)
        val amber = ContextCompat.getColor(ctx, R.color.pro_amber)
        val red = ContextCompat.getColor(ctx, R.color.pro_red)

        chipGpsPassive.setTextColor(if (tier == Prefs.GpsTier.PASSIVE) active else inactive)
        chipGpsBalanced.setTextColor(if (tier == Prefs.GpsTier.BALANCED) amber else inactive)
        chipGpsHigh.setTextColor(if (tier == Prefs.GpsTier.HIGH) red else inactive)

        gpsAccuracyLabel.text = when (tier) {
            Prefs.GpsTier.PASSIVE -> "~varies"
            Prefs.GpsTier.BALANCED -> "~100m"
            Prefs.GpsTier.HIGH -> "~3m"
        }
    }

    private fun setupMapControls() {
        btnMapTheme.setOnClickListener {
            showMapThemeDialog()
        }

        // Map card doesn't need the "enable GPS" overlay anymore since GPS
        // is always in at least passive mode. But update state just in case.
        mapCard.onEnableGpsRequested = {
            setGpsTier(Prefs.GpsTier.PASSIVE)
        }
    }

    private fun setupBottomBar() {
        // Export button (Point 14, 16)
        btnExportData.setOnClickListener {
            exportData()
        }

        // Sessions button (Point 15)
        btnSessions.setOnClickListener {
            startActivity(Intent(requireContext(), SessionListActivity::class.java))
        }

        // Clear map data
        btnClearMapData.setOnClickListener {
            showClearMapDataDialog()
        }
    }

    private fun loadMap() {
        val ctx = requireContext()
        if (Prefs.isGpsTrackingEnabled(ctx)) {
            mapCard.loadDataPoints()
            mapCard.startLocationTracking()
        }
    }

    /**
     * Export session data and show snackbar with "OPEN" action (Point 16).
     */
    private fun exportData() {
        val ctx = requireContext()
        val ma = mainActivity ?: return

        try {
            val dataPoints = ma.buildExportDataPoints()
            if (dataPoints.isEmpty()) {
                Toast.makeText(ctx, "No data to export", Toast.LENGTH_SHORT).show()
                return
            }

            val metadata = DataExportManager.SessionMetadata(
                sessionId = DataExportManager.generateSessionId(),
                startTime = ma.sessionStartMs,
                endTime = System.currentTimeMillis(),
                sampleCount = ma.sampleCount,
                deviceIds = Prefs.getDevices(ctx).filter { it.enabled }.map { it.id },
                appVersion = "1.38"
            )

            val results = DataExportManager.exportSession(ctx, dataPoints, metadata)

            // Show snackbar with "OPEN" action (Point 16)
            val fileCount = results.size
            val snackbar = Snackbar.make(
                requireView(),
                "Exported $fileCount file(s)",
                Snackbar.LENGTH_LONG
            )
            snackbar.setAction("SHARE") {
                // Share the CSV file
                val csvFile = results[DataExportManager.ExportFormat.CSV]
                if (csvFile != null) {
                    shareFile(csvFile)
                }
            }
            snackbar.setActionTextColor(ContextCompat.getColor(ctx, R.color.pro_cyan))
            snackbar.view.setBackgroundColor(ContextCompat.getColor(ctx, R.color.pro_surface))
            snackbar.show()

        } catch (e: Exception) {
            Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File) {
        val ctx = requireContext()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share export"))
    }

    private fun showMapThemeDialog() {
        val ctx = requireContext()
        val themes = Prefs.MapTheme.values()
        val themeNames = themes.map { it.displayName }.toTypedArray()
        val current = themes.indexOf(Prefs.getMapTheme(ctx))

        AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
            .setTitle("Map Theme")
            .setSingleChoiceItems(themeNames, current) { dialog, which ->
                val selected = themes[which]
                Prefs.setMapTheme(ctx, selected)
                mapCard.setMapTheme(selected)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearMapDataDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx, R.style.DarkDialogTheme)
            .setTitle("Clear Map Data")
            .setMessage("This will permanently delete all hexagon readings from the radiation map. This action cannot be undone.\n\nContinue?")
            .setPositiveButton("Clear Data") { _, _ ->
                Prefs.clearMapDataPoints(ctx)
                mapCard.clearMapData()
                Toast.makeText(ctx, "Map data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Called by MainActivity when a new reading arrives (any device).
     */
    fun addReading(uSvH: Float, cps: Float) {
        if (!isAdded || view == null) return
        mapCard.addReading(uSvH, cps)
    }

    /**
     * Update GPS accuracy display.
     */
    fun updateGpsAccuracy(accuracyMeters: Float) {
        if (!isAdded || view == null) return
        gpsAccuracyLabel.text = "~${accuracyMeters.toInt()}m"
    }

    override fun onResume() {
        super.onResume()
        val ctx = context ?: return
        if (Prefs.isGpsTrackingEnabled(ctx)) {
            mapCard.startLocationTracking()
        }
        // Update chip state
        val tier = Prefs.getGpsTier(ctx)
        updateGpsTierHighlight(tier)
    }

    override fun onPause() {
        super.onPause()
        mapCard.stopLocationTracking()
    }
}
