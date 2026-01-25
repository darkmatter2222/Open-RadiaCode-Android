package com.radiacode.ble

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    
    private lateinit var adapter: SessionAdapter
    private var sessions = mutableListOf<SessionManager.Session>()
    private val selectedForComparison = mutableSetOf<String>()

    private val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_list)

        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.sessionRecyclerView)
        emptyView = findViewById(R.id.emptyView)
        fab = findViewById(R.id.fabNewSession)
        compareButton = findViewById(R.id.compareButton)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Sessions"
        toolbar.setNavigationOnClickListener { finish() }

        loadSessions()
        setupRecyclerView()
        
        fab.setOnClickListener { startNewSession() }
        compareButton.setOnClickListener { compareSessions() }
        
        updateCompareButtonVisibility()
    }

    private fun loadSessions() {
        sessions = SessionManager.getSessions(this).toMutableList()
        updateEmptyView()
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            sessions = sessions,
            onItemClick = { session -> openSession(session) },
            onItemLongClick = { session -> toggleSelection(session) },
            isSelected = { session -> selectedForComparison.contains(session.id) },
            formatDate = { dateFormat.format(Date(it)) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
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

        AlertDialog.Builder(this)
            .setTitle("Start New Session")
            .setMessage("Enter a name for this recording session")
            .setView(input)
            .setPositiveButton("Start") { _, _ ->
                val name = input.text.toString().takeIf { it.isNotBlank() }
                val session = SessionManager.startSession(this, name)
                sessions.add(0, session)
                adapter.notifyItemInserted(0)
                recyclerView.scrollToPosition(0)
                updateEmptyView()
                
                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSession(session: SessionManager.Session) {
        val options = mutableListOf("View Details", "Rename", "Add Notes", "Export CSV", "Delete")
        if (session.isActive) {
            options.add(0, "Stop Recording")
        }

        AlertDialog.Builder(this)
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
        SessionManager.stopSession(this)
        loadSessions()
        adapter.notifyDataSetChanged()
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
    }

    private fun showSessionDetails(session: SessionManager.Session) {
        val details = buildString {
            appendLine("📋 ${session.name}")
            appendLine()
            appendLine("⏱️ Duration: ${session.durationFormatted}")
            appendLine("📊 Samples: ${session.sampleCount}")
            appendLine()
            appendLine("☢️ Dose Rate:")
            appendLine("   Min: ${String.format("%.4f", session.doseMin)} μSv/h")
            appendLine("   Max: ${String.format("%.4f", session.doseMax)} μSv/h")
            appendLine("   Avg: ${String.format("%.4f", session.doseMean)} μSv/h")
            appendLine()
            appendLine("📈 Count Rate:")
            appendLine("   Min: ${String.format("%.1f", session.cpsMin)} cps")
            appendLine("   Max: ${String.format("%.1f", session.cpsMax)} cps")
            appendLine("   Avg: ${String.format("%.1f", session.cpsMean)} cps")
            
            if (session.locationName != null) {
                appendLine()
                appendLine("📍 Location: ${session.locationName}")
            }
            
            if (session.notes.isNotEmpty()) {
                appendLine()
                appendLine("📝 Notes: ${session.notes}")
            }
        }

        AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
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

        AlertDialog.Builder(this)
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
            Toast.makeText(this, "No data in session", Toast.LENGTH_SHORT).show()
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
        AlertDialog.Builder(this)
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
            Toast.makeText(this, "Select up to 2 sessions to compare", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Cannot compare sessions", Toast.LENGTH_SHORT).show()
            return
        }

        val details = buildString {
            appendLine("📊 Comparison")
            appendLine()
            appendLine("Session 1: ${comparison.session1.name}")
            appendLine("Session 2: ${comparison.session2.name}")
            appendLine()
            appendLine("☢️ Dose Rate (avg):")
            appendLine("   ${String.format("%.4f", comparison.session1.doseMean)} → ${String.format("%.4f", comparison.session2.doseMean)} μSv/h")
            val doseArrow = if (comparison.doseAvgDiff > 0) "↑" else "↓"
            appendLine("   $doseArrow ${String.format("%.1f", kotlin.math.abs(comparison.doseAvgDiffPercent))}%")
            appendLine()
            appendLine("📈 Count Rate (avg):")
            appendLine("   ${String.format("%.1f", comparison.session1.cpsMean)} → ${String.format("%.1f", comparison.session2.cpsMean)} cps")
            val cpsArrow = if (comparison.cpsAvgDiff > 0) "↑" else "↓"
            appendLine("   $cpsArrow ${String.format("%.1f", kotlin.math.abs(comparison.cpsAvgDiffPercent))}%")
            appendLine()
            appendLine("💡 ${comparison.summary}")
        }

        AlertDialog.Builder(this)
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
        private val onItemLongClick: (SessionManager.Session) -> Unit,
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
            holder.statsText.text = "${session.durationFormatted} • ${session.sampleCount} samples • avg ${String.format("%.3f", session.doseMean)} μSv/h"
            
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
                onItemLongClick(session)
                true
            }
        }

        override fun getItemCount() = sessions.size
    }
}
