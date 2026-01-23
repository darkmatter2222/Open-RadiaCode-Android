# 🧠 VEGA Statistical Intelligence System

*"I am quietly analyzing. I will speak when there is something worth saying."*

---

## The Vision

Transform Open RadiaCode from a **reactive alarm system** into a **proactive radiological intelligence companion** that uses real-time statistical analysis, forecasting, and anomaly detection to provide meaningful insights—not noise.

---

## Implementation Status

| # | Feature | Status | Notes |
|---|---------|--------|-------|
| 1 | Adaptive Baseline Learning | ✅ Implemented | EWMA-based adaptive baseline in StatisticalEngine |
| 2 | Z-Score Anomaly Detection | ✅ Implemented | Real-time z-score with 1σ/2σ/3σ/4σ levels |
| 3 | Rate-of-Change Detection | ✅ Implemented | First derivative tracking, trend detection |
| 4 | CUSUM Change-Point Detection | ✅ Implemented | Catches subtle persistent shifts |
| 5 | Real-Time Dose Rate Forecasting | ✅ Implemented | Holt-Winters + visual forecast on chart |
| 6 | Predictive Threshold Crossing | ✅ Implemented | Warns BEFORE hitting user thresholds |
| 7 | Cumulative Dose Forecasting | ✅ Implemented | Tracks session dose, projects time-to-limit |
| 8 | Source Proximity Estimation | ✅ Implemented | Inverse-square law distance estimation |
| 9 | Poisson Uncertainty Quantification | ✅ Implemented | sqrt(N) uncertainty, confidence bounds |
| 10 | Moving Average Crossover Signals | ✅ Implemented | Golden/Death cross detection, configurable windows |
| 11 | Bayesian Changepoint Detection | ✅ Implemented | Online regime change detection with probability |
| 12 | Autocorrelation Pattern Recognition | ✅ Implemented | ACF-based periodic pattern detection |
| 13 | Location-Aware Anomaly Detection | ⬜ Not Started | |
| 14 | Spatial Gradient Analysis | ⬜ Not Started | |
| 15 | Hotspot Prediction & Interpolation | ⬜ Not Started | |
| 16 | Ensemble Voting Alerts | ⬜ Not Started | |
| 17 | Confidence-Weighted Speech | ✅ Implemented | Vega announcements vary by confidence |
| 18 | Smart Pre-Built Alert Profiles | ⬜ Not Started | |
| 19 | Contextual Alert Suppression | ⬜ Not Started | |
| 20 | Proactive Statistical Insights | ⬜ Not Started | |

---

## 🎯 TIER 1: Foundational Statistical Intelligence

### 1. Adaptive Baseline Learning (ABL)
> *"I have learned your normal. I will tell you when something changes."*

**Description:**
- Continuously learn the user's personal "background normal" using exponential weighted moving average
- Adapt to time-of-day patterns, location history, and environmental context
- Baseline auto-adjusts over 24h/7d/30d windows

**Vega speaks:** *"Your current environment is 0.08 µSv/h. This is within your established normal for this location."*

**Implementation Details:**
- Create `StatisticalEngine.kt` class to hold all statistical calculations
- Store baseline data in SharedPreferences or Room database
- Track: mean, variance, sample count, last update time
- Use exponential weighted moving average (EWMA) with configurable α

**Data Structure:**
```kotlin
data class BaselineStats(
    val mean: Double,
    val variance: Double,
    val standardDeviation: Double,
    val sampleCount: Long,
    val lastUpdateMs: Long,
    val windowType: WindowType // MINUTE_1, MINUTE_5, HOUR_1, ADAPTIVE
)
```

---

### 2. Z-Score Anomaly Detection
> *"Statistical deviation detected. Confidence: 99.7%."*

**Description:**
- Real-time z-score calculation against rolling baseline
- Configurable alert thresholds: 1σ (68%), 2σ (95%), 3σ (99.7%)
- Visual indicator showing current reading's position on normal distribution

**Alert config:** "Alert me when readings exceed [1/2/3] standard deviations from my [1min/5min/1hr/adaptive] baseline"

**Implementation Details:**
- Z-score = (current - mean) / standardDeviation
- Map z-score to percentile for user-friendly display
- Add z-score indicator to dashboard

**Confidence Levels:**
| Z-Score | Confidence | Description |
|---------|------------|-------------|
| 1σ | 68.27% | Slightly unusual |
| 2σ | 95.45% | Notably unusual |
| 3σ | 99.73% | Highly anomalous |
| 4σ | 99.99% | Extremely rare |

---

### 3. Rate-of-Change (ROC) Detection
> *"Dose rate increasing. You are approaching a source."*

**Description:**
- Calculate first derivative (µSv/h per second)
- Detect acceleration (second derivative) for rapid changes
- Alert on sustained increases even if absolute value is low

**Perfect for:** Detecting approach to sources before you're close

**Implementation Details:**
- Track last N readings with timestamps
- Calculate: `roc = (current - previous) / timeDeltaSeconds`
- Smooth with moving average to reduce noise
- Second derivative for acceleration detection

**Alert Types:**
- Rising rapidly: ROC > threshold for N consecutive readings
- Falling rapidly: ROC < -threshold for N consecutive readings
- Accelerating: Second derivative positive and significant

---

### 4. CUSUM Change-Point Detection
> *"I have detected a subtle but persistent shift in background levels."*

**Description:**
- Cumulative Sum algorithm detects small, sustained changes that threshold alerts miss
- Catches gradual contamination or slow source approach
- Configurable sensitivity parameter

**Example:** Background slowly drifts from 0.12 to 0.18 over 10 minutes—CUSUM catches it, static alarm doesn't

**Implementation Details:**
```kotlin
// CUSUM algorithm
val slack = k * standardDeviation  // k typically 0.5
val threshold = h * standardDeviation  // h typically 4-5

cusumHigh = max(0, cusumHigh + (reading - mean - slack))
cusumLow = min(0, cusumLow + (reading - mean + slack))

if (cusumHigh > threshold) // Upward shift detected
if (cusumLow < -threshold) // Downward shift detected
```

---

## 📈 TIER 2: Forecasting & Prediction

### 5. Real-Time Dose Rate Forecasting
> *"Based on current trend, I project readings will reach 0.45 µSv/h within 2 minutes."*

**Description:**
- **Visual:** Dotted line extending from current reading showing 30s/1m/5m forecast
- **Shaded confidence interval** (fan chart) showing uncertainty
- Uses Holt-Winters exponential smoothing for trend + seasonality
- Updates every reading

**Dashboard Enhancement:**
```
┌─────────────────────────────────────────┐
│ REAL-TIME DOSE RATE                     │
│ ╭─────────╮                             │
│ │ 0.23    │ µSv/h                       │
│ ╰─────────╯                             │
│ ▁▂▃▄▅▆▇█▇▆▅▄▃▂▁▂▃▄▅▆▇···░░░            │
│                        └── forecast     │
│                    (dotted + confidence)│
└─────────────────────────────────────────┘
```

**Implementation Details:**
- Holt-Winters double exponential smoothing
- Level equation: `L_t = α * y_t + (1 - α) * (L_{t-1} + T_{t-1})`
- Trend equation: `T_t = β * (L_t - L_{t-1}) + (1 - β) * T_{t-1}`
- Forecast: `F_{t+h} = L_t + h * T_t`
- Confidence interval widens with forecast horizon

---

### 6. Predictive Threshold Crossing
> *"At current rate of increase, you will exceed 1.0 µSv/h in approximately 4 minutes 23 seconds."*

**Description:**
- Forecast when readings will cross user-defined thresholds
- Early warning system—alert BEFORE the threshold is hit

**Alert config:** "Warn me [30s/1m/5m] before I'm predicted to exceed [threshold]"

**Implementation Details:**
- Use linear extrapolation from current trend
- Calculate: `timeToThreshold = (threshold - current) / rateOfChange`
- Alert when `timeToThreshold < warningWindow`

---

### 7. Cumulative Dose Forecasting
> *"You have accumulated 2.3 µSv today. At current rate, you will reach your daily target of 10 µSv in 8 hours."*

**Description:**
- Track running cumulative dose
- Project time-to-limit based on current readings and historical patterns
- Support for daily/weekly/monthly dose budgets
- Visual progress bar with forecast endpoint

**Implementation Details:**
- Integrate dose rate over time: `cumulativeDose += doseRate * deltaTime`
- Project remaining time: `remainingTime = (limit - cumulative) / currentRate`
- Store daily/weekly/monthly totals in database

---

### 8. Source Proximity Estimation
> *"Based on inverse-square analysis, I estimate you are approximately 3.2 meters from a point source. Closest approach projected in 12 seconds."*

**Description:**
- Use rate-of-change + inverse square law to estimate distance
- Predict dose at closest approach based on current trajectory

**Mind-blowing for:** Walking toward a source—know what you'll experience before you get there

**Implementation Details:**
- Inverse square law: `I ∝ 1/r²`
- If rate is increasing, estimate distance from rate of change
- Requires multiple readings while moving toward source
- Calculate: `distanceRatio = sqrt(I1/I2)`

---

## 🔬 TIER 3: Advanced Statistical Analysis

### 9. Poisson Uncertainty Quantification
> *"Count rate: 142 CPS ± 12 (1σ). Statistical uncertainty: 8.4%."*

**Description:**
- Radioactive decay follows Poisson statistics
- Calculate proper uncertainty bounds (√N)
- Only alert when change exceeds statistical uncertainty

**Reduces false alarms** from normal counting statistics

**Implementation Details:**
- For N counts: uncertainty = √N
- Relative uncertainty = √N / N = 1/√N
- Only flag as significant if: `|observed - expected| > k * sqrt(expected)`

---

### 10. Moving Average Crossover Signals
> *"Short-term average has crossed above long-term average. Trend reversal detected."*

**Description:**
- 10s MA vs 60s MA (configurable)
- "Golden cross" = readings trending up
- "Death cross" = readings trending down
- Visual crossover indicator on charts

**Alert:** "Notify me when short-term trend crosses long-term trend"

**Implementation Details:**
- Calculate simple moving averages for two windows
- Detect crossover: `(shortMA_prev < longMA_prev) && (shortMA_curr > longMA_curr)`
- Debounce to prevent rapid flip-flopping

---

### 11. Bayesian Changepoint Detection
> *"I am 94% confident that environmental conditions changed 47 seconds ago."*

**Description:**
- Probabilistic detection of regime changes
- Quantifies uncertainty in change detection
- Identifies exact moment conditions changed

**Example:** Walk from outside to inside a building—Vega detects the transition

**Implementation Details:**
- Online Bayesian changepoint detection (Adams & MacKay algorithm)
- Maintain run length probability distribution
- Detect when probability of recent changepoint exceeds threshold

---

### 12. Autocorrelation Pattern Recognition
> *"I have detected a periodic pattern in readings with a 23-second cycle. Possible rotating machinery nearby."*

**Description:**
- Detect periodic patterns (HVAC, industrial equipment, etc.)
- Identify the period and amplitude of oscillations
- Alert on appearance/disappearance of patterns

**Insight:** Understanding WHY readings fluctuate

**Implementation Details:**
- Calculate autocorrelation function for lag 1 to N
- Find peaks in ACF to identify periodicities
- Monitor for pattern emergence/disappearance

---

## 🗺️ TIER 4: Geospatial Intelligence

### 13. Location-Aware Anomaly Detection
> *"Current reading at this location is 2.1 standard deviations above your historical average for this area."*

**Description:**
- Build location-specific baselines using hexagon grid
- Compare current reading to what's "normal" at THIS location
- Detect if a familiar place has changed

**Perfect for:** Repeat surveys, monitoring known areas

**Implementation Details:**
- Use existing hexagon grid system
- Store per-hexagon baseline statistics
- Compare current reading to location-specific baseline

---

### 14. Spatial Gradient Analysis
> *"Dose rate gradient: +0.03 µSv/h per meter, bearing 045°. Source direction identified."*

**Description:**
- Calculate spatial derivative as you move
- Determine direction of increasing/decreasing radiation
- Arrow indicator pointing toward source

**Game-changer for:** Source localization and contamination boundary mapping

**Implementation Details:**
- Track readings with GPS coordinates
- Calculate gradient vector from recent position/reading pairs
- Display compass bearing toward increasing radiation

---

### 15. Hotspot Prediction & Interpolation
> *"Based on collected data, I predict elevated readings 12 meters to your northeast."*

**Description:**
- Gaussian process regression for spatial interpolation
- Predict readings at unmeasured locations
- Confidence-weighted uncertainty

**Visualization:** Heatmap with predicted values between measured points

**Implementation Details:**
- Use Kriging or Gaussian process for spatial interpolation
- Generate prediction grid around current location
- Display uncertainty alongside predictions

---

## 🧬 TIER 5: Intelligent Alert System

### 16. Ensemble Voting Alerts
> *"Multiple detection algorithms agree. Alert confidence: HIGH."*

**Description:**
- Run Z-score + CUSUM + ROC simultaneously
- Only alert when 2/3 or 3/3 agree
- Dramatically reduces false positives

**Alert levels:**
- 1/3 algorithms: "Watching" (silent)
- 2/3 algorithms: "Attention" (gentle notification)
- 3/3 algorithms: "Alert" (Vega speaks)

**Implementation Details:**
- Create AlertEnsemble class managing multiple detectors
- Voting system with configurable thresholds
- Track individual detector states

---

### 17. Confidence-Weighted Speech
> From calm to urgent based on statistical certainty

**Confidence Levels:**
- **Low confidence (70-90%):** *"I'm observing an interesting pattern. Continued monitoring recommended."*
- **Medium confidence (90-99%):** *"Statistical analysis indicates an anomaly. Attention advised."*
- **High confidence (99%+):** *"Anomaly confirmed with high statistical certainty. Immediate attention recommended."*

**Implementation Details:**
- Map statistical confidence to speech urgency
- Vary voice parameters or phrasing based on confidence
- Pre-bake audio for different confidence levels

---

### 18. Smart Pre-Built Alert Profiles
> *"Selecting alert profile: Survey Mode. Anomaly detection sensitivity: HIGH."*

**Pre-configured profiles:**

| Profile | Description | Included Alerts |
|---------|-------------|-----------------|
| **Background Explorer** | Casual monitoring | 3σ anomaly, extreme threshold only |
| **Survey Mode** | Active searching | 2σ anomaly, ROC detection, spatial gradient |
| **Hotspot Hunter** | Source localization | 1σ anomaly, gradient following, proximity estimation |
| **Safety First** | Occupational use | Cumulative dose tracking, predictive threshold crossing |
| **Data Scientist** | All alerts, all data | Everything enabled, full statistics display |

**Implementation Details:**
- Create AlertProfile data class
- Store profiles in resources or database
- Quick-switch UI in settings or notification

---

### 19. Contextual Alert Suppression
> *"I detected elevated readings, but you are in a known elevated area. Suppressing routine alert."*

**Description:**
- Learn which locations have naturally elevated background
- Suppress alerts that match learned patterns
- Still alert if readings exceed learned expectations for that location

**Reduces alert fatigue** while maintaining safety

**Implementation Details:**
- Tag hexagons as "known elevated" after repeated visits
- Adjust alert thresholds based on location context
- Allow manual marking of locations

---

## 🎭 TIER 6: The Vega Experience

### 20. Proactive Statistical Insights
> *"Vega speaks only when there is something worth saying."*

**Periodic Insight System** (not alerts—insights):

- **Hourly summary:** *"In the past hour, your average exposure was 0.14 µSv/h with a standard deviation of 0.02. No anomalies detected."*

- **Location insight:** *"You have entered an area where your historical average is 15% above your overall baseline."*

- **Trend insight:** *"I've observed a gradual 8% decrease in background levels over the past 3 hours. Weather front passage likely."*

- **Statistical milestone:** *"You have now collected 10,000 readings. Your lifetime baseline standard deviation has stabilized to 0.023 µSv/h."*

- **Forecast insight:** *"Based on current patterns, I predict stable conditions for the next 30 minutes."*

**Implementation Details:**
- Schedule periodic analysis tasks
- Generate natural language summaries
- Queue insights for appropriate moments (not during alerts)

---

## 📊 New Alert Configuration UI

```
┌─────────────────────────────────────────────────────┐
│  ⚡ CREATE STATISTICAL ALERT                        │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ALERT TYPE                                         │
│  ○ Static Threshold (current)                       │
│  ○ Standard Deviation (σ)      ← NEW                │
│  ○ Rate of Change              ← NEW                │
│  ○ Predictive Crossing         ← NEW                │
│  ○ CUSUM Change Detection      ← NEW                │
│                                                     │
│  ─────────────────────────────────────────────────  │
│                                                     │
│  BASELINE WINDOW                                    │
│  [●] Adaptive (learns your normal)                  │
│  [ ] 1 minute   [ ] 5 minutes   [ ] 1 hour          │
│                                                     │
│  SENSITIVITY                                        │
│  Less sensitive ────●──────────── More sensitive    │
│                   (2σ)                              │
│                                                     │
│  ─────────────────────────────────────────────────  │
│                                                     │
│  [ ] Speak alert (Vega voice)                       │
│  [ ] Vibrate    [ ] Sound    [ ] Notification       │
│                                                     │
│                     [Cancel]  [Create Alert]        │
└─────────────────────────────────────────────────────┘
```

---

## 🛠️ Implementation Priority

### Phase 1: Core Statistics (Tier 1) - CURRENT
1. ⬜ Z-Score Anomaly Detection (#2)
2. ⬜ Rate-of-Change Detection (#3)
3. ⬜ Adaptive Baseline Learning (#1)
4. ⬜ CUSUM Change-Point Detection (#4)

### Phase 2: Forecasting (Tier 2)
5. ⬜ Real-Time Forecasting with visual dotted line (#5)
6. ⬜ Predictive Threshold Crossing (#6)
7. ⬜ Cumulative Dose Forecasting (#7)
8. ⬜ Source Proximity Estimation (#8)

### Phase 3: Advanced Stats (Tier 3)
9. ⬜ Poisson Uncertainty (#9)
10. ⬜ Moving Average Crossover (#10)
11. ⬜ Bayesian Changepoint (#11)
12. ⬜ Autocorrelation Pattern Recognition (#12)

### Phase 4: Geospatial (Tier 4)
13. ⬜ Location-Aware Anomaly Detection (#13)
14. ⬜ Spatial Gradient Analysis (#14)
15. ⬜ Hotspot Prediction (#15)

### Phase 5: Intelligent Alerts (Tier 5)
16. ⬜ Ensemble Voting (#16)
17. ⬜ Confidence-Weighted Speech (#17)
18. ⬜ Smart Pre-Built Alert Profiles (#18)
19. ⬜ Contextual Alert Suppression (#19)

### Phase 6: Vega Experience (Tier 6)
20. ⬜ Proactive Insights (#20)

---

## 💡 The Philosophy

> *"The best alert is the one you never had to hear because nothing was wrong. The second best alert is the one that told you something meaningful you couldn't have known otherwise."*

This system transforms Vega from a simple alarm into a **statistical sentinel**—quietly analyzing thousands of readings, understanding patterns, learning baselines, and speaking only when the mathematics reveal something genuinely significant.

**The goal:** A user walks around with the app, forgets it's even running, and then Vega speaks: *"I've been analyzing for the past 47 minutes. I have detected a statistically significant anomaly 15 meters ahead. Confidence: 98.2%."*

That's the dream. That's data science in your pocket.

---

## Architecture Notes

### Core Classes to Create

1. **StatisticalEngine.kt** - Central statistics calculator
   - Rolling statistics (mean, variance, std dev)
   - Z-score calculation
   - Rate of change calculation
   - CUSUM algorithm
   - Forecasting (Holt-Winters)

2. **BaselineManager.kt** - Manages adaptive baselines
   - Per-location baselines
   - Time-window baselines
   - Persistence to storage

3. **AnomalyDetector.kt** - Anomaly detection algorithms
   - Z-score detector
   - CUSUM detector
   - ROC detector
   - Ensemble voting

4. **StatisticalAlertEvaluator.kt** - Evaluates statistical alerts
   - Integrates with existing AlertEvaluator
   - Handles new alert types

5. **ForecastEngine.kt** - Time series forecasting
   - Holt-Winters implementation
   - Confidence interval calculation
   - Threshold crossing prediction

### Data Flow

```
RadiaCodeForegroundService
    │
    ▼
handleDeviceReading(doseRate, cps, timestamp)
    │
    ├──► StatisticalEngine.addReading()
    │       ├──► Update rolling statistics
    │       ├──► Calculate z-score
    │       ├──► Calculate rate of change
    │       ├──► Update CUSUM
    │       └──► Update forecast
    │
    ├──► AnomalyDetector.evaluate()
    │       ├──► Check z-score threshold
    │       ├──► Check CUSUM threshold
    │       ├──► Check ROC threshold
    │       └──► Ensemble vote
    │
    └──► StatisticalAlertEvaluator.evaluate()
            └──► Trigger alerts/Vega speech
```

---

*Let's build the future of radiological monitoring.* 🚀
