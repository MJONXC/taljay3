package com.zeldaromtool

import android.app.ActivityManager
import android.content.Context
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Profile chosen for the device. */
data class Tuning(
    val bufferSize: Int,   // size of each read/write block
    val queueDepth: Int,   // how many blocks are "in flight" between reader and writer
    val turbo: Boolean
)

object DeviceTuning {

    private const val MB = 1024 * 1024

    /** Profile in use. Set at the start of each extraction (writeItems). */
    @Volatile
    var current: Tuning = Tuning(bufferSize = 4 * MB, queueDepth = 3, turbo = false)

    // A 16 GB phone reports ~15 GB in totalMem, so the cutoff is 14 and not 16.
    private const val TURBO_RAM_GB = 14.0
    private const val TURBO_SPEED_MBS = 200.0

    fun totalRamGb(ctx: Context): Double {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / 1024.0 / 1024.0 / 1024.0
    }

    /**
     * Measures the REAL write speed (MB/s) in the chosen output folder.
     *
     * The test file is written and forced to disk (fsync) BEFORE stopping the timer.
     * Without this Android only copies to memory and the result shows several GB/s, even on
     * slow storage. Reading is not measured here because the memory cache would inflate it
     * the same way (and writing is the extraction bottleneck).
     *
     * Works with File or with SAF/DocumentFile: you just provide how to open and delete.
     * Returns 0.0 if the test fails.
     */
    fun measureSpeedMBs(
        openOut: () -> OutputStream,
        cleanup: () -> Unit,
        testMb: Int = 128
    ): Double {
        return try {
            val block = ByteArray(4 * MB) { (it * 31).toByte() }
            var mbs = 0.0
            openOut().use { out ->
                val t0 = System.nanoTime()
                repeat(testMb / 4) { out.write(block) }
                out.flush()
                // File streams (including SAF ones) allow forcing the write to disk.
                (out as? FileOutputStream)?.fd?.sync()
                val secs = (System.nanoTime() - t0) / 1e9
                mbs = testMb / secs
            }
            mbs
        } catch (e: Exception) {
            0.0
        } finally {
            runCatching { cleanup() }
        }
    }

    fun choose(ctx: Context, speedMBs: Double): Tuning {
        val ram = totalRamGb(ctx)
        return when {
            ram >= TURBO_RAM_GB && speedMBs >= TURBO_SPEED_MBS ->
                Tuning(bufferSize = 8 * MB, queueDepth = 4, turbo = true)
            ram >= 7.0 ->
                Tuning(bufferSize = 4 * MB, queueDepth = 3, turbo = false)
            else ->
                Tuning(bufferSize = 1 * MB, queueDepth = 2, turbo = false)
        }
    }
}

/**
 * Pipelined copy: one thread produces blocks (read + decrypt) while the current thread writes.
 * [fill] fills the given buffer (from index 0) and returns how many bytes it put in,
 * or -1 when done.
 * Returns true if it finished, false if it was cancelled.
 */
fun copyPipelined(
    fill: (ByteArray) -> Int,
    output: OutputStream,
    tuning: Tuning,
    isCancelled: () -> Boolean,
    onBytes: (Int) -> Unit
): Boolean {
    class Chunk(val data: ByteArray, var len: Int)

    val total = tuning.queueDepth + 1
    val free = ArrayBlockingQueue<Chunk>(total)
    val full = ArrayBlockingQueue<Chunk>(total + 1)
    repeat(total) { free.put(Chunk(ByteArray(tuning.bufferSize), 0)) }
    val eof = Chunk(ByteArray(0), -1)

    val failed = AtomicBoolean(false)
    val readError = AtomicReference<Throwable?>(null)

    val reader = Thread {
        try {
            while (!isCancelled() && !failed.get()) {
                val c = free.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val n = fill(c.data)
                if (n < 0) break
                c.len = n
                full.put(c)
            }
        } catch (t: Throwable) {
            readError.set(t)
        } finally {
            full.put(eof)
        }
    }
    reader.start()

    var writeError: Throwable? = null
    while (true) {
        val c = full.take()
        if (c === eof) break
        if (!failed.get()) {
            try {
                output.write(c.data, 0, c.len)
                onBytes(c.len)
            } catch (t: Throwable) {
                writeError = t
                failed.set(true)   // keep draining the queue just so the reader can finish
            }
        }
        free.put(c)
    }
    reader.join()

    writeError?.let { throw it }
    readError.get()?.let { throw it }
    return !isCancelled()
}

/** Version for a plain InputStream. */
fun copyPipelined(
    input: InputStream,
    output: OutputStream,
    tuning: Tuning,
    isCancelled: () -> Boolean,
    onBytes: (Int) -> Unit
): Boolean = copyPipelined({ buf -> input.read(buf) }, output, tuning, isCancelled, onBytes)

/*
 * In AndroidManifest, inside <application>, add: android:largeHeap="true"
 * (turbo mode uses about 40-50 MB of buffers).
 */
