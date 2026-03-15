package com.radiacode.ble

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * In-App Help Center & FAQ
 * Searchable help database with topics and troubleshooting
 */
class HelpCenterActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var searchInput: EditText
    private lateinit var categoriesList: RecyclerView
    private lateinit var topicsList: RecyclerView
    private lateinit var topicDetailContainer: LinearLayout
    private lateinit var topicTitle: TextView
    private lateinit var topicContent: TextView
    private lateinit var backToListButton: View

    private val allTopics = mutableListOf<HelpTopic>()
    private var currentCategory: HelpCategory? = null

    data class HelpCategory(
        val id: String,
        val name: String,
        val emoji: String,
        val description: String
    )

    data class HelpTopic(
        val id: String,
        val categoryId: String,
        val title: String,
        val content: String,
        val keywords: List<String> = emptyList()
    )

    private val categories = listOf(
        HelpCategory("getting_started", "Getting Started", "1", "Setup and first steps"),
        HelpCategory("understanding", "Understanding Readings", "2", "What the numbers mean"),
        HelpCategory("features", "Features", "3", "How to use app features"),
        HelpCategory("troubleshooting", "Troubleshooting", "4", "Fixing common problems"),
        HelpCategory("safety", "Safety Info", "5", "Radiation safety guidance"),
        HelpCategory("glossary", "Glossary", "6", "Terms and definitions")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_help_center)

            toolbar = findViewById(R.id.toolbar)
            searchInput = findViewById(R.id.searchInput)
            categoriesList = findViewById(R.id.categoriesList)
            topicsList = findViewById(R.id.topicsList)
            topicDetailContainer = findViewById(R.id.topicDetailContainer)
            topicTitle = findViewById(R.id.topicTitle)
            topicContent = findViewById(R.id.topicContent)
            backToListButton = findViewById(R.id.backToListButton)

            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.title = "Help Center"
            toolbar.setNavigationOnClickListener { handleBack() }

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            })

            loadTopics()
            setupCategories()
            setupSearch()

            backToListButton.setOnClickListener {
                showCategories()
            }

            showCategories()
        } catch (e: Exception) {
            Log.e("RadiaCode", "HelpCenterActivity.onCreate crashed", e)
            finish()
        }
    }

    private fun loadTopics() {
        allTopics.clear()
        
        // Getting Started
        allTopics.add(HelpTopic(
            "first_connection", "getting_started",
            "Connecting to Your RadiaCode",
            """
To connect your RadiaCode device:

1. **Turn on your RadiaCode** - Press the power button until the screen lights up

2. **Open the app** - Launch Open RadiaCode on your phone

3. **Go to Device settings** - Tap the hamburger menu (☰) → Device

4. **Find your device** - Tap "Find Devices" and wait for scanning

5. **Select your RadiaCode** - Tap the device name (usually "RC-10X-xxxxxx")

6. **Wait for connection** - The status will show "Connected" with a green dot

**Tips:**
• Make sure Bluetooth is enabled on your phone
• Keep the RadiaCode close to your phone during initial pairing
• The official RadiaCode app must be disconnected first
• Don't pair in Android Bluetooth settings - use the app instead
            """.trimIndent(),
            listOf("connect", "pair", "bluetooth", "setup", "first time", "device")
        ))

        allTopics.add(HelpTopic(
            "adding_widget", "getting_started",
            "Adding Home Screen Widgets",
            """
To add a widget to your home screen:

1. **Long-press** on an empty area of your home screen
2. Tap **Widgets** from the menu
3. Find **Open RadiaCode** in the widget list
4. Choose a widget style:
   - **Simple**: Compact with just values
   - **Charts**: Includes sparkline history
   - **Map**: Shows radiation map (NEW)
5. **Drag** the widget to your home screen
6. **Configure** the widget (colors, fields, device binding)
7. Tap **Save** to finish

The widget will update automatically while the service runs.

**Pro tip:** You can add multiple widgets bound to different devices!
            """.trimIndent(),
            listOf("widget", "home screen", "add", "configure")
        ))

        // Understanding Readings
        allTopics.add(HelpTopic(
            "dose_rate_explained", "understanding",
            "What is Dose Rate?",
            """
**Dose rate** measures the amount of ionizing radiation energy absorbed per unit time.

**Units:**
• **μSv/h** (microsieverts per hour) - most common
• **nSv/h** (nanosieverts per hour) - for very low readings
• 1 μSv/h = 1000 nSv/h

**Typical values:**
• 0.05-0.15 μSv/h: Normal indoor background
• 0.1-0.3 μSv/h: Normal outdoor background
• 0.3-1.0 μSv/h: Elevated (granite, altitude)
• 1-5 μSv/h: Airplane cruising altitude
• >10 μSv/h: Medical/industrial sources

**Why it fluctuates:**
Radiation is a random process. Your readings will vary even in the same location. This is normal! The app shows averages and trends to help you see the true level.

**Annual dose context:**
At 0.1 μSv/h, your annual dose would be ~0.88 mSv - well below the 1 mSv/year public limit.
            """.trimIndent(),
            listOf("dose", "rate", "μSv", "usv", "microsievert", "radiation", "reading")
        ))

        allTopics.add(HelpTopic(
            "z_score_explained", "understanding",
            "What is Z-Score?",
            """
**Z-score** tells you how unusual a reading is compared to the recent average.

**Formula:** Z = (value - mean) / standard deviation

**Interpretation:**
• **Z = 0**: Exactly average
• **Z = ±1**: Within normal variation (68% of readings)
• **Z = ±2**: Somewhat unusual (95% of readings fall within)
• **Z > ±3**: Statistically significant (only 0.3% chance)

**In the app:**
• **Green** sparkline segments: Near average (|Z| < 1)
• **Yellow** segments: Elevated (1 < |Z| < 2)
• **Red** segments: Anomalous (|Z| > 2)

**Why it matters:**
Z-scores help distinguish real changes from normal statistical noise. A reading of 0.08 μSv/h might seem "high" compared to 0.06, but if σ=0.02, Z=1 means it's perfectly normal variation.
            """.trimIndent(),
            listOf("z-score", "zscore", "statistics", "sigma", "standard deviation", "anomaly")
        ))

        // Features
        allTopics.add(HelpTopic(
            "simple_vs_expert", "features",
            "Simple Mode vs Expert Mode",
            """
The app offers two display modes:

**🟢 Simple Mode**
Best for: Quick glances, non-technical users
Shows:
• Large dose rate number
• Safety status (Safe/Elevated/High)
• Basic context ("Like normal background")
• Connection status

**🔬 Expert Mode**
Best for: Data analysis, surveys, research
Shows:
• All metrics with statistics
• Z-scores and trend indicators
• Full charts with zoom/pan
• Intelligence report
• Isotope identification
• Data science panels

**To switch modes:**
Settings → Display → Dashboard Mode

You can switch anytime - your data is preserved.
            """.trimIndent(),
            listOf("mode", "simple", "expert", "beginner", "advanced")
        ))

        allTopics.add(HelpTopic(
            "session_recording", "features",
            "Recording Sessions",
            """
Sessions let you capture and name periods of data collection.

**Starting a session:**
1. Tap the quick action menu (⊕ button)
2. Select "Start Recording"
3. Enter a name (optional)
4. The session starts immediately

**While recording:**
• All readings are saved with timestamps
• GPS location is captured (if enabled)
• A red "RECORDING" badge appears

**Stopping a session:**
1. Tap the quick action menu
2. Select "Stop Recording"
3. Session is saved with statistics

**Managing sessions:**
Menu → Sessions to:
• View session details
• Compare two sessions
• Export to CSV
• Add notes
• Delete old sessions

**Use cases:**
• Surveys: "Kitchen survey 2024-01-15"
• Locations: "Basement vs Upstairs"
• Experiments: "Before/After remediation"
            """.trimIndent(),
            listOf("session", "record", "recording", "save", "export", "compare")
        ))

        // Troubleshooting
        allTopics.add(HelpTopic(
            "device_not_found", "troubleshooting",
            "Device Not Found During Scan",
            """
If your RadiaCode doesn't appear in the scan:

**Check the basics:**
✓ RadiaCode is powered ON (screen showing)
✓ Phone Bluetooth is enabled
✓ Location services enabled (required for BLE on Android)
✓ App has Bluetooth & Location permissions

**Common issues:**

**1. Already connected elsewhere**
The RadiaCode can only connect to one app. Make sure:
• Official RadiaCode app is closed/disconnected
• No other phone is connected to this device

**2. Paired in Android settings**
The device should NOT be paired in your phone's Bluetooth settings. If it is:
• Go to Android Settings → Bluetooth
• Find "RC-10X-xxxxx" and tap "Forget"
• Try scanning again in the app

**3. Device in wrong mode**
Make sure RadiaCode isn't in USB mode or firmware update mode.

**4. Battery low**
A very low battery can affect Bluetooth. Charge your RadiaCode.

**Still not working?**
• Restart both phone and RadiaCode
• Reinstall the app
• Try from a different phone to isolate the issue
            """.trimIndent(),
            listOf("not found", "scan", "find", "connect", "bluetooth", "problem")
        ))

        allTopics.add(HelpTopic(
            "connection_drops", "troubleshooting",
            "Connection Keeps Dropping",
            """
Frequent disconnections can happen due to several factors:

**Distance & Interference**
• Keep phone within 5 meters of RadiaCode
• Avoid obstacles between devices
• Move away from WiFi routers, microwaves
• Metal objects can block Bluetooth

**Phone Battery Optimization**
Android may kill the app to save battery:
1. Go to Settings → Apps → Open RadiaCode
2. Battery → Don't optimize (or Unrestricted)
3. This keeps the service running

**RadiaCode Battery**
Low battery can cause disconnects:
• Check battery level in the app
• Charge when below 20%

**Android Version Issues**
Some Android versions have Bluetooth bugs:
• Update to latest Android version
• Update the app to latest version

**Auto-Reconnect**
The app will automatically try to reconnect. If it doesn't:
• Open the app manually
• Or reboot your phone

**Nuclear Option**
If nothing works:
1. Forget device in Android Bluetooth settings
2. Force close the app
3. Restart phone
4. Re-pair through the app
            """.trimIndent(),
            listOf("disconnect", "drop", "connection", "lost", "unstable")
        ))

        // Safety
        allTopics.add(HelpTopic(
            "safety_levels", "safety",
            "Radiation Safety Levels",
            """
**Understanding radiation dose limits:**

**Public Limits (NRC/ICRP):**
• 1 mSv/year (1000 μSv) above natural background
• That's ~0.11 μSv/h continuous exposure

**Occupational Limits:**
• 50 mSv/year for radiation workers
• With training and monitoring

**Context for readings:**

🟢 **< 0.5 μSv/h - SAFE**
Normal background range. No concerns.

🟡 **0.5-2 μSv/h - ELEVATED**
Above typical but safe for extended periods. Common near granite, at altitude.

🟠 **2-10 μSv/h - HIGH**
Limit time in area. Find and identify the source.

🔴 **> 10 μSv/h - VERY HIGH**
Minimize exposure. Leave area unless you understand the source.

**Perspective:**
• Chest X-ray: 20 μSv (one-time)
• Flight NYC→LA: 40 μSv
• Average American: 3100 μSv/year (medical + natural)

**The app is a tool, not medical advice.** For professional radiation safety guidance, consult a health physicist.
            """.trimIndent(),
            listOf("safe", "safety", "limit", "danger", "high", "level")
        ))

        // Glossary
        allTopics.add(HelpTopic(
            "glossary_terms", "glossary",
            "Glossary of Terms",
            """
**Becquerel (Bq)**: Unit of radioactivity. 1 Bq = 1 decay per second.

**CPS**: Counts per second. Raw detection rate.

**CPM**: Counts per minute. CPS × 60.

**Dose**: Energy absorbed from radiation.

**Dose Rate**: Dose per unit time (μSv/h).

**Gamma Ray**: High-energy photon from nuclear decay.

**Half-life**: Time for half of radioactive atoms to decay.

**Isotope**: Variant of an element with specific neutrons.

**keV**: Kiloelectronvolts. Energy unit for gamma rays.

**μSv**: Microsievert. 1/1,000,000 of a Sievert.

**ROI**: Region of Interest. Energy window for isotope detection.

**Scintillator**: Crystal that flashes when hit by radiation.

**Sievert (Sv)**: Unit of biological radiation dose.

**Spectrum**: Distribution of gamma ray energies.

**Standard Deviation (σ)**: Measure of data spread.

**Z-Score**: How many σ a value is from the mean.
            """.trimIndent(),
            listOf("glossary", "term", "definition", "what is", "meaning")
        ))
    }

    private fun setupCategories() {
        categoriesList.layoutManager = LinearLayoutManager(this)
        categoriesList.adapter = CategoryAdapter(categories) { category ->
            showCategory(category)
        }
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) {
                    searchTopics(query)
                } else if (query.isEmpty()) {
                    showCategories()
                }
            }
        })
    }

    private fun showCategories() {
        currentCategory = null
        categoriesList.visibility = View.VISIBLE
        topicsList.visibility = View.GONE
        topicDetailContainer.visibility = View.GONE
        backToListButton.visibility = View.GONE
        supportActionBar?.title = "Help Center"
    }

    private fun showCategory(category: HelpCategory) {
        currentCategory = category
        val topics = allTopics.filter { it.categoryId == category.id }
        
        categoriesList.visibility = View.GONE
        topicsList.visibility = View.VISIBLE
        topicDetailContainer.visibility = View.GONE
        backToListButton.visibility = View.VISIBLE
        supportActionBar?.title = category.name

        topicsList.layoutManager = LinearLayoutManager(this)
        topicsList.adapter = TopicAdapter(topics) { topic ->
            showTopic(topic)
        }
    }

    private fun searchTopics(query: String) {
        val lowerQuery = query.lowercase()
        val results = allTopics.filter { topic ->
            topic.title.lowercase().contains(lowerQuery) ||
            topic.content.lowercase().contains(lowerQuery) ||
            topic.keywords.any { it.contains(lowerQuery) }
        }

        categoriesList.visibility = View.GONE
        topicsList.visibility = View.VISIBLE
        topicDetailContainer.visibility = View.GONE
        backToListButton.visibility = View.VISIBLE
        supportActionBar?.title = "Search: $query"

        topicsList.adapter = TopicAdapter(results) { topic ->
            showTopic(topic)
        }
    }

    private fun showTopic(topic: HelpTopic) {
        categoriesList.visibility = View.GONE
        topicsList.visibility = View.GONE
        topicDetailContainer.visibility = View.VISIBLE
        backToListButton.visibility = View.VISIBLE
        supportActionBar?.title = topic.title

        topicTitle.text = topic.title
        topicContent.text = topic.content
    }

    private fun handleBack() {
        when {
            topicDetailContainer.visibility == View.VISIBLE -> {
                if (currentCategory != null) {
                    showCategory(currentCategory!!)
                } else {
                    showCategories()
                }
            }
            topicsList.visibility == View.VISIBLE -> showCategories()
            else -> finish()
        }
    }

    // Adapters
    inner class CategoryAdapter(
        private val items: List<HelpCategory>,
        private val onClick: (HelpCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val emoji: TextView = view.findViewById(R.id.categoryEmoji)
            val name: TextView = view.findViewById(R.id.categoryName)
            val desc: TextView = view.findViewById(R.id.categoryDesc)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_help_category, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.emoji.text = item.emoji
            holder.name.text = item.name
            holder.desc.text = item.description
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    inner class TopicAdapter(
        private val items: List<HelpTopic>,
        private val onClick: (HelpTopic) -> Unit
    ) : RecyclerView.Adapter<TopicAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.topicTitle)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_help_topic, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
