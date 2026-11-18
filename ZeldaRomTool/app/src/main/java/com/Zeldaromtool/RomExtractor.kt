package com.zeldaromtool

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Reads the structure of an NSP (PFS0) or XCI (HFS0) and lists the files inside it. */
object RomExtractor {

    data class Entry(val name: String, val offset: Long, val size: Long)

    class Cancelled : Exception()

    private fun readAt(ch: FileChannel, pos: Long, len: Int): ByteBuffer {
        val bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        var p = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n < 0) throw IllegalStateException("File is smaller than expected")
            p += n
        }
        bb.flip()
        return bb
    }

    /** PFS0 (NSP) or HFS0 (XCI partitions). Returns absolute offsets in the file. */
    private fun parseFs(ch: FileChannel, base: Long, hfs: Boolean): List<Entry> {
        val tag = if (hfs) "HFS0" else "PFS0"
        val head = readAt(ch, base, 16)
        val magic = ByteArray(4)
        head.get(magic)
        if (String(magic, Charsets.US_ASCII) != tag) throw IllegalStateException("Invalid $tag header")
        val count = head.int
        val strSize = head.int
        if (count < 0 || count > 100_000 || strSize < 0 || strSize > 16 * 1024 * 1024) {
            throw IllegalStateException("Corrupted $tag header")
        }
        val entSize = if (hfs) 0x40 else 0x18
        val table = readAt(ch, base + 16, count * entSize + strSize)
        val strings = ByteArray(strSize)
        table.position(count * entSize)
        table.get(strings)
        val dataStart = base + 16 + count * entSize + strSize

        val out = ArrayList<Entry>()
        for (i in 0 until count) {
            table.position(i * entSize)
            val off = table.long
            val size = table.long
            val nameOff = table.int
            if (off < 0 || size < 0 || nameOff < 0 || nameOff > strings.size) {
                throw IllegalStateException("Corrupted entry $i")
            }
            var end = nameOff
            while (end < strings.size && strings[end] != 0.toByte()) end++
            out.add(Entry(String(strings, nameOff, end - nameOff, Charsets.UTF_8), dataStart + off, size))
        }
        return out
    }

    /**
     * File that is neither NSP nor XCI: accepted if it is a standalone NCA (for example, a DLC or an
     * update). Verified by decrypting the header, so it does not depend on the file extension.
     */
    private fun listLooseNca(ctx: Context, ch: FileChannel): List<Entry> {
        val size = ch.size()
        if (size < 0xC00) throw IllegalStateException("Does not look like an NSP, XCI or NCA")
        try {
            Nca.readHeader(ch, 0, GameExtractor.loadKeys(ctx))
        } catch (e: Exception) {
            throw IllegalStateException("Does not look like an NSP, XCI or NCA. ${e.message}")
        }
        return listOf(Entry("file.nca", 0L, size))
    }

    private fun listIn(ctx: Context, ch: FileChannel): List<Entry> {
        val size = ch.size()
        if (size < 0x104) return listLooseNca(ctx, ch)
        val m0 = ByteArray(4).also { readAt(ch, 0, 4).get(it) }
        if (String(m0, Charsets.US_ASCII) == "PFS0") return parseFs(ch, 0, false)

        val m1 = ByteArray(4).also { readAt(ch, 0x100, 4).get(it) }
        if (String(m1, Charsets.US_ASCII) != "HEAD") return listLooseNca(ctx, ch)
        val rootOffset = readAt(ch, 0x130, 8).long
        val result = ArrayList<Entry>()
        for (part in parseFs(ch, rootOffset, true)) {
            // "update" and "logo" are skipped; the game is in "secure" (and sometimes "normal")
            if (part.name == "secure" || part.name == "normal") {
                result.addAll(parseFs(ch, part.offset, true))
            }
        }
        if (result.isEmpty()) throw IllegalStateException("No game files found in the XCI")
        return result
    }

    fun list(ctx: Context, uri: Uri): List<Entry> {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not open the file")
        return pfd.use { listIn(ctx, FileInputStream(it.fileDescriptor).channel) }
    }
}
