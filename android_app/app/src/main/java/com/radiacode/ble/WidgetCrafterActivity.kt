package com.radiacode.ble

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Widget Crafter Studio - Central hub for managing all widgets
 * 
 * Features:
 * - View all configured widgets
 * - Edit widget configurations
 * - Duplicate widget configurations
 * - Delete widget configurations
 * - Organize by device
 * - Preview widget appearance
 */
class WidgetCrafterActivity : AppCompatActivity() {

    private lateinit var widgetRecycler: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var adapter: WidgetConfigAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_crafter)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        loadWidgets()
    }

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.backButton)?.setOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        widgetRecycler = findViewById(R.id.widgetRecycler)
        emptyState = findViewById(R.id.emptyState)

        adapter = WidgetConfigAdapter(
            onEdit = { widgetId, config -> editWidget(widgetId, config) },
            onDuplicate = { widgetId, config -> duplicateWidget(widgetId, config) },
            onDelete = { widgetId -> deleteWidget(widgetId) }
        )

        widgetRecycler.layoutManager = LinearLayoutManager(this)
        widgetRecycler.adapter = adapter
    }

    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.addWidgetFab)?.setOnClickListener {
            showAddWidgetInstructions()
        }
    }

    private fun loadWidgets() {
        val allConfigs = Prefs.getAllWidgetConfigs(this)
        
        // Get currently active widget IDs from all providers
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val activeWidgetIds = mutableSetOf<Int>()
        
        listOf(
            ComponentName(this, ChartWidgetProvider::class.java),
            ComponentName(this, SimpleWidgetProvider::class.java),
            ComponentName(this, RadiaCodeWidgetProvider::class.java)
        ).forEach { provider ->
            activeWidgetIds.addAll(appWidgetManager.getAppWidgetIds(provider).toList())
        }
        
        // Filter to only show active widgets with configs, and also add active widgets without configs
        val configuredIds = allConfigs.map { it.widgetId }.toSet()
        val activeConfigsList = mutableListOf<WidgetConfig>()
        
        // Add configured widgets that are still active
        allConfigs.filter { activeWidgetIds.contains(it.widgetId) }.forEach {
            activeConfigsList.add(it)
        }
        
        // Add active widgets without configs (with default config)
        activeWidgetIds.filter { !configuredIds.contains(it) }.forEach { widgetId ->
            activeConfigsList.add(WidgetConfig(widgetId = widgetId))
        }
        
        if (activeConfigsList.isEmpty()) {
            widgetRecycler.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            widgetRecycler.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            adapter.submitList(activeConfigsList.map { it.widgetId to it })
        }
    }

    private fun editWidget(widgetId: Int, config: WidgetConfig) {
        // Launch config activity in edit mode
        val intent = Intent(this, WidgetConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra("edit_mode", true)
        }
        startActivity(intent)
    }

    private fun duplicateWidget(widgetId: Int, config: WidgetConfig) {
        // Find the next available widget ID (negative for "virtual" configs)
        val existingIds = Prefs.getConfiguredWidgetIds(this)
        var newId = -1
        while (existingIds.contains(newId)) {
            newId--
        }
        
        Prefs.duplicateWidgetConfig(this, widgetId, newId, config.deviceId)
        Toast.makeText(this, "Widget configuration duplicated", Toast.LENGTH_SHORT).show()
        loadWidgets()
    }

    private fun deleteWidget(widgetId: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete Widget Configuration")
            .setMessage("Are you sure you want to delete this widget configuration? The widget will revert to default settings.")
            .setPositiveButton("Delete") { _, _ ->
                Prefs.deleteWidgetConfig(this, widgetId)
                Toast.makeText(this, "Widget configuration deleted", Toast.LENGTH_SHORT).show()
                loadWidgets()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddWidgetInstructions() {
        AlertDialog.Builder(this)
            .setTitle("Add Widget to Home Screen")
            .setMessage(
                "To add a widget:\n\n" +
                "1. Long-press on your home screen\n" +
                "2. Tap \"Widgets\"\n" +
                "3. Find \"Open RadiaCode\" widgets\n" +
                "4. Drag your preferred widget to the home screen\n" +
                "5. Configure the widget when prompted"
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadWidgets()
    }
}

/**
 * Adapter for displaying widget configurations in a RecyclerView
 */
class WidgetConfigAdapter(
    private val onEdit: (Int, WidgetConfig) -> Unit,
    private val onDuplicate: (Int, WidgetConfig) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<WidgetConfigAdapter.ViewHolder>() {

    private var items: List<Pair<Int, WidgetConfig>> = emptyList()

    fun submitList(newItems: List<Pair<Int, WidgetConfig>>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_widget_config, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (widgetId, config) = items[position]
        holder.bind(widgetId, config)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.widgetCard)
        private val templateName: TextView = view.findViewById(R.id.templateName)
        private val deviceName: TextView = view.findViewById(R.id.deviceName)
        private val colorScheme: TextView = view.findViewById(R.id.colorScheme)
        private val chartType: TextView = view.findViewById(R.id.chartType)
        private val editButton: ImageButton = view.findViewById(R.id.editButton)
        private val duplicateButton: ImageButton = view.findViewById(R.id.duplicateButton)
        private val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)

        fun bind(widgetId: Int, config: WidgetConfig) {
            // Template name
            templateName.text = config.layoutTemplate.name.replace("_", " ").lowercase()
                .replaceFirstChar { it.uppercase() }
            
            // Device name
            val device = if (config.deviceId != null) {
                Prefs.getDevices(itemView.context).find { it.id == config.deviceId }?.displayName ?: config.deviceId
            } else {
                "All Devices"
            }
            deviceName.text = device
            
            // Color scheme
            colorScheme.text = "🎨 ${config.colorScheme.name.lowercase().replaceFirstChar { it.uppercase() }}"
            
            // Chart type
            chartType.text = "📊 ${config.chartType.name.lowercase().replaceFirstChar { it.uppercase() }}"
            
            // Action buttons
            editButton.setOnClickListener { onEdit(widgetId, config) }
            duplicateButton.setOnClickListener { onDuplicate(widgetId, config) }
            deleteButton.setOnClickListener { onDelete(widgetId) }
            
            // Card click for edit
            card.setOnClickListener { onEdit(widgetId, config) }
        }
    }
}
