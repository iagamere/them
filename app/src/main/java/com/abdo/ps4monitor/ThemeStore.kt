package com.abdo.ps4monitor
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
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
    val swatches: List<Pair<Int, Float>>, val createdAt: Long) {
    fun toSeed() = ThemeSeed(primary, secondary, tertiary, mono, darkPicture, swatches.map { (c, s) -> Ok.lch(c).let { l -> Swatch(c, s, l.l, l.c, l.h) } })
}

/** A theme-looking add-on found on the console. */
class ThemeCandidate(val dir: String, val title: String, val contentId: String, val iconPath: String?, val pic0: String?, val pic1: String?)
class ThemeScan(val items: List<ThemeCandidate>, val notes: List<String>, val listings: Int)
/** Result of reading one theme source: the profile to review (not saved yet), its thumbnail and what was read. */
class ThemeBuilt(val profile: ThemeProfile, val thumb: Bitmap?, val info: String)

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
    private lateinit var dir: File

    fun init(c: Context) {
        dir = File(c.filesDir, "themes").apply { mkdirs() }
        profiles.value = runCatching {
            val a = JSONArray(Store.sp.getString("ps4themes", "[]"))
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        val id = Store.sp.getString("ps4theme_active", null)
        active.value = profiles.value.firstOrNull { it.id == id }
    }

    private fun toJson(p: ThemeProfile) = JSONObject().put("id", p.id).put("name", p.name).put("src", p.source).put("p", p.primary)
        .put("s", p.secondary ?: JSONObject.NULL).put("t", p.tertiary ?: JSONObject.NULL).put("mono", p.mono).put("dp", p.darkPicture).put("at", p.createdAt)
        .put("sw", JSONArray(p.swatches.map { JSONArray().put(it.first).put(it.second.toDouble()) }))
    private fun fromJson(o: JSONObject) = ThemeProfile(o.getString("id"), o.getString("name"), o.optString("src"), o.getInt("p"),
        if (o.isNull("s")) null else o.getInt("s"), if (o.isNull("t")) null else o.getInt("t"), o.optBoolean("mono"), o.optBoolean("dp", true),
        o.optJSONArray("sw")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { x -> x.getInt(0) to x.getDouble(1).toFloat() } } } ?: emptyList(), o.optLong("at"))

    private fun persist() { Store.sp.edit().putString("ps4themes", JSONArray(profiles.value.map { toJson(it) }).toString()).putString("ps4theme_active", active.value?.id).apply() }

    fun thumbFile(id: String) = File(dir, "$id.jpg")
    fun thumb(id: String): Bitmap? = thumbFile(id).takeIf { it.exists() }?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path) }.getOrNull() }

    fun save(p: ThemeProfile, thumb: Bitmap?, use: Boolean) {
        thumb?.let { runCatching { thumbFile(p.id).outputStream().use { o -> it.compress(Bitmap.CompressFormat.JPEG, 88, o) } } }
        profiles.value = profiles.value.filter { it.id != p.id } + p
        if (use) active.value = p
        persist()
    }
    fun use(id: String?) { active.value = profiles.value.firstOrNull { it.id == id }; persist() }
    fun delete(id: String) {
        profiles.value = profiles.value.filter { it.id != id }; thumbFile(id).delete()
        if (active.value?.id == id) active.value = null
        persist()
    }
    fun rename(id: String, name: String) {
        profiles.value = profiles.value.map { if (it.id == id) it.copy(name = name) else it }
        if (active.value?.id == id) active.value = profiles.value.firstOrNull { it.id == id }
        persist()
    }
    /** The person tapped another swatch: same picture, another accent. */
    fun withPrimary(p: ThemeProfile, argb: Int): ThemeProfile = p.toSeed().withPrimary(argb).let {
        p.copy(primary = it.primary, secondary = it.secondary, tertiary = it.tertiary, mono = false)
    }
    fun applyPrimary(id: String, argb: Int) {
        val p = profiles.value.firstOrNull { it.id == id } ?: return
        val n = withPrimary(p, argb)
        profiles.value = profiles.value.map { if (it.id == id) n else it }
        if (active.value?.id == id) active.value = n
        persist()
    }

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

    /** Pictures -> profile. Null when no picture has a visible pixel. */
    private fun build(name: String, source: String, pictures: List<Bitmap>, info: String): ThemeBuilt? {
        if (pictures.isEmpty()) return null
        val seed = ThemeExtract.seed(pictures.fold(IntArray(0)) { a, b -> a + pixelsOf(b) }) ?: return null
        val p = ThemeProfile(java.util.UUID.randomUUID().toString(), name, source, seed.primary, seed.secondary, seed.tertiary, seed.monochrome, seed.darkPicture,
            seed.swatches.take(8).map { it.argb to it.share }, System.currentTimeMillis())
        return ThemeBuilt(p, small(pictures[0]), info)
    }

    /** A theme .pkg chosen on the phone. Only what is stored in the clear is read (see PkgTheme); nothing is decrypted. Returns the result or an error text. */
    suspend fun fromPkg(ctx: Context, uri: Uri, fileName: String): Pair<ThemeBuilt?, String?> = withContext(Dispatchers.IO) {
        try {
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
            val pics = info.pictures().mapNotNull { Thumbs.decode(it, 512) }
            if (pics.isEmpty()) return@withContext null to (if (info.title != null)
                tr("\"${info.title}\" has no readable picture in clear: its pictures are inside the encrypted part of the package. Choose a picture of the theme instead (a preview or a screenshot).",
                    "«${info.title}» لا تحتوي على صورة مقروءة: صورها داخل الجزء المشفّر من الحزمة. اختر صورة للثيم بدلًا من ذلك (معاينة أو لقطة شاشة).")
                else tr("No readable picture in this package.", "لا توجد صورة مقروءة في هذه الحزمة."))
            val name = info.title?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.').ifBlank { "Theme" }
            val note = buildString {
                append(info.contentId)
                if (!info.looksLikeTheme) append("  •  ").append(tr("this package does not look like a theme", "هذه الحزمة لا تبدو ثيمًا"))
            }
            build(name, "pkg", pics, note)?.let { it to null } ?: (null to tr("The picture has no visible colours.", "الصورة بلا ألوان مرئية."))
        } catch (e: PkgError) {
            null to if (e.notPkg) tr("This is not a PS4 package (.pkg).", "هذا ليس ملف حزمة PS4 (.pkg).") else e.message
        } catch (e: IOException) {
            null to tr("Could not read the file: ${e.message}. Copy it to the phone's storage and try again.", "تعذّرت قراءة الملف: ${e.message}. انسخه إلى ذاكرة الهاتف وأعد المحاولة.")
        } catch (e: SecurityException) { null to tr("No permission to read this file.", "لا إذن لقراءة هذا الملف.") }
    }

    /** Any picture: a preview of the theme, a screenshot of the PS4 home screen (the most faithful source), a wallpaper. */
    suspend fun fromImage(ctx: Context, uri: Uri, fileName: String): Pair<ThemeBuilt?, String?> = withContext(Dispatchers.IO) {
        try {
            val limit = 40 shl 20
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > limit) return@withContext null to tr("The picture is too big.", "الصورة كبيرة جدًا.") }
                out.toByteArray()
            } ?: return@withContext null to tr("Could not open the file.", "تعذّر فتح الملف.")
            val bmp = Thumbs.decode(bytes, 1024) ?: return@withContext null to tr("This is not a readable picture.", "هذه ليست صورة قابلة للقراءة.")
            build(fileName.substringBeforeLast('.').ifBlank { "Theme" }, "image", listOf(bmp), "")?.let { it to null } ?: (null to tr("The picture has no visible colours.", "الصورة بلا ألوان مرئية."))
        } catch (e: Exception) { null to (e.message ?: "error") }
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
            val pics = listOfNotNull(c.iconPath, c.pic0, c.pic1).mapNotNull { Thumbs.load(ps4, it, 0, 512) }
            if (pics.isEmpty()) return@withContext null to tr("The console has no readable picture for this theme.", "لا توجد صورة مقروءة لهذا الثيم على الجهاز.")
            build(c.title, "ps4", pics, c.contentId)?.let { it to null } ?: (null to tr("The picture has no visible colours.", "الصورة بلا ألوان مرئية."))
        } catch (e: Exception) { null to (e.message ?: "error") }
    }
}
