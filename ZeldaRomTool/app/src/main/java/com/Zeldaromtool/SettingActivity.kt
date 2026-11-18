
package com.zeldaromtool

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var ui: Ui

    private val updaterExecutor = Executors.newSingleThreadExecutor()

    private var latestRelease: Updater.Release? = null
    private var pendingApk: File? = null

    private lateinit var updateInfoText: TextView
    private lateinit var checkUpdateButton: android.widget.Button
    private lateinit var installUpdateButton: android.widget.Button

    private val pickFolder =
        registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    private val installPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val apk = pendingApk

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.canRequestPackageInstalls() &&
                apk != null
            ) {
                installApk(apk)
            } else {
                toast(
                    "Permission not granted. " +
                        "Allow the installation to continue."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildUi()
    }

    override fun onDestroy() {
        updaterExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        ui = Ui(this, AppSettings.palette(this))
        val pal = ui.pal

        ui.applyBars(this)
        window.setBackgroundDrawable(ColorDrawable(pal.bg))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                ui.dp(20),
                ui.dp(12),
                ui.dp(20),
                ui.dp(24)
            )
        }

        // ---------- Top ----------
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                TextView(this@SettingsActivity).apply {
                    text = "←"
                    textSize = 24f
                    setTextColor(pal.fg)
                    gravity = Gravity.CENTER
                    contentDescription = "Back"
                    isClickable = true
                    setOnClickListener {
                        this@SettingsActivity.finish()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ui.dp(48),
                        ui.dp(48)
                    )
                }
            )

            addView(
                TextView(this@SettingsActivity).apply {
                    text = "Settings"
                    textSize = 22f
                    setTextColor(pal.fg)
                }
            )
        }

        root.addView(top)

        // ---------- Update ----------
        root.addView(ui.sectionTitle("Update"))

        root.addView(
            ui.body(
                "Check whether a newer version of " +
                    "ZeldaRomTool has been published on GitHub. " +
                    "If an update is available, you can download " +
                    "the APK and start the installation."
            )
        )

        val installedVersion = getInstalledVersion()

        root.addView(
            ui.body("Installed version: $installedVersion")
        )

        updateInfoText = ui.body()
        root.addView(updateInfoText)

        checkUpdateButton = ui.roundButton(
            "Check for update",
            true
        ) {
            checkForUpdates()
        }

        root.addView(checkUpdateButton)

        installUpdateButton = ui.roundButton(
            "Download and install update",
            false
        ) {
            downloadAndInstall()
        }

        installUpdateButton.visibility = View.GONE
        root.addView(installUpdateButton)

        refreshUpdaterSection()

        // ---------- Appearance ----------
        root.addView(ui.sectionTitle("Appearance"))

        val current = AppSettings.themeMode(this)

        val options = listOf(
            Triple(
                ThemeMode.AMOLED,
                "🌑",
                "AMOLED (pure black)"
            ),
            Triple(
                ThemeMode.DARK,
                "🌙",
                "Standard dark"
            ),
            Triple(
                ThemeMode.LIGHT,
                "☀️",
                "Light"
            )
        )

        for ((mode, icon, label) in options) {
            val (row, status) = ui.card(icon, label) {
                AppSettings.setThemeMode(this, mode)
                buildUi()
            }

            if (mode == current) {
                status.text = "✔ in use"
                status.setTextColor(pal.accent)
            } else {
                status.text = "Tap to use"
            }

            root.addView(row)
        }

        // ---------- Files and folders ----------
        root.addView(ui.sectionTitle("Files and folders"))

        root.addView(
            ui.body(
                "Choose a folder on your phone (for example, inside Documents). " +
                    "The app creates the folder “${AppFolders.ROOT_NAME}/${AppFolders.LOGS_NAME}” inside it " +
                    "and only accesses the folder you choose."
            )
        )

        val tree = AppSettings.treeUri(this)

        val (folderRow, folderStatus) = ui.card(
            "📁",
            "App folder"
        ) {
            pickFolder.launch(null)
        }

        if (tree != null) {
            folderStatus.text =
                "✔ ${AppFolders.folderName(this, tree)}/" +
                    "${AppFolders.ROOT_NAME}/${AppFolders.LOGS_NAME}"

            folderStatus.setTextColor(pal.accent)
        } else {
            folderStatus.text = "✘ no folder chosen"
        }

        root.addView(folderRow)

        if (tree != null) {
            root.addView(
                ui.roundButton(
                    "Remove folder access",
                    false
                ) {
                    revokeFolder()
                }
            )
        }

        // ---------- Error log ----------
        root.addView(ui.sectionTitle("Error log"))

        root.addView(
            ui.body(
                "Records the error when the app closes unexpectedly or reading/extraction fails. " +
                    "Only works while the app is open. Slide to the right to enable."
            )
        )

        val logStatus = ui.body()

        fun refreshLogStatus() {
            logStatus.setTextColor(pal.muted)

            logStatus.text = when {
                !AppSettings.logEnabled(this) ->
                    "Disabled: no log is written."

                AppSettings.logsDirUri(this) != null ->
                    "Logs are saved to ${AppFolders.ROOT_NAME}/${AppFolders.LOGS_NAME} in the chosen folder."

                else ->
                    "No folder chosen: logs go to Android/data/$packageName/files/logs. " +
                        "Choose a folder above to find the logs more easily."
            }
        }

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )

        val sw = SwitchCompat(this).apply {
            text = "Save error log"
            textSize = 16f
            setTextColor(pal.fg)

            isChecked = AppSettings.logEnabled(
                this@SettingsActivity
            )

            thumbTintList = ColorStateList(
                states,
                intArrayOf(pal.accent, pal.muted)
            )

            trackTintList = ColorStateList(
                states,
                intArrayOf(
                    (pal.accent and 0x00FFFFFF) or 0x66000000,
                    pal.cardStroke
                )
            )

            setOnCheckedChangeListener { _, on ->
                AppSettings.setLogEnabled(
                    this@SettingsActivity,
                    on
                )

                refreshLogStatus()
            }
        }

        val logRow = LinearLayout(this).apply {
            setPadding(
                ui.dp(18),
                ui.dp(10),
                ui.dp(18),
                ui.dp(10)
            )

            background = ui.shape(
                pal.cardBg,
                24,
                pal.cardStroke,
                1
            )

            addView(
                sw,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    0,
                    ui.dp(6),
                    0,
                    ui.dp(6)
                )
            }
        }

        root.addView(logRow)
        root.addView(logStatus)

        refreshLogStatus()

        root.addView(
            ui.roundButton(
                "Write test log",
                false
            ) {
                val where = CrashLogger.writeTest(this)

                toast(
                    if (where != null) {
                        "Test log written to: $where"
                    } else {
                        "Nothing was written. Enable the error log first."
                    }
                )
            }
        )

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(pal.bg)
                addView(root)
            }
        )
    }

    // =========================================================
    // UPDATER
    // =========================================================

    private fun getInstalledVersion(): String {
        return try {
            val info = packageManager.getPackageInfo(
                packageName,
                0
            )

            info.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun refreshUpdaterSection() {
        val release = latestRelease

        if (release == null) {
            updateInfoText.text =
                "Tap “Check for update” to query GitHub."

            installUpdateButton.visibility = View.GONE
            return
        }

        val installed = getInstalledVersion()

        val newer = Updater.isNewerVersion(
            release.version,
            installed
        )

        if (newer) {
            updateInfoText.text =
                "New version available: ${release.version}\n" +
                    "Installed version: $installed\n" +
                    "An update is available."

            installUpdateButton.visibility = View.VISIBLE
        } else {
            updateInfoText.text =
                "Available version: ${release.version}\n" +
                    "Installed version: $installed\n" +
                    "✔ ZeldaRomTool is up to date."

            installUpdateButton.visibility = View.GONE
        }
    }

    private fun checkForUpdates() {
        checkUpdateButton.isEnabled = false
        checkUpdateButton.alpha = 0.5f

        updateInfoText.text =
            "Checking the latest release on GitHub..."

        updaterExecutor.execute {
            try {
                val release = Updater.checkLatestRelease()

                runOnUiThread {
                    latestRelease = release

                    refreshUpdaterSection()

                    checkUpdateButton.isEnabled = true
                    checkUpdateButton.alpha = 1f

                    toast("Check complete.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateInfoText.text =
                        "Could not check for updates.\n" +
                            (e.message ?: "Unknown error.")

                    checkUpdateButton.isEnabled = true
                    checkUpdateButton.alpha = 1f

                    installUpdateButton.visibility = View.GONE

                    toast("Failed to query GitHub.")
                }
            }
        }
    }

    private fun downloadAndInstall() {
        val release = latestRelease

        if (release == null) {
            toast("Check for updates first.")
            return
        }

        val installed = getInstalledVersion()

        if (!Updater.isNewerVersion(release.version, installed)) {
            refreshUpdaterSection()
            toast("The app is already up to date.")
            return
        }

        installUpdateButton.isEnabled = false
        installUpdateButton.alpha = 0.5f

        updateInfoText.text =
            "Downloading ZeldaRomTool ${release.version}...\n" +
                "Please wait for the download to finish."

        updaterExecutor.execute {
            try {
                val directory = File(cacheDir, "updates")

                val apk = Updater.downloadApk(
                    release,
                    directory
                )

                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    installUpdateButton.alpha = 1f

                    updateInfoText.text =
                        "Download complete.\n" +
                            "Preparing the installation..."

                    requestInstallPermissionOrInstall(apk)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    installUpdateButton.isEnabled = true
                    installUpdateButton.alpha = 1f

                    updateInfoText.text =
                        "Failed to download the update.\n" +
                            (e.message ?: "Unknown error.")

                    toast("Could not download the APK.")
                }
            }
        }
    }

    private fun requestInstallPermissionOrInstall(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                pendingApk = apk

                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )

                installPermissionLauncher.launch(intent)

                toast(
                    "Allow ZeldaRomTool to be installed " +
                        "on the next Android screen."
                )

                return
            }
        }

        installApk(apk)
    }

    private fun installApk(apk: File) {
        try {
            if (!apk.exists()) {
                toast("The update file was not found.")
                return
            }

            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apk
            )

            val intent = Intent(
                Intent.ACTION_VIEW
            ).apply {
                setDataAndType(
                    apkUri,
                    "application/vnd.android.package-archive"
                )

                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(intent)

            updateInfoText.text =
                "The Android installer has been opened. " +
                    "Confirm the installation to finish the update."

        } catch (e: Exception) {
            CrashLogger.logError(
                this,
                "Install update",
                e
            )

            toast(
                "Could not open the installer: ${e.message}"
            )
        }
    }

    // =========================================================
    // FOLDERS
    // =========================================================

    private fun onFolderPicked(uri: Uri) {
        try {
            val flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            contentResolver.takePersistableUriPermission(
                uri,
                flags
            )

            val logs = AppFolders.ensureLogsDir(
                this,
                uri
            )

            if (logs == null) {
                toast(
                    "Could not create the folders there. " +
                        "Choose another folder (for example, inside Documents)."
                )
                return
            }

            val old = AppSettings.treeUri(this)

            if (old != null && old != uri) {
                try {
                    contentResolver.releasePersistableUriPermission(
                        old,
                        flags
                    )
                } catch (ignored: Exception) {
                }
            }

            AppSettings.setFolder(
                this,
                uri,
                logs.uri
            )

            toast(
                "Folders created: ${AppFolders.ROOT_NAME}/${AppFolders.LOGS_NAME}"
            )

            buildUi()

        } catch (e: Exception) {
            CrashLogger.logError(
                this,
                "Choose folder",
                e
            )

            toast("Error accessing the folder: ${e.message}")
        }
    }

    private fun revokeFolder() {
        val tree = AppSettings.treeUri(this)

        if (tree != null) {
            try {
                contentResolver.releasePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (ignored: Exception) {
            }
        }

        AppSettings.clearFolder(this)

        toast("Access removed.")

        buildUi()
    }

    private fun toast(msg: String) {
        Toast.makeText(
            this,
            msg,
            Toast.LENGTH_LONG
        ).show()
    }
}
