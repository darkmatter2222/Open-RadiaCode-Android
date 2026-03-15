package com.radiacode.ble.testing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.radiacode.ble.BuildConfig
import com.radiacode.ble.DashboardFragment
import com.radiacode.ble.DeviceFragment
import com.radiacode.ble.IsotopeFragment
import com.radiacode.ble.MainActivity
import com.radiacode.ble.MainPagerAdapter
import com.radiacode.ble.MapFragment
import com.radiacode.ble.Prefs
import com.radiacode.ble.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only BroadcastReceiver that allows external test scripts to introspect
 * app state and exercise UI controls via ADB:
 *
 *   adb shell am broadcast -a com.radiacode.ble.TEST -e cmd <command> ...
 *
 * Results are logged to logcat with tag "TestResult" as JSON for easy parsing.
 *
 * Commands:
 *   ping                  -- verify receiver is alive
 *   app_state             -- active tab, connection status, BLE data flow
 *   tab_info <index>      -- detailed state of a specific tab
 *   switch_tab <index>    -- switch to a tab and report result
 *   scale_state           -- map color scale control state
 *   set_scale <min> <max> -- enter manual scale values and apply
 *   toggle_auto_scale     -- toggle auto/manual scale mode
 *   prefs_dump            -- dump key preferences
 *   view_tree <rootId>    -- dump view hierarchy visibility
 *   run_suite             -- run all tests in sequence
 */
class TestReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "TestResult"
        const val ACTION = "com.radiacode.ble.TEST"

        // Singleton ref to the live activity so we can introspect views.
        // Set by MainActivity in onCreate, cleared in onDestroy.
        @Volatile
        var activityRef: MainActivity? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "TestReceiver disabled in release builds")
            return
        }
        if (intent.action != ACTION) return

        val cmd = intent.getStringExtra("cmd") ?: "ping"
        val handler = Handler(Looper.getMainLooper())

        // Run on main thread to safely access views
        handler.post {
            try {
                val result = executeCommand(context, cmd, intent)
                Log.i(TAG, result.toString())
            } catch (e: Exception) {
                val err = JSONObject().apply {
                    put("cmd", cmd)
                    put("status", "ERROR")
                    put("error", "${e.javaClass.simpleName}: ${e.message}")
                }
                Log.e(TAG, err.toString())
            }
        }
    }

    private fun executeCommand(context: Context, cmd: String, intent: Intent): JSONObject {
        return when (cmd) {
            "ping" -> cmdPing()
            "app_state" -> cmdAppState(context)
            "tab_info" -> cmdTabInfo(context, intent.getStringExtra("tab")?.toIntOrNull() ?: -1)
            "switch_tab" -> cmdSwitchTab(intent.getStringExtra("tab")?.toIntOrNull() ?: 0)
            "scale_state" -> cmdScaleState(context)
            "set_scale" -> cmdSetScale(
                context,
                intent.getStringExtra("min") ?: "0",
                intent.getStringExtra("max") ?: "1"
            )
            "toggle_auto_scale" -> cmdToggleAutoScale(context)
            "prefs_dump" -> cmdPrefsDump(context)
            "view_tree" -> cmdViewTree(intent.getStringExtra("root_id") ?: "")
            "run_suite" -> cmdRunSuite(context)
            else -> JSONObject().apply {
                put("cmd", cmd)
                put("status", "UNKNOWN_COMMAND")
            }
        }
    }

    // ── ping ────────────────────────────────────────────────────────────

    private fun cmdPing(): JSONObject = JSONObject().apply {
        put("cmd", "ping")
        put("status", "OK")
        put("debug", BuildConfig.DEBUG)
        put("version", BuildConfig.VERSION_NAME)
        put("activity_alive", activityRef != null)
    }

    // ── app_state ───────────────────────────────────────────────────────

    private fun cmdAppState(context: Context): JSONObject {
        val activity = activityRef
        val result = JSONObject().apply {
            put("cmd", "app_state")
            put("status", if (activity != null) "OK" else "NO_ACTIVITY")
        }

        if (activity == null) return result

        val viewPager = activity.findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = activity.findViewById<TabLayout>(R.id.tabLayout)

        result.put("current_tab", viewPager?.currentItem ?: -1)
        result.put("tab_count", tabLayout?.tabCount ?: 0)
        result.put("selected_tab_text", tabLayout?.getTabAt(viewPager?.currentItem ?: 0)?.text ?: "")

        // Connection states
        val connectionStates = activity.getDeviceConnectionStates()
        val connJson = JSONObject()
        connectionStates.forEach { (id, state) ->
            connJson.put(id.takeLast(8), state.name)
        }
        result.put("device_connections", connJson)
        result.put("connected_count", connectionStates.count { it.value.name == "CONNECTED" })

        // Check if toolbar status indicates live data
        val statusLabel = activity.findViewById<TextView>(R.id.statusLabel)
        result.put("status_text", statusLabel?.text?.toString() ?: "")

        val statusDot = activity.findViewById<View>(R.id.statusDot)
        result.put("status_dot_visible", statusDot?.visibility == View.VISIBLE)

        return result
    }

    // ── tab_info ────────────────────────────────────────────────────────

    private fun cmdTabInfo(context: Context, tabIndex: Int): JSONObject {
        val activity = activityRef ?: return JSONObject().apply {
            put("cmd", "tab_info")
            put("status", "NO_ACTIVITY")
        }

        val result = JSONObject().apply {
            put("cmd", "tab_info")
            put("tab", tabIndex)
        }

        val fragment = getFragment(activity, tabIndex)
        result.put("fragment_exists", fragment != null)
        result.put("fragment_class", fragment?.javaClass?.simpleName ?: "null")
        result.put("fragment_resumed", fragment?.isResumed ?: false)
        result.put("fragment_visible", fragment?.isVisible ?: false)

        when (fragment) {
            is DashboardFragment -> addDashboardInfo(result, fragment)
            is MapFragment -> addMapInfo(result, fragment, context)
            is IsotopeFragment -> addIsotopeInfo(result, fragment)
            is DeviceFragment -> addDeviceInfo(result, fragment)
        }

        result.put("status", "OK")
        return result
    }

    private fun addDashboardInfo(result: JSONObject, fragment: DashboardFragment) {
        val view = fragment.view ?: return
        result.put("has_dose_card", view.findViewById<View>(R.id.doseCard) != null)
        result.put("has_cps_card", view.findViewById<View>(R.id.cpsCard) != null)
        result.put("has_dose_chart", view.findViewById<View>(R.id.doseChart) != null)
        result.put("has_cps_chart", view.findViewById<View>(R.id.cpsChart) != null)
        result.put("has_time_chips", view.findViewById<View>(R.id.chipWindow10s) != null)
        result.put("has_session_info", view.findViewById<View>(R.id.sessionInfo) != null)

        // Check chart panel visibility
        val doseChartPanel = view.findViewById<View>(R.id.doseChartPanel)
        result.put("dose_chart_visible", doseChartPanel?.visibility == View.VISIBLE)
        val cpsChartPanel = view.findViewById<View>(R.id.cpsChartPanel)
        result.put("cps_chart_visible", cpsChartPanel?.visibility == View.VISIBLE)
    }

    private fun addMapInfo(result: JSONObject, fragment: MapFragment, context: Context) {
        val view = fragment.view ?: return

        // Map core views
        result.put("has_map_card", view.findViewById<View>(R.id.mapCard) != null)
        result.put("has_map_bottom_bar", view.findViewById<View>(R.id.mapBottomBar) != null)

        // GPS tier controls
        result.put("has_gps_passive", view.findViewById<View>(R.id.chipGpsPassive) != null)
        result.put("has_gps_balanced", view.findViewById<View>(R.id.chipGpsBalanced) != null)
        result.put("has_gps_high", view.findViewById<View>(R.id.chipGpsHigh) != null)
        result.put("gps_tier", Prefs.getGpsTier(context))

        // Scale controls
        val chipAuto = view.findViewById<View>(R.id.chipScaleAuto)
        val editMin = view.findViewById<EditText>(R.id.editScaleMin)
        val editMax = view.findViewById<EditText>(R.id.editScaleMax)
        val btnSet = view.findViewById<View>(R.id.btnApplyScale)

        result.put("has_scale_auto_chip", chipAuto != null)
        result.put("has_scale_min", editMin != null)
        result.put("has_scale_max", editMax != null)
        result.put("has_scale_set_btn", btnSet != null)
        result.put("scale_auto_enabled", Prefs.isMapScaleAuto(context))
        result.put("scale_min_value", editMin?.text?.toString() ?: "")
        result.put("scale_max_value", editMax?.text?.toString() ?: "")
        result.put("scale_min_enabled", editMin?.isEnabled ?: false)
        result.put("scale_max_enabled", editMax?.isEnabled ?: false)

        // Export/sessions buttons
        result.put("has_export_btn", view.findViewById<View>(R.id.btnExportData) != null)
        result.put("has_sessions_btn", view.findViewById<View>(R.id.btnSessions) != null)
        result.put("has_theme_btn", view.findViewById<View>(R.id.btnMapTheme) != null)
    }

    private fun addIsotopeInfo(result: JSONObject, fragment: IsotopeFragment) {
        val view = fragment.view ?: return
        result.put("has_isotope_panel", view.findViewById<View>(R.id.isotopePanel) != null)
        result.put("has_scan_btn", view.findViewById<View>(R.id.isotopeScanBtn) != null)
        result.put("has_realtime_switch", view.findViewById<View>(R.id.isotopeRealtimeSwitch) != null)
        result.put("has_chart_container", view.findViewById<View>(R.id.isotopeChartContainer) != null)
        result.put("has_status_label", view.findViewById<View>(R.id.isotopeStatusLabel) != null)

        val statusLabel = view.findViewById<TextView>(R.id.isotopeStatusLabel)
        result.put("isotope_status_text", statusLabel?.text?.toString() ?: "")

        val topResult = view.findViewById<TextView>(R.id.isotopeTopResult)
        result.put("isotope_top_result", topResult?.text?.toString() ?: "")
    }

    private fun addDeviceInfo(result: JSONObject, fragment: DeviceFragment) {
        val view = fragment.view ?: return
        result.put("has_connection_dot", view.findViewById<View>(R.id.connectionDot) != null)
        result.put("has_connection_status", view.findViewById<View>(R.id.connectionStatus) != null)
        result.put("has_find_devices_btn", view.findViewById<View>(R.id.findDevicesButton) != null)
        result.put("has_reconnect_btn", view.findViewById<View>(R.id.reconnectButton) != null)
        result.put("has_stop_service_btn", view.findViewById<View>(R.id.stopServiceButton) != null)
        result.put("has_auto_connect_switch", view.findViewById<View>(R.id.autoConnectSwitch) != null)

        val connectionStatus = view.findViewById<TextView>(R.id.connectionStatus)
        result.put("connection_status_text", connectionStatus?.text?.toString() ?: "")
    }

    // ── switch_tab ──────────────────────────────────────────────────────

    private fun cmdSwitchTab(tabIndex: Int): JSONObject {
        val activity = activityRef ?: return JSONObject().apply {
            put("cmd", "switch_tab")
            put("status", "NO_ACTIVITY")
        }

        val viewPager = activity.findViewById<ViewPager2>(R.id.viewPager)
        val previousTab = viewPager?.currentItem ?: -1
        viewPager?.setCurrentItem(tabIndex, false)

        return JSONObject().apply {
            put("cmd", "switch_tab")
            put("status", "OK")
            put("previous_tab", previousTab)
            put("requested_tab", tabIndex)
            put("current_tab", viewPager?.currentItem ?: -1)
        }
    }

    // ── scale_state ─────────────────────────────────────────────────────

    private fun cmdScaleState(context: Context): JSONObject {
        val activity = activityRef
        val result = JSONObject().apply {
            put("cmd", "scale_state")
        }

        // Prefs state
        result.put("pref_auto", Prefs.isMapScaleAuto(context))
        result.put("pref_min", Prefs.getMapScaleMin(context).toDouble())
        result.put("pref_max", Prefs.getMapScaleMax(context).toDouble())

        // Live view state (if on map tab)
        val mapFragment = activity?.let { getFragment(it, MainPagerAdapter.TAB_MAP) }
        val view = mapFragment?.view
        if (view != null) {
            val editMin = view.findViewById<EditText>(R.id.editScaleMin)
            val editMax = view.findViewById<EditText>(R.id.editScaleMax)
            val chipAuto = view.findViewById<View>(R.id.chipScaleAuto)

            result.put("view_min_text", editMin?.text?.toString() ?: "")
            result.put("view_max_text", editMax?.text?.toString() ?: "")
            result.put("view_min_enabled", editMin?.isEnabled ?: false)
            result.put("view_max_enabled", editMax?.isEnabled ?: false)
            result.put("view_auto_alpha", chipAuto?.alpha?.toDouble() ?: -1.0)
        }

        result.put("status", "OK")
        return result
    }

    // ── set_scale ───────────────────────────────────────────────────────

    private fun cmdSetScale(context: Context, minStr: String, maxStr: String): JSONObject {
        val activity = activityRef ?: return JSONObject().apply {
            put("cmd", "set_scale")
            put("status", "NO_ACTIVITY")
        }

        val min = minStr.toFloatOrNull()
        val max = maxStr.toFloatOrNull()
        if (min == null || max == null) {
            return JSONObject().apply {
                put("cmd", "set_scale")
                put("status", "INVALID_VALUES")
                put("min_input", minStr)
                put("max_input", maxStr)
            }
        }

        // Set values in prefs
        Prefs.setMapScaleAuto(context, false)
        Prefs.setMapScaleMin(context, min)
        Prefs.setMapScaleMax(context, max)

        // Update Edit fields if map tab is visible
        val mapFragment = getFragment(activity, MainPagerAdapter.TAB_MAP)
        val view = mapFragment?.view
        if (view != null) {
            val editMin = view.findViewById<EditText>(R.id.editScaleMin)
            val editMax = view.findViewById<EditText>(R.id.editScaleMax)
            editMin?.setText(min.toString())
            editMax?.setText(max.toString())

            // Click the SET button to trigger the apply logic
            view.findViewById<View>(R.id.btnApplyScale)?.performClick()
        }

        return JSONObject().apply {
            put("cmd", "set_scale")
            put("status", "OK")
            put("min", min.toDouble())
            put("max", max.toDouble())
            put("auto", false)
        }
    }

    // ── toggle_auto_scale ───────────────────────────────────────────────

    private fun cmdToggleAutoScale(context: Context): JSONObject {
        val activity = activityRef ?: return JSONObject().apply {
            put("cmd", "toggle_auto_scale")
            put("status", "NO_ACTIVITY")
        }

        val wasBefore = Prefs.isMapScaleAuto(context)

        // Click the AUTO chip to toggle
        val mapFragment = getFragment(activity, MainPagerAdapter.TAB_MAP)
        val view = mapFragment?.view
        view?.findViewById<View>(R.id.chipScaleAuto)?.performClick()

        val isNow = Prefs.isMapScaleAuto(context)

        return JSONObject().apply {
            put("cmd", "toggle_auto_scale")
            put("status", "OK")
            put("was_auto", wasBefore)
            put("is_auto", isNow)
            put("toggled", wasBefore != isNow)
        }
    }

    // ── prefs_dump ──────────────────────────────────────────────────────

    private fun cmdPrefsDump(context: Context): JSONObject {
        return JSONObject().apply {
            put("cmd", "prefs_dump")
            put("status", "OK")
            put("gps_tier", Prefs.getGpsTier(context))
            put("gps_tracking_enabled", Prefs.isGpsTrackingEnabled(context))
            put("map_scale_auto", Prefs.isMapScaleAuto(context))
            put("map_scale_min", Prefs.getMapScaleMin(context).toDouble())
            put("map_scale_max", Prefs.getMapScaleMax(context).toDouble())
            put("pause_live_enabled", Prefs.isPauseLiveEnabled(context))
        }
    }

    // ── view_tree ───────────────────────────────────────────────────────

    private fun cmdViewTree(rootIdName: String): JSONObject {
        val activity = activityRef ?: return JSONObject().apply {
            put("cmd", "view_tree")
            put("status", "NO_ACTIVITY")
        }

        val rootView = if (rootIdName.isBlank()) {
            activity.window.decorView
        } else {
            val resId = activity.resources.getIdentifier(rootIdName, "id", activity.packageName)
            if (resId == 0) {
                return JSONObject().apply {
                    put("cmd", "view_tree")
                    put("status", "INVALID_ROOT_ID")
                    put("root_id", rootIdName)
                }
            }
            activity.findViewById(resId)
        }

        val tree = JSONArray()
        dumpViewTree(rootView, 0, tree)

        return JSONObject().apply {
            put("cmd", "view_tree")
            put("status", "OK")
            put("root", rootIdName.ifBlank { "decorView" })
            put("view_count", tree.length())
            put("tree", tree)
        }
    }

    private fun dumpViewTree(view: View?, depth: Int, output: JSONArray, maxDepth: Int = 4) {
        if (view == null || depth > maxDepth) return

        val indent = "  ".repeat(depth)
        val idName = try {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id)
            else null
        } catch (_: Exception) { null }

        val entry = JSONObject().apply {
            put("depth", depth)
            put("class", view.javaClass.simpleName)
            put("id", idName ?: "-")
            put("visibility", when (view.visibility) {
                View.VISIBLE -> "VISIBLE"
                View.INVISIBLE -> "INVISIBLE"
                View.GONE -> "GONE"
                else -> "UNKNOWN"
            })
            put("enabled", view.isEnabled)
            if (view is TextView) {
                put("text", view.text?.toString()?.take(50) ?: "")
            }
        }
        output.put(entry)

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpViewTree(view.getChildAt(i), depth + 1, output, maxDepth)
            }
        }
    }

    // ── run_suite ───────────────────────────────────────────────────────

    private fun cmdRunSuite(context: Context): JSONObject {
        val results = JSONArray()
        var passed = 0
        var failed = 0

        fun test(name: String, testFn: () -> Boolean) {
            try {
                val ok = testFn()
                results.put(JSONObject().apply {
                    put("test", name)
                    put("result", if (ok) "PASS" else "FAIL")
                })
                if (ok) passed++ else failed++
            } catch (e: Exception) {
                results.put(JSONObject().apply {
                    put("test", name)
                    put("result", "ERROR")
                    put("error", "${e.javaClass.simpleName}: ${e.message}")
                })
                failed++
            }
        }

        val activity = activityRef

        // ── Core App Tests ──

        test("activity_alive") {
            activity != null
        }

        test("viewpager_exists") {
            activity?.findViewById<ViewPager2>(R.id.viewPager) != null
        }

        test("tablayout_exists") {
            activity?.findViewById<TabLayout>(R.id.tabLayout) != null
        }

        test("tab_count_is_4") {
            val tabLayout = activity?.findViewById<TabLayout>(R.id.tabLayout)
            tabLayout?.tabCount == 4
        }

        test("toolbar_exists") {
            activity?.findViewById<View>(R.id.toolbar) != null
        }

        test("status_dot_exists") {
            activity?.findViewById<View>(R.id.statusDot) != null
        }

        // ── Tab Navigation Tests ──

        val viewPager = activity?.findViewById<ViewPager2>(R.id.viewPager)
        val originalTab = viewPager?.currentItem ?: 0

        for (tabIdx in 0 until 4) {
            test("switch_tab_$tabIdx") {
                viewPager?.setCurrentItem(tabIdx, false)
                viewPager?.currentItem == tabIdx
            }
        }

        // ── Dashboard Tab (0) Tests ──

        viewPager?.setCurrentItem(0, false)
        val dashFrag = activity?.let { getFragment(it, 0) }

        test("dashboard_fragment_exists") {
            dashFrag is DashboardFragment
        }

        test("dashboard_dose_card") {
            dashFrag?.view?.findViewById<View>(R.id.doseCard) != null
        }

        test("dashboard_cps_card") {
            dashFrag?.view?.findViewById<View>(R.id.cpsCard) != null
        }

        test("dashboard_dose_chart") {
            dashFrag?.view?.findViewById<View>(R.id.doseChart) != null
        }

        test("dashboard_cps_chart") {
            dashFrag?.view?.findViewById<View>(R.id.cpsChart) != null
        }

        test("dashboard_time_windows") {
            val v = dashFrag?.view
            v?.findViewById<View>(R.id.chipWindow10s) != null &&
            v?.findViewById<View>(R.id.chipWindow1m) != null &&
            v?.findViewById<View>(R.id.chipWindow10m) != null &&
            v?.findViewById<View>(R.id.chipWindow1h) != null
        }

        // ── Map Tab (1) Tests ──

        viewPager?.setCurrentItem(1, false)
        val mapFrag = activity?.let { getFragment(it, 1) }

        test("map_fragment_exists") {
            mapFrag is MapFragment
        }

        test("map_card_view") {
            mapFrag?.view?.findViewById<View>(R.id.mapCard) != null
        }

        test("map_gps_tier_chips") {
            val v = mapFrag?.view
            v?.findViewById<View>(R.id.chipGpsPassive) != null &&
            v?.findViewById<View>(R.id.chipGpsBalanced) != null &&
            v?.findViewById<View>(R.id.chipGpsHigh) != null
        }

        test("map_scale_auto_chip") {
            mapFrag?.view?.findViewById<View>(R.id.chipScaleAuto) != null
        }

        test("map_scale_min_field") {
            mapFrag?.view?.findViewById<EditText>(R.id.editScaleMin) != null
        }

        test("map_scale_max_field") {
            mapFrag?.view?.findViewById<EditText>(R.id.editScaleMax) != null
        }

        test("map_scale_set_button") {
            mapFrag?.view?.findViewById<View>(R.id.btnApplyScale) != null
        }

        test("map_auto_scale_default") {
            Prefs.isMapScaleAuto(context)
        }

        test("map_scale_fields_disabled_when_auto") {
            val isAuto = Prefs.isMapScaleAuto(context)
            val minEnabled = mapFrag?.view?.findViewById<EditText>(R.id.editScaleMin)?.isEnabled
            val maxEnabled = mapFrag?.view?.findViewById<EditText>(R.id.editScaleMax)?.isEnabled
            if (isAuto) {
                minEnabled == false && maxEnabled == false
            } else {
                // If not auto, fields should be enabled
                minEnabled == true && maxEnabled == true
            }
        }

        test("map_export_button") {
            mapFrag?.view?.findViewById<View>(R.id.btnExportData) != null
        }

        test("map_sessions_button") {
            mapFrag?.view?.findViewById<View>(R.id.btnSessions) != null
        }

        test("map_theme_button") {
            mapFrag?.view?.findViewById<View>(R.id.btnMapTheme) != null
        }

        test("map_bottom_bar") {
            mapFrag?.view?.findViewById<View>(R.id.mapBottomBar) != null
        }

        // ── Scale Toggle Test ──

        test("scale_toggle_auto_to_manual") {
            val wasAuto = Prefs.isMapScaleAuto(context)
            if (wasAuto) {
                // Click AUTO chip
                mapFrag?.view?.findViewById<View>(R.id.chipScaleAuto)?.performClick()
                val nowAuto = Prefs.isMapScaleAuto(context)
                val fieldsEnabled = mapFrag?.view?.findViewById<EditText>(R.id.editScaleMin)?.isEnabled == true
                // Restore auto mode
                mapFrag?.view?.findViewById<View>(R.id.chipScaleAuto)?.performClick()
                !nowAuto && fieldsEnabled
            } else {
                // Toggle to auto and back
                mapFrag?.view?.findViewById<View>(R.id.chipScaleAuto)?.performClick()
                val nowAuto = Prefs.isMapScaleAuto(context)
                mapFrag?.view?.findViewById<View>(R.id.chipScaleAuto)?.performClick()
                nowAuto
            }
        }

        // ── Isotope Tab (2) Tests ──

        viewPager?.setCurrentItem(2, false)
        val isoFrag = activity?.let { getFragment(it, 2) }

        test("isotope_fragment_exists") {
            isoFrag is IsotopeFragment
        }

        test("isotope_panel") {
            isoFrag?.view?.findViewById<View>(R.id.isotopePanel) != null
        }

        test("isotope_scan_button") {
            isoFrag?.view?.findViewById<View>(R.id.isotopeScanBtn) != null
        }

        test("isotope_realtime_switch") {
            isoFrag?.view?.findViewById<View>(R.id.isotopeRealtimeSwitch) != null
        }

        test("isotope_chart_container") {
            isoFrag?.view?.findViewById<View>(R.id.isotopeChartContainer) != null
        }

        // ── Device Tab (3) Tests ──

        viewPager?.setCurrentItem(3, false)
        val devFrag = activity?.let { getFragment(it, 3) }

        test("device_fragment_exists") {
            devFrag is DeviceFragment
        }

        test("device_connection_status") {
            devFrag?.view?.findViewById<View>(R.id.connectionStatus) != null
        }

        test("device_find_devices_btn") {
            devFrag?.view?.findViewById<View>(R.id.findDevicesButton) != null
        }

        test("device_reconnect_btn") {
            devFrag?.view?.findViewById<View>(R.id.reconnectButton) != null
        }

        test("device_auto_connect_switch") {
            devFrag?.view?.findViewById<View>(R.id.autoConnectSwitch) != null
        }

        // ── BLE Data Flow Tests ──

        test("has_connected_devices") {
            val states = activity?.getDeviceConnectionStates()
            states != null && states.isNotEmpty()
        }

        test("has_active_connections") {
            val states = activity?.getDeviceConnectionStates()
            states?.any { it.value.name == "CONNECTED" } == true
        }

        // ── Preferences Consistency ──

        test("prefs_gps_tier_valid") {
            val tier = Prefs.getGpsTier(context)
            tier == Prefs.GpsTier.PASSIVE || tier == Prefs.GpsTier.BALANCED || tier == Prefs.GpsTier.HIGH
        }

        test("prefs_scale_min_less_than_max") {
            val min = Prefs.getMapScaleMin(context)
            val max = Prefs.getMapScaleMax(context)
            min < max
        }

        // Restore original tab
        viewPager?.setCurrentItem(originalTab, false)

        return JSONObject().apply {
            put("cmd", "run_suite")
            put("status", "OK")
            put("passed", passed)
            put("failed", failed)
            put("total", passed + failed)
            put("results", results)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun getFragment(activity: MainActivity, position: Int): Fragment? {
        return activity.supportFragmentManager.findFragmentByTag("f$position")
    }
}
