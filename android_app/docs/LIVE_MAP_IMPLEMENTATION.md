# Live Map Feature Implementation Summary

## Overview
This document describes the implementation of a real-time live map feature for the Open RadiaCode Android app. The map displays radiation readings at GPS locations as the user moves around with their device.

## Changes Made

### 1. Dependencies (build.gradle.kts)
- Added OSMDroid library v6.1.18 for OpenStreetMap integration
- Updated version from 0.41 to 0.42
- Updated versionCode from 29 to 30

### 2. Permissions (AndroidManifest.xml)
- Added ACCESS_FINE_LOCATION permission for all Android versions (required for map feature)
- Added ACCESS_COARSE_LOCATION permission as fallback
- Added INTERNET permission for map tile downloads
- Added WRITE_EXTERNAL_STORAGE (for Android 9 and below) for map tile caching

### 3. Data Storage (Prefs.kt)
Added three new methods for map data management:

#### Data Structure
```kotlin
data class MapDataPoint(
    val latitude: Double,
    val longitude: Double,
    val uSvPerHour: Float,
    val cps: Float,
    val timestampMs: Long
)
```

#### Methods
- `addMapDataPoint()` - Adds a new GPS+reading point, limits to 500 points
- `getMapDataPoints()` - Retrieves all stored map points
- `clearMapDataPoints()` - Clears all map data

Data is stored as CSV format in SharedPreferences for efficiency.

### 4. UI Component (MapCardView.kt)
Created a custom LinearLayout-based view with the following features:

#### Core Functionality
- **OSMDroid MapView** integration with multi-touch controls
- **GPS Location Tracking** using LocationManager
  - Updates every 5 seconds or 10 meters
  - Automatically centers map on user location
  - Handles permission checks gracefully
- **Metric Selector** - Spinner dropdown to choose between:
  - Dose Rate (µSv/h or nSv/h based on user preferences)
  - Count Rate (CPS or CPM based on user preferences)
- **Reset Button** - Clears all map markers and stored data
- **Dynamic Scale Bar** - Shows min/max range with gradient color coding

#### Marker Rendering
- Circular markers color-coded by radiation level:
  - Green (low) → Yellow (medium) → Red (high)
  - Normalized based on current dataset min/max
- Marker popup shows formatted dose and count rate values
- Updates automatically when metric type changes

#### Lifecycle Management
- Starts location tracking in `startLocationTracking()`
- Properly removes location listener in `stopLocationTracking()`
- Cleans up MapView in `onDetachedFromWindow()`

### 5. Layout Integration (view_map_card.xml)
Simple layout structure:
- Header with "LIVE MAP" title, metric selector, and reset button
- MapView at 240dp height
- FrameLayout container for programmatically-added ScaleBarView

### 6. Dashboard Integration (activity_main.xml)
- Added MapCardView after CPS chart panel
- Positioned before isotope detection panel
- Uses consistent 12dp margin like other cards

### 7. MainActivity Integration (MainActivity.kt)
#### Initialization
- Added `mapCard` field reference
- Created `setupMapCard()` method:
  - Loads existing data points from storage
  - Starts location tracking if permissions granted
- Added call to `setupMapCard()` in `onCreate()`

#### Permission Handling
- Updated `requiredPermissions` to always request ACCESS_FINE_LOCATION
- Added map location tracking start in `onRequestPermissionsResult()`

#### Data Flow
- Added `mapCard.addReading()` call in UI update loop
- Triggered whenever new reading arrives from device
- Only adds points when not paused and device is selected

## Technical Details

### Map Library Choice
**OSMDroid** was chosen over Google Maps because:
- Open source and doesn't require API keys
- No usage limits or billing
- Works offline with cached tiles
- Well-maintained and actively developed
- Compatible with minSdk 26 (Android 8.0)

### Location Update Strategy
- **Interval**: 5 seconds
- **Distance**: 10 meters
- **Provider**: GPS_PROVIDER for accuracy
- Fallback to last known location on startup

### Data Persistence Strategy
- CSV format: `lat,lon,dose,cps,timestamp`
- Line-separated for easy parsing
- Limited to 500 points (oldest removed first)
- Survives app restarts
- Minimal storage overhead

### Color Coding Algorithm
```
if value < 50% of range:
    interpolate(green, yellow)
else:
    interpolate(yellow, red)
```

This ensures smooth gradient transitions and clear visual distinction between radiation levels.

## User Experience Flow

1. **First Launch**
   - App requests location permission (along with Bluetooth and notifications)
   - User grants location permission
   - Map starts at default location (0,0) until GPS lock acquired

2. **Normal Operation**
   - Map centers on user's current location
   - Every ~1 second when device reading arrives:
     - If GPS location available, marker is placed on map
     - Marker color reflects radiation level
     - Scale bar updates to show current range
   - User can pan/zoom map freely
   - Markers persist across sessions

3. **Metric Switching**
   - User taps Spinner to select "Dose Rate" or "Count Rate"
   - All markers re-render with new color coding based on selected metric
   - Scale bar updates to show appropriate units and range

4. **Reset**
   - User taps reset button
   - All markers removed from map
   - Stored data points cleared
   - Scale bar resets to default range

## Design Consistency

The map card follows the existing app design system:

### Colors
- Background: `pro_surface` (#1A1A1E)
- Border: `pro_border` (#2A2A2E) via `card_background` drawable
- Title: `pro_text_muted` (#6E6E78)
- Markers: `pro_green`, `pro_yellow`, `pro_red` gradient

### Typography
- Title: 11sp, bold, 0.1 letter spacing (consistent with other cards)

### Layout
- 12dp card padding
- 8dp internal spacing
- 28dp icon buttons with 4dp padding
- Matches spacing of dose/CPS chart cards

## Future Enhancements

Potential improvements for future versions:

1. **Heatmap Mode** - Display smooth gradient overlay instead of discrete markers
2. **Track Recording** - Connect markers with lines to show movement path
3. **Export GPX** - Export map data to GPX format for use in other mapping apps
4. **Clustering** - Group nearby markers when zoomed out
5. **Time Filter** - Show only markers from last X minutes/hours
6. **Marker Labels** - Optional always-visible value labels on markers
7. **Offline Maps** - Pre-cache map tiles for offline use
8. **Background Tracking** - Continue adding markers even when app is in background

## Testing Checklist

When testing this feature on device:

- [ ] Build succeeds without errors
- [ ] App requests location permission on first launch
- [ ] Map loads and displays properly
- [ ] GPS location acquired and map centers on user
- [ ] Markers appear at correct locations
- [ ] Marker colors reflect radiation levels accurately
- [ ] Scale bar updates with correct min/max values
- [ ] Metric selector switches between dose/count rate
- [ ] All markers re-render when metric changes
- [ ] Reset button clears all markers
- [ ] Data persists across app restarts
- [ ] Map can be panned and zoomed
- [ ] No memory leaks or crashes
- [ ] UI remains responsive while tracking

## Files Modified

1. `app/build.gradle.kts` - Dependencies and version
2. `app/src/main/AndroidManifest.xml` - Permissions
3. `app/src/main/java/com/radiacode/ble/Prefs.kt` - Data storage
4. `app/src/main/java/com/radiacode/ble/MainActivity.kt` - Integration
5. `app/src/main/java/com/radiacode/ble/ui/MapCardView.kt` - New component
6. `app/src/main/res/layout/activity_main.xml` - Dashboard layout
7. `app/src/main/res/layout/view_map_card.xml` - New layout
8. `README.md` - Feature documentation

## Conclusion

The live map feature is fully implemented and integrated into the dashboard. It follows the app's existing patterns for data storage, UI components, and design consistency. The implementation is efficient, with minimal memory overhead and clean lifecycle management.

The feature provides valuable spatial context for radiation readings, allowing users to identify hotspots and track exposure as they move through different areas.
