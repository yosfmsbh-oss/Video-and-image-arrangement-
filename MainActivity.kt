package com.yousef.farz

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.heightIn
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
import kotlinx.coroutines.withContext

data class Media(val id: Long, val uri: Uri, val isVideo: Boolean, val folder: String)

data class Album(val id: Long, val name: String, val isVideo: Boolean)

/** All app state. Sorting decisions are saved on the device so they survive closing the app. */
class AppState(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("farz", Context.MODE_PRIVATE)

    val all = mutableStateListOf<Media>()
    val kept = mutableStateMapOf<Long, Long>() // media id -> album id (0 = no album)
    val trashed = mutableStateListOf<Long>()
    val albums = mutableStateListOf<Album>()
    val history = mutableStateListOf<Long>()
    var loaded by mutableStateOf(false)

    /** The device folder being sorted. null means every folder. */
    var folderFilter by mutableStateOf<String?>(null)

    val inScope by derivedStateOf { all.filter { folderFilter == null || it.folder == folderFilter } }
    val queue by derivedStateOf { inScope.filter { it.id !in kept && it.id !in trashed } }

    /** Device folders with how many items in each are still unsorted. */
    val folders by derivedStateOf {
        all.groupBy { it.folder }
            .map { (name, list) -> name to list.count { it.id !in kept && it.id !in trashed } }
            .sortedBy { it.first.lowercase() }
    }

    fun selectFolder(name: String?) {
        folderFilter = name
        prefs.edit().putString("folder", name).apply()
    }

    init {
        folderFilter = prefs.getString("folder", null)
        prefs.getStringSet("kept", emptySet())?.forEach { s ->
            val p = s.split("=")
            val id = p.getOrNull(0)?.toLongOrNull()
            val album = p.getOrNull(1)?.toLongOrNull()
            if (id != null && album != null) kept[id] = album
        }
        prefs.getStringSet("trashed", emptySet())?.forEach { s ->
            s.toLongOrNull()?.let { trashed.add(it) }
        }
        prefs.getStringSet("albums", emptySet())?.forEach { s ->
            val p = s.split("|", limit = 3)
            val id = p.getOrNull(0)?.toLongOrNull()
            if (id != null && p.size == 3) albums.add(Album(id, p[2], p[1] == "1"))
        }
        albums.sortBy { it.id }
        if (!prefs.getBoolean("init", false)) {
            albums.add(Album(1, "المفضلة", false))
            albums.add(Album(2, "سفر", false))
            albums.add(Album(3, "عائلة", false))
            albums.add(Album(4, "لقطات مميزة", true))
            albums.add(Album(5, "ذكريات", true))
            save()
        }
    }

    private fun save() {
        prefs.edit()
            .putStringSet("kept", kept.map { "${it.key}=${it.value}" }.toSet())
            .putStringSet("trashed", trashed.map { it.toString() }.toSet())
            .putStringSet("albums", albums.map { "${it.id}|${if (it.isVideo) 1 else 0}|${it.name}" }.toSet())
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
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
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
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val folder = c.getString(folderCol) ?: "غير مصنف"
                    val video = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out.add(Media(id, ContentUris.withAppendedId(base, id), video, folder))
                }
            }
            out
        }
        all.clear()
        all.addAll(list)
        val ids = list.map { it.id }.toSet()
        kept.keys.retainAll(ids)
        trashed.retainAll(ids)
        if (folderFilter != null && list.none { it.folder == folderFilter }) selectFolder(null)
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
        albums.add(Album(next, clean, isVideo))
        save()
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = AppState(applicationContext)
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
                Text("فرز الصور", fontSize = 26.sp, fontWeight = FontWeight.Bold)
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
    var showFolders by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (state.loaded && state.all.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                AssistChip(
                    onClick = { showFolders = true },
                    label = { Text("المجلد: ${state.folderFilter ?: "كل المجلدات"} ▾") }
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { SortContent(state) }
    }
    if (showFolders) {
        AlertDialog(
            onDismissRequest = { showFolders = false },
            title = { Text("اختر المجلد الذي تفرزه") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    item {
                        FolderRow("كل المجلدات", state.folders.sumOf { it.second }, state.folderFilter == null) {
                            state.selectFolder(null)
                            showFolders = false
                        }
                    }
                    items(state.folders, key = { it.first }) { (name, left) ->
                        FolderRow(name, left, state.folderFilter == name) {
                            state.selectFolder(name)
                            showFolders = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFolders = false }) { Text("إغلاق") } }
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
            (if (selected) "✓ " else "") + name,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        Text("$left", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SortContent(state: AppState) {
    val queue = state.queue
    val cur = queue.firstOrNull()
    var showAdd by remember { mutableStateOf(false) }

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
            SwipeCard(cur, state, Modifier.weight(1f).fillMaxWidth())
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundBtn("✕", Color(0xFFD2473B), 68.dp) { state.trash(cur) }
                RoundBtn("↶", MaterialTheme.colorScheme.onSurfaceVariant, 50.dp, state.history.isNotEmpty()) {
                    state.undo()
                }
                RoundBtn("✓", Color(0xFF1F8A5B), 68.dp) { state.keep(cur, 0) }
            }
            Text(
                if (cur.isVideo) "هذا فيديو، تظهر ألبومات الفيديو فقط" else "هذه صورة، تظهر ألبومات الصور فقط",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.albums.filter { it.isVideo == cur.isVideo }) { a ->
                    AssistChip(onClick = { state.keep(cur, a.id) }, label = { Text(a.name) })
                }
                item {
                    AssistChip(onClick = { showAdd = true }, label = { Text("+ ألبوم جديد") })
                }
            }
            Text(
                "اسحب يميناً للاحتفاظ، وللأعلى للحذف",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            .pointerInput(m.id) {
                detectDragGestures(
                    onDragEnd = {
                        val o = off
                        if (o.y < -threshold && -o.y > abs(o.x)) {
                            state.trash(m)
                        } else if (o.x > threshold) {
                            state.keep(m, 0)
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
                "▶ فيديو",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
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
                    AlbumTile(a.name, if (a.isVideo) "فيديو" else "صور", list.size, list.firstOrNull(), loader) {
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
