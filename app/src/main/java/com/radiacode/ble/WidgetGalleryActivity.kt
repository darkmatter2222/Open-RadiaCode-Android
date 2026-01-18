package com.radiacode.ble

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * Widget Gallery Activity
 * Browse available widget styles with live previews before adding to home screen
 */
class WidgetGalleryActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    
    data class WidgetInfo(
        val name: String,
        val description: String,
        val previewImage: Int, // Resource ID
        val widgetClass: Class<*>,
        val minWidth: Int,
        val minHeight: Int,
        val features: List<String>
    )

    private val widgets = listOf(
        WidgetInfo(
            name = "Compact Widget",
            description = "Minimal widget showing current dose rate with color-coded background",
            previewImage = R.drawable.widget_preview_compact,
            widgetClass = UnifiedWidgetProvider::class.java,
            minWidth = 2,
            minHeight = 1,
            features = listOf("Dose rate", "Color status", "Small footprint")
        ),
        WidgetInfo(
            name = "Standard Widget",
            description = "Shows dose rate, count rate, and connection status",
            previewImage = R.drawable.widget_preview_standard,
            widgetClass = UnifiedWidgetProvider::class.java,
            minWidth = 3,
            minHeight = 2,
            features = listOf("Dose rate", "Count rate", "Status indicator", "Last update time")
        ),
        WidgetInfo(
            name = "Chart Widget",
            description = "Full-featured widget with sparkline history chart",
            previewImage = R.drawable.widget_preview_chart,
            widgetClass = UnifiedWidgetProvider::class.java,
            minWidth = 4,
            minHeight = 2,
            features = listOf("Dose rate", "Count rate", "Sparkline chart", "Trend indicator", "Statistics")
        ),
        WidgetInfo(
            name = "Map Widget",
            description = "Shows current location with radiation overlay on a mini map",
            previewImage = R.drawable.widget_preview_map,
            widgetClass = UnifiedWidgetProvider::class.java,
            minWidth = 4,
            minHeight = 3,
            features = listOf("Live map", "Location marker", "Radiation heatmap", "Dose rate overlay")
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_gallery)

        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.widgetList)
        emptyState = findViewById(R.id.emptyState)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Widget Gallery"
        toolbar.setNavigationOnClickListener { onBackPressed() }

        setupWidgetList()
    }

    private fun setupWidgetList() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = WidgetAdapter(widgets) { widget ->
            requestAddWidget(widget)
        }
    }

    private fun requestAddWidget(widget: WidgetInfo) {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, widget.widgetClass)

        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            // For Android 8.0+, can request pin
            val intent = Intent(this, WidgetConfigActivityV2::class.java)
            appWidgetManager.requestPinAppWidget(provider, null, null)
        } else {
            // Show instructions for older devices
            showManualInstructions()
        }
    }

    private fun showManualInstructions() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Add Widget Manually")
            .setMessage("""
                To add a widget:
                
                1. Long-press on your home screen
                2. Tap "Widgets"
                3. Find "Open RadiaCode"
                4. Drag the widget to your home screen
                5. Configure and save
            """.trimIndent())
            .setPositiveButton("Got it", null)
            .show()
    }

    inner class WidgetAdapter(
        private val items: List<WidgetInfo>,
        private val onAddClick: (WidgetInfo) -> Unit
    ) : RecyclerView.Adapter<WidgetAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.widgetName)
            val description: TextView = view.findViewById(R.id.widgetDescription)
            val dimensions: TextView = view.findViewById(R.id.widgetDimensions)
            val features: TextView = view.findViewById(R.id.widgetFeatures)
            val preview: android.widget.ImageView = view.findViewById(R.id.widgetPreview)
            val addButton: Button = view.findViewById(R.id.addWidgetButton)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_widget_gallery, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.description.text = item.description
            holder.dimensions.text = "${item.minWidth} × ${item.minHeight} cells"
            holder.features.text = item.features.joinToString(" • ")
            
            // Try to load preview, use placeholder if not found
            try {
                holder.preview.setImageResource(item.previewImage)
            } catch (e: Exception) {
                holder.preview.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1E"))
            }

            holder.addButton.setOnClickListener { onAddClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
