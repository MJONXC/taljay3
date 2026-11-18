package com.zeldaromtool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var ui: Ui
    private lateinit var appliedTheme: ThemeMode

    private lateinit var prodStatus: TextView
    private lateinit var titleStatus: TextView
    private lateinit var romStatus: TextView
    private lateinit var romCard: View
    private lateinit var extractButton: Button
    private lateinit var destinationStatus: TextView
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var romUri: Uri? = null
    private var romEntries: List<RomExtractor.Entry>? = null
    private var romName = ""
    private var destinationTreeUri: Uri? = null
    private var waitingForDestination = false
    private var extracting = false

    private val pickProd = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast(KeysManager.importProd(this, uri).message)
            refresh()
        }
    }

    private val pickTitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toast(KeysManager.importTitle(this, uri).message)
            refresh()
        }
    }

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadRom(uri)
    }

    private val pickDestination = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                destinationTreeUri = uri
                getSharedPreferences("zeldaromtool", MODE_PRIVATE).edit()
                    .putString("destination_tree_uri", uri.toString()).apply()
                updateDestinationStatus()
                toast("Destination folder selected.")
                if (waitingForDestination) {
                    waitingForDestination = false
                    startExtraction()
                }
            } catch (e: Exception) {
                waitingForDestination = false
                toast("Could not authorize this folder: ${e.message}")
            }
        } else {
            waitingForDestination = false
        }
    }

    // Notification permission (Android 13+): progress is shown in the notification bar.
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchService()
    }

    // Receives progress from the background service while the screen is open.
    private val stateListener: (UnifyState.Snapshot) -> Unit = { s -> runOnUiThread { render(s) } }

    private fun setOn(v: View, on: Boolean) = ui.setOn(v, on)

    // ---------- screen ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        CrashLogger.install(this)
        val savedTree = getSharedPreferences("zeldaromtool", MODE_PRIVATE)
            .getString("destination_tree_uri", null)
        destinationTreeUri = savedTree?.let { Uri.parse(it) }
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        UnifyState.listener = stateListener
        render(UnifyState.snapshot)
    }

    override fun onStop() {
        // Only release the listener if it still belongs to this screen (another screen may have taken over).
        if (UnifyState.listener === stateListener) UnifyState.listener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Came back from Settings with a different theme: rebuild the screen without losing state.
        if (AppSettings.themeMode(this) != appliedTheme) buildUi()
    }

    /** Builds (or rebuilds) the whole screen with the current theme colors. */
    private fun buildUi() {
        val oldRom = if (::romStatus.isInitialized) romStatus.text.toString() else ""
        val oldProgressText = if (::progressText.isInitialized) progressText.text.toString() else ""
        val oldProgress = if (::progressBar.isInitialized) progressBar.progress else 0

        appliedTheme = AppSettings.themeMode(this)
        ui = Ui(this, AppSettings.palette(this))
        val pal = ui.pal
        ui.applyBars(this)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(24))
        }

        // Top bar: gear on the left, three bars on the right.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.iconButton(R.drawable.ic_settings, "Settings") {
                this@MainActivity.startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Zelda ROM Tool"; textSize = 20f; setTextColor(pal.fg); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ui.iconButton(R.drawable.ic_menu, "Menu") { anchor -> showMenu(anchor) })
        }
        root.addView(topBar)
        root.addView(ui.body("Import your keys first. They stay on this phone only."))

        root.addView(ui.sectionTitle("1. Keys"))
        val (prodCard, ps) = ui.card("🔑", "prod.keys") { pickProd.launch(arrayOf("*/*")) }
        prodStatus = ps
        root.addView(prodCard)
        val (titleCard, ts) = ui.card("🗝️", "title.keys") { pickTitle.launch(arrayOf("*/*")) }
        titleStatus = ts
        root.addView(titleCard)
        root.addView(ui.roundButton("Delete keys", false) {
            KeysManager.clear(this); refresh(); toast("Keys deleted.")
        })

        root.addView(ui.sectionTitle("2. Base game or DLC"))
        val (rc, rs) = ui.card("🎮", "Game or DLC (XCI / NSP / NCA)") { pickRom.launch(arrayOf("*/*")) }
        romCard = rc
        romStatus = rs
        root.addView(rc)

        root.addView(ui.sectionTitle("3. Extraction"))
        root.addView(ui.body("Choose where to save the extracted files. The app will remember the last folder you selected."))
        val destinationButton = ui.roundButton("Choose destination folder", false) {
            pickDestination.launch(destinationTreeUri)
        }
        root.addView(destinationButton)
        destinationStatus = ui.body()
        root.addView(destinationStatus)
        extractButton = ui.roundButton("Extract ExeFS and RomFS", true) { startExtraction() }
        setOn(extractButton, false)
        root.addView(extractButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(pal.accent)
            progressBackgroundTintList = ColorStateList.valueOf(pal.cardStroke)
        }
        root.addView(progressBar)
        progressText = ui.body()
        root.addView(progressText)

        cancelButton = ui.roundButton("Cancel extraction", false) {
            UnifyState.cancel = true
            progressText.text = "Cancelling…"
        }
        cancelButton.visibility = View.GONE
        root.addView(cancelButton)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            addView(root)
        })

        // Restore what was already on screen before rebuilding the layout.
        refresh()
        updateDestinationStatus()
        if (romEntries != null && oldRom.isNotBlank()) romStatus.text = oldRom
        progressText.text = oldProgressText
        if (extracting) {
            progressBar.progress = oldProgress
            progressBar.visibility = View.VISIBLE
            cancelButton.visibility = View.VISIBLE
            setOn(extractButton, false)
            setOn(romCard, false)
        } else {
            setOn(extractButton, romEntries != null)
        }
    }

    // ---------- three-bar menu ----------

    /** Menu in the app theme: follows AMOLED / dark / light, with rounded corners. */
    private fun showMenu(anchor: View) {
        val pal = ui.pal

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
            background = GradientDrawable().apply {
                setColor(pal.bg)
                cornerRadius = ui.dp(16).toFloat()
                setStroke(ui.dp(1), pal.cardStroke)
            }
        }

        val popup = PopupWindow(
            box,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            // Transparent window background: without it the rounded corners show up square.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = ui.dp(8).toFloat()
        }

        fun addItem(text: String, onClick: () -> Unit) {
            val rippleColor = (pal.accent and 0x00FFFFFF) or 0x33000000
            val mask = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = ui.dp(12).toFloat()
            }
            box.addView(TextView(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(pal.fg)
                minWidth = ui.dp(240)
                setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
                background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask)
                isClickable = true
                setOnClickListener {
                    popup.dismiss()
                    onClick()
                }
            })
        }

        addItem("🔀  Merge base game + update") {
            startActivity(Intent(this, UnifierActivity::class.java))
        }

        popup.showAsDropDown(anchor, 0, ui.dp(4), Gravity.END)
    }

    override fun onDestroy() {
        // Extraction runs in the (background) service: closing the screen cancels nothing.
        if (isFinishing) CrashLogger.uninstall()
        super.onDestroy()
    }

    private fun refresh() {
        val p = KeysManager.prodCount(this)
        val t = KeysManager.titleCount(this)
        prodStatus.text = if (p > 0) "✔ loaded ($p keys)" else "✘ not loaded (required)"
        titleStatus.text = if (t > 0) "✔ loaded ($t title keys)" else "• not loaded (optional)"
        val ready = KeysManager.hasProd(this)
        setOn(romCard, ready)
        if (!ready) romStatus.text = "Import prod.keys to unlock this step."
        else if (romStatus.text.isEmpty() || romStatus.text.startsWith("Import")) {
            romStatus.text = "Tap to choose the XCI, NSP or NCA"
        }
    }

    /** Reads the ROM structure (fast, headers only) and enables the extract button. */
    private fun loadRom(uri: Uri) {
        keepAccess(uri)
        romUri = null
        romEntries = null
        setOn(extractButton, false)
        progressText.text = ""
        romStatus.text = "Reading the ROM…"
        Thread {
            try {
                val list = RomExtractor.list(this, uri)
                val name = displayName(uri)
                val format = detectFormat(uri)
                val kind = GameExtractor.kindOf(this, uri, list)
                val total = list.sumOf { it.size }
                runOnUiThread {
                    romUri = uri
                    romEntries = list
                    romName = name
                    romStatus.text = "$name\n$format${if (kind != null) " • $kind" else ""} • ${list.size} files • ${fmt(total)}"
                    setOn(extractButton, !extracting)
                }
            } catch (e: Exception) {
                CrashLogger.logError(this, "Read the ROM", e)
                runOnUiThread { romStatus.text = "Error reading the ROM: ${e.message}" }
            }
        }.start()
    }

    private fun startExtraction() {
        if (romUri == null || romEntries == null) return
        if (extracting) return
        if (destinationTreeUri == null) {
            waitingForDestination = true
            pickDestination.launch(null)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchService()
    }

    /** Starts extraction in the foreground service: keeps going with the screen off or the app closed. */
    private fun launchService() {
        val rom = romUri ?: return
        val tree = destinationTreeUri ?: return
        if (extracting) return

        val folder = romName.substringBeforeLast('.')
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .ifBlank { "extracted_game" }

        render(UnifyState.Snapshot(true, 0, 0, "Preparing…", null))
        try {
            UnifyService.start(this, UnifyService.MODE_EXTRACT, rom, null, tree, folder)
        } catch (e: Exception) {
            CrashLogger.logError(this, "Start the service", e)
            render(UnifyState.Snapshot(false, 0, 0, "", "Could not start in the background: ${e.message}"))
            return
        }
        askBatteryOptimizationOnce()
    }

    /** Keeps access to the chosen file even with the screen closed. */
    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    /** Asks once for Android not to kill the app during long extractions. */
    private fun askBatteryOptimizationOnce() {
        val prefs = getSharedPreferences("zeldaromtool", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
        }
    }

    /** Updates the screen according to the service state. */
    private fun render(s: UnifyState.Snapshot) {
        if (s.running) {
            extracting = true
            setOn(extractButton, false)
            setOn(romCard, false)
            cancelButton.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            showProgress(s.done, s.total, s.file)
        } else if (s.message != null) {
            UnifyState.clearMessage()
            finishExtraction(s.message)
        }
    }

    private fun showProgress(done: Long, total: Long, file: String) {
        if (total <= 0) {
            progressBar.progress = 0
            progressText.text = file
            return
        }
        val frac = done.toDouble() / total
        progressBar.progress = (frac * 1000).toInt()
        progressText.text = String.format("%.1f%%\n%s\n%s / %s", frac * 100, file, fmt(done), fmt(total))
    }

    private fun finishExtraction(msg: String) {
        extracting = false
        cancelButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressText.text = msg
        setOn(romCard, KeysManager.hasProd(this))
        setOn(extractButton, romEntries != null)
        toast(msg.lines().first())
    }

    private fun updateDestinationStatus() {
        if (!::destinationStatus.isInitialized) return
        destinationStatus.text = if (destinationTreeUri == null) {
            "No folder selected."
        } else {
            "Destination selected: folder authorized by Android."
        }
    }

    private fun fmt(b: Long): String = when {
        b >= (1L shl 30) -> String.format("%.2f GB", b / (1L shl 30).toDouble())
        b >= (1L shl 20) -> String.format("%.1f MB", b / (1L shl 20).toDouble())
        else -> "$b B"
    }

    private fun detectFormat(uri: Uri): String = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val ch = FileInputStream(pfd.fileDescriptor).channel
            val a = ByteBuffer.allocate(4)
            ch.read(a, 0)
            val b = ByteBuffer.allocate(4)
            ch.read(b, 0x100)
            when {
                String(a.array(), Charsets.US_ASCII) == "PFS0" -> "NSP"
                String(b.array(), Charsets.US_ASCII) == "HEAD" -> "XCI"
                else -> "NCA"
            }
        } ?: "could not open"
    } catch (e: Exception) {
        "read error: ${e.message}"
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
