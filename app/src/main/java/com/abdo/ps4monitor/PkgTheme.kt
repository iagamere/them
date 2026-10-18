package com.abdo.ps4monitor

/**
 * What can be learned from a PS4 theme package WITHOUT decrypting anything.
 * Verified on a real official theme ("God of War - 10th Anniversary"): the package's entry table holds param.sfo (CATEGORY=ac, TITLE, CONTENT_ID,
 * PUBTOOLINFO with "bgmhome_loud" = the theme has home-screen music) and icon0.png (512x512) in clear. The wallpaper, the icons and theme.xml
 * (font / focus colours) are inside the encrypted pfs_image.dat and are NOT readable, so the picture used for the colours is icon0.png
 * (and pic0 / pic1 when a package has them).
 *
 * Pure Kotlin (uses [PkgFormat] for the container layout): the reader works on any random-access source, so it is tested on a plain JVM.
 */
fun interface RandomRead {
    /** Up to [length] bytes at [offset]; fewer (or none) at the end of the source. */
    fun read(offset: Long, length: Int): ByteArray
}

class PkgThemeInfo(
    val contentId: String, val title: String?, val titleId: String?, val category: String?, val sfo: Map<String, String>,
    val icon: ByteArray?, val pic0: ByteArray?, val pic1: ByteArray?, val notes: List<String>) {
    /** Every readable picture of the package, icon first. */
    fun pictures(): List<ByteArray> = listOfNotNull(icon, pic0, pic1)
    val looksLikeTheme: Boolean get() = PkgTheme.looksLikeTheme(sfo)
}

object PkgTheme {
    private const val MAX_PICTURE = 8L shl 20
    private const val MAX_ENTRIES = 512

    /** A theme is an add-on ("ac") whose param.sfo mentions home-screen music or a "theme" title, or whose label is a PS Store theme label (ETH...). A hint only: the person can always pick any package. */
    fun looksLikeTheme(sfo: Map<String, String>): Boolean {
        if (sfo["CATEGORY"] != "ac") return false
        val label = sfo["CONTENT_ID"]?.substringAfterLast('_', "").orEmpty()
        return (sfo["PUBTOOLINFO"]?.contains("bgmhome", true) == true) ||
            (sfo["TITLE"]?.contains(Regex("theme|ثيم|thème|tema", RegexOption.IGNORE_CASE)) == true) ||
            label.startsWith("ETH")
    }

    /** Reads header, entry table, param.sfo and the clear pictures. Throws [PkgError] for something that is not a PS4 package. */
    fun read(src: RandomRead): PkgThemeInfo {
        val notes = ArrayList<String>()
        val head = src.read(0, 0x1000)
        val h = PkgFormat.parseHeader(head)                       // PkgError (notPkg) when the magic is wrong
        if (h.entryCount !in 1..MAX_ENTRIES) throw PkgError("The package entry table looks wrong (${h.entryCount} entries)")
        val table = src.read(h.tableOffset, h.entryCount * 32)
        if (table.size < h.entryCount * 32) throw PkgError("The package entry table is cut off (the file may be incomplete)")
        val raw = PkgFormat.parseTable(table, 0, h.entryCount)

        fun body(id: Long): ByteArray? {
            val e = raw.firstOrNull { it.id == id } ?: return null
            if (e.flags1 and 0x80000000L != 0L) { notes.add("${PkgFormat.KNOWN[id] ?: id.toString(16)} is encrypted"); return null }
            if (e.size <= 0 || e.size > MAX_PICTURE) return null
            val b = src.read(e.offset, e.size.toInt())
            if (b.size.toLong() != e.size) { notes.add("${PkgFormat.KNOWN[id] ?: id.toString(16)} is cut off"); return null }
            return b
        }

        val sfo: Map<String, String> = body(PkgFormat.ID_PARAM)?.let { b -> runCatching { PkgFormat.parseSfo(b).toMap() }.getOrElse { notes.add("param.sfo: ${it.message}"); emptyMap() } } ?: emptyMap()
        if (sfo.isEmpty()) notes.add("no readable param.sfo")
        val icon = body(PkgFormat.ID_ICON0)
        return PkgThemeInfo(h.contentId, sfo["TITLE"], sfo["TITLE_ID"], sfo["CATEGORY"], sfo, icon, body(PkgFormat.ID_PIC0), body(PkgFormat.ID_PIC1), notes)
    }
}
