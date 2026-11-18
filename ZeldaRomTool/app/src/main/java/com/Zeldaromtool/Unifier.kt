package com.zeldaromtool

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.channels.FileChannel

/** Merges the base game (NSP/XCI) with the update (NCA, or NSP/XCI containing the NCA). */
object Unifier {

    private fun findProgram(
        ch: FileChannel,
        entries: List<RomExtractor.Entry>,
        keys: Keys,
        tickets: Map<String, ByteArray>
    ): Nca? {
        for (e in entries) {
            if (!e.name.endsWith(".nca", true)) continue
            if (Nca.peekType(ch, e.offset, keys) == 0) return Nca(ch, e.offset, keys, tickets)
        }
        return null
    }

    /** If the file is NSP/XCI, list its contents; otherwise treat it as a standalone NCA. */
    private fun listUpdate(ctx: Context, uri: Uri, ch: FileChannel): List<RomExtractor.Entry> {
        val size = ch.size()
        if (size < 0x400) throw IllegalStateException("Update file is too small.")
        val m0 = String(readBytes(ch, 0, 4), Charsets.US_ASCII)
        val m1 = String(readBytes(ch, 0x100, 4), Charsets.US_ASCII)
        return if (m0 == "PFS0" || m1 == "HEAD") {
            RomExtractor.list(ctx, uri)
        } else {
            listOf(RomExtractor.Entry("update.nca", 0L, size))
        }
    }

    fun unify(
        ctx: Context,
        baseUri: Uri,
        baseEntries: List<RomExtractor.Entry>,
        updateUri: Uri,
        outTreeUri: Uri,
        outFolderName: String,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long, file: String) -> Unit
    ) {
        if (baseEntries.any { it.name.endsWith(".ncz", true) }) {
            throw IllegalStateException("NSZ/XCZ (compressed) is not supported. Use a regular NSP or XCI.")
        }
        val keys = GameExtractor.loadKeys(ctx)

        val basePfd = ctx.contentResolver.openFileDescriptor(baseUri, "r")
            ?: throw IllegalStateException("Could not open the base game")
        basePfd.use { bp ->
            val updPfd = ctx.contentResolver.openFileDescriptor(updateUri, "r")
                ?: throw IllegalStateException("Could not open the update")
            updPfd.use { up ->
                val bch = FileInputStream(bp.fileDescriptor).channel
                val uch = FileInputStream(up.fileDescriptor).channel

                val updEntries = listUpdate(ctx, updateUri, uch)
                if (updEntries.any { it.name.endsWith(".ncz", true) }) {
                    throw IllegalStateException("Compressed update (NCZ) is not supported.")
                }

                val tickets = HashMap<String, ByteArray>()
                tickets.putAll(GameExtractor.collectTickets(bch, baseEntries))
                tickets.putAll(GameExtractor.collectTickets(uch, updEntries))

                // 1. Program NCA of the base game and Program NCA of the update
                val baseNca = findProgram(bch, baseEntries, keys, tickets)
                    ?: throw IllegalStateException("Could not find the game NCA (Program) in the base game.")
                val updNca = findProgram(uch, updEntries, keys, tickets)
                    ?: throw IllegalStateException("Could not find the update NCA (Program) in this file.")

                // 2. The update must belong to this game (update Title ID = base + 0x800)
                val diff = baseNca.titleId xor updNca.titleId
                if (diff != 0L && diff != 0x800L) {
                    throw IllegalStateException("This update does not belong to this base game (different Title ID).")
                }

                // 3. Link the patch RomFS to the base game RomFS
                val baseRomfs = baseNca.sections.firstOrNull { it.fsType == 0 && it.dataOff >= 0 }
                for (sec in updNca.sections) {
                    if (sec.fsType == 0 && sec.encType == 4) {
                        if (baseRomfs == null) throw IllegalStateException("The base game has no RomFS.")
                        updNca.attachBase(sec, baseNca, baseRomfs)
                    }
                }

                // 4. ExeFS and RomFS: whatever the update has wins; the rest comes from the base game
                val items = ArrayList<GameExtractor.Item>()
                items.addAll(GameExtractor.listItems(updNca, true, true))
                if (items.none { it.path.startsWith("exefs/") }) {
                    items.addAll(GameExtractor.listItems(baseNca, true, false).filter { it.path.startsWith("exefs/") })
                }
                if (items.none { it.path.startsWith("romfs/") }) {
                    items.addAll(GameExtractor.listItems(baseNca, false, true))
                }
                if (items.isEmpty()) throw IllegalStateException("No ExeFS/RomFS files found.")

                GameExtractor.writeItems(ctx, outTreeUri, outFolderName, items, isCancelled, onProgress)
            }
        }
    }
}
