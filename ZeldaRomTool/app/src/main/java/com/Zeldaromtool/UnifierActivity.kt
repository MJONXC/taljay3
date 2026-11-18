package com.zeldaromtool

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** "Merge base game + update" screen. The work runs in UnifyService (background). */
class UnifierActivity : AppCompatActivity() {

    private lateinit var ui: Ui

    private lateinit var baseCard: View
    private lateinit var baseStatus: TextView
    private lateinit var updateCard: View
    private lateinit var updateStatus: TextView
    private lateinit var destinationStatus: TextView
    private lateinit var unifyButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var baseUri: Uri? = null
    private var baseEntries: List<RomExtractor.Entry>? = null
    private var baseName = ""
    private var updateUri: Uri? = null
    private var destinationTreeUri: Uri? = null
    private var waitingForDestination = false
    private var working = false
    private var cancelDialog: Dialog? = null

    private val pickBase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadBase(uri)
    }

    private val pickUpdate = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            keepAccess(uri)
            updateUri = uri
            updateStatus.text = "${displayName(uri)}\nUpdate selected"
            updateButton()
        }
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
                    startUnify()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        destinationTreeUri = getSharedPreferences("zeldaromtool", MODE_PRIVATE)
            .getString("destination_tree_uri", null)?.let { Uri.parse(it) }
        buildUi()
    }

    private val stateListener: (UnifyState.Snapshot) -> Unit = { s -> runOnUiThread { render(s) } }

    override fun onStart() {
        super.onStart()
        UnifyState.listener = stateListener
        render(UnifyState.snapshot)
    }

    override fun onStop() {
        // Only release the listener if it still belongs to this screen (the home screen may have taken over).
        if (UnifyState.listener === stateListener) UnifyState.listener = null
        cancelDialog?.dismiss()
        super.onStop()
    }

    private fun buildUi() {
        ui = Ui(this, AppSettings.palette(this))
        val pal = ui.pal
        ui.applyBars(this)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Merge base game + update"
            textSize = 20f
            setTextColor(pal.fg)
            gravity = Gravity.CENTER
        })
        root.addView(ui.body("Choose the base game and the update NCA. The app merges them and saves the already-updated ExeFS and RomFS. You can leave the app: merging keeps running in the background."))

        root.addView(ui.sectionTitle("1. Base game"))
        val (bc, bs) = ui.card("🎮", "Base game (XCI / NSP)") { pickBase.launch(arrayOf("*/*")) }
        baseCard = bc
        baseStatus = bs
        root.addView(bc)

        root.addView(ui.sectionTitle("2. Update"))
        val (uc, us) = ui.card("⬆️", "Update (NCA)") { pickUpdate.launch(arrayOf("*/*")) }
        updateCard = uc
        updateStatus = us
        root.addView(uc)

        root.addView(ui.sectionTitle("3. Destination"))
        root.addView(ui.roundButton("Choose destination folder", false) {
            pickDestination.launch(destinationTreeUri)
        })
        destinationStatus = ui.body()
        root.addView(destinationStatus)

        unifyButton = ui.roundButton("Merge and extract", true) { startUnify() }
        root.addView(unifyButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(pal.accent)
            progressBackgroundTintList = ColorStateList.valueOf(pal.cardStroke)
        }
        root.addView(progressBar)
        progressText = ui.body()
        root.addView(progressText)

        // Tapping Cancel opens the confirmation pop-up; it only cancels if the user confirms.
        cancelButton = ui.roundButton("Cancel", false) {
            if (cancelDialog?.isShowing != true) {
                cancelDialog = CancelConfirmDialog.show(
                    activity = this,
                    progress = { progressBar.progress },
                    onConfirmCancel = {
                        if (working) {
                            UnifyState.cancel = true
                            progressText.text = "Cancelling…"
                        }
                    }
                )
            }
        }
        cancelButton.visibility = View.GONE
        root.addView(cancelButton)

        root.addView(ui.roundButton("Back", false) { finish() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            addView(root)
        })

        baseStatus.text = "Tap to choose the XCI or NSP"
        updateStatus.text = "Tap to choose the update NCA"
        updateDestinationStatus()
        ui.setOn(baseCard, KeysManager.hasProd(this))
        ui.setOn(updateCard, KeysManager.hasProd(this))
        if (!KeysManager.hasProd(this)) baseStatus.text = "Import prod.keys on the home screen first."
        updateButton()
    }

    private fun updateButton() {
        ui.setOn(unifyButton, baseEntries != null && updateUri != null && !working)
    }

    /** Keeps access to the chosen file even with the screen closed. */
    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun loadBase(uri: Uri) {
        keepAccess(uri)
        baseUri = null
        baseEntries = null
        updateButton()
        baseStatus.text = "Reading the base game…"
        Thread {
            try {
                val list = RomExtractor.list(this, uri)
                val name = displayName(uri)
                runOnUiThread {
                    baseUri = uri
                    baseEntries = list
                    baseName = name
                    baseStatus.text = "$name\n${list.size} files • ${fmt(list.sumOf { it.size })}"
                    updateButton()
                }
            } catch (e: Exception) {
                CrashLogger.logError(this, "Read the base game", e)
                runOnUiThread { baseStatus.text = "Error reading the base game: ${e.message}" }
            }
        }.start()
    }

    private fun startUnify() {
        if (baseUri == null || baseEntries == null || updateUri == null) return
        if (working) return
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

    private fun launchService() {
        val base = baseUri ?: return
        val update = updateUri ?: return
        val tree = destinationTreeUri ?: return
        if (working) return

        val folder = (baseName.substringBeforeLast('.') + " (updated)")
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .ifBlank { "updated_game" }

        render(UnifyState.Snapshot(true, 0, 0, "Preparing…", null))
        try {
            UnifyService.start(this, UnifyService.MODE_UNIFY, base, update, tree, folder)
        } catch (e: Exception) {
            CrashLogger.logError(this, "Start the service", e)
            render(UnifyState.Snapshot(false, 0, 0, "", "Could not start in the background: ${e.message}"))
            return
        }
        askBatteryOptimizationOnce()
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
            working = true
            ui.setOn(baseCard, false)
            ui.setOn(updateCard, false)
            cancelButton.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            updateButton()
            showProgress(s.done, s.total, s.file)
        } else if (s.message != null) {
            UnifyState.clearMessage()
            endWork(s.message)
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

    private fun endWork(msg: String) {
        cancelDialog?.dismiss()
        working = false
        cancelButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressText.text = msg
        ui.setOn(baseCard, KeysManager.hasProd(this))
        ui.setOn(updateCard, KeysManager.hasProd(this))
        updateButton()
        toast(msg.lines().first())
    }

    private fun updateDestinationStatus() {
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

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
