package com.zeldaromtool

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader for the RomFS section of an update NCA (BKTR / AES-CTR-EX).
 *
 * The "virtual" RomFS is assembled like this: the relocation table says, for each chunk, whether
 * the bytes come from the base game (already decrypted) or from the patch itself. Patch chunks are
 * decrypted with AES-CTR using the counter from the subsection table.
 *
 * Optimizations: adjacent chunks that continue one another are merged (fewer pieces to
 * read), lookups use plain arrays, and bytes are read and decrypted directly into the
 * destination buffer (no temporary arrays). Small base-game chunks go through a read cache
 * of aligned blocks, always reusing the same array.
 */
class Bktr(
    private val patch: Nca,
    private val sec: Section,
    private val baseNca: Nca,
    private val baseSec: Section
) {
    private class Reloc(val virt: Long, val phys: Long, val isPatch: Boolean)
    private class Sub(val offset: Long, val ctrVal: Int)

    // Relocation table (already merged)
    private var relVirt = LongArray(0)
    private var relPhys = LongArray(0)
    private var relPatch = BooleanArray(0)
    private var relCount = 0

    // Subsection table (already merged)
    private var subOff = LongArray(0)
    private var subCtr = IntArray(0)
    private var subCount = 0

    // Base game read cache (one aligned block, reused array)
    private val cacheData = ByteArray(BASE_BLOCK)
    private var cacheStart = -1L
    private var cacheLen = 0

    init {
        val rawRel = ArrayList<Reloc>()
        val rawSub = ArrayList<Sub>()
        loadBuckets(sec.relOff, sec.relSize, 0x14) { b, o ->
            rawRel.add(Reloc(b.getLong(o), b.getLong(o + 8), b.getInt(o + 16) != 0))
        }
        loadBuckets(sec.subOff, sec.subSize, 0x10) { b, o ->
            rawSub.add(Sub(b.getLong(o), b.getInt(o + 12)))
        }
        if (rawRel.isEmpty()) throw IllegalStateException("The update relocation table is empty.")
        rawRel.sortBy { it.virt }
        rawSub.sortBy { it.offset }

        // Merge chunks that continue one another (same source and contiguous positions).
        relVirt = LongArray(rawRel.size)
        relPhys = LongArray(rawRel.size)
        relPatch = BooleanArray(rawRel.size)
        var n = 0
        for (r in rawRel) {
            if (n > 0 && relPatch[n - 1] == r.isPatch &&
                relPhys[n - 1] + (r.virt - relVirt[n - 1]) == r.phys
            ) continue
            relVirt[n] = r.virt
            relPhys[n] = r.phys
            relPatch[n] = r.isPatch
            n++
        }
        relCount = n

        // Consecutive subsections with the same counter produce the same cipher stream: merge.
        subOff = LongArray(rawSub.size)
        subCtr = IntArray(rawSub.size)
        var m = 0
        for (s in rawSub) {
            if (m > 0 && subCtr[m - 1] == s.ctrVal) continue
            subOff[m] = s.offset
            subCtr[m] = s.ctrVal
            m++
        }
        subCount = m
    }

    /** Each table has a 0x4000-byte header followed by 0x4000-byte "buckets". */
    private fun loadBuckets(
        tableOff: Long,
        tableSize: Long,
        entrySize: Int,
        onEntry: (ByteBuffer, Int) -> Unit
    ) {
        if (tableOff <= 0 || tableSize < 0x4000) {
            throw IllegalStateException("This update does not have the expected BKTR tables.")
        }
        val head = ByteBuffer.wrap(patch.readCtr(sec, tableOff, 0x10, null)).order(ByteOrder.LITTLE_ENDIAN)
        val buckets = head.getInt(4)
        if (buckets < 0 || buckets > 4096) throw IllegalStateException("Corrupted BKTR table.")
        for (i in 0 until buckets) {
            val b = ByteBuffer.wrap(
                patch.readCtr(sec, tableOff + 0x4000L * (1 + i), 0x4000, null)
            ).order(ByteOrder.LITTLE_ENDIAN)
            val count = b.getInt(4)
            if (count < 0 || 0x10 + count * entrySize > 0x4000) {
                throw IllegalStateException("Corrupted BKTR table.")
            }
            for (k in 0 until count) onEntry(b, 0x10 + k * entrySize)
        }
    }

    /** Index of the last item with value <= [key], or -1. */
    private fun floorIndex(arr: LongArray, count: Int, key: Long): Int {
        var lo = 0
        var hi = count - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] <= key) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Reads data stored in the patch itself (physical offset [p] within the section) directly into [out]. */
    private fun readPatch(p: Long, len: Int, out: ByteArray, outOff: Int) {
        var done = 0
        while (done < len) {
            val cur = p + done
            val idx = floorIndex(subOff, subCount, cur)
            val ctrVal: Int? = if (idx >= 0) subCtr[idx] else null
            val end = when {
                idx >= 0 && idx + 1 < subCount -> subOff[idx + 1]
                idx < 0 && subCount > 0 -> subOff[0]
                else -> Long.MAX_VALUE
            }
            val n = minOf((len - done).toLong(), end - cur).toInt()
            if (n <= 0) throw IllegalStateException("Inconsistent subsection table.")
            patch.readCtrInto(sec, cur, n, ctrVal, out, outOff + done)
            done += n
        }
    }

    /**
     * Reads base game data (offset [phys] within the section) directly into [out].
     * Large chunks go straight to the destination. Small chunks read an aligned block
     * (BASE_BLOCK) once and their neighbors come from the cache, without reading the file again.
     */
    private fun readBase(phys: Long, n: Int, out: ByteArray, outOff: Int) {
        if (cacheLen > 0 && phys >= cacheStart && phys + n <= cacheStart + cacheLen) {
            System.arraycopy(cacheData, (phys - cacheStart).toInt(), out, outOff, n)
            return
        }
        if (n >= BASE_BLOCK) {
            baseNca.readInto(baseSec, phys, n, out, outOff)
            return
        }
        val blockStart = phys and (BASE_BLOCK - 1).toLong().inv()
        val limit = baseSec.dataOff + baseSec.dataSize
        val want = minOf(BASE_BLOCK.toLong(), limit - blockStart)
        if (want < (phys - blockStart) + n) {
            // Near the end of the section (or unknown size): read only what is needed.
            baseNca.readInto(baseSec, phys, n, out, outOff)
            return
        }
        cacheLen = 0 // if the read fails, the cache is not left half-filled
        baseNca.readInto(baseSec, blockStart, want.toInt(), cacheData, 0)
        cacheStart = blockStart
        cacheLen = want.toInt()
        System.arraycopy(cacheData, (phys - blockStart).toInt(), out, outOff, n)
    }

    /** Reads [len] bytes of the updated RomFS, starting at [pos] (relative to the section start). */
    fun read(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        readInto(pos, len, out, 0)
        return out
    }

    /** Same as [read], but writes directly into [out] starting at [outOff]. */
    fun readInto(pos: Long, len: Int, out: ByteArray, outOff: Int) {
        var done = 0
        while (done < len) {
            val cur = pos + done
            val idx = floorIndex(relVirt, relCount, cur)
            if (idx < 0) throw IllegalStateException("Position outside the relocation table.")
            val segEnd = if (idx + 1 < relCount) relVirt[idx + 1] else Long.MAX_VALUE
            val n = minOf((len - done).toLong(), segEnd - cur).toInt()
            if (n <= 0) throw IllegalStateException("Inconsistent relocation table.")
            val phys = relPhys[idx] + (cur - relVirt[idx])
            if (relPatch[idx]) readPatch(phys, n, out, outOff + done)
            else readBase(phys, n, out, outOff + done)
            done += n
        }
    }

    private companion object {
        /** Size (power of 2) of the base game read block for small chunks. */
        const val BASE_BLOCK = 256 * 1024
    }
}
