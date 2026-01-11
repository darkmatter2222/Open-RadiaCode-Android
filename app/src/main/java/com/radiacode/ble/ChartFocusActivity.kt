package com.radiacode.ble

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ChartFocusActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_KIND = "kind" // "dose" | "cps"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var chart: SparklineView
    private lateinit var statsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart_focus)

        toolbar = findViewById(R.id.toolbar)
        chart = findViewById(R.id.chart)
        statsText = findViewById(R.id.statsText)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val kind = intent?.getStringExtra(EXTRA_KIND)?.lowercase(Locale.US) ?: "dose"
        supportActionBar?.title = if (kind == "cps") "Count rate" else "Dose"

        val threshold = if (kind == "cps") {
            null
        } else {
            val tUsvH = Prefs.getDoseThresholdUsvH(this, 0f)
            if (tUsvH <= 0f) {
                null
            } else {
                when (Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)) {
                    Prefs.DoseUnit.USV_H -> tUsvH
                    Prefs.DoseUnit.NSV_H -> tUsvH * 1000.0f
                }
            }
        }
        chart.setThreshold(threshold)

        // Pull current window of samples from MainActivity via a sticky-ish approach (Prefs + CSV are persisted,
        // but for focus mode we just show the latest window snapshot passed in intent if present).
        // If not present, we fall back to the last reading only.
        val series = intent?.getLongArrayExtra("ts")?.toList().orEmpty()
        val values = intent?.getFloatArrayExtra("v")?.toList().orEmpty()

        if (series.isNotEmpty() && values.isNotEmpty() && series.size == values.size) {
            chart.setSeries(series, values)
            statsText.text = stats(values, kind)
        } else {
            val last = Prefs.getLastReading(this)
            if (last == null) {
                chart.setSeries(emptyList(), emptyList())
                statsText.text = ""
            } else {
                val v = if (kind == "cps") last.cps else last.uSvPerHour
                chart.setSeries(listOf(last.timestampMs), listOf(v))
                statsText.text = stats(listOf(v), kind)
            }
        }
    }

    private fun stats(values: List<Float>, kind: String): String {
        if (values.isEmpty()) return ""
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var sum = 0.0f
        for (v in values) {
            minV = min(minV, v)
            maxV = max(maxV, v)
            sum += v
        }
        val avg = sum / max(1, values.size)
        val unit = if (kind == "cps") {
            when (Prefs.getCountUnit(this, Prefs.CountUnit.CPS)) {
                Prefs.CountUnit.CPS -> "cps"
                Prefs.CountUnit.CPM -> "cpm"
            }
        } else {
            when (Prefs.getDoseUnit(this, Prefs.DoseUnit.USV_H)) {
                Prefs.DoseUnit.USV_H -> "μSv/h"
                Prefs.DoseUnit.NSV_H -> "nSv/h"
            }
        }
        return String.format(Locale.US, "Min %.3f • Avg %.3f • Max %.3f %s", minV, avg, maxV, unit)
    }
}
