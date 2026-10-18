package com.abdo.ps4monitor
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A saved "PS4 theme look": the accent colours found in one theme's pictures (see ThemeColor.kt) plus a small thumbnail.
 * The scheme itself is NOT stored: it is rebuilt from the colours for light or dark at display time.
 */
data class ThemeProfile(
    val id: String, val name: String, val source: String,
    val primary: Int, val secondary: Int?, val tertiary: Int?, val mono: Boolean, val darkPicture: Boolean,
    val swatches: List<Pair<Int, Float>>, val createdAt: Long,
    /** a wallpaper file exists for this theme / the person wants it shown / sharp PS4-style corners / how strongly the wallpaper is dimmed (0.5..0.95) */
    val wall: Boolean = false, val useWall: Boolean = true, val sharp: Boolean = false, val dim: Float = 0.78f) {
    fun toSeed() = ThemeSeed(primary, secondary, tertiary, mono, darkPicture, swatches.map { (c, s) -> Ok.lch(c).let { l -> Swatch(c, s, l.l, l.c, l.h) } })
}

/** A theme-looking add-on found on the console. */
class ThemeCandidate(val dir: String, val title: String, val contentId: String, val iconPath: String?, val pic0: String?, val pic1: String?)
class ThemeScan(val items: List<ThemeCandidate>, val notes: List<String>, val listings: Int)
/** Result of reading one theme source: the profile to review (not saved yet), its thumbnail and what was read. */
class ThemeBuilt(val profile: ThemeProfile, val thumb: Bitmap?, val info: String, val wall: Bitmap? = null, val func: Bitmap? = null) {
    fun with(p: ThemeProfile) = ThemeBuilt(p, thumb, info, wall, func)
}

fun SchemeColors.toColorScheme(): ColorScheme =
    if (dark) darkColorScheme(
        primary = Color(primary), onPrimary = Color(onPrimary), primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary), secondary = Color(secondary), onSecondary = Color(onSecondary), secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer), tertiary = Color(tertiary), onTertiary = Color(onTertiary), tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer), background = Color(background), onBackground = Color(onBackground), surface = Color(surface),
        onSurface = Color(onSurface), surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(onSurfaceVariant), surfaceTint = Color(surfaceTint),
        inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface), error = Color(error), onError = Color(onError),
        errorContainer = Color(errorContainer), onErrorContainer = Color(onErrorContainer), outline = Color(outline), outlineVariant = Color(outlineVariant),
        scrim = Color(scrim), surfaceBright = Color(surfaceBright), surfaceDim = Color(surfaceDim), surfaceContainer = Color(surfaceContainer),
        surfaceContainerHigh = Color(surfaceContainerHigh), surfaceContainerHighest = Color(surfaceContainerHighest), surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainerLowest = Color(surfaceContainerLowest))
    else lightColorScheme(
        primary = Color(primary), onPrimary = Color(onPrimary), primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
        inversePrimary = Color(inversePrimary), secondary = Color(secondary), onSecondary = Color(onSecondary), secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer), tertiary = Color(tertiary), onTertiary = Color(onTertiary), tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer), background = Color(background), onBackground = Color(onBackground), surface = Color(surface),
        onSurface = Color(onSurface), surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(onSurfaceVariant), surfaceTint = Color(surfaceTint),
        inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface), error = Color(error), onError = Color(onError),
        errorContainer = Color(errorContainer), onErrorContainer = Color(onErrorContainer), outline = Color(outline), outlineVariant = Color(outlineVariant),
        scrim = Color(scrim), surfaceBright = Color(surfaceBright), surfaceDim = Color(surfaceDim), surfaceContainer = Color(surfaceContainer),
        surfaceContainerHigh = Color(surfaceContainerHigh), surfaceContainerHighest = Color(surfaceContainerHighest), surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainerLowest = Color(surfaceContainerLowest))

object ThemeStore {
    /** The theme the whole app is coloured with, or null (then the phone's colours / the default purple are used). Observable by Compose. */
    val active = mutableStateOf<ThemeProfile?>(null)
    val profiles = mutableStateOf<List<ThemeProfile>>(emptyList())
    /** The wallpaper of the active theme (the app draws it behind everything), or null. */
    val wall = mutableStateOf<ImageBitmap?>(null)
    private lateinit var dir: File

    fun init(c: Context) {
        dir = File(c.filesDir, "themes").apply { mkdirs() }
        profiles.value = runCatching {
            val a = JSONArray(Store.sp.getString("ps4themes", "[]"))
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        val id = Store.sp.getString("ps4theme_active", null)
        active.value = profiles.value.firstOrNull { it.id == id }
        refreshWall()
    }

    private fun toJson(p: ThemeProfile) = JSONObject().put("id", p.id).put("name", p.name).put("src", p.source).put("p", p.primary)
        .put("s", p.secondary ?: JSONObject.NULL).put("t", p.tertiary ?: JSONObject.NULL).put("mono", p.mono).put("dp", p.darkPicture).put("at", p.createdAt)
        .put("wall", p.wall).put("uw", p.useWall).put("sharp", p.sharp).put("dim", p.dim.toDouble())
        .put("sw", JSONArray(p.swatches.map { JSONArray().put(it.first).put(it.second.toDouble()) }))
    private fun fromJson(o: JSONObject) = ThemeProfile(o.getString("id"), o.getString("name"), o.optString("src"), o.getInt("p"),
        if (o.isNull("s")) null else o.getInt("s"), if (o.isNull("t")) null else o.getInt("t"), o.optBoolean("mono"), o.optBoolean("dp", true),
        o.optJSONArray("sw")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { x -> x.getInt(0) to x.getDouble(1).toFloat() } } } ?: emptyList(), o.optLong("at"),
        o.optBoolean("wall"), o.optBoolean("uw", true), o.optBoolean("sharp"), o.optDouble("dim", 0.78).toFloat().coerceIn(0.5f, 0.95f))

    private fun persist() { Store.sp.edit().putString("ps4themes", JSONArray(profiles.value.map { toJson(it) }).toString()).putString("ps4theme_active", active.value?.id).apply() }

    fun thumbFile(id: String) = File(dir, "$id.jpg")
    fun wallFile(id: String) = File(dir, "${id}_wall.jpg")
    fun funcFile(id: String) = File(dir, "${id}_func.jpg")
    private fun load(f: File): Bitmap? = f.takeIf { it.exists() }?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path) }.getOrNull() }
    fun thumb(id: String): Bitmap? = load(thumbFile(id))
    fun wallBitmap(id: String): Bitmap? = load(wallFile(id))
    fun funcBitmap(id: String): Bitmap? = load(funcFile(id))

    /** Loads the wallpaper of the active theme into [wall] (a small JPEG, decoding takes a few milliseconds). */
    private fun refreshWall() {
        val p = active.value
        wall.value = if (p != null && p.wall && p.useWall) wallBitmap(p.id)?.asImageBitmap() else null
    }
    private fun jpeg(f: File, b: Bitmap?) { if (b != null) runCatching { f.outputStream().use { o -> b.compress(Bitmap.CompressFormat.JPEG, 90, o) } } }

    fun save(b: ThemeBuilt, use: Boolean) {
        val p = b.profile.copy(wall = b.wall != null)
        jpeg(thumbFile(p.id), b.thumb); jpeg(wallFile(p.id), b.wall); jpeg(funcFile(p.id), b.func)
        profiles.value = profiles.value.filter { it.id != p.id } + p
        if (use) active.value = p
        persist(); refreshWall()
    }
    fun use(id: String?) { active.value = profiles.value.firstOrNull { it.id == id }; persist(); refreshWall() }
    fun delete(id: String) {
        profiles.value = profiles.value.filter { it.id != id }; thumbFile(id).delete(); wallFile(id).delete(); funcFile(id).delete()
        if (active.value?.id == id) active.value = null
        persist(); refreshWall()
    }
    /** Changes one saved theme (name, accent, wallpaper on/off, sharp corners, dimming). */
    fun update(id: String, f: (ThemeProfile) -> ThemeProfile) {
        val before = active.value
        profiles.value = profiles.value.map { if (it.id == id) f(it) else it }
        if (active.value?.id == id) active.value = profiles.value.firstOrNull { it.id == id }
        persist()
        val now = active.value
        if (before?.id != now?.id || before?.wall != now?.wall || before?.useWall != now?.useWall) refreshWall()      // not on every slider tick: dimming needs no reload
    }
    /** The person tapped another swatch: same picture, another accent. */
    fun withPrimary(p: ThemeProfile, argb: Int): ThemeProfile = p.toSeed().withPrimary(argb).let {
        p.copy(primary = it.primary, secondary = it.secondary, tertiary = it.tertiary, mono = false)
    }
    fun applyPrimary(id: String, argb: Int) = update(id) { withPrimary(it, argb) }

    // ---------------------------------------------------------------- building a profile from a source
    fun pixelsOf(b: Bitmap, maxSide: Int = 128): IntArray {
        val f = maxSide.toFloat() / maxOf(b.width, b.height)
        val s = if (f < 1f) Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true) else b
        val px = IntArray(s.width * s.height); s.getPixels(px, 0, s.width, 0, 0, s.width, s.height); return px
    }
    private fun small(b: Bitmap, side: Int = 160): Bitmap {
        val f = side.toFloat() / maxOf(b.width, b.height); if (f >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }
    private fun fit(b: Bitmap, side: Int): Bitmap {
        val f = side.toFloat() / maxOf(b.width, b.height); if (f >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    /**
     * [icon] = the theme's own icon (icon0.png), [preview] = a picture of the theme: usually the capture of the PS4's "Settings > Themes" screen that theme packs
     * carry (then the HOME panel becomes the wallpaper and the SETTINGS panel is kept too, see PreviewPanels), otherwise any picture (used whole as the wallpaper).
     * The accent colours come from the wallpaper first (what the PS4 really shows), with the icon counting less.
     */
    private fun build(name: String, source: String, icon: Bitmap?, preview: Bitmap?, info: String): ThemeBuilt? {
        var wall: Bitmap? = null; var func: Bitmap? = null; var how = ""
        if (preview != null) {
            val pp = panelsOf(preview)
            if (pp != null) {
                wall = Bitmap.createBitmap(preview, pp.home.x, pp.home.y, pp.home.w, pp.home.h)
                func = Bitmap.createBitmap(preview, pp.func.x, pp.func.y, pp.func.w, pp.func.h)
                how = tr("wallpaper and settings screen cut from the theme's preview picture", "الخلفية وشاشة الإعدادات مقصوصتان من صورة معاينة الثيم")
            } else { wall = fit(preview, 1280); how = tr("the picture is used as the wallpaper", "الصورة تُستخدم كخلفية") }
        }
        val colours = listOfNotNull(wall?.let { pixelsOf(it, 128) }, icon?.let { pixelsOf(it, 64) })
        if (colours.isEmpty()) return null
        val seed = ThemeExtract.seed(colours.fold(IntArray(0)) { a, b -> a + b }) ?: return null
        val p = ThemeProfile(java.util.UUID.randomUUID().toString(), name, source, seed.primary, seed.secondary, seed.tertiary, seed.monochrome, seed.darkPicture,
            seed.swatches.take(8).map { it.argb to it.share }, System.currentTimeMillis(), wall = wall != null, sharp = wall != null)
        val note = listOf(info, how).filter { it.isNotBlank() }.joinToString("  •  ")
        return ThemeBuilt(p, small(icon ?: wall!!), note, wall, func)
    }

    /** The two panels of a "Settings > Themes" capture, or null when the picture is not one. */
    private fun panelsOf(b: Bitmap): PanelPair? {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return PreviewPanels.find(px, b.width, b.height)
    }

    private fun bytesSource(b: ByteArray) = RandomRead { off, len -> if (off < 0 || off >= b.size) ByteArray(0) else b.copyOfRange(off.toInt(), minOf(b.size.toLong(), off + len).toInt()) }

    /** Keeps only the first [cap] bytes written; the next byte throws [Reached], which stops an extraction early (a theme .pkg is 30 MB, its readable part is in the first MB). */
    private class Reached : IOException("cap")
    private class CapSink(val cap: Int) : java.io.OutputStream() {
        val buf = java.io.ByteArrayOutputStream(); var reached = false
        override fun write(b: Int) { if (buf.size() >= cap) { reached = true; throw Reached() }; buf.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = cap - buf.size()
            if (len > room) { if (room > 0) buf.write(b, off, room); reached = true; throw Reached() }
            buf.write(b, off, len)
        }
    }
    private fun readCapped(ins: java.io.InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val tmp = ByteArray(64 * 1024)
        while (out.size() < cap) { val n = ins.read(tmp, 0, minOf(tmp.size, cap - out.size())); if (n < 0) break; out.write(tmp, 0, n) }
        return out.toByteArray()
    }

    /** What a theme pack held: the package's clear part and every picture in it. */
    private class Pack { var pkg: PkgThemeInfo? = null; val pictures = ArrayList<Bitmap>(); val names = ArrayList<String>() }
    private val IMG = setOf("jpg", "jpeg", "png", "webp")

    private fun visit(pack: Pack, name: String, read: (Int) -> ByteArray) {
        val ext = name.substringAfterLast('.', "").lowercase()
        pack.names.add(name.substringAfterLast('/').substringAfterLast('\\'))
        if (ext == "pkg") {
            val info = runCatching { PkgTheme.read(bytesSource(read(2 shl 20))) }.getOrNull() ?: return
            val cur = pack.pkg
            if (cur == null || (cur.icon == null && info.icon != null)) pack.pkg = info
        } else if (ext in IMG && pack.pictures.size < 6) {
            Thumbs.decode(read(25 shl 20), 1920)?.let { pack.pictures.add(it) }
        }
    }

    private fun readZip(ctx: Context, uri: Uri, pack: Pack) {
        java.util.zip.ZipInputStream(ctx.contentResolver.openInputStream(uri) ?: throw IOException("open")).use { z ->
            var e = z.nextEntry
            while (e != null) { if (!e.isDirectory) visit(pack, e.name) { cap -> readCapped(z, cap) }; e = z.nextEntry }
        }
    }
    /** RAR (versions 1.5 - 4.x, what theme packs use) through the junrar library; the archive is copied to the cache first because the reader needs a file. */
    private fun readRar(ctx: Context, uri: Uri, pack: Pack) {
        val tmp = File(ctx.cacheDir, "theme_import.rar")
        try {
            (ctx.contentResolver.openInputStream(uri) ?: throw IOException("open")).use { ins -> tmp.outputStream().use { o -> ins.copyTo(o) } }
            val a = com.github.junrar.Archive(tmp)
            try {
                var fh = a.nextFileHeader()
                while (fh != null) {
                    if (!fh.isDirectory) {
                        val h = fh
                        visit(pack, h.fileName) { cap ->
                            val sink = CapSink(cap)
                            try { a.extractFile(h, sink) } catch (e: Throwable) { if (!sink.reached) throw e }
                            sink.buf.toByteArray()
                        }
                    }
                    fh = a.nextFileHeader()
                }
            } finally { runCatching { a.close() } }
        } finally { tmp.delete() }
    }

    private fun magic(ctx: Context, uri: Uri): ByteArray = ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, 8) } ?: ByteArray(0)

    /**
     * Any theme source chosen on the phone: a theme pack (.rar / .zip holding the .pkg and its preview picture), a bare .pkg, or a picture. The kind is
     * recognised by the file's first bytes, not its name. Returns the result to review, or an error text.
     */
    suspend fun fromFile(ctx: Context, uri: Uri, fileName: String): Pair<ThemeBuilt?, String?> = withContext(Dispatchers.IO) {
        try {
            val m = magic(ctx, uri)
            fun b(i: Int) = if (i < m.size) m[i].toInt() and 0xFF else -1
            val baseName = fileName.substringBeforeLast('.').ifBlank { "Theme" }
            when {
                (b(0) == 'R'.code && b(1) == 'a'.code && b(2) == 'r'.code && b(3) == '!'.code) || (b(0) == 'P'.code && b(1) == 'K'.code) -> {
                    val pack = Pack()
                    if (b(0) == 'P'.code) readZip(ctx, uri, pack) else readRar(ctx, uri, pack)
                    val pkg = pack.pkg
                    val preview = pack.pictures.firstOrNull { panelsOf(it) != null } ?: pack.pictures.maxByOrNull { it.width.toLong() * it.height }
                    val icon = pkg?.icon?.let { Thumbs.decode(it, 512) }
                    val name = pkg?.title?.takeIf { it.isNotBlank() } ?: baseName
                    val info = pkg?.contentId.orEmpty()
                    if (preview == null && icon == null) return@withContext null to (if (pkg == null && pack.names.isEmpty()) tr("The archive is empty.", "الأرشيف فارغ.")
                        else tr("No picture of the theme was found in this file (files: ${pack.names.take(5).joinToString()}).", "لم تُوجد صورة للثيم في هذا الملف (الملفات: ${pack.names.take(5).joinToString()})."))
                    build(name, "pkg", icon, preview, info)?.let { it to null } ?: (null to tr("The pictures have no visible colours.", "الصور بلا ألوان مرئية."))
                }
                b(0) == 0x7F && b(1) == 'C'.code && b(2) == 'N'.code && b(3) == 'T'.code -> {
                    val info = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                            val ch = fis.channel; val size = ch.size()
                            PkgTheme.read { off, len ->
                                if (off < 0 || off >= size) ByteArray(0) else {
                                    val bb = ByteBuffer.allocate(minOf(len.toLong(), size - off).toInt()); var pos = off
                                    while (bb.hasRemaining()) { val r = ch.read(bb, pos); if (r <= 0) break; pos += r }
                                    bb.array().copyOf(bb.position())
                                }
                            }
                        }
                    } ?: return@withContext null to tr("Could not open the file.", "تعذّر فتح الملف.")
                    val icon = info.icon?.let { Thumbs.decode(it, 512) }
                        ?: return@withContext null to tr("\"${info.title ?: baseName}\" has no readable picture in clear: its pictures are inside the encrypted part of the package. Choose the theme's pack (.rar / .zip) or a picture of the theme instead.",
                            "«${info.title ?: baseName}» لا تحتوي صورة مقروءة: صورها داخل الجزء المشفّر من الحزمة. اختر حزمة الثيم (.rar / .zip) أو صورة للثيم بدلًا من ذلك.")
                    build(info.title?.takeIf { it.isNotBlank() } ?: baseName, "pkg", icon, null, info.contentId)?.let { it to null } ?: (null to tr("The picture has no visible colours.", "الصورة بلا ألوان مرئية."))
                }
                else -> {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, (40 shl 20) + 1) } ?: return@withContext null to tr("Could not open the file.", "تعذّر فتح الملف.")
                    if (bytes.size > 40 shl 20) return@withContext null to tr("The file is too big.", "الملف كبير جدًا.")
                    val bmp = Thumbs.decode(bytes, 1920) ?: return@withContext null to tr("Not a theme pack, a PS4 package or a readable picture.", "ليس حزمة ثيم ولا حزمة PS4 ولا صورة قابلة للقراءة.")
                    build(baseName, "image", null, bmp, "")?.let { it to null } ?: (null to tr("The picture has no visible colours.", "الصورة بلا ألوان مرئية."))
                }
            }
        } catch (e: PkgError) {
            null to if (e.notPkg) tr("This is not a PS4 package (.pkg).", "هذا ليس ملف حزمة PS4 (.pkg).") else e.message
        } catch (e: com.github.junrar.exception.RarException) {
            null to tr("Could not open the RAR (${e.message}). It may be damaged, password-protected, or a RAR version the app cannot read: extract it on a computer or re-pack it as ZIP.", "تعذّر فتح الـRAR (${e.message}). قد يكون تالفاً أو محميّاً بكلمة سر أو بإصدار لا يقرؤه التطبيق: استخرجه على حاسوب أو أعد ضغطه بصيغة ZIP.")
        } catch (e: java.util.zip.ZipException) {
            null to tr("Could not open the ZIP (${e.message}).", "تعذّر فتح الـZIP (${e.message}).")
        } catch (e: IOException) {
            null to tr("Could not read the file: ${e.message}. Copy it to the phone's storage and try again.", "تعذّرت قراءة الملف: ${e.message}. انسخه إلى ذاكرة الهاتف وأعد المحاولة.")
        } catch (e: SecurityException) { null to tr("No permission to read this file.", "لا إذن لقراءة هذا الملف.")
        } catch (e: OutOfMemoryError) { null to tr("The picture is too big for the phone's memory.", "الصورة كبيرة جدًا على ذاكرة الهاتف.") }
    }

    // ---------------------------------------------------------------- finding themes on the console
    /**
     * Looks for theme add-ons in the console's metadata folders (through ezRemote Server, read-only). NOT verified on a console: where PS4 firmware keeps the
     * metadata of an installed theme is not documented, so this walks the folders (3 levels, at most [maxListings] folders) and keeps every folder with a
     * param.sfo that looks like a theme (CATEGORY ac + home music / "theme" in the title / a store theme label). [ThemeScan.notes] says what the console refused.
     */
    suspend fun scanPs4(ps4: Ps4, maxListings: Int = 450, progress: (Int) -> Unit = {}): ThemeScan {
        val notes = ArrayList<String>(); val found = ArrayList<ThemeCandidate>()
        val roots = listOf("/user/appmeta", "/system_data/priv/appmeta", "/user/addcont")
        var listings = 0; var other = 0
        val stack = ArrayDeque<Pair<String, Int>>()
        roots.reversed().forEach { stack.add(it to 0) }
        while (stack.isNotEmpty() && listings < maxListings) {
            kotlin.coroutines.coroutineContext.ensureActive()          // the sheet was closed: stop asking the console
            val (dir, depth) = stack.removeLast()
            listings++; progress(listings)
            val l = try { EzServer.fsList(ps4, dir, 8000) }
                catch (e: EzServer.FsUnavailable) { if (depth == 0) notes.add("$dir: not allowed or not reachable (${e.message})"); continue }
                catch (e: EzError) { if (depth == 0) notes.add("$dir: ${e.message}"); continue }
                catch (e: Exception) { notes.add("$dir: ${e.message}"); continue }
            fun has(n: String) = l.firstOrNull { !it.isDir && it.name.equals(n, true) }?.name?.let { "$dir/$it" }
            val sfoPath = has("param.sfo")
            if (sfoPath != null) {
                val sfo = runCatching { Ps4Info.parseSfo(EzServer.fsRead(ps4, sfoPath, 0, 64 * 1024, 6000)) }.getOrDefault(emptyMap())
                if (sfo.isNotEmpty() && sfo["CATEGORY"] == "ac") {
                    if (PkgTheme.looksLikeTheme(sfo)) found.add(ThemeCandidate(dir, sfo["TITLE"] ?: dir.substringAfterLast('/'), sfo["CONTENT_ID"].orEmpty(), has("icon0.png"), has("pic0.png"), has("pic1.png")))
                    else other++
                }
            }
            if (depth < 3) l.filter { it.isDir }.forEach { stack.add("$dir/${it.name}" to depth + 1) }
        }
        if (stack.isNotEmpty()) notes.add("Stopped after $maxListings folders (the console has more).")
        if (other > 0) notes.add("$other other add-on(s) (not themes) were ignored.")
        return ThemeScan(found.distinctBy { it.dir }, notes, listings)
    }

    suspend fun fromCandidate(ps4: Ps4, c: ThemeCandidate): Pair<ThemeBuilt?, String?> = withContext(Dispatchers.IO) {
        try {
            val icon = c.iconPath?.let { Thumbs.load(ps4, it, 0, 512) }
            val pic = listOfNotNull(c.pic1, c.pic0).firstNotNullOfOrNull { Thumbs.load(ps4, it, 0, 1280) }
            if (icon == null && pic == null) return@withContext null to tr("The console has no readable picture for this theme.", "لا توجد صورة مقروءة لهذا الثيم على الجهاز.")
            build(c.title, "ps4", icon, pic, c.contentId)?.let { it to null } ?: (null to tr("The picture has no visible colours.", "الصورة بلا ألوان مرئية."))
        } catch (e: Exception) { null to (e.message ?: "error") }
    }
}
