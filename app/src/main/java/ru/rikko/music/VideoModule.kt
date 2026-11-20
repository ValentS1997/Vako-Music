package ru.rikko.music

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class VideoDestination(val mode: String, val videos: List<LocalVideo>, val folderVolume: String? = null, val folderPath: String? = null)

object VideoModule {
    @Composable
    fun Screen(openSettings: () -> Unit, onOpenVideo: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val playlists = remember { VideoPlaylistStore(context) }
        val historyStore = remember { VideoHistoryStore(context) }
        val marks = remember { VideoMarksStore(context) }
        val navPrefs = remember { context.getSharedPreferences("vako_video_nav", 0) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        var allowed by remember { mutableStateOf(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) }
        var videos by remember { mutableStateOf<List<LocalVideo>>(emptyList()) }
        var playlistList by remember { mutableStateOf(playlists.all()) }
        var history by remember { mutableStateOf(historyStore.all()) }
        var error by remember { mutableStateOf("") }
        var selectedVolume by remember { mutableStateOf(navPrefs.getString("volume", null)) }
        var folder by remember { mutableStateOf(navPrefs.getString("folder", "").orEmpty()) }
        var tab by remember { mutableStateOf(0) } // folders, all, playlists, recent, favorites, bookmarks
        var favoriteUris by remember { mutableStateOf(marks.favorites()) }
        var bookmarks by remember { mutableStateOf(marks.bookmarks()) }
        var selectedPlaylist by remember { mutableStateOf<String?>(null) }
        var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
        var sort by remember { mutableStateOf(1) }
        var sortMenu by remember { mutableStateOf(false) }
        var refresh by remember { mutableStateOf(0) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    history = historyStore.all()
                    favoriteUris = marks.favorites()
                    bookmarks = marks.bookmarks()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        var inputKind by remember { mutableStateOf("") }
        var inputText by remember { mutableStateOf("") }
        var inputVideo by remember { mutableStateOf<LocalVideo?>(null) }
        var inputFolder by remember { mutableStateOf<String?>(null) }
        var target by remember { mutableStateOf<VideoDestination?>(null) }
        var playlistPicker by remember { mutableStateOf<List<LocalVideo>?>(null) }
        var pendingWrite by remember { mutableStateOf<Pair<List<VideoEdit>, String>?>(null) }
        var failedWrite by remember { mutableStateOf<Pair<List<VideoEdit>, String>?>(null) }
        var failedCopy by remember { mutableStateOf<Triple<List<LocalVideo>, String, String>?>(null) }
        var detail by remember { mutableStateOf<LocalVideo?>(null) }
        var newPlaylistItems by remember { mutableStateOf<List<String>>(emptyList()) }
        var pendingPlaylistDelete by remember { mutableStateOf<String?>(null) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            allowed = it
            if (it) refresh++
        }
        LaunchedEffect(selectedVolume, folder) {
            navPrefs.edit().putString("volume", selectedVolume).putString("folder", folder).apply()
        }
        val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                onOpenVideo()
                context.startActivity(Intent(context, VideoPlayerActivity::class.java).apply {
                    data = uri
                    putExtra(VideoPlayerActivity.EXTRA_TITLE, uri.lastPathSegment ?: "Видео")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        }
        val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val operation = pendingWrite
            pendingWrite = null
            if (result.resultCode == Activity.RESULT_OK && operation != null) {
                scope.launch {
                    val report = withContext(Dispatchers.IO) { VideoMediaManager.applyEdits(context, operation.first) }
                    failedWrite = if (report.failed.isEmpty()) null else report.failed to operation.second
                    error = "${operation.second}: ${report.changed} из ${operation.first.size}" +
                        if (report.failed.isEmpty()) "" else ". Не изменено: " +
                            report.failed.take(3).joinToString { it.name ?: it.uri.lastPathSegment ?: "видео" }
                    selectedUris = emptySet()
                    refresh++
                }
            } else error = "Изменение отменено"
        }
        val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                selectedUris = emptySet()
                refresh++
                error = "Видео удалено"
            } else error = "Удаление отменено"
        }
        fun write(edits: List<VideoEdit>, label: String) {
            if (edits.isEmpty()) { error = "В папке нет видео"; return }
            if (edits.map { it.uri }.distinct().size != edits.size) {
                error = "Одно видео попало в операцию несколько раз"; return
            }
            failedWrite = null
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    pendingWrite = edits to label
                    val request = MediaStore.createWriteRequest(context.contentResolver, edits.map { it.uri }.distinct())
                    writeLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } else scope.launch {
                    val report = withContext(Dispatchers.IO) { VideoMediaManager.applyEdits(context, edits) }
                    failedWrite = if (report.failed.isEmpty()) null else report.failed to label
                    error = "$label: ${report.changed} из ${edits.size}" +
                        if (report.failed.isEmpty()) "" else ". Не изменено: " +
                            report.failed.take(3).joinToString { it.name ?: it.uri.lastPathSegment ?: "видео" }
                    selectedUris = emptySet(); refresh++
                }
            } catch (exception: Exception) { error = exception.localizedMessage ?: "Не удалось изменить видео" }
        }
        fun delete(items: List<LocalVideo>) {
            if (items.isEmpty()) return
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val request = MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri }.distinct())
                    deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                } else error = "Удаление требует Android 11 или новее"
            } catch (exception: Exception) { error = exception.localizedMessage ?: "Не удалось удалить видео" }
        }
        fun share(items: List<LocalVideo>) {
            if (items.isEmpty()) return
            val uris = ArrayList(items.map { it.uri })
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Поделиться видео"))
        }
        fun open(video: LocalVideo, playlist: String? = null, queue: List<LocalVideo> = videos) {
            onOpenVideo()
            context.startActivity(Intent(context, VideoPlayerActivity::class.java).apply {
                data = video.uri
                putExtra(VideoPlayerActivity.EXTRA_TITLE, video.name)
                putExtra(VideoPlayerActivity.EXTRA_DURATION, video.durationMs)
                if (playlist != null) putExtra(VideoPlayerActivity.EXTRA_PLAYLIST, playlist)
                val position = queue.indexOfFirst { it.uri == video.uri }
                val nearby = if (position < 0) listOf(video) else
                    queue.subList((position - 250).coerceAtLeast(0), (position + 251).coerceAtMost(queue.size))
                putStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE, ArrayList(nearby.map { it.uri.toString() }))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
        fun openRecent(entry: VideoHistoryEntry) {
            val video = videos.firstOrNull { it.uri.toString() == entry.uri }
            if (video == null) {
                error = "Видео недоступно или удалено. Выберите другое видео."
                return
            }
            onOpenVideo()
            context.startActivity(Intent(context, VideoPlayerActivity::class.java).apply {
                data = video.uri
                putExtra(VideoPlayerActivity.EXTRA_TITLE, video.name)
                putExtra(VideoPlayerActivity.EXTRA_DURATION, video.durationMs)
                putExtra(VideoPlayerActivity.EXTRA_START_POSITION, entry.positionMs)
                val recentQueue = history.mapNotNull { item -> videos.firstOrNull { it.uri.toString() == item.uri } }
                putStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE, ArrayList(recentQueue.map { it.uri.toString() }))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
        fun openBookmark(bookmark: VideoBookmark) {
            val video = videos.firstOrNull { it.uri.toString() == bookmark.uri }
            if (video == null) { error = "Видео закладки недоступно"; return }
            onOpenVideo()
            context.startActivity(Intent(context, VideoPlayerActivity::class.java).apply {
                data = video.uri
                putExtra(VideoPlayerActivity.EXTRA_TITLE, video.name)
                putExtra(VideoPlayerActivity.EXTRA_DURATION, video.durationMs)
                putExtra(VideoPlayerActivity.EXTRA_START_POSITION, bookmark.positionMs)
                putStringArrayListExtra(VideoPlayerActivity.EXTRA_QUEUE, arrayListOf(video.uri.toString()))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
        fun sorted(list: List<LocalVideo>): List<LocalVideo> = when (sort) {
            0 -> list.sortedBy { it.name.lowercase() }
            2 -> list.sortedByDescending { it.durationMs }
            3 -> list.sortedByDescending { it.sizeBytes }
            else -> list.sortedByDescending { it.addedSeconds }
        }
        fun copyVideos(items: List<LocalVideo>, volume: String, path: String) {
            failedCopy = null
            scope.launch {
                try {
                    val report = withContext(Dispatchers.IO) {
                        VideoMediaManager.copy(context, items, volume, path)
                    }
                    failedCopy = if (report.failed.isEmpty()) null else Triple(report.failed, volume, path)
                    error = "Скопировано: ${report.copied} из ${items.size}" +
                        if (report.failed.isEmpty()) "" else ". Не скопированы: " +
                            report.failed.take(3).joinToString { it.name }
                    selectedUris = emptySet()
                    refresh++
                } catch (exception: Exception) {
                    error = exception.localizedMessage ?: "Ошибка копирования"
                }
            }
        }
        fun applyTarget(action: VideoDestination, volume: String, path: String) {
            try {
                val clean = path.trim('/')
                require(clean.isNotEmpty() && clean.split('/').all { it.isNotBlank() && it !in listOf(".", "..") }) { "Укажите папку назначения" }
                when (action.mode) {
                    "copy" -> copyVideos(action.videos, volume, clean)
                    "move" -> write(VideoMediaManager.move(action.videos, volume, clean), "Перемещено")
                    "move_folder" -> {
                        require(action.folderVolume == volume) { "Папку можно переместить только на том же накопителе" }
                        write(VideoMediaManager.moveFolder(videos, volume, action.folderPath!!, clean), "Папка перемещена")
                    }
                }
                target = null
            } catch (exception: Exception) { error = exception.localizedMessage ?: "Неверная папка" }
        }
        fun folderVideos(volume: String, path: String): List<LocalVideo> =
            videos.filter { it.volume == volume && (it.path == path || it.path.startsWith("$path/")) }

        BackHandler(enabled = (tab == 0 && selectedVolume != null) || (tab == 2 && selectedPlaylist != null) || selectedUris.isNotEmpty()) {
            when {
                selectedUris.isNotEmpty() -> selectedUris = emptySet()
                tab == 2 -> selectedPlaylist = null
                folder.isNotEmpty() -> folder = folder.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                else -> selectedVolume = null
            }
        }
        LaunchedEffect(allowed, refresh) {
            if (allowed) try {
                videos = withContext(Dispatchers.IO) { VideoStore.scan(context) }
                if (selectedVolume != null && videos.none { it.volume == selectedVolume }) {
                    selectedVolume = null; folder = ""
                } else if (selectedVolume != null) {
                    var candidate = folder.trimEnd('/')
                    while (candidate.isNotEmpty() && videos.none { it.volume == selectedVolume &&
                            (it.path == candidate || it.path.startsWith("$candidate/")) }) {
                        candidate = candidate.substringBeforeLast('/', "")
                    }
                    folder = if (candidate.isEmpty()) "" else "$candidate/"
                }
                playlistList = playlists.all()
                history = historyStore.all()
            } catch (exception: Exception) { error = exception.localizedMessage ?: "Не удалось прочитать видео" }
        }
        val videoByUri = videos.associateBy { it.uri.toString() }
        val selectedVideos = videos.filter { it.uri.toString() in selectedUris }
        val visibleVideos = when (tab) {
            1 -> sorted(videos)
            2 -> selectedPlaylist?.let { name -> playlistList.firstOrNull { it.name == name }?.items?.mapNotNull(videoByUri::get) }.orEmpty()
            3 -> history.mapNotNull { videoByUri[it.uri] }
            4 -> sorted(videos.filter { it.uri.toString() in favoriteUris })
            else -> if (selectedVolume == null) emptyList() else sorted(videos.filter { it.volume == selectedVolume && it.path == folder.trimEnd('/') })
        }
        val folders = if (tab != 0) emptyList() else if (selectedVolume == null) videos.map { it.volume }.distinct().sorted()
            else videos.asSequence().filter { it.volume == selectedVolume && it.path.startsWith(folder) }
                .mapNotNull { it.path.removePrefix(folder).substringBefore('/').takeIf(String::isNotEmpty) }.distinct().sorted().toList()

        Column(Modifier.fillMaxSize()) {
            Text("Видео", style = MaterialTheme.typography.titleLarge)
            if (!allowed) {
                Spacer(Modifier.height(8.dp))
                Text("Разрешите доступ к видео телефона и карты памяти.")
                Button(onClick = { permissionLauncher.launch(permission) }) { Text("Разрешить доступ к видео") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoAction("＋", "Открыть видеофайл", { fileLauncher.launch(arrayOf("video/*")) })
                if (allowed) VideoAction("⟳", "Обновить видео", { refresh++ })
            }
            if (allowed && history.isNotEmpty()) {
                TextButton(onClick = { openRecent(history.first()) }) { Text("▶ Продолжить последнее: ${history.first().title}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            failedWrite?.let { (edits, label) ->
                TextButton(onClick = { write(edits, label) }) {
                    Text("Повторить только неуспешные (${edits.size})")
                }
            }
            failedCopy?.let { (items, volume, path) ->
                TextButton(onClick = { copyVideos(items, volume, path) }) {
                    Text("Повторить копирование (${items.size})")
                }
            }
            if (!allowed) {
                TextButton(onClick = openSettings) { Text("Настроить разделы") }
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Папки", "Все", "Плейлисты", "Недавние", "Избранное", "Закладки").forEachIndexed { index, label ->
                    TextButton(onClick = { tab = index; selectedUris = emptySet() }) {
                        Text(label, color = if (tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (tab == 2) TextButton(onClick = { newPlaylistItems = emptyList(); inputKind = "create_playlist"; inputText = "" }) { Text("+") }
                else if (tab == 3) TextButton(onClick = { inputKind = "clear_history" }) { Text("Очистить") }
                else {
                    VideoAction("⇅", "Сортировка видео", { sortMenu = true })
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf("Имя", "Сначала новые", "Длительность", "Размер").forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { sort = index; sortMenu = false })
                        }
                    }
                }
            }
            if (selectedUris.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    TextButton(onClick = { selectedUris = emptySet() }) { Text("${selectedUris.size} ×") }
                    TextButton(onClick = { playlistPicker = selectedVideos }) { Text("В плейлист") }
                    TextButton(onClick = { target = VideoDestination("move", selectedVideos) }) { Text("Перенести") }
                    TextButton(onClick = { target = VideoDestination("copy", selectedVideos) }) { Text("Копировать") }
                    TextButton(onClick = { delete(selectedVideos) }) { Text("Удалить") }
                }
            }
            if (tab == 0 || (tab == 2 && selectedPlaylist != null)) {
                Row {
                    if (selectedVolume != null || selectedPlaylist != null) TextButton(onClick = {
                        if (tab == 2) selectedPlaylist = null
                        else if (folder.isNotEmpty()) folder = folder.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                        else selectedVolume = null
                    }) { Text("←") }
                    Text(
                        if (tab == 2) selectedPlaylist ?: "Плейлисты" else if (selectedVolume == null) "Накопители"
                        else if (folder.isEmpty()) volumeTitle(selectedVolume!!) else folder.trimEnd('/').substringAfterLast('/'),
                        modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold
                    )
                    if (tab == 0 && folder.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        var menu by remember(folder) { mutableStateOf(false) }
                        VideoAction("⋮", "Действия с текущей папкой", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Плейлист из папки") }, onClick = {
                                menu = false; inputKind = "folder_playlist"; inputText = folder.trimEnd('/').substringAfterLast('/'); inputFolder = folder.trimEnd('/')
                            })
                            DropdownMenuItem(text = { Text("Переименовать видео в папке") }, onClick = {
                                menu = false; inputKind = "rename_folder"; inputText = folder.trimEnd('/').substringAfterLast('/'); inputFolder = folder.trimEnd('/')
                            })
                            DropdownMenuItem(text = { Text("Переместить видео в папке") }, onClick = {
                                menu = false; target = VideoDestination("move_folder", emptyList(), selectedVolume, folder.trimEnd('/'))
                            })
                            DropdownMenuItem(text = { Text("Копировать видео из папки") }, onClick = {
                                menu = false; target = VideoDestination("copy", folderVideos(selectedVolume!!, folder.trimEnd('/')))
                            })
                            DropdownMenuItem(text = { Text("Удалить видео в папке") }, onClick = {
                                menu = false; delete(folderVideos(selectedVolume!!, folder.trimEnd('/')))
                            })
                        }
                    }
                }
            }
            if (videos.isEmpty() && tab != 2) Text("Видео не найдено. Отдельный файл можно открыть через системный выбор.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tab == 3 && history.isEmpty()) Text("История просмотра пока пуста.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tab == 4 && favoriteUris.isEmpty()) Text("Избранных видео пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tab == 5 && bookmarks.isEmpty()) Text("Закладок пока нет. Добавьте фрагмент из меню видеоплеера.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f)) {
                if (tab == 5) {
                    items(bookmarks, key = { "${it.uri}:${it.positionMs}" }) { mark ->
                        VideoRow(mark.label.ifBlank { mark.title }, "${mark.title} · ${VideoStore.formatDuration(mark.positionMs)}",
                            onClick = { openBookmark(mark) },
                            menu = listOf("Удалить закладку" to {
                                marks.removeBookmark(mark); bookmarks = marks.bookmarks()
                            }))
                    }
                } else if (tab == 2 && selectedPlaylist == null) {
                    items(playlistList, key = { it.name }) { playlist ->
                        VideoRow(playlist.name, "${playlist.items.size} видео", onClick = { selectedPlaylist = playlist.name },
                            menu = listOf(
                                "Переименовать" to { inputKind = "rename_playlist"; inputText = playlist.name; selectedPlaylist = playlist.name },
                                "Удалить плейлист" to { pendingPlaylistDelete = playlist.name }
                            ))
                    }
                } else {
                    items(folders) { name ->
                        val volume = selectedVolume
                        val path = if (volume == null) "" else (folder + name).trim('/')
                        VideoRow("▣  ${if (volume == null) volumeTitle(name) else name}",
                            if (volume == null) "Накопитель" else "Папка · ${folderVideos(volume, path).size} видео",
                            onClick = { if (volume == null) selectedVolume = name else folder += "$name/" },
                            menu = if (volume == null) emptyList() else listOf(
                                "Плейлист из папки" to { inputKind = "folder_playlist"; inputText = name; inputFolder = path },
                                "Переименовать видео в папке" to { inputKind = "rename_folder"; inputText = name; inputFolder = path },
                                "Переместить видео в папке" to { target = VideoDestination("move_folder", emptyList(), volume, path) },
                                "Копировать видео из папки" to { target = VideoDestination("copy", folderVideos(volume, path)) },
                                "Удалить видео в папке" to { delete(folderVideos(volume, path)) }
                            ))
                    }
                    items(visibleVideos, key = { it.uri.toString() }) { video ->
                        val uri = video.uri.toString()
                        val saved = history.firstOrNull { it.uri == uri }?.positionMs
                            ?: context.getSharedPreferences("vako_video", 0).getLong(uri, 0)
                        val details = buildString {
                            append(VideoStore.formatDuration(video.durationMs))
                            if (video.width > 0) append(" · ${video.width}×${video.height}")
                            if (saved > 10_000) append(" · Продолжить ${VideoStore.formatDuration(saved)}")
                        }
                        VideoRow(video.name, details,
                            checked = uri in selectedUris,
                            onClick = { if (selectedUris.isNotEmpty()) selectedUris = if (uri in selectedUris) selectedUris - uri else selectedUris + uri else if (tab == 3) openRecent(history.first { it.uri == uri }) else open(video, selectedPlaylist, visibleVideos) },
                            onLongClick = { selectedUris = selectedUris + uri },
                            menu = buildList {
                                add("Выбрать" to { selectedUris = selectedUris + uri })
                                add("В плейлист…" to { playlistPicker = listOf(video) })
                                add((if (uri in favoriteUris) "Убрать из избранного" else "В избранное") to {
                                    marks.toggleFavorite(uri); favoriteUris = marks.favorites()
                                })
                                if (selectedPlaylist != null) {
                                    add("Убрать из плейлиста" to {
                                        playlists.remove(selectedPlaylist!!, uri); playlistList = playlists.all()
                                    })
                                    add("Выше" to { playlists.move(selectedPlaylist!!, uri, -1); playlistList = playlists.all() })
                                    add("Ниже" to { playlists.move(selectedPlaylist!!, uri, 1); playlistList = playlists.all() })
                                }
                                add("Переименовать" to { inputKind = "rename_video"; inputText = video.name.substringBeforeLast('.'); inputVideo = video })
                                add("Переместить…" to { target = VideoDestination("move", listOf(video)) })
                                add("Копировать…" to { target = VideoDestination("copy", listOf(video)) })
                                add("Поделиться" to { share(listOf(video)) })
                                add("Сведения" to { detail = video })
                                add("Удалить" to { delete(listOf(video)) })
                            })
                    }
                }
            }
        }
        if (inputKind == "clear_history") {
            AlertDialog(onDismissRequest = { inputKind = "" }, title = { Text("Очистить историю видео?") },
                text = { Text("Список недавних просмотров будет удалён. Сами видео останутся на устройстве.") },
                confirmButton = { TextButton(onClick = { historyStore.clear(); history = emptyList(); inputKind = "" }) { Text("Очистить") } },
                dismissButton = { TextButton(onClick = { inputKind = "" }) { Text("Отмена") } })
        }
        if (inputKind.isNotEmpty() && inputKind != "clear_history") {
            val kind = inputKind
            AlertDialog(onDismissRequest = { inputKind = "" },
                title = { Text(when (kind) { "create_playlist", "folder_playlist" -> "Название видеоплейлиста"; "rename_playlist" -> "Переименовать плейлист"; "rename_video" -> "Переименовать видео"; "rename_folder" -> "Переименовать видео в папке"; else -> "Новая папка" }) },
                text = { OutlinedTextField(value = inputText, onValueChange = { inputText = it }, singleLine = true, label = { Text(if (kind == "new_destination") "Путь, например Movies/Мои видео" else "Название") }) },
                confirmButton = { TextButton(onClick = {
                    try {
                        when (kind) {
                            "create_playlist" -> playlists.create(inputText, newPlaylistItems)
                            "folder_playlist" -> playlists.create(inputText, folderVideos(selectedVolume!!, inputFolder!!).map { it.uri.toString() })
                            "rename_playlist" -> { playlists.rename(selectedPlaylist!!, inputText); selectedPlaylist = inputText.trim() }
                            "rename_video" -> write(listOf(VideoMediaManager.rename(inputVideo!!, inputText)), "Переименовано")
                            "rename_folder" -> {
                                write(VideoMediaManager.renameFolder(videos, selectedVolume!!, inputFolder!!, inputText), "Папка переименована")
                                if (folder.trimEnd('/') == inputFolder) folder = folder.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                            }
                            "new_destination" -> target?.let { action ->
                                val volume = action.folderVolume ?: action.videos.firstOrNull()?.volume ?: selectedVolume ?: "external_primary"
                                applyTarget(action, volume, inputText)
                            }
                        }
                        playlistList = playlists.all(); inputKind = ""
                    } catch (exception: Exception) { error = exception.localizedMessage ?: "Ошибка" }
                }) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = { inputKind = "" }) { Text("Отмена") } })
        }
        target?.takeIf { inputKind != "new_destination" }?.let { action ->
            val available = videos.map { it.volume to it.path }.filter { it.second.isNotBlank() }.distinct()
                .filter { action.mode == "copy" || it.first == (action.folderVolume ?: action.videos.firstOrNull()?.volume) }
                .filter { action.mode != "move_folder" || (it.second != action.folderPath && !it.second.startsWith("${action.folderPath}/")) }
                .sortedWith(compareBy({ it.first }, { it.second }))
            AlertDialog(onDismissRequest = { target = null }, title = { Text("Папка назначения") },
                text = { LazyColumn(Modifier.height(320.dp)) {
                    item { TextButton(onClick = { inputText = "Movies/Новая папка"; inputKind = "new_destination" }) { Text("+ Новая папка…") } }
                    items(available) { (volume, path) ->
                        TextButton(onClick = { applyTarget(action, volume, path) }) { Text("${volumeTitle(volume)}/$path", maxLines = 2) }
                    }
                } }, confirmButton = { TextButton(onClick = { target = null }) { Text("Закрыть") } })
        }
        playlistPicker?.let { picks ->
            AlertDialog(onDismissRequest = { playlistPicker = null }, title = { Text("Добавить в видеоплейлист") },
                text = { LazyColumn(Modifier.height(300.dp)) {
                    items(playlistList) { playlist ->
                        TextButton(onClick = {
                            playlists.add(playlist.name, picks.map { it.uri.toString() })
                            playlistList = playlists.all(); playlistPicker = null; selectedUris = emptySet()
                        }) { Text(playlist.name) }
                    }
                } }, confirmButton = { TextButton(onClick = { newPlaylistItems = picks.map { it.uri.toString() }; playlistPicker = null; inputKind = "create_playlist"; inputText = "" }) { Text("Новый плейлист") } },
                dismissButton = { TextButton(onClick = { playlistPicker = null }) { Text("Отмена") } })
        }
        detail?.let { video ->
            AlertDialog(onDismissRequest = { detail = null }, title = { Text(video.name) },
                text = { Text("${volumeTitle(video.volume)}/${video.path}\n${video.width}×${video.height} · ${VideoStore.formatDuration(video.durationMs)}\n${video.sizeBytes / 1024 / 1024} МБ") },
                confirmButton = { TextButton(onClick = { detail = null }) { Text("Закрыть") } })
        }
        pendingPlaylistDelete?.let { name ->
            AlertDialog(onDismissRequest = { pendingPlaylistDelete = null }, title = { Text("Удалить плейлист «$name»?") },
                text = { Text("Видео останутся на устройстве.") },
                confirmButton = { TextButton(onClick = {
                    playlists.delete(name); playlistList = playlists.all(); pendingPlaylistDelete = null
                    if (selectedPlaylist == name) selectedPlaylist = null
                }) { Text("Удалить") } },
                dismissButton = { TextButton(onClick = { pendingPlaylistDelete = null }) { Text("Отмена") } })
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun VideoAction(symbol: String, label: String, onClick: () -> Unit) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
            Text(symbol, fontSize = 27.sp, lineHeight = 28.sp)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun VideoRow(title: String, details: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
                         checked: Boolean = false, menu: List<Pair<String, () -> Unit>> = emptyList()) {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick ?: { open = true }).padding(vertical = 10.dp)) {
                Text(if (checked) "✓ $title" else title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold, color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (menu.isNotEmpty()) {
                VideoAction("⋮", "Действия с $title", { open = true })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    menu.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
                }
            }
        }
    }

    private fun volumeTitle(name: String): String =
        if (name == "external_primary") "Память телефона" else "Карта памяти · $name"
}
