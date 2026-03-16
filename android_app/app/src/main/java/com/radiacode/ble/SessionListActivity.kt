package com.radiacode.ble

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import android.widget.PopupMenu
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.HapticFeedbackConstants
import java.text.SimpleDateFormat
import java.util.*

/**
 * Session Management Activity
 * View, compare, export, and manage recorded sessions
 */
class SessionListActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var fab: FloatingActionButton
    private lateinit var compareButton: Button
    private lateinit var swipeRefresh: SwipeRefreshLayout
    
    private lateinit var adapter: SessionAdapter
    private var sessions = mutableListOf<SessionManager.Session>()
    private val selectedForComparison = mutableSetOf<String>()

    private val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshIntervalMs = 3000L
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (sessions.any { it.isActive }) {
                loadSessions()
                adapter.notifyDataSetChanged()
            }
            refreshHandler.postDelayed(this, refreshIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_list)

        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.sessionRecyclerView)
        emptyView = findViewById(R.id.emptyView)
        fab = findViewById(R.id.fabNewSession)
        compareButton = findViewById(R.id.compareButton)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.pro_cyan)
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.pro_surface)
        )
        swipeRefresh.setOnRefreshListener {
            loadSessions()
            adapter.notifyDataSetChanged()
            updateEmptyView()
            swipeRefresh.isRefreshing = false
        }

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Sessions"
        toolbar.setNavigationOnClickListener { finish() }

        loadSessions()
        setupRecyclerView()
        
        fab.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            startNewSession()
        }
        compareButton.setOnClickListener { compareSessions() }
        
        updateCompareButtonVisibility()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            loadSessions()
            adapter.notifyDataSetChanged()
        }
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, refreshIntervalMs)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    /** Convert dose value from uSv/h to the user's preferred unit. */
    private fun formatDose(value: Float, pattern: String = "%.3f"): String {
        val isNano = Prefs.isDoseNanoMode(this)
        val displayValue = if (isNano) value * 1000f else value
        return String.format(pattern, displayValue)
    }

    private fun doseUnitLabel(): String =
        if (Prefs.isDoseNanoMode(this)) "nSv/h" else "uSv/h"

    private fun loadSessions() {
        val fresh = SessionManager.getSessions(this).toMutableList()
        // For any active session, refresh sample count from actual stored data
        fresh.forEachIndexed { idx, s ->
            if (s.isActive) {
                val data = SessionManager.getSessionData(this, s.id)
                if (data.isNotEmpty()) {
                    fresh[idx] = s.copy(
                        sampleCount = data.size,
                        doseMin = data.minOf { it.uSvPerHour },
                        doseMax = data.maxOf { it.uSvPerHour },
                        doseMean = data.map { it.uSvPerHour }.average().toFloat(),
                        cpsMin = data.minOf { it.cps },
                        cpsMax = data.maxOf { it.cps },
                        cpsMean = data.map { it.cps }.average().toFloat()
                    )
                }
            }
        }
        // Update list in place so the adapter's reference stays valid
        sessions.clear()
        sessions.addAll(fresh)
        updateEmptyView()
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            sessions = sessions,
            onItemClick = { session -> openSession(session) },
            onItemLongClick = { anchor, session -> showContextMenu(anchor, session) },
            isSelected = { session -> selectedForComparison.contains(session.id) },
            formatDate = { dateFormat.format(Date(it)) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun showContextMenu(anchor: View, session: SessionManager.Session) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val popup = PopupMenu(this, anchor)
        if (session.isActive) popup.menu.add("Stop Recording")
        popup.menu.add("Rename")
        popup.menu.add("Add Notes")
        popup.menu.add("Export CSV")
        val selectLabel = if (selectedForComparison.contains(session.id)) "Deselect" else "Select for Compare"
        popup.menu.add(selectLabel)
        popup.menu.add("Delete")
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Stop Recording" -> stopSession(session)
                "Rename" -> renameSession(session)
                "Add Notes" -> editNotes(session)
                "Export CSV" -> exportSession(session)
                "Select for Compare", "Deselect" -> toggleSelection(session)
                "Delete" -> confirmDelete(session)
            }
            true
        }
        popup.show()
    }

    private fun updateEmptyView() {
        if (sessions.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun startNewSession() {
        val input = EditText(this).apply {
            hint = "Session name (optional)"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Start New Session")
            .setMessage("Enter a name for this recording session")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val name = input.text.toString().takeIf { it.isNotBlank() }
                SessionManager.startSession(this, name)
                loadSessions()
                adapter.notifyDataSetChanged()
                recyclerView.scrollToPosition(0)
                updateEmptyView()
                
                Snackbar.make(findViewById(android.R.id.content), "Recording started", Snackbar.LENGTH_SHORT)
                    .setBackgroundTint(ContextCompat.getColor(this, R.color.pro_surface))
                    .setTextColor(ContextCompat.getColor(this, R.color.pro_cyan))
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSession(session: SessionManager.Session) {
        val options = mutableListOf("View Details", "Rename", "Add Notes", "Export CSV", "Delete")
        if (session.isActive) {
            options.add(0, "Stop Recording")
        }

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(session.name)
            .setItems(options.toTypedArray()) { _, which ->
                val action = options[which]
                when (action) {
                    "Stop Recording" -> stopSession(session)
                    "View Details" -> showSessionDetails(session)
                    "Rename" -> renameSession(session)
                    "Add Notes" -> editNotes(session)
                    "Export CSV" -> exportSession(session)
                    "Delete" -> confirmDelete(session)
                }
            }
            .show()
    }

    private fun stopSession(session: SessionManager.Session) {
        findViewById<View>(android.R.id.content).performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        SessionManager.stopSession(this)
        loadSessions()
        adapter.notifyDataSetChanged()
        updateEmptyView()
        Snackbar.make(findViewById(android.R.id.content), "Recording stopped", Snackbar.LENGTH_SHORT)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.pro_surface))
            .setTextColor(ContextCompat.getColor(this, R.color.pro_cyan))
            .show()
    }

    private fun showSessionDetails(session: SessionManager.Session) {
        // Refresh stats from stored data for accuracy
        val data = SessionManager.getSessionData(this, session.id)
        val liveSession = if (data.isNotEmpty()) {
            session.copy(
                sampleCount = data.size,
                doseMin = data.minOf { it.uSvPerHour },
                doseMax = data.maxOf { it.uSvPerHour },
                doseMean = data.map { it.uSvPerHour }.average().toFloat(),
                cpsMin = data.minOf { it.cps },
                cpsMax = data.maxOf { it.cps },
                cpsMean = data.map { it.cps }.average().toFloat()
            )
        } else session

        val unit = doseUnitLabel()
        val details = buildString {
            appendLine(liveSession.name)
            appendLine()
            appendLine("Duration: ${liveSession.durationFormatted}")
            appendLine("Samples: ${liveSession.sampleCount}")
            appendLine()
            appendLine("Dose Rate ($unit):")
            appendLine("   Min: ${formatDose(liveSession.doseMin)} $unit")
            appendLine("   Max: ${formatDose(liveSession.doseMax)} $unit")
            appendLine("   Avg: ${formatDose(liveSession.doseMean)} $unit")
            appendLine()
            appendLine("Count Rate:")
            appendLine("   Min: ${String.format("%.1f", liveSession.cpsMin)} cps")
            appendLine("   Max: ${String.format("%.1f", liveSession.cpsMax)} cps")
            appendLine("   Avg: ${String.format("%.1f", liveSession.cpsMean)} cps")
            
            if (liveSession.locationName != null) {
                appendLine()
                appendLine("Location: ${liveSession.locationName}")
            }
            
            if (liveSession.notes.isNotEmpty()) {
                appendLine()
                appendLine("Notes: ${liveSession.notes}")
            }
        }

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Session Details")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun renameSession(session: SessionManager.Session) {
        val input = EditText(this).apply {
            setText(session.name)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Rename Session")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().takeIf { it.isNotBlank() } ?: session.name
                val updated = session.copy(name = newName)
                SessionManager.updateSession(this, updated)
                loadSessions()
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editNotes(session: SessionManager.Session) {
        val input = EditText(this).apply {
            setText(session.notes)
            hint = "Add notes about this session"
            minLines = 3
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Session Notes")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val updated = session.copy(notes = input.text.toString())
                SessionManager.updateSession(this, updated)
                loadSessions()
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportSession(session: SessionManager.Session) {
        val data = SessionManager.getSessionData(this, session.id)
        if (data.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), "No data in session", Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, R.color.pro_surface))
                .setTextColor(ContextCompat.getColor(this, R.color.pro_cyan))
                .show()
            return
        }

        // Generate CSV
        val csv = buildString {
            appendLine("timestamp,uSvPerHour,cps,latitude,longitude,deviceId")
            data.forEach { appendLine(it.toCsv()) }
        }

        // Share
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_TEXT, csv)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "RadiaCode Session: ${session.name}")
        }
        startActivity(android.content.Intent.createChooser(intent, "Export Session"))
    }

    private fun confirmDelete(session: SessionManager.Session) {
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Delete Session?")
            .setMessage("This will permanently delete \"${session.name}\" and all its data.")
            .setPositiveButton("Delete") { _, _ ->
                SessionManager.deleteSession(this, session.id)
                sessions.removeAll { it.id == session.id }
                selectedForComparison.remove(session.id)
                adapter.notifyDataSetChanged()
                updateEmptyView()
                updateCompareButtonVisibility()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleSelection(session: SessionManager.Session) {
        if (selectedForComparison.contains(session.id)) {
            selectedForComparison.remove(session.id)
        } else if (selectedForComparison.size < 2) {
            selectedForComparison.add(session.id)
        } else {
            Snackbar.make(findViewById(android.R.id.content), "Select up to 2 sessions to compare", Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, R.color.pro_surface))
                .setTextColor(ContextCompat.getColor(this, R.color.pro_cyan))
                .show()
        }
        adapter.notifyDataSetChanged()
        updateCompareButtonVisibility()
    }

    private fun updateCompareButtonVisibility() {
        compareButton.visibility = if (selectedForComparison.size == 2) View.VISIBLE else View.GONE
    }

    private fun compareSessions() {
        val ids = selectedForComparison.toList()
        if (ids.size != 2) return

        val comparison = SessionManager.compareSessions(this, ids[0], ids[1])
        if (comparison == null) {
            Snackbar.make(findViewById(android.R.id.content), "Cannot compare sessions", Snackbar.LENGTH_SHORT)
                .setBackgroundTint(ContextCompat.getColor(this, R.color.pro_surface))
                .setTextColor(ContextCompat.getColor(this, R.color.pro_cyan))
                .show()
            return
        }

        val cUnit = doseUnitLabel()
        val details = buildString {
            appendLine("Comparison")
            appendLine()
            appendLine("Session 1: ${comparison.session1.name}")
            appendLine("Session 2: ${comparison.session2.name}")
            appendLine()
            appendLine("Dose Rate (avg):")
            appendLine("   ${formatDose(comparison.session1.doseMean)} -> ${formatDose(comparison.session2.doseMean)} $cUnit")
            val doseArrow = if (comparison.doseAvgDiff > 0) "+" else "-"
            appendLine("   $doseArrow ${String.format("%.1f", kotlin.math.abs(comparison.doseAvgDiffPercent))}%")
            appendLine()
            appendLine("Count Rate (avg):")
            appendLine("   ${String.format("%.1f", comparison.session1.cpsMean)} -> ${String.format("%.1f", comparison.session2.cpsMean)} cps")
            val cpsArrow = if (comparison.cpsAvgDiff > 0) "+" else "-"
            appendLine("   $cpsArrow ${String.format("%.1f", kotlin.math.abs(comparison.cpsAvgDiffPercent))}%")
            appendLine()
            appendLine(comparison.summary)
        }

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Session Comparison")
            .setMessage(details)
            .setPositiveButton("OK") { _, _ ->
                selectedForComparison.clear()
                adapter.notifyDataSetChanged()
                updateCompareButtonVisibility()
            }
            .show()
    }

    /**
     * Simple adapter for sessions list
     */
    inner class SessionAdapter(
        private val sessions: List<SessionManager.Session>,
        private val onItemClick: (SessionManager.Session) -> Unit,
        private val onItemLongClick: (View, SessionManager.Session) -> Unit,
        private val isSelected: (SessionManager.Session) -> Boolean,
        private val formatDate: (Long) -> String
    ) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.sessionName)
            val dateText: TextView = view.findViewById(R.id.sessionDate)
            val statsText: TextView = view.findViewById(R.id.sessionStats)
            val statusBadge: TextView = view.findViewById(R.id.sessionStatus)
            val container: View = view
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_session, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val session = sessions[position]
            
            holder.nameText.text = session.name
            holder.dateText.text = formatDate(session.startTimeMs)
            holder.statsText.text = "${session.durationFormatted} \u2022 ${session.sampleCount} samples \u2022 avg ${formatDose(session.doseMean)} ${doseUnitLabel()}"
            
            if (session.isActive) {
                holder.statusBadge.text = "● RECORDING"
                holder.statusBadge.setTextColor(ContextCompat.getColor(this@SessionListActivity, R.color.pro_red))
                holder.statusBadge.visibility = View.VISIBLE
            } else {
                holder.statusBadge.visibility = View.GONE
            }

            // Selection highlight
            if (isSelected(session)) {
                holder.container.setBackgroundColor(ContextCompat.getColor(this@SessionListActivity, R.color.pro_cyan) and 0x30FFFFFF)
            } else {
                holder.container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            holder.container.setOnClickListener { onItemClick(session) }
            holder.container.setOnLongClickListener { 
                onItemLongClick(it, session)
                true
            }
        }

        override fun getItemCount() = sessions.size
    }
}
