package com.radiacode.ble

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Activity for configuring which isotopes are enabled for detection
 * and other isotope identification settings.
 */
class IsotopeSettingsActivity : AppCompatActivity() {
    
    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var showDaughtersSwitch: SwitchMaterial
    private lateinit var selectAllBtn: MaterialButton
    private lateinit var deselectAllBtn: MaterialButton
    
    private lateinit var adapter: IsotopeAdapter
    private val enabledIsotopes = mutableSetOf<String>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_isotope_settings)
        
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.isotopeRecyclerView)
        showDaughtersSwitch = findViewById(R.id.showDaughtersSwitch)
        selectAllBtn = findViewById(R.id.selectAllBtn)
        deselectAllBtn = findViewById(R.id.deselectAllBtn)
        
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Isotope Settings"
        toolbar.setNavigationOnClickListener { finish() }
        
        // Load current settings
        enabledIsotopes.addAll(Prefs.getEnabledIsotopes(this))
        showDaughtersSwitch.isChecked = Prefs.isShowIsotopeDaughters(this)
        
        // Setup recycler
        adapter = IsotopeAdapter(
            isotopes = IsotopeLibrary.ALL_ISOTOPES,
            enabledIsotopes = enabledIsotopes,
            onIsotopeToggled = { isotopeId, enabled ->
                if (enabled) {
                    enabledIsotopes.add(isotopeId)
                } else {
                    enabledIsotopes.remove(isotopeId)
                }
                saveSettings()
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        // Show daughters toggle
        showDaughtersSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setShowIsotopeDaughters(this, isChecked)
        }
        
        // Select/Deselect all
        selectAllBtn.setOnClickListener {
            enabledIsotopes.clear()
            enabledIsotopes.addAll(IsotopeLibrary.ALL_ISOTOPES.map { iso -> iso.id })
            adapter.notifyDataSetChanged()
            saveSettings()
        }
        
        deselectAllBtn.setOnClickListener {
            enabledIsotopes.clear()
            adapter.notifyDataSetChanged()
            saveSettings()
        }
    }
    
    private fun saveSettings() {
        Prefs.setEnabledIsotopes(this, enabledIsotopes)
    }
    
    /**
     * Adapter for displaying isotopes grouped by category.
     */
    private inner class IsotopeAdapter(
        private val isotopes: List<IsotopeLibrary.Isotope>,
        private val enabledIsotopes: MutableSet<String>,
        private val onIsotopeToggled: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        
        private val TYPE_HEADER = 0
        private val TYPE_ITEM = 1
        
        // Build a list of items (headers + isotopes) grouped by category
        private val items: List<Any> = buildItems()
        
        private fun buildItems(): List<Any> {
            val result = mutableListOf<Any>()
            val byCategory = isotopes.groupBy { iso -> iso.category }
            
            // Order categories
            val orderedCategories = listOf(
                IsotopeLibrary.Category.NATURAL,
                IsotopeLibrary.Category.MEDICAL,
                IsotopeLibrary.Category.INDUSTRIAL,
                IsotopeLibrary.Category.FISSION,
                IsotopeLibrary.Category.CALIBRATION
            )
            
            for (category in orderedCategories) {
                val categoryIsotopes = byCategory[category] ?: continue
                result.add(category)  // Header
                result.addAll(categoryIsotopes)
            }
            
            return result
        }
        
        override fun getItemCount() = items.size
        
        override fun getItemViewType(position: Int): Int {
            return if (items[position] is IsotopeLibrary.Category) TYPE_HEADER else TYPE_ITEM
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_isotope_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_isotope_row, parent, false)
                ItemViewHolder(view)
            }
        }
        
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderViewHolder -> {
                    val category = items[position] as IsotopeLibrary.Category
                    holder.bind(category)
                }
                is ItemViewHolder -> {
                    val isotope = items[position] as IsotopeLibrary.Isotope
                    holder.bind(isotope)
                }
            }
        }
        
        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val titleText: TextView = view.findViewById(R.id.categoryTitle)
            
            fun bind(category: IsotopeLibrary.Category) {
                titleText.text = when (category) {
                    IsotopeLibrary.Category.NATURAL -> "🌍 Natural/Background"
                    IsotopeLibrary.Category.MEDICAL -> "⚕️ Medical"
                    IsotopeLibrary.Category.INDUSTRIAL -> "🏭 Industrial"
                    IsotopeLibrary.Category.FISSION -> "☢️ Fission Products"
                    IsotopeLibrary.Category.CALIBRATION -> "📐 Calibration"
                }
            }
        }
        
        inner class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val checkbox: CheckBox = view.findViewById(R.id.isotopeCheckbox)
            private val nameText: TextView = view.findViewById(R.id.isotopeName)
            private val energyText: TextView = view.findViewById(R.id.isotopeEnergies)
            private val decayText: TextView = view.findViewById(R.id.isotopeDecay)
            
            fun bind(isotope: IsotopeLibrary.Isotope) {
                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = enabledIsotopes.contains(isotope.id)
                nameText.text = isotope.name
                
                // Format energies
                val energies = isotope.gammaLines.take(3).joinToString(", ") { line ->
                    "%.0f keV".format(line.energyKeV)
                }
                energyText.text = energies
                
                // Decay info
                val parentChain = isotope.parentChain
                if (!parentChain.isNullOrEmpty()) {
                    decayText.visibility = View.VISIBLE
                    decayText.text = "↳ Part of $parentChain chain"
                } else {
                    decayText.visibility = View.GONE
                }
                
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    onIsotopeToggled(isotope.id, isChecked)
                }
                
                itemView.setOnClickListener {
                    checkbox.isChecked = !checkbox.isChecked
                }
            }
        }
    }
}
