package com.zeldaromtool

import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * The app's own log. Only works while the app is open and only writes if the
 * "Save error log" switch is enabled in Settings.
 */
object CrashLogger {

    private var installed = false
    private var previous: Thread.UncaughtExceptionHandler? = null

    /** Registers the handler for unexpected crashes. */
    @Synchronized
    fun install(ctx: Context) {
        if (installed) return
        val app = ctx.applicationContext
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, "CRASH", "Thread \"${thread.name}\"", error)
            } catch (ignored: Throwable) {
            }
            val prev = previous
            if (prev != null) {
                prev.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
        installed = true
    }

    @Synchronized
    fun uninstall() {
        if (!installed) return
        Thread.setDefaultUncaughtExceptionHandler(previous)
        previous = null
        installed = false
    }

    /** For errors the app catches itself (e.g. failure to read or extract the ROM). */
    fun logError(ctx: Context, where: String, error: Throwable) {
        try {
            write(ctx.applicationContext, "ERROR", where, error)
        } catch (ignored: Throwable) {
        }
    }

    /** Test button in Settings. Returns where it wrote, or null if it did not write. */
    fun writeTest(ctx: Context): String? =
        try {
            write(
                ctx.applicationContext, "TEST", "Test button in Settings",
                RuntimeException("Test log (not a real error)")
            )
        } catch (e: Throwable) {
            null
        }

    private fun write(ctx: Context, kind: String, where: String, error: Throwable): String? {
        if (!AppSettings.logEnabled(ctx)) return null
        val text = report(ctx, kind, where, error)
        val base = kind.lowercase(Locale.US) + "_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        if (writeToChosenFolder(ctx, base, text)) return "chosen folder (Zelda ROM Tool/Logs)"

        return try {
            File(AppFolders.privateLogsDir(ctx), "$base.txt").writeText(text)
            "Android/data/${ctx.packageName}/files/logs"
        } catch (e: Exception) {
            null
        }
    }

    private fun writeToChosenFolder(ctx: Context, baseName: String, text: String): Boolean {
        return try {
            val dir = AppSettings.logsDirUri(ctx) ?: return false
            // no ".txt" in the name: the system adds the extension from the text/plain type
            val doc = DocumentsContract.createDocument(ctx.contentResolver, dir, "text/plain", baseName)
                ?: return false
            ctx.contentResolver.openOutputStream(doc)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: return false
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun report(ctx: Context, kind: String, where: String, error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val keys = try {
            KeysManager.hasProd(ctx)
        } catch (e: Exception) {
            false
        }
        return buildString {
            appendLine("Zelda ROM Tool - report ($kind)")
            appendLine("Date: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("Where: $where")
            appendLine("App: version $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine(
                "App memory: ${(rt.totalMemory() - rt.freeMemory()) / mb} MB in use, max ${rt.maxMemory() / mb} MB"
            )
            appendLine("prod.keys loaded: $keys")
            appendLine()
            append(sw.toString())
        }
    }
}
