@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Light or dark exactly as the app decides it (the Appearance setting, else the phone). */
@Composable fun appDark(): Boolean = when (Store.theme.intValue) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }

private fun displayName(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Theme"

/** A small mock of the app (surface, card with progress, buttons) drawn with the scheme this theme would give, so the result is seen BEFORE it is applied. */
@Composable private fun ThemePreview(p: ThemeProfile) {
    val dark = appDark()
    val cs = remember(p, dark) { ThemeScheme.build(p.toSeed(), dark).toColorScheme() }
    val shapes = MaterialTheme.shapes; val typo = MaterialTheme.typography
    MaterialTheme(colorScheme = cs, shapes = shapes, typography = typo) {
        Surface(Modifier.fillMaxWidth(), color = cs.surface, shape = shapes.large, border = BorderStroke(1.dp, cs.outlineVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.name, style = typo.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Card(Modifier.fillMaxWidth(), shape = shapes.large, colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tr("Downloading", "جارٍ التحميل") + "  62%", color = cs.onSurface)
                        LinearProgressIndicator(progress = { 0.62f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = cs.primary, trackColor = cs.primary.copy(alpha = 0.22f))
                        Text("4.1 MB/s  •  ETA 12:40", style = typo.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}) { Lbl(tr("Pause", "إيقاف مؤقت")) }
                    FilledTonalButton(onClick = {}) { Lbl(tr("Files", "الملفات")) }
                    FilterChip(selected = true, onClick = {}, label = { Lbl(tr("Selected", "محدد")) })
                    AssistChip(onClick = {}, label = { Lbl(tr("Chip", "شريحة")) })
                }
                Card(Modifier.fillMaxWidth(), shape = shapes.large, colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
                    Text(tr("Primary container", "حاوية أساسية"), Modifier.padding(12.dp), color = cs.onPrimaryContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = shapes.small, color = cs.secondaryContainer) { Text(tr("Secondary", "ثانوي"), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = cs.onSecondaryContainer, style = typo.labelMedium) }
                    Surface(shape = shapes.small, color = cs.tertiaryContainer) { Text(tr("Tertiary", "ثالثي"), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = cs.onTertiaryContainer, style = typo.labelMedium) }
                }
            }
        }
    }
}

/** The colours found in the picture; tap one to make it the accent. */
@Composable private fun Swatches(p: ThemeProfile, onPick: (Int) -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurface
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        p.swatches.filter { (c, _) -> Ok.lch(c).let { it.c >= 0.03 && it.l in 0.15..0.95 } }.take(7).forEach { (c, _) ->
            val sel = c == p.primary
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c)).then(if (sel) Modifier.border(3.dp, outline, CircleShape) else Modifier.border(1.dp, outline.copy(alpha = 0.25f), CircleShape))
                .clickable { onPick(c) })
        }
    }
}

@Composable private fun Thumb(bmp: Bitmap?, size: Int = 56) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Ico(R.drawable.ic_palette, 24.dp, MaterialTheme.colorScheme.outline)
    }
}

@Composable fun ThemeScreen(nav: NavController) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val profiles by ThemeStore.profiles; val active by ThemeStore.active
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ThemeBuilt?>(null) }
    var showScan by remember { mutableStateOf(false) }
    val ps4 = Ps4Repo.active()

    fun finish(r: Pair<ThemeBuilt?, String?>) { pending = r.first; error = r.second; busy = false }
    val pkgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { busy = true; error = null; scope.launch { finish(ThemeStore.fromPkg(ctx, uri, displayName(ctx, uri))) } }
    }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { busy = true; error = null; scope.launch { finish(ThemeStore.fromImage(ctx, uri, displayName(ctx, uri))) } }
    }

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { BackHeader(tr("PS4 theme", "ثيم PS4"), nav) }
        item { Panel {
            Text(tr("Colour the app like one of your PS4 themes.", "لوّن التطبيق مثل أحد ثيمات الـPS4 لديك."), fontWeight = FontWeight.SemiBold)
            Dim(tr("The colours are taken from the theme's pictures (its icon, a preview, or a screenshot of your home screen) and turned into a full light / dark colour scheme with readable text. A PS4 theme's wallpaper and its own colour file are encrypted inside the package, so they cannot be read directly.",
                "تُؤخذ الألوان من صور الثيم (أيقونته أو معاينته أو لقطة شاشة لواجهتك الرئيسية) وتتحول إلى نظام ألوان فاتح / داكن كامل بنصوص مقروءة. أما خلفية ثيم PS4 وملف ألوانه فمشفّران داخل الحزمة ولا يمكن قراءتهما مباشرة."))
        } }
        item { Panel {
            SectionTitle(tr("Add a theme", "إضافة ثيم"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = !busy, onClick = { pkgPicker.launch(arrayOf("*/*")) }) { Ico(R.drawable.ic_package, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("From a theme .pkg", "من ملف ثيم .pkg")) }
                FilledTonalButton(enabled = !busy, onClick = { imgPicker.launch(arrayOf("image/*")) }) { Ico(R.drawable.ic_file, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("From a picture", "من صورة")) }
                FilledTonalButton(enabled = !busy && ps4 != null, onClick = { showScan = true }) { Ico(R.drawable.ic_console, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Find on my PS4", "بحث في الـPS4")) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Dim(tr("Most faithful: a screenshot of the PS4 home screen with the theme applied (PS4 captures can be saved to the phone from the PS4 information screen), then choose it with \"From a picture\".",
                "الأدق: لقطة شاشة للواجهة الرئيسية في الـPS4 والثيم مطبّق (يمكن حفظ لقطات الـPS4 على الهاتف من شاشة معلومات PS4)، ثم اخترها بـ«من صورة»."))
        } }
        pending?.let { b ->
            item(key = "review") { Panel {
                SectionTitle(tr("Review", "مراجعة"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(b.thumb, 72)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(b.profile.name, { pending = ThemeBuilt(b.profile.copy(name = it), b.thumb, b.info) }, singleLine = true, label = { Text(tr("Name", "الاسم")) }, modifier = Modifier.fillMaxWidth())
                        if (b.info.isNotBlank()) Dim(b.info, maxLines = 2)
                    }
                }
                Dim(tr("Accent colour (tap another colour to change it):", "اللون الأساسي (اضغط لونًا آخر لتغييره):"))
                Swatches(b.profile) { c -> pending = ThemeBuilt(ThemeStore.withPrimary(b.profile, c), b.thumb, b.info) }
                if (b.profile.mono) Dim(tr("This picture has almost no colour, so the scheme is neutral.", "هذه الصورة بلا ألوان تقريبًا، لذلك نظام الألوان محايد."))
                ThemePreview(b.profile)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = b.profile.name.isNotBlank(), onClick = { ThemeStore.save(b.profile, b.thumb, true); pending = null }) { Lbl(tr("Save and use", "حفظ واستخدام")) }
                    OutlinedButton(enabled = b.profile.name.isNotBlank(), onClick = { ThemeStore.save(b.profile, b.thumb, false); pending = null }) { Lbl(tr("Save only", "حفظ فقط")) }
                    TextButton(onClick = { pending = null }) { Lbl(tr("Discard", "تجاهل")) }
                }
            } }
        }
        item { SectionTitle(tr("Saved themes", "الثيمات المحفوظة")) }
        item {
            Card(Modifier.fillMaxWidth().clickable { ThemeStore.use(null) }, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = active == null, onClick = { ThemeStore.use(null) })
                    Column(Modifier.weight(1f)) { Text(tr("No PS4 theme", "بدون ثيم PS4"), fontWeight = FontWeight.SemiBold); Dim(tr("Phone colours or the default purple (Settings > Appearance)", "ألوان الهاتف أو البنفسجي الافتراضي (الإعدادات > المظهر)")) }
                }
            }
        }
        if (profiles.isEmpty()) item { Dim(tr("No saved themes yet.", "لا توجد ثيمات محفوظة بعد.")) }
        items(profiles, key = { it.id }) { p ->
            val th by produceState<Bitmap?>(null, p.id) { value = withContext(Dispatchers.IO) { ThemeStore.thumb(p.id) } }
            Card(Modifier.fillMaxWidth().clickable { ThemeStore.use(p.id) }, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = active?.id == p.id, onClick = { ThemeStore.use(p.id) })
                    Thumb(th)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Dim(when (p.source) { "pkg" -> tr("From a package", "من حزمة"); "ps4" -> tr("From the PS4", "من الـPS4"); else -> tr("From a picture", "من صورة") }, maxLines = 1)
                        Swatches(p) { c -> ThemeStore.applyPrimary(p.id, c) }
                    }
                    IconButton(onClick = { ThemeStore.delete(p.id) }) { Ico(R.drawable.ic_delete, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        active?.let { a -> item(key = "preview") { Column(Modifier.fillMaxWidth(),verticalArrangement = Arrangement.spacedBy(6.dp)) { Dim(tr("The app now looks like this:", "يبدو التطبيق الآن هكذا:")); ThemePreview(a) } } }
    }
    if (showScan && ps4 != null) ScanSheet(ps4, onClose = { showScan = false }) { c ->
        showScan = false; busy = true; error = null
        scope.launch { finish(ThemeStore.fromCandidate(ps4, c)) }
    }
}

@Composable private fun ScanSheet(ps4: Ps4, onClose: () -> Unit, onPick: (ThemeCandidate) -> Unit) {
    var reason by remember { mutableStateOf<String?>(null) }
    var res by remember { mutableStateOf<ThemeScan?>(null) }
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(ps4.id) {
        reason = withContext(Dispatchers.IO) { EzServer.prepareInfo(ps4) }
        if (reason == null) res = withContext(Dispatchers.IO) { ThemeStore.scanPs4(ps4) { n = it } }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(tr("Themes found on ${ps4.name}", "الثيمات الموجودة على ${ps4.name}"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            val r = res
            when {
                reason != null -> item { Text(Tx.t(reason!!)) }
                r == null -> item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Dim(tr("Looking through the console's folders…", "جارٍ البحث في مجلدات الجهاز…") + " $n") } }
                else -> {
                    if (r.items.isEmpty()) item { Column {
                        EmptyState(R.drawable.ic_palette, tr("No theme found in the folders the app can read.", "لم يُعثر على ثيم في المجلدات التي يستطيع التطبيق قراءتها."))
                        Dim(tr("Where the console keeps the information of an installed theme is not documented, so this search is a best effort. Use \"From a theme .pkg\" or \"From a picture\" instead, or add folders under PS4 information > Device > Extra folders (for example /user/addcont) and search again.",
                            "مكان حفظ معلومات الثيم المثبّت في الجهاز غير موثّق، لذلك هذا البحث بأفضل جهد. استخدم «من ملف ثيم .pkg» أو «من صورة»، أو أضف مجلدات في معلومات PS4 > الجهاز > مجلدات إضافية (مثل /user/addcont) وأعد البحث."))
                    } }
                    items(r.items, key = { it.dir }) { c ->
                        val paths = listOfNotNull(c.iconPath)
                        val icon by produceState<Bitmap?>(null, c.dir) { value = Thumbs.loadAny(ps4, paths, 128) }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(c) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Thumb(icon)
                            Column(Modifier.weight(1f)) { Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Dim(c.contentId.ifBlank { c.dir }, maxLines = 1) }
                        }
                    }
                    item { NoteList(r.notes) }
                }
            }
        }
    }
}
