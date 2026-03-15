package com.radiacode.ble

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 adapter for the main tab navigation.
 * Tabs: Dashboard | Map | Isotope ID | Device
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        const val TAB_DASHBOARD = 0
        const val TAB_MAP = 1
        const val TAB_ISOTOPE = 2
        const val TAB_DEVICE = 3
        const val TAB_COUNT = 4

        val TAB_TITLES = arrayOf("Dashboard", "Map", "Isotope ID", "Device")
    }

    override fun getItemCount(): Int = TAB_COUNT

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            TAB_DASHBOARD -> DashboardFragment()
            TAB_MAP -> MapFragment()
            TAB_ISOTOPE -> IsotopeFragment()
            TAB_DEVICE -> DeviceFragment()
            else -> DashboardFragment()
        }
    }
}
