package app.mapscoordinator

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity(), LocationListener {

    companion object {
        const val REQ_PERM = 1
        const val REQ_PICK = 2
        const val RELEASES_URL = "https://github.com/Blackout-78/Maps-Coordinator/releases"
        val NAVY = Color.parseColor("#0E1424")
        val CARD = Color.parseColor("#141B30")
        val BORDER = Color.parseColor("#2A3558")
        val TEXT = Color.parseColor("#EAF0FF")
        val SUB = Color.parseColor("#C5D0EA")
        val DIM = Color.parseColor("#9AA6C4")
        val ACCENT = Color.parseColor("#4DA3FF")
        val AMBER = Color.parseColor("#F2B632")
    }

    private lateinit var map: MapView
    private lateinit var root: FrameLayout
    private lateinit var gridText: TextView
    private lateinit var infoText: TextView
    private lateinit var emptyText: TextView
    private lateinit var settingsView: View
    private lateinit var settingsStats: TextView
    private lateinit var progressView: TextView
    private lateinit var mapScreen: FrameLayout
    private var lastLocation: Location? = null
    private var importing = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    @Suppress("DEPRECATION")
    private fun versionCode(): Long = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (e: Exception) { 0L }

    private fun stamp(): String = "v${versionName()} · build ${versionCode()} · Night"

    private fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        setStroke(dp(1), BORDER)
        cornerRadius = dp(16).toFloat()
    }

    private fun pill(bg: Int, stroke: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(bg)
        cornerRadius = dp(22).toFloat()
        if (stroke) setStroke(dp(1), Color.parseColor("#3A4772"))
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(if (primary) NAVY else TEXT)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        background = pill(if (primary) ACCENT else Color.TRANSPARENT, !primary)
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NAVY
        window.navigationBarColor = NAVY

        root = FrameLayout(this)
        root.setBackgroundColor(NAVY)

        mapScreen = FrameLayout(this)
        map = MapView(this)
        map.onUserPan = { updateButtons() }
        mapScreen.addView(map, FrameLayout.LayoutParams(-1, -1))

        // top info card
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        top.addView(label("MGRS · MY POSITION · WGS 84", 11f, DIM, true))
        gridText = label("Waiting for GPS…", 22f, TEXT, true).apply { typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD) }
        top.addView(gridText)
        infoText = label("Go outside for the first GPS lock.", 12f, SUB)
        top.addView(infoText)
        val topLp = FrameLayout.LayoutParams(-1, -2)
        topLp.setMargins(dp(12), dp(12), dp(12), 0)
        mapScreen.addView(top, topLp)

        // empty-state hint
        emptyText = label("No map yet.\nTap Import map and pick a .osm.pbf file from your phone.", 15f, SUB).apply {
            gravity = Gravity.CENTER
        }
        val emptyLp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        mapScreen.addView(emptyText, emptyLp)

        // zoom buttons
        val zoom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zoom.addView(roundButton("+") { map.zoomBy(1.6) })
        zoom.addView(roundButton("−") { map.zoomBy(1 / 1.6) })
        val zoomLp = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)
        zoomLp.setMargins(0, 0, dp(12), 0)
        mapScreen.addView(zoom, zoomLp)

        // bottom bar
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = card()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val recenter = button("Recenter", true) { recenter() }
        val copy = button("Copy grid", false) { copyGrid() }
        val settings = button("Settings", false) { showSettings(true) }
        val bl = LinearLayout.LayoutParams(0, -2, 1f)
        bl.setMargins(dp(3), 0, dp(3), 0)
        bottom.addView(recenter, bl)
        bottom.addView(copy, bl)
        bottom.addView(settings, bl)
        val bottomLp = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        bottomLp.setMargins(dp(12), 0, dp(12), dp(26))
        mapScreen.addView(bottom, bottomLp)

        mapScreen.addView(stampView(), stampLp())
        root.addView(mapScreen, FrameLayout.LayoutParams(-1, -1))

        buildSettings()
        root.addView(settingsView, FrameLayout.LayoutParams(-1, -1))
        settingsView.visibility = View.GONE

        // import progress overlay
        progressView = label("", 16f, TEXT, true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E60E1424"))
            visibility = View.GONE
            setOnClickListener { }
        }
        root.addView(progressView, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        refreshMapState()
        requestLocation()
    }

    private fun roundButton(t: String, onClick: () -> Unit): Button = Button(this).apply {
        text = t
        setTextColor(TEXT)
        textSize = 20f
        background = GradientDrawable().apply { setColor(CARD); setStroke(dp(1), BORDER); cornerRadius = dp(22).toFloat() }
        val lp = LinearLayout.LayoutParams(dp(44), dp(44))
        lp.setMargins(0, dp(5), 0, dp(5))
        layoutParams = lp
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    private fun stampView(): TextView = label(stamp(), 10f, DIM)
    private fun stampLp(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(dp(12), 0, 0, dp(7))
        return lp
    }

    private fun buildSettings() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(40))
        }
        col.addView(label("Settings", 26f, TEXT, true))
        col.addView(label("Maps, version and updates", 13f, SUB))

        fun section(t: String) = col.addView(label(t, 11f, DIM, true).apply { setPadding(0, dp(18), 0, dp(6)) })

        section("MAPS")
        val mapsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        settingsStats = label("", 14f, TEXT)
        mapsCard.addView(settingsStats)
        mapsCard.addView(label("Maps come from a file you choose. The app has no internet permission.", 12f, SUB).apply { setPadding(0, dp(4), 0, dp(8)) })
        mapsCard.addView(button("Import map file", true) { pickFile() })
        mapsCard.addView(button("Delete all maps", false) {
            MapStore.clear(this)
            map.clearCache()
            refreshMapState()
            Toast.makeText(this, "Maps deleted", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(mapsCard)

        section("VERSION")
        val vCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        vCard.addView(label("v${versionName()} · build ${versionCode()} · Night", 14f, TEXT, true))
        vCard.addView(button("Open GitHub Releases", true) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
            } catch (e: Exception) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        vCard.addView(label("Updates install only through GitHub. The app itself stays offline.", 12f, SUB).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(vCard)

        section("CREDITS")
        val cCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        cCard.addView(label("Map data © OpenStreetMap contributors (ODbL).", 13f, TEXT))
        col.addView(cCard)

        col.addView(button("Back to map", false) { showSettings(false) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        col.addView(label(stamp(), 10f, DIM).apply { setPadding(0, dp(14), 0, 0) })

        val sv = ScrollView(this).apply {
            setBackgroundColor(NAVY)
            addView(col, ViewGroup.LayoutParams(-1, -2))
        }
        settingsView = sv
    }

    private fun showSettings(show: Boolean) {
        settingsView.visibility = if (show) View.VISIBLE else View.GONE
        if (show) refreshMapState()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (importing) return
        if (settingsView.visibility == View.VISIBLE) showSettings(false) else super.onBackPressed()
    }

    private fun refreshMapState() {
        // Quick check on the main thread; the slow folder scan runs in the background.
        val any = MapStore.hasAny(this)
        map.hasMaps = any
        emptyText.visibility = if (any) View.GONE else View.VISIBLE
        map.invalidate()
        if (!any) {
            settingsStats.text = "No maps installed."
            return
        }
        settingsStats.text = "Counting maps…"
        Thread {
            val cells = MapStore.cellCount(this)
            val mb = MapStore.sizeBytes(this) / (1024 * 1024)
            val regions = MapStore.regions(this)
            runOnUiThread {
                settingsStats.text = "${regions.size} map file(s) imported · $cells cells · $mb MB\n" + regions.joinToString(", ")
            }
        }.start()
    }

    private fun updateButtons() { /* reserved for follow state */ }

    private fun recenter() {
        val l = lastLocation
        if (l != null) {
            map.centerLat = l.latitude
            map.centerLon = l.longitude
            map.follow = true
            map.invalidate()
        } else {
            Toast.makeText(this, "No GPS fix yet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyGrid() {
        val l = lastLocation
        if (l == null) {
            Toast.makeText(this, "No GPS fix yet", Toast.LENGTH_SHORT).show()
            return
        }
        val g = Mgrs.format(l.latitude, l.longitude)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MGRS", g))
        Toast.makeText(this, "Copied $g", Toast.LENGTH_SHORT).show()
    }

    // ---- location (Android's own GPS, no Google services) ----
    private fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_PERM)
        } else startLocation()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startLocation()
            else infoText.text = "Location permission is off. Turn it on in phone settings."
        }
    }

    private var started = false
    private fun startLocation() {
        if (started) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            started = true
        } catch (e: SecurityException) {
            infoText.text = "Location permission is off."
        } catch (e: Exception) {
            infoText.text = "GPS is not available."
        }
    }

    override fun onLocationChanged(l: Location) {
        lastLocation = l
        map.hasFix = true
        map.myLat = l.latitude
        map.myLon = l.longitude
        map.myAccuracy = l.accuracy
        map.myBearing = if (l.hasBearing() && l.hasSpeed() && l.speed > 0.5f) l.bearing else -1f
        if (map.follow) {
            map.centerLat = l.latitude
            map.centerLon = l.longitude
        }
        gridText.text = Mgrs.format(l.latitude, l.longitude)
        val gmf = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis())
        val dec = gmf.declination
        val decText = String.format("%.0f° %s", Math.abs(dec), if (dec >= 0) "E" else "W")
        val mph = l.speed * 2.23694f
        val ft = l.altitude * 3.28084
        val hdg = if (l.hasBearing() && l.speed > 0.5f) String.format("%03.0f°", l.bearing) else "---"
        infoText.text = String.format("Elev %,.0f ft · Heading %s · ±%.0f m · %.0f mph · declination %s", ft, hdg, l.accuracy, mph, decText)
        map.invalidate()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        super.onDestroy()
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        } catch (e: Exception) { }
    }

    // ---- import ----
    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            startImport(uri)
        }
    }

    private fun fileInfo(uri: Uri): Pair<String, Long> {
        var name = "map"
        var size = -1L
        val c: Cursor? = contentResolver.query(uri, null, null, null, null)
        c?.use {
            if (it.moveToFirst()) {
                val ni = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = it.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = it.getString(ni) ?: name
                if (si >= 0 && !it.isNull(si)) size = it.getLong(si)
            }
        }
        return Pair(name, size)
    }

    private fun startImport(uri: Uri) {
        if (importing) return
        importing = true
        val (name, size) = fileInfo(uri)
        showSettings(false)
        progressView.text = "Importing $name…\nThis can take several minutes for a big file.\nKeep the app open."
        progressView.visibility = View.VISIBLE
        thread {
            var message = ""
            var result: PbfImporter.Result? = null
            try {
                val imp = PbfImporter(applicationContext) { msg ->
                    runOnUiThread { progressView.text = "Importing $name\n$msg\nKeep the app open." }
                }
                val stream = contentResolver.openInputStream(uri) ?: throw PbfImporter.ImportException("Could not open the file.")
                result = stream.use { imp.import(it, name, size) }
                message = "Imported ${result.ways} roads and trails"
            } catch (e: PbfImporter.ImportException) {
                message = e.message ?: "Import failed"
            } catch (e: OutOfMemoryError) {
                message = "Ran out of memory. Try a smaller region."
            } catch (e: Exception) {
                message = "Import failed: " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                importing = false
                progressView.visibility = View.GONE
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                map.clearCache()
                refreshMapState()
                val r = result
                if (r != null) {
                    map.centerLat = r.centerLat
                    map.centerLon = r.centerLon
                    map.follow = false
                    map.mpp = 8.0
                    map.invalidate()
                }
            }
        }
    }
}
