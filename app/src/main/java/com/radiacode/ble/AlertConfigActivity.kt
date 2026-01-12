package com.radiacode.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import java.util.UUID

/**
 * Smart Alerts Configuration Activity
 * 
 * Provides a wizard-like interface for managing 0-10 statistical alerts.
 * Alerts can trigger based on:
 * - Absolute threshold (above/below value)
 * - Statistical significance (outside N standard deviations)
 * - Duration requirement (must persist for X seconds)
 */
class AlertConfigActivity : AppCompatActivity() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "radiacode_alerts"
        const val NOTIFICATION_CHANNEL_NAME = "RadiaCode Alerts"
        const val MAX_ALERTS = 10
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var fab: FloatingActionButton
    private lateinit var adapter: AlertAdapter

    private var alerts = mutableListOf<Prefs.SmartAlert>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert_config)

        // Ensure notification channel exists
        createNotificationChannel()
        
        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.alertRecyclerView)
        emptyView = findViewById(R.id.emptyView)
        fab = findViewById(R.id.fabAddAlert)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Smart Alerts"
        toolbar.setNavigationOnClickListener { finish() }

        // Load alerts
        alerts = Prefs.getSmartAlerts(this).toMutableList()

        // Setup RecyclerView
        adapter = AlertAdapter(
            alerts = alerts,
            onEdit = { position -> showAlertDialog(position) },
            onToggle = { position, enabled -> toggleAlert(position, enabled) },
            onDelete = { position -> deleteAlert(position) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Swipe to delete
        val itemTouchHelper = ItemTouchHelper(SwipeToDeleteCallback { position ->
            deleteAlert(position)
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)

        fab.setOnClickListener {
            if (alerts.size >= MAX_ALERTS) {
                Toast.makeText(this, "Maximum $MAX_ALERTS alerts allowed", Toast.LENGTH_SHORT).show()
            } else {
                showAlertDialog(null)
            }
        }

        // Empty state button
        findViewById<View>(R.id.btnCreateFirst)?.setOnClickListener {
            showAlertDialog(null)
        }

        updateEmptyState()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Radiation level alerts"
                enableVibration(true)
                enableLights(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun updateEmptyState() {
        emptyView.visibility = if (alerts.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (alerts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showAlertDialog(editPosition: Int?) {
        val isEdit = editPosition != null
        val existing = if (isEdit) alerts[editPosition!!] else null

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_alert_config, null)
        
        // Find views
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.inputAlertName)
        val metricSpinner = dialogView.findViewById<Spinner>(R.id.spinnerMetric)
        val conditionSpinner = dialogView.findViewById<Spinner>(R.id.spinnerCondition)
        val thresholdLayout = dialogView.findViewById<TextInputLayout>(R.id.layoutThreshold)
        val thresholdInput = dialogView.findViewById<TextInputEditText>(R.id.inputThreshold)
        val sigmaLayout = dialogView.findViewById<TextInputLayout>(R.id.layoutSigma)
        val sigmaSpinner = dialogView.findViewById<Spinner>(R.id.spinnerSigma)
        val durationInput = dialogView.findViewById<TextInputEditText>(R.id.inputDuration)
        val cooldownInput = dialogView.findViewById<TextInputEditText>(R.id.inputCooldown)

        // Setup metric spinner
        val metrics = listOf("Dose Rate" to "dose", "Count Rate" to "count")
        metricSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, 
            metrics.map { it.first })

        // Setup condition spinner
        val conditions = listOf(
            "Above threshold" to "above",
            "Below threshold" to "below",
            "Outside σ band" to "outside_sigma"
        )
        conditionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            conditions.map { it.first })

        // Setup sigma spinner (1σ, 2σ, 3σ)
        val sigmaOptions = listOf("1σ (68%)" to 1.0, "2σ (95%)" to 2.0, "3σ (99.7%)" to 3.0)
        sigmaSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            sigmaOptions.map { it.first })

        // Show/hide fields based on condition
        conditionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val isSigma = conditions[pos].second == "outside_sigma"
                thresholdLayout.visibility = if (isSigma) View.GONE else View.VISIBLE
                sigmaLayout.visibility = if (isSigma) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Pre-populate if editing
        if (existing != null) {
            nameInput.setText(existing.name)
            metricSpinner.setSelection(metrics.indexOfFirst { it.second == existing.metric }.coerceAtLeast(0))
            conditionSpinner.setSelection(conditions.indexOfFirst { it.second == existing.condition }.coerceAtLeast(0))
            if (existing.condition == "outside_sigma") {
                sigmaSpinner.setSelection(sigmaOptions.indexOfFirst { it.second == existing.sigma }.coerceAtLeast(0))
            } else {
                thresholdInput.setText(String.format(Locale.US, "%.4f", existing.threshold))
            }
            durationInput.setText(existing.durationSeconds.toString())
            cooldownInput.setText((existing.cooldownMs / 1000).toString())
        } else {
            // Defaults
            durationInput.setText("5")
            cooldownInput.setText("60")
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_RadiaCodeBLE_Dialog)
            .setTitle(if (isEdit) "Edit Alert" else "Create Alert")
            .setView(dialogView)
            .setPositiveButton(if (isEdit) "Save" else "Create", null) // Set later for validation
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Validate
                val name = nameInput.text?.toString()?.trim() ?: ""
                if (name.isEmpty()) {
                    nameInput.error = "Name required"
                    return@setOnClickListener
                }

                val metric = metrics[metricSpinner.selectedItemPosition].second
                val condition = conditions[conditionSpinner.selectedItemPosition].second
                
                val threshold: Double
                val sigma: Double
                
                if (condition == "outside_sigma") {
                    threshold = 0.0
                    sigma = sigmaOptions[sigmaSpinner.selectedItemPosition].second
                } else {
                    val thresholdText = thresholdInput.text?.toString()?.trim() ?: ""
                    threshold = thresholdText.toDoubleOrNull() ?: run {
                        thresholdInput.error = "Invalid number"
                        return@setOnClickListener
                    }
                    sigma = 2.0
                }

                val duration = durationInput.text?.toString()?.toIntOrNull() ?: 5
                val cooldown = (cooldownInput.text?.toString()?.toIntOrNull() ?: 60) * 1000L

                val alert = Prefs.SmartAlert(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    enabled = existing?.enabled ?: true,
                    metric = metric,
                    condition = condition,
                    threshold = threshold,
                    sigma = sigma,
                    durationSeconds = duration,
                    cooldownMs = cooldown,
                    lastTriggeredMs = existing?.lastTriggeredMs ?: 0L
                )

                if (isEdit) {
                    Prefs.updateSmartAlert(this, alert)
                    alerts[editPosition!!] = alert
                    adapter.notifyItemChanged(editPosition)
                } else {
                    Prefs.addSmartAlert(this, alert)
                    alerts.add(alert)
                    adapter.notifyItemInserted(alerts.size - 1)
                }
                updateEmptyState()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun toggleAlert(position: Int, enabled: Boolean) {
        val alert = alerts[position].copy(enabled = enabled)
        Prefs.updateSmartAlert(this, alert)
        alerts[position] = alert
    }

    private fun deleteAlert(position: Int) {
        val alert = alerts[position]
        Prefs.deleteSmartAlert(this, alert.id)
        alerts.removeAt(position)
        adapter.notifyItemRemoved(position)
        updateEmptyState()
    }

    // RecyclerView Adapter
    private class AlertAdapter(
        private val alerts: MutableList<Prefs.SmartAlert>,
        private val onEdit: (Int) -> Unit,
        private val onToggle: (Int, Boolean) -> Unit,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<AlertAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.alertName)
            val description: TextView = view.findViewById(R.id.alertDescription)
            val switch: SwitchMaterial = view.findViewById(R.id.alertSwitch)
            val editButton: ImageButton = view.findViewById(R.id.btnEdit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_alert, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val alert = alerts[position]
            holder.name.text = alert.name
            holder.description.text = formatDescription(alert)
            holder.switch.isChecked = alert.enabled
            holder.switch.setOnCheckedChangeListener { _, isChecked ->
                onToggle(holder.adapterPosition, isChecked)
            }
            holder.editButton.setOnClickListener { onEdit(holder.adapterPosition) }
            holder.itemView.setOnClickListener { onEdit(holder.adapterPosition) }
        }

        override fun getItemCount() = alerts.size

        private fun formatDescription(alert: Prefs.SmartAlert): String {
            val metricLabel = if (alert.metric == "dose") "Dose" else "Count"
            val conditionLabel = when (alert.condition) {
                "above" -> "above ${String.format(Locale.US, "%.4f", alert.threshold)}"
                "below" -> "below ${String.format(Locale.US, "%.4f", alert.threshold)}"
                "outside_sigma" -> "outside ${alert.sigma.toInt()}σ"
                else -> alert.condition
            }
            return "$metricLabel $conditionLabel for ${alert.durationSeconds}s"
        }
    }

    // Swipe to delete callback
    private class SwipeToDeleteCallback(
        private val onSwipe: (Int) -> Unit
    ) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            onSwipe(viewHolder.adapterPosition)
        }
    }
}
