# Visual Explanation of Hexagon Orientations

## The Problem: Angle Mismatch

```
BEFORE FIX - INCORRECT (Pointy-Top Corners on Flat-Top Grid):

       Pointy corners (starting at -30°):
            
              30°
               /\
         -30°/  \90°
            /    \
           /______\
     210°          150°
           \      /
            \    /
         240°\  /120°
              \/
             270°

This creates hexagons that don't align with the flat-top coordinate grid!
```

```
AFTER FIX - CORRECT (Flat-Top Corners on Flat-Top Grid):

       Flat corners (starting at 0°):
            
           _____
      60° /     \ 0°
         /       \
    120°|         |
        |    ●    |  (center)
    180°|         |
         \       /
      240°\_____/ 300°

This creates hexagons that tessellate perfectly!
```

## Hexagon Coordinate Systems

### Flat-Top Hexagons (WHAT WE USE):
```
    ___     ___     ___
   /   \___/   \___/   \
   \___/   \___/   \___/
   /   \___/   \___/   \
   \___/   \___/   \___/
   
   - Flat edges on top/bottom
   - Corners at: 0°, 60°, 120°, 180°, 240°, 300°
   - Width: size * sqrt(3)
   - Height: size * 2
   - Hex centers form horizontal rows
```

### Pointy-Top Hexagons (WHAT WE HAD):
```
      /\      /\      /\
     /  \    /  \    /  \
    /    \  /    \  /    \
    \    /  \    /  \    /
     \  /    \  /    \  /
      \/      \/      \/
      
   - Pointy vertices on top/bottom
   - Corners at: -30°, 30°, 90°, 150°, 210°, 270°
   - Width: size * 2
   - Height: size * sqrt(3)
   - Hex centers form vertical columns
```

## The Coordinate Conversion Formulas

The code uses these formulas (flat-top hexagon math):

**Forward (lat/lng → hex q,r):**
```kotlin
val x = lng * metersPerDegreeLng
val y = lat * metersPerDegreeLat
val q = (sqrt(3.0) / 3.0 * x - 1.0 / 3.0 * y) / size
val r = (2.0 / 3.0 * y) / size
```

**Reverse (hex q,r → lat/lng):**
```kotlin
val x = size * (sqrt(3.0) * q + sqrt(3.0) / 2.0 * r)
val y = size * (3.0 / 2.0 * r)
val lng = x / metersPerDegreeLng
val lat = y / metersPerDegreeLat
```

These formulas are specifically for **flat-top hexagons**, so the corner generation must also use flat-top angles.

## Why Corners Must Match the Grid

When you convert a GPS coordinate to a hex cell ID (q, r), then convert that back to a center point, and generate corners from that center:

1. The (q, r) identifies a specific hex cell in the grid
2. Converting (q, r) back to (lat, lng) gives the hex center
3. The corners must be offset from that center using the SAME geometry
4. If corners use different angles, they don't align with the grid!

**Result of mismatch:**
- Hexagons overlap instead of touching edge-to-edge
- Grid doesn't tessellate properly
- Tap detection becomes unreliable

**Result of correct match:**
- Perfect tessellation
- Each hex occupies exactly one cell in the grid
- No gaps, no overlaps
