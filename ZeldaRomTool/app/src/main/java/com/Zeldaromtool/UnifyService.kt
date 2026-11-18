package com.zeldaromtool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** State shared between the service and the screen. */
object UnifyState {
    class Snapshot(
        val running: Boolean,
        val done: Long,
        val total: Long,
        val file: String,
        val message: String?
    )

    @Volatile var snapshot = Snapshot(false, 0, 0, "", null)
    @Volatile var listener: ((Snapshot) -> Unit)? = null
    @Volatile var cancel = false

    fun post(s: Snapshot) {
        snapshot = s
        listener?.invoke(s)
    }

    /** The screen already showed the final message; avoids repeating it on reopen. */
    fun clearMessage() {
        val s = snapshot
        if (!s.running && s.message != null) snapshot = Snapshot(false, 0, 0, "", null)
    }
}

/** Runs extraction / merging in the background, with a progress notification. */
class UnifyService : Service() {

    companion object {
        const val MODE_UNIFY = "unify"
        const val MODE_EXTRACT = "extract"
        private const val ACTION_START = "com.zeldaromtool.START"
        private const val ACTION_CANCEL = "com.zeldaromtool.CANCEL"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_BASE = "base"
        private const val EXTRA_UPDATE = "update"
        private const val EXTRA_TREE = "tree"
        private const val EXTRA_FOLDER = "folder"
        private const val CHANNEL = "extracao"
        private const val NOTIF_RUN = 42
        private const val NOTIF_DONE = 43

        fun start(ctx: Context, mode: String, base: Uri, update: Uri?, tree: Uri, folder: String) {
            val i = Intent(ctx, UnifyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_BASE, base.toString())
                .putExtra(EXTRA_UPDATE, update?.toString())
                .putExtra(EXTRA_TREE, tree.toString())
                .putExtra(EXTRA_FOLDER, folder)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private var worker: Thread? = null
    private var lastNotify = 0L
    private var lastPost = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                UnifyState.cancel = true
                return START_NOT_STICKY
            }
            ACTION_START -> {
                goForeground(buildRunning(0, 0, "Preparing…"))
                if (worker?.isAlive == true) return START_NOT_STICKY
                val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNIFY
                val base = Uri.parse(intent.getStringExtra(EXTRA_BASE))
                val update = intent.getStringExtra(EXTRA_UPDATE)?.let { Uri.parse(it) }
                val tree = Uri.parse(intent.getStringExtra(EXTRA_TREE))
                val folder = intent.getStringExtra(EXTRA_FOLDER) ?: "game"
                UnifyState.cancel = false
                UnifyState.post(UnifyState.Snapshot(true, 0, 0, "Preparing…", null))
                worker = Thread { run(mode, base, update, tree, folder) }.also { it.start() }
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun run(mode: String, base: Uri, update: Uri?, tree: Uri, folder: String) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeldaromtool:extracao")
        wl.setReferenceCounted(false)
        wl.acquire(12L * 60 * 60 * 1000)

        val result: String = try {
            val entries = RomExtractor.list(this, base)
            val progress = { done: Long, total: Long, file: String -> onProgress(done, total, file) }
            if (mode == MODE_UNIFY && update != null) {
                Unifier.unify(
                    ctx = this,
                    baseUri = base,
                    baseEntries = entries,
                    updateUri = update,
                    outTreeUri = tree,
                    outFolderName = folder,
                    isCancelled = { UnifyState.cancel },
                    onProgress = progress
                )
            } else {
                GameExtractor.extract(
                    ctx = this,
                    romUri = base,
                    entries = entries,
                    outTreeUri = tree,
                    outFolderName = folder,
                    isCancelled = { UnifyState.cancel },
                    onProgress = progress
                )
            }
            if (mode == MODE_UNIFY) "✔ Merge complete!\nUpdated ExeFS and RomFS saved to:\n$folder"
            else "✔ Extraction complete!\nExtracted files saved to:\n$folder"
        } catch (e: RomExtractor.Cancelled) {
            "Operation cancelled. The partial file was removed."
        } catch (e: Exception) {
            CrashLogger.logError(this, "Background extraction", e)
            "Error: ${e.message}"
        } catch (t: Throwable) {
            "Error: ${t.message}"
        } finally {
            if (wl.isHeld) wl.release()
        }

        UnifyState.post(UnifyState.Snapshot(false, 0, 0, "", result))
        notifyDone(result)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onProgress(done: Long, total: Long, file: String) {
        val now = System.currentTimeMillis()
        if (now - lastPost < 400 && done < total) return
        lastPost = now
        UnifyState.post(UnifyState.Snapshot(true, done, total, file, null))
        if (now - lastNotify >= 1000) {
            lastNotify = now
            getSystemService(NotificationManager::class.java).notify(NOTIF_RUN, buildRunning(done, total, file))
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Game extraction", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildRunning(done: Long, total: Long, file: String): Notification {
        ensureChannel()
        val pct = if (total > 0) ((done * 1000) / total).toInt() else 0
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, UnifyService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (total > 0) String.format("Extracting… %.1f%%", pct / 10.0) else "Extracting…")
            .setContentText(file)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, pct, total <= 0)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun notifyDone(msg: String) {
        ensureChannel()
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(msg.lines().first())
            .setContentText(msg.lines().drop(1).joinToString(" "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_RUN, n)
        }
    }
}
