# Live Map View Implementation

This document describes the live map view feature implementation for the Open RadiaCode Android app.

## Overview

The live map view displays radiation readings on an interactive Google Map, allowing users to visualize spatial distribution of dose/count rate measurements as they collect data.

## Components

### 1. MapCardView (`ui/MapCardView.kt`)

Custom Android view that integrates Google Maps with radiation data visualization.

**Features:**
- Interactive Google Map with user location
- Color-coded markers for readings (green → yellow → red gradient)
- Metric selector: Dose Rate (μSv/h) or Count Rate (cps/cpm)
- Dynamic legend showing min/max values
- Reset button to clear all markers
- Dark theme map styling
- Persistent storage of readings with GPS coordinates

**Key Methods:**
- `onCreate(Bundle)` - Initialize map view (call from Activity lifecycle)
- `addReading(MapReading)` - Add a reading with GPS coordinates
- `addReadingAtCurrentLocation(...)` - Add reading using current device location
- `clearReadings()` - Remove all markers from map
- `onResume()`, `onPause()`, etc. - Lifecycle methods to forward to MapView

### 2. Data Storage (`Prefs.kt`)

Extended Prefs object with map reading storage.

**New Types:**
```kotlin
data class MapReading(
    val latitude: Double,
    val longitude: Double,
    val uSvPerHour: Float,
    val cps: Float,
    val timestampMs: Long
)
```

**New Methods:**
- `addMapReading(Context, String, MapReading)` - Store reading with GPS
- `getMapReadings(Context, String): List<MapReading>` - Retrieve readings for device
- `clearMapReadings(Context, String)` - Clear all map readings for device

Storage format: Semicolon-separated entries of `timestamp,lat,lon,dose,cps`

### 3. Integration (`MainActivity.kt`)

Map card integrated into dashboard panel.

**Changes:**
- Added location permissions (ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
- Added map card view binding
- Added `setupMapCard(Bundle)` initialization
- Added map lifecycle forwarding (onStart, onResume, onPause, onStop, etc.)
- Added automatic reading updates with GPS location in UI loop
- Readings automatically saved to map when GPS location is available

## Layout Structure

```xml
<!-- activity_main.xml -->
<com.radiacode.ble.ui.MapCardView
    android:id="@+id/mapCard"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

The card includes:
- Header with title, metric selector dropdown, and reset button
- Google MapView (300dp height by default)
- Legend bar with color gradient and min/max labels

## Permissions

Required permissions added to AndroidManifest.xml:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

Google Maps API key:
```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="AIzaSyDUm7nN1Z8fM6fV3z4z9gR8q9aZ2V8E2Zo" />
```

## Dependencies

Added to `app/build.gradle.kts`:
```kotlin
implementation("com.google.android.gms:play-services-maps:18.2.0")
implementation("com.google.android.gms:play-services-location:21.1.0")
```

## Color Coding

Readings are color-coded based on normalized value (min to max):
- **Green** (low): Values near minimum
- **Yellow** (medium): Mid-range values  
- **Red** (high): Values near maximum

Formula:
```kotlin
normalized = (value - min) / (max - min)
if (normalized < 0.5) {
    // Green to yellow transition
    red = normalized * 2 * 255
    green = 255
} else {
    // Yellow to red transition
    red = 255
    green = (1 - normalized) * 2 * 255
}
```

## Map Styling

Dark theme map style applied via `map_style_dark.json` to match app design:
- Dark background (#1a1a1e)
- Muted labels and features
- Low-contrast roads and boundaries
- Dark water bodies

## Usage Flow

1. User launches app → Location permissions requested
2. App connects to RadiaCode device
3. New readings arrive → GPS location captured
4. Reading + GPS stored and displayed on map as colored circle
5. Legend updates to show current min/max range
6. User can switch between dose rate and count rate views
7. User can reset map to clear all markers
8. Readings persist across app restarts (per device)

## Testing Checklist

- [ ] Location permission prompt appears on first launch
- [ ] Map displays with user location
- [ ] Readings appear as colored circles when device connected
- [ ] Colors change appropriately based on value range
- [ ] Metric selector switches between dose/count rate
- [ ] Legend shows correct min/max values
- [ ] Reset button clears all markers
- [ ] Map retains readings after app restart
- [ ] Map works with multiple devices (separate data per device)
- [ ] Dark theme applied correctly

## Known Limitations

1. **API Key**: Uses unrestricted key suitable for development. For production, restrict key to app package and SHA-1 fingerprint.

2. **GPS Accuracy**: Readings require GPS fix. Indoor or poor GPS conditions may result in missing/inaccurate locations.

3. **Memory**: Large numbers of markers (>1000) may impact performance. Current limit: 1000 readings per device.

4. **Network**: Map tiles require internet connectivity. Cached tiles used when offline.

## Future Enhancements

- [ ] Heatmap overlay option
- [ ] Export map as image
- [ ] Clustering for high-density areas
- [ ] Time-based filtering (show last hour/day/week)
- [ ] Route replay animation
- [ ] Offline map tile caching
- [ ] Custom marker shapes/sizes based on value
- [ ] Map bounds auto-adjustment to show all markers
