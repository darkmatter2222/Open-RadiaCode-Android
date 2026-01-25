# Hexagon Tessellation Bug Fix

## Problem

Hexagons were rendering on top of each other instead of tessellating cleanly (see issue screenshot). This created overlapping hexagons that didn't form a proper hexagonal grid.

## Root Cause

The bug was caused by a mismatch between the coordinate system and the corner generation:

1. **Coordinate Conversion (latLngToHexId → hexIdToLatLng)**: The formulas used were for **flat-top hexagons**
   - Forward: `q = (sqrt(3)/3 * x - 1/3 * y) / size`, `r = (2/3 * y) / size`
   - Reverse: `x = size * (sqrt(3) * q + sqrt(3)/2 * r)`, `y = size * (3/2 * r)`

2. **Corner Generation (getHexCorners)**: The angles used were for **pointy-top hexagons**
   - Old: `angleDeg = 60.0 * i - 30.0` (starts at -30°, typical for pointy-top)
   - This created corners that didn't match the coordinate system!

## The Fix

Changed the corner angle calculation to match flat-top hexagon geometry:

**Before:**
```kotlin
val angleDeg = 60.0 * i - 30.0  // Pointy-top: start at -30°
```

**After:**
```kotlin
val angleDeg = 60.0 * i  // Flat-top: start at 0°
```

## Why This Works

For a hexagonal tessellation to work correctly, the corner positions must be consistent with the coordinate system used to convert between lat/lng and hex grid coordinates.

### Flat-Top Hexagon Geometry:
- Hexagons have flat edges on top and bottom
- Corners are at angles: 0°, 60°, 120°, 180°, 240°, 300° (starting from the right and going counter-clockwise)
- Width: `size * sqrt(3)`
- Height: `size * 2`
- Horizontal spacing: `size * sqrt(3)`
- Vertical spacing: `size * 1.5`

### Pointy-Top Hexagon Geometry:
- Hexagons have pointy vertices on top and bottom
- Corners are at angles: -30°, 30°, 90°, 150°, 210°, 270°
- Width: `size * 2`
- Height: `size * sqrt(3)`

The coordinate conversion formulas in the code implement flat-top geometry, so the corners must also use flat-top angles for proper tessellation.

## Files Modified

1. `/app/src/main/java/com/radiacode/ble/FullscreenMapActivity.kt` - Line 615
2. `/app/src/main/java/com/radiacode/ble/ui/MapCardView.kt` - Line 537

Both files had the same bug and received the same fix.

## Expected Result

After this fix, hexagons should:
- Form a clean hexagonal grid pattern
- Never overlap with each other
- Tessellate perfectly with no gaps
- Each hex cell should be uniquely identifiable by its (q, r) axial coordinates
- Adjacent hexagons should share edges seamlessly

## Testing

To verify the fix works:
1. Build and install the app
2. Navigate to the map view
3. Collect radiation readings while moving around
4. Observe that hexagons form a clean tessellating pattern
5. Zoom in/out to verify hexagons remain properly aligned
6. Tap on hexagons to verify they respond to the correct cell
