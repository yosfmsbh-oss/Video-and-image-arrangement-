package com.yousef.farz

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.view.TextureView
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.key
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Media(val id: Long, val uri: Uri, val isVideo: Boolean, val folder: String, val path: String)

/** An album made in the app. When [path] is set it stands for a real folder on the phone. */
data class Album(val id: Long, val name: String, val isVideo: Boolean, val path: String? = null)

/** Android only lets apps move photos into DCIM and Pictures, and videos into DCIM and Movies. */
fun canMoveTo(path: String, isVideo: Boolean): Boolean =
    path.startsWith("DCIM/") || path.startsWith(if (isVideo) "Movies/" else "Pictures/")

fun canMoveAny(path: String): Boolean = canMoveTo(path, false) || canMoveTo(path, true)

/** The real phone folder for an album made in the app: photos go to Pictures, videos to Movies. */
fun folderPathFor(name: String, isVideo: Boolean): String {
    val safe = name.replace(Regex("[\\\\/:*?\"<>|\\n]"), " ").trim().trim('.').ifEmpty { "Album" }
    return (if (isVideo) "Movies/" else "Pictures/") + safe + "/"
}

/** All app state. Sorting decisions are saved on the device so they survive closing the app. */
class AppState(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("farz", Context.MODE_PRIVATE)

    val all = mutableStateListOf<Media>()
    val kept = mutableStateMapOf<Long, Long>() // media id -> album id (0 = no album)
    val trashed = mutableStateListOf<Long>()
    val albums = mutableStateListOf<Album>()
    val history = mutableStateListOf<Long>()
    var loaded by mutableStateOf(false)

    /** True while the app is on screen, so videos stop when you leave it. */
    var foreground by mutableStateOf(true)
    var muted by mutableStateOf(prefs.getBoolean("muted", false))

    fun toggleMute() {
        muted = !muted
        prefs.edit().putBoolean("muted", muted).apply()
    }

    /** The device folders being sorted. Empty means every folder. */
    var selectedFolders by mutableStateOf<Set<String>>(emptySet())

    val inScope by derivedStateOf {
        all.filter { selectedFolders.isEmpty() || it.folder in selectedFolders }
    }
    val queue by derivedStateOf { inScope.filter { it.id !in kept && it.id !in trashed } }

    /** Device folders with how many items in each are still unsorted. */
    val folders by derivedStateOf {
        all.groupBy { it.folder }
            .map { (name, list) -> name to list.count { it.id !in kept && it.id !in trashed } }
            .sortedBy { it.first.lowercase() }
    }

    private fun setFolders(set: Set<String>) {
        selectedFolders = set
        prefs.edit().putStringSet("folders", set).apply()
    }

    fun toggleFolder(name: String) {
        setFolders(if (name in selectedFolders) selectedFolders - name else selectedFolders + name)
    }

    fun clearFolders() = setFolders(emptySet())

    init {
        selectedFolders = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
        prefs.getStringSet("kept", emptySet())?.forEach { s ->
            val p = s.split("=")
            val id = p.getOrNull(0)?.toLongOrNull()
            val album = p.getOrNull(1)?.toLongOrNull()
            if (id != null && album != null) kept[id] = album
        }
        prefs.getStringSet("trashed", emptySet())?.forEach { s ->
            s.toLongOrNull()?.let { trashed.add(it) }
        }
        prefs.getString("history", "")?.split(",")?.forEach { s ->
            s.toLongOrNull()?.let { history.add(it) }
        }
        prefs.getStringSet("albums", emptySet())?.forEach { s ->
            val p = s.split("|", limit = 3)
            val id = p.getOrNull(0)?.toLongOrNull()
            if (id != null && p.size == 3) albums.add(Album(id, p[2], p[1] == "1"))
        }
        prefs.getStringSet("devalbums", emptySet())?.forEach { s ->
            // new format: id|type|path|name. Older format: id|path|name (photos).
            val p = s.split("|", limit = 4)
            val id = p.getOrNull(0)?.toLongOrNull()
            if (id != null && p.size == 4) albums.add(Album(id, p[3], p[1] == "1", p[2]))
            else if (id != null && p.size == 3) albums.add(Album(id, p[2], !canMoveTo(p[1], false), p[1]))
        }
        albums.sortBy { it.id }
        // Albums made by older versions only lived inside the app: give them a real phone folder.
        var migrated = false
        for (i in albums.indices) {
            val a = albums[i]
            if (a.path == null) {
                albums[i] = a.copy(path = folderPathFor(a.name, a.isVideo))
                migrated = true
            }
        }
        if (migrated) save()
        if (!prefs.getBoolean("init", false)) {
            albums.add(Album(1, "المفضلة", false, folderPathFor("المفضلة", false)))
            albums.add(Album(2, "سفر", false, folderPathFor("سفر", false)))
            albums.add(Album(3, "عائلة", false, folderPathFor("عائلة", false)))
            albums.add(Album(4, "لقطات مميزة", true, folderPathFor("لقطات مميزة", true)))
            albums.add(Album(5, "ذكريات", true, folderPathFor("ذكريات", true)))
            save()
        }
    }

    private fun save() {
        prefs.edit()
            .putStringSet("kept", kept.map { "${it.key}=${it.value}" }.toSet())
            .putStringSet("trashed", trashed.map { it.toString() }.toSet())
            .putStringSet(
                "albums",
                albums.filter { it.path == null }.map { "${it.id}|${if (it.isVideo) 1 else 0}|${it.name}" }.toSet()
            )
            .putStringSet(
                "devalbums",
                albums.filter { it.path != null }
                    .map { "${it.id}|${if (it.isVideo) 1 else 0}|${it.path}|${it.name}" }.toSet()
            )
            .putString("history", history.takeLast(300).joinToString(","))
            .putBoolean("init", true)
            .apply()
    }

    suspend fun load() {
        val list = withContext(Dispatchers.IO) {
            val out = ArrayList<Media>()
            val files = MediaStore.Files.getContentUri("external")
            val proj = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
            val args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            )
            ctx.contentResolver.query(
                files, proj, sel, args,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val folderCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val folder = c.getString(folderCol) ?: "غير مصنف"
                    val path = c.getString(pathCol) ?: ""
                    val video = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out.add(Media(id, ContentUris.withAppendedId(base, id), video, folder, path))
                }
            }
            out
        }
        all.clear()
        all.addAll(list)
        val ids = list.map { it.id }.toSet()
        kept.keys.retainAll(ids)
        trashed.retainAll(ids)
        val existing = list.map { it.folder }.toSet()
        if (!existing.containsAll(selectedFolders)) setFolders(selectedFolders.intersect(existing))
        loaded = true
        save()
    }

    fun keep(m: Media, albumId: Long) {
        kept[m.id] = albumId
        history.add(m.id)
        save()
    }

    fun trash(m: Media) {
        kept.remove(m.id)
        trashed.add(m.id)
        history.add(m.id)
        save()
    }

    fun undo() {
        val id = history.removeLastOrNull() ?: return
        kept.remove(id)
        trashed.remove(id)
        save()
    }

    fun restore(id: Long) {
        kept.remove(id)
        trashed.remove(id)
        save()
    }

    fun addAlbum(name: String, isVideo: Boolean) {
        val clean = name.replace("|", " ").replace("\n", " ").trim()
        if (clean.isEmpty() || albums.any { it.name == clean && it.isVideo == isVideo }) return
        val next = (albums.maxOfOrNull { it.id } ?: 0L) + 1
        albums.add(Album(next, clean, isVideo, folderPathFor(clean, isVideo)))
        save()
    }

    /** Phone folders that photos or videos can be moved into: path to display name, with item count. */
    val deviceFolders by derivedStateOf {
        all.filter { it.path.isNotEmpty() && canMoveAny(it.path) }
            .groupBy { it.path }
            .map { (path, list) -> Triple(path, list.first().folder, list.size) }
            .sortedBy { it.second.lowercase() }
    }

    fun toggleDeviceAlbum(path: String, name: String, isVideo: Boolean) {
        val existing = albums.firstOrNull { it.path == path }
        if (existing != null) {
            albums.remove(existing)
        } else {
            val next = (albums.maxOfOrNull { it.id } ?: 0L) + 1
            albums.add(Album(next, name.replace("|", " ").replace("\n", " "), isVideo, path))
        }
        save()
    }

    fun setDeviceAlbumType(path: String, isVideo: Boolean) {
        val i = albums.indexOfFirst { it.path == path }
        if (i >= 0) {
            albums[i] = albums[i].copy(isVideo = isVideo)
            save()
        }
    }

    /** Kept items whose album is a phone folder they are not in yet: item and target path. */
    val pendingMoves by derivedStateOf {
        all.mapNotNull { m ->
            val albumId = kept[m.id] ?: return@mapNotNull null
            val target = albums.firstOrNull { it.id == albumId }?.path ?: return@mapNotNull null
            if (m.path != target) m to target else null
        }
    }

    /**
     * Moves each item to its album folder. When Android refuses a direct move (files from other apps,
     * other folders, or a name clash), the file is copied into the album folder instead and the
     * original is queued for the device trash, so the result is the same as a move.
     */
    suspend fun moveFiles(plan: List<Pair<Media, String>>): Int {
        val copied = ArrayList<Long>()
        val bad = withContext(Dispatchers.IO) {
            var failed = 0
            plan.forEach { (m, path) ->
                var ok = false
                try {
                    val v = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
                    ok = ctx.contentResolver.update(m.uri, v, null, null) > 0
                } catch (e: Exception) {
                    ok = false
                }
                if (!ok) {
                    if (copyInto(m, path)) copied.add(m.id) else failed++
                }
            }
            failed
        }
        if (copied.isNotEmpty()) {
            copied.forEach { if (it !in trashed) trashed.add(it) }
            save()
        }
        return bad
    }

    private fun copyInto(m: Media, path: String): Boolean {
        val cr = ctx.contentResolver
        var name: String? = null
        var mime: String? = null
        try {
            cr.query(
                m.uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    mime = c.getString(1)
                }
            }
        } catch (e: Exception) {
            return false
        }
        val base = if (m.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name ?: "farz_${m.id}")
            put(MediaStore.MediaColumns.MIME_TYPE, mime ?: if (m.isVideo) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = try {
            cr.insert(base, values)
        } catch (e: Exception) {
            null
        } ?: return false
        return try {
            val input = cr.openInputStream(m.uri) ?: throw IllegalStateException("no input")
            val output = cr.openOutputStream(target) ?: throw IllegalStateException("no output")
            input.use { i -> output.use { o -> i.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            cr.update(target, done, null, null)
            true
        } catch (e: Exception) {
            try { cr.delete(target, null, null) } catch (_: Exception) {}
            false
        }
    }

    /** Called after the system confirmed that the queued items were moved to the device trash. */
    fun onDeleted() {
        val gone = trashed.toSet()
        all.removeAll { it.id in gone }
        trashed.clear()
        history.removeAll { it in gone }
        save()
    }
}

class MainActivity : ComponentActivity() {
    private var appState: AppState? = null

    override fun onStart() {
        super.onStart()
        appState?.foreground = true
    }

    override fun onStop() {
        super.onStop()
        appState?.foreground = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = AppState(applicationContext)
        appState = state
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                App(state)
            }
        }
    }
}

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

@Composable
fun App(state: AppState) {
    val ctx = LocalContext.current
    fun hasPerm() = mediaPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasPerm()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasPerm() }

    LaunchedEffect(granted) { if (granted) state.load() }

    Surface(Modifier.fillMaxSize()) {
        if (granted) {
            Main(state)
        } else {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("ترتيب الاستديو", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    "يحتاج التطبيق إذن الوصول إلى الصور والفيديو ليعرضها لك وتفرزها.",
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Button(onClick = { permLauncher.launch(mediaPermissions()) }) { Text("السماح بالوصول") }
            }
        }
    }
}

@Composable
fun Main(state: AppState) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val labels = listOf("فرز", "الألبومات", "المحذوفات")
    val glyphs = listOf("⇆", "▦", "✕")
    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { i, t ->
                    val n = when (i) {
                        0 -> state.queue.size
                        1 -> state.kept.size
                        else -> state.trashed.size
                    }
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(glyphs[i], fontSize = 20.sp) },
                        label = { Text("$t ($n)") }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> SortScreen(state)
                1 -> AlbumsScreen(state)
                else -> TrashScreen(state)
            }
        }
    }
}

@Composable
fun rememberLoader(): ImageLoader {
    val ctx = LocalContext.current
    return remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
}

@Composable
fun Centered(text: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
fun RoundBtn(
    label: String,
    color: Color,
    size: Dp = 60.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) color else color.copy(alpha = 0.3f),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SortScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFolders by remember { mutableStateOf(false) }
    var movePlan by remember { mutableStateOf<List<Pair<Media, String>>>(emptyList()) }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            state.onDeleted()
            Toast.makeText(ctx, "تم تنفيذ الفرز", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(ctx, "لم يُحذف شيء", Toast.LENGTH_LONG).show()
        }
    }

    fun runTrash() {
        val items = state.all.filter { it.id in state.trashed }
        if (items.isEmpty()) {
            Toast.makeText(ctx, "تم تنفيذ الفرز", Toast.LENGTH_LONG).show()
            return
        }
        val pi = MediaStore.createTrashRequest(ctx.contentResolver, items.map { it.uri }, true)
        trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    val moveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        val plan = movePlan
        movePlan = emptyList()
        if (r.resultCode == Activity.RESULT_OK) {
            scope.launch {
                val failed = state.moveFiles(plan)
                state.load()
                if (failed > 0) {
                    Toast.makeText(ctx, "تعذر نقل $failed من الملفات", Toast.LENGTH_LONG).show()
                }
                runTrash()
            }
        } else {
            Toast.makeText(ctx, "أُلغي التنفيذ", Toast.LENGTH_LONG).show()
        }
    }

    fun execute() {
        val plan = state.pendingMoves
        if (plan.isEmpty()) {
            runTrash()
        } else {
            movePlan = plan
            val pi = MediaStore.createWriteRequest(ctx.contentResolver, plan.map { it.first.uri })
            moveLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    val pendingCount = state.pendingMoves.size + state.trashed.size

    Column(Modifier.fillMaxSize()) {
        if (state.loaded && state.all.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                AssistChip(
                    onClick = { showFolders = true },
                    label = {
                        val sel = state.selectedFolders
                        Text(
                            "المجلدات: " + when (sel.size) {
                                0 -> "الكل"
                                1 -> sel.first()
                                else -> "${sel.size} مجلدات"
                            } + " ▾"
                        )
                    }
                )
            }
        }
        if (state.loaded && pendingCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(onClick = { execute() }) {
                    Text("تنفيذ الفرز ($pendingCount)")
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { SortContent(state) }
    }
    if (showFolders) {
        AlertDialog(
            onDismissRequest = { showFolders = false },
            title = { Text("اختر المجلدات التي تفرزها") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item {
                        FolderRow("كل المجلدات", state.folders.sumOf { it.second }, state.selectedFolders.isEmpty()) {
                            state.clearFolders()
                        }
                    }
                    items(state.folders, key = { it.first }) { (name, left) ->
                        FolderRow(name, left, name in state.selectedFolders) {
                            state.toggleFolder(name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFolders = false }) { Text("تم") } }
        )
    }
}

@Composable
fun FolderRow(name: String, left: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            (if (selected) "☑ " else "☐ ") + name,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        Text("$left", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortContent(state: AppState) {
    val queue = state.queue
    val cur = queue.firstOrNull()
    var showAdd by remember { mutableStateOf(false) }
    var showDevice by remember { mutableStateOf(false) }

    if (!state.loaded) {
        Centered("جاري التحميل…")
    } else if (cur == null) {
        Centered(if (state.all.isEmpty()) "لا توجد صور أو فيديوهات في الجهاز" else "خلصت كل الملفات") {
            if (state.history.isNotEmpty()) {
                TextButton(onClick = { state.undo() }) { Text("تراجع عن آخر إجراء") }
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "${state.inScope.size - queue.size} من ${state.inScope.size} تم فرزها",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            key(cur.id) { SwipeCard(cur, state, Modifier.weight(1f).fillMaxWidth()) }
            Text(
                if (cur.isVideo) "هذا فيديو، تظهر ألبومات الفيديو فقط" else "هذه صورة، تظهر ألبومات الصور فقط",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Chips wrap onto several rows and scroll, so many folders fit at once.
            // The list is built here, outside the FlowRow, so it is rebuilt for every file:
            // photos get only photo albums, videos only video albums.
            val isVideoNow = cur.isVideo
            val shownAlbums = state.albums.filter {
                it.isVideo == isVideoNow && (it.path == null || canMoveTo(it.path, isVideoNow))
            }.toList()
            key(cur.id, isVideoNow) {
            FlowRow(
                Modifier.fillMaxWidth().heightIn(max = 190.dp).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                shownAlbums.forEach { a ->
                    AssistChip(
                        onClick = { state.keep(cur, a.id) },
                        label = { Text("📁 ${a.name}") }
                    )
                }
                AssistChip(onClick = { showAdd = true }, label = { Text("+ ألبوم جديد") })
                AssistChip(onClick = { showDevice = true }, label = { Text("+ مجلد من الجوال") })
            }
            }
            Text(
                "يميناً للاحتفاظ، للأعلى للحذف، يساراً للرجوع للسابق",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showDevice) {
            AlertDialog(
                onDismissRequest = { showDevice = false },
                title = { Text("مجلدات الجوال للنقل إليها") },
                text = {
                    Column {
                        Text(
                            "علّم المجلدات التي تريدها بين الأزرار، وحدد نوع كل مجلد: صور أو فيديو. تظهر المجلدات التي يسمح أندرويد بالنقل إليها.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn(Modifier.heightIn(max = 340.dp)) {
                            items(state.deviceFolders, key = { it.first }) { (path, name, count) ->
                                val album = state.albums.firstOrNull { it.path == path }
                                val on = album != null
                                val photoOk = canMoveTo(path, false)
                                val videoOk = canMoveTo(path, true)
                                val startVideo = if (cur.isVideo) videoOk else !photoOk
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable { state.toggleDeviceAlbum(path, name, startVideo) }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                (if (on) "☑ " else "☐ ") + name,
                                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                path,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (album != null) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(top = 6.dp)
                                        ) {
                                            if (photoOk) {
                                                FilterChip(
                                                    selected = !album.isVideo,
                                                    onClick = { state.setDeviceAlbumType(path, false) },
                                                    label = { Text("صور") }
                                                )
                                            }
                                            if (videoOk) {
                                                FilterChip(
                                                    selected = album.isVideo,
                                                    onClick = { state.setDeviceAlbumType(path, true) },
                                                    label = { Text("فيديو") }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showDevice = false }) { Text("تم") } }
            )
        }
        if (showAdd) {
            AddAlbumDialog(
                defaultVideo = cur.isVideo,
                onDismiss = { showAdd = false },
                onAdd = { n, v ->
                    state.addAlbum(n, v)
                    showAdd = false
                }
            )
        }
    }
}

@Composable
fun SwipeCard(m: Media, state: AppState, modifier: Modifier) {
    val loader = rememberLoader()
    var off by remember(m.id) { mutableStateOf(Offset.Zero) }
    val threshold = 170f
    Box(
        modifier
            .graphicsLayer {
                translationX = off.x
                translationY = off.y
                rotationZ = off.x / 40f
            }
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
    ) {
        val ctx = LocalContext.current
        var paused by remember(m.id) { mutableStateOf(false) }
        var ratio by remember(m.id) { mutableStateOf(0f) }
        // One player per video, drawn on a TextureView so it moves and rotates with the card.
        val exo: ExoPlayer? = if (m.isVideo) {
            remember(m.id) {
                ExoPlayer.Builder(ctx).build().apply {
                    setMediaItem(MediaItem.fromUri(m.uri))
                    repeatMode = Player.REPEAT_MODE_ONE
                    playWhenReady = false
                    prepare()
                }
            }
        } else {
            null
        }
        if (exo != null) {
            DisposableEffect(exo) {
                val l = object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        }
                    }
                }
                exo.addListener(l)
                onDispose {
                    exo.removeListener(l)
                    exo.release()
                }
            }
            LaunchedEffect(exo, paused, state.foreground) {
                exo.playWhenReady = !paused && state.foreground
            }
            LaunchedEffect(exo, state.muted) {
                exo.volume = if (state.muted) 0f else 1f
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // A fresh TextureView for every video, always wired to this video's player;
                // otherwise a new player would play sound only while the picture stays frozen.
                key(m.id) {
                    AndroidView(
                        factory = { c -> TextureView(c) },
                        update = { tv ->
                            if (tv.tag !== exo) {
                                tv.tag = exo
                                exo.setVideoTextureView(tv)
                            }
                        },
                        modifier = if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            // The whole photo is shown, never cropped.
            AsyncImage(
                model = m.uri,
                imageLoader = loader,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Transparent layer on top that receives the swipes and the tap, since the video view would swallow them.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(m.id) {
                    detectTapGestures {
                        if (m.isVideo) paused = !paused
                    }
                }
                .pointerInput(m.id) {
                    detectDragGestures(
                        onDragEnd = {
                            val o = off
                            if (o.y < -threshold && -o.y > abs(o.x)) {
                                state.trash(m)
                            } else if (o.x > threshold) {
                                state.keep(m, 0)
                            } else if (o.x < -threshold) {
                                off = Offset.Zero
                                state.undo()
                            } else {
                                off = Offset.Zero
                            }
                        },
                        onDragCancel = { off = Offset.Zero }
                    ) { change, drag ->
                        change.consume()
                        off += drag
                    }
                }
        )
        if (m.isVideo) {
            Text(
                if (paused) "متوقف · اضغط للتشغيل" else "فيديو · اضغط للإيقاف",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Text(
                if (state.muted) "🔇" else "🔊",
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .background(Color(0x99000000), CircleShape)
                    .clickable { state.toggleMute() }
                    .padding(10.dp)
            )
        }
        Text(
            "احتفظ",
            color = Color(0xFF1F8A5B),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .alpha((off.x / threshold).coerceIn(0f, 1f))
                .background(Color(0xDDFFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Text(
            "السابق",
            color = Color(0xFF1F6F8B),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .alpha((-off.x / threshold).coerceIn(0f, 1f))
                .background(Color(0xDDFFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Text(
            "احذف",
            color = Color(0xFFD2473B),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(20.dp)
                .alpha((-off.y / threshold).coerceIn(0f, 1f))
                .background(Color(0xDDFFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AddAlbumDialog(defaultVideo: Boolean, onDismiss: () -> Unit, onAdd: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var video by remember { mutableStateOf(defaultVideo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ألبوم جديد") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("اسم الألبوم") }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    FilterChip(selected = !video, onClick = { video = false }, label = { Text("صور") })
                    FilterChip(selected = video, onClick = { video = true }, label = { Text("فيديو") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, video) }) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun Thumb(m: Media, loader: ImageLoader, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = m.uri,
            imageLoader = loader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (m.isVideo) {
            Text(
                "▶",
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
            )
        }
    }
}

@Composable
fun AlbumsScreen(state: AppState) {
    var filter by remember { mutableIntStateOf(0) } // 0 all, 1 photos, 2 videos
    var open by remember { mutableStateOf<Long?>(null) } // -1 = all kept
    var selected by remember { mutableStateOf<Media?>(null) }
    val loader = rememberLoader()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf<Pair<List<Uri>, String>?>(null) }
    val moveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        val job = moving
        moving = null
        if (r.resultCode == Activity.RESULT_OK && job != null) {
            scope.launch {
                val failed = withContext(Dispatchers.IO) {
                    var bad = 0
                    job.first.forEach { uri ->
                        try {
                            val v = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, job.second) }
                            if (ctx.contentResolver.update(uri, v, null, null) < 1) bad++
                        } catch (e: Exception) {
                            bad++
                        }
                    }
                    bad
                }
                state.load()
                Toast.makeText(
                    ctx,
                    if (failed == 0) "تم النقل" else "تعذر نقل $failed من الملفات",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun matches(video: Boolean) = filter == 0 || (filter == 1 && !video) || (filter == 2 && video)

    val opened = open
    BackHandler(enabled = opened != null) { open = null }

    if (opened != null) {
        val album = state.albums.firstOrNull { it.id == opened }
        val shown = state.all.filter {
            val a = state.kept[it.id]
            a != null && (if (opened == -1L) matches(it.isVideo) else a == opened)
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open = null }) { Text("رجوع") }
                Text(album?.name ?: "كل المحفوظات", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            val target = album?.path
            if (target != null) {
                val pending = shown.filter { it.path != target }
                Button(
                    enabled = pending.isNotEmpty(),
                    onClick = {
                        moving = pending.map { it.uri } to target
                        val pi = MediaStore.createWriteRequest(ctx.contentResolver, pending.map { it.uri })
                        moveLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }
                ) { Text("انقل ${pending.size} إلى مجلد الجوال") }
                Text(
                    "الفرز هنا داخل التطبيق فقط. اضغط الزر ليُنقل فعلياً إلى $target بعد تأكيد النظام.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (shown.isEmpty()) {
                Centered("لا يوجد شيء هنا بعد")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(shown, key = { it.id }) { m ->
                        Thumb(m, loader, Modifier.clickable { selected = m })
                    }
                }
            }
        }
    } else {
        val tiles = state.albums.filter { matches(it.isVideo) }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("الكل", "صور", "فيديو").forEachIndexed { i, t ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(t) })
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    val all = state.all.filter { state.kept[it.id] != null && matches(it.isVideo) }
                    AlbumTile("كل المحفوظات", "", all.size, all.firstOrNull(), loader) { open = -1L }
                }
                items(tiles, key = { it.id }) { a ->
                    val list = state.all.filter { state.kept[it.id] == a.id }
                    AlbumTile(a.name, (if (a.path != null) "مجلد " else "") + (if (a.isVideo) "فيديو" else "صور"), list.size, list.firstOrNull(), loader) {
                        open = a.id
                    }
                }
            }
        }
    }

    val sel = selected
    if (sel != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(if (sel.isVideo) "فيديو" else "صورة") },
            text = { Thumb(sel, loader, Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { state.restore(sel.id); selected = null }) { Text("إرجاع للفرز") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { state.trash(sel); selected = null }) { Text("حذف") }
                    TextButton(onClick = { selected = null }) { Text("إغلاق") }
                }
            }
        )
    }
}

@Composable
fun AlbumTile(
    name: String,
    type: String,
    count: Int,
    cover: Media?,
    loader: ImageLoader,
    onClick: () -> Unit
) {
    Card(onClick = onClick) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) {
                    AsyncImage(
                        model = cover.uri,
                        imageLoader = loader,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("فارغ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(if (type.isEmpty()) name else "$name · $type", fontWeight = FontWeight.SemiBold)
                Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun TrashScreen(state: AppState) {
    val ctx = LocalContext.current
    val loader = rememberLoader()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> if (r.resultCode == Activity.RESULT_OK) state.onDeleted() }

    val shown = state.all.filter { it.id in state.trashed }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            enabled = shown.isNotEmpty(),
            onClick = {
                val pi = MediaStore.createTrashRequest(ctx.contentResolver, shown.map { it.uri }, true)
                launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
        ) { Text("احذف من الجهاز (${shown.size})") }
        Text(
            "العناصر هنا لم تُحذف بعد. عند الضغط على الزر يطلب النظام تأكيدك ثم تنتقل إلى سلة الجهاز. اضغط على عنصر لإرجاعه إلى الفرز.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (shown.isEmpty()) {
            Centered("لا توجد عناصر محذوفة")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(shown, key = { it.id }) { m ->
                    Thumb(m, loader, Modifier.clickable { state.restore(m.id) })
                }
            }
        }
    }
}
