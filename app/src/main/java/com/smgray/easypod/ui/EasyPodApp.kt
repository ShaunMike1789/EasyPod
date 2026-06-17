package com.smgray.easypod.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi
import com.smgray.easypod.ImportState
import com.smgray.easypod.BackupActionState
import com.smgray.easypod.FeedActionState
import com.smgray.easypod.MainUiState
import com.smgray.easypod.MainViewModel
import com.smgray.easypod.SyncActionState
import com.smgray.easypod.data.DownloadSummary
import com.smgray.easypod.data.EpisodeSummary
import com.smgray.easypod.data.QueueEpisodeSummary
import com.smgray.easypod.data.FeedSummary
import com.smgray.easypod.data.CategorySummary
import com.smgray.easypod.data.SmartPlaylistSummary
import com.smgray.easypod.data.SmartPlayRuleDraft
import com.smgray.easypod.data.SmartPlayRuleSummary
import com.smgray.easypod.downloads.DownloadState
import com.smgray.easypod.playback.PlaybackUiState
import com.smgray.easypod.smartplay.SmartPlayRuleEngine
import com.smgray.easypod.sync.SyncResolution
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.text.DateFormat
import java.util.Date

private enum class EasyPodSection(val label: String) {
    Episodes("Episodes"),
    Feeds("Feeds"),
    Playlist("Playlist"),
    SmartPlay("SmartPlay"),
    Downloads("Downloads"),
    Settings("Settings"),
}

private enum class EpisodeFilter(val label: String) {
    All("All"),
    Unplayed("Unplayed"),
    Queued("Queued"),
    Downloaded("Downloaded"),
    Locked("Locked"),
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EasyPodApp(
    viewModel: MainViewModel,
    externalEpisodeSearchQuery: String? = null,
    onExternalEpisodeSearchConsumed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf(EasyPodSection.Episodes) }
    var showAddFeedDialog by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingNotificationAction by remember {
        mutableStateOf<(() -> Unit)?>(null)
    }
    val context = LocalContext.current
    LaunchedEffect(externalEpisodeSearchQuery) {
        if (!externalEpisodeSearchQuery.isNullOrBlank()) {
            section = EasyPodSection.Episodes
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        pendingNotificationAction?.invoke()
        pendingNotificationAction = null
    }
    val withDownloadNotifications: (() -> Unit) -> Unit = { action ->
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val toggleDownload: (EpisodeSummary) -> Unit = { episode ->
        if (
            episode.downloadState == DownloadState.QUEUED ||
            episode.downloadState == DownloadState.RUNNING ||
            episode.downloadState == DownloadState.COMPLETE
        ) {
            viewModel.toggleDownload(episode)
        } else {
            withDownloadNotifications { viewModel.toggleDownload(episode) }
        }
    }

    val importer = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importLegacyBackup)
    }
    val opmlImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importOpml)
    }
    val opmlExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/x-opml"),
    ) { uri ->
        uri?.let(viewModel::exportOpml)
    }
    val backupExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val backupImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pendingRestoreUri = uri
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerHeader()
                EasyPodSection.entries.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        selected = section == item,
                        onClick = {
                            section = item
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(section.label) },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("Menu")
                        }
                    },
                    actions = {
                        MediaRouteButton()
                    },
                )
            },
            bottomBar = {
                MiniPlayer(
                    playback = state.playback,
                    backwardSkipSeconds =
                        state.library.playerSettings.backwardSkipSeconds,
                    forwardSkipSeconds =
                        state.library.playerSettings.forwardSkipSeconds,
                    sleepTimerActive =
                        state.library.persistedPlayback?.sleepTimerEndsAt != null,
                    onToggle = viewModel::togglePlayPause,
                    onPrevious = viewModel::skipPrevious,
                    onNext = viewModel::skipNext,
                    onRewind = viewModel::rewind,
                    onForward = viewModel::forward,
                    onSpeed = viewModel::cyclePlaybackSpeed,
                    onSleep = { showSleepDialog = true },
                    onOpen = { showNowPlaying = true },
                )
            },
        ) { padding ->
            when (section) {
                EasyPodSection.Episodes -> EpisodesPage(
                    state = state,
                    externalSearchQuery = externalEpisodeSearchQuery,
                    onExternalSearchConsumed = onExternalEpisodeSearchConsumed,
                    onImport = {
                        importer.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "application/x-sqlite3",
                            ),
                        )
                    },
                    onDismissImportMessage = viewModel::dismissImportMessage,
                    onPlayEpisode = viewModel::playEpisode,
                    onToggleQueue = { episode ->
                        if (episode.inQueue) {
                            viewModel.removeFromQueue(episode.id)
                        } else {
                            viewModel.addToQueue(episode.id)
                        }
                    },
                    onToggleDownload = toggleDownload,
                    onSetPlayed = viewModel::setEpisodePlayed,
                    onSetLocked = viewModel::setEpisodeLocked,
                    modifier = Modifier.padding(padding),
                )

                EasyPodSection.Feeds -> FeedsPage(
                    state = state,
                    onAddFeed = { showAddFeedDialog = true },
                    onImportOpml = {
                        opmlImporter.launch(
                            arrayOf(
                                "text/xml",
                                "application/xml",
                                "text/x-opml",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onExportOpml = {
                        opmlExporter.launch("easypod-subscriptions.opml")
                    },
                    onDismissAction = viewModel::dismissFeedAction,
                    onSetAutoDownload = viewModel::setFeedAutoDownload,
                    onCreateCategory = viewModel::createCategory,
                    onSetFeedCategory = viewModel::setFeedCategory,
                    onDeleteCategory = viewModel::deleteCategory,
                    modifier = Modifier.padding(padding),
                )

                EasyPodSection.Playlist -> PlaylistPage(
                    queue = state.library.queue,
                    onPlay = viewModel::playQueuedEpisode,
                    onMove = viewModel::moveQueueItem,
                    onRemove = viewModel::removeFromQueue,
                    onClear = viewModel::clearQueue,
                    modifier = Modifier.padding(padding),
                )

                EasyPodSection.Downloads -> DownloadsPage(
                    downloads = state.library.downloads,
                    onRetry = { episodeId ->
                        withDownloadNotifications {
                            viewModel.requestDownload(episodeId)
                        }
                    },
                    onCancel = viewModel::cancelDownload,
                    onDelete = viewModel::deleteDownload,
                    modifier = Modifier.padding(padding),
                )

                EasyPodSection.SmartPlay -> SmartPlayPage(
                    playlists = state.library.smartPlaylists,
                    rules = state.library.smartPlayRules,
                    feeds = state.library.feeds,
                    categories = state.library.categories,
                    onLoad = { viewModel.loadSmartPlaylist(it, false) },
                    onPlay = { viewModel.loadSmartPlaylist(it, true) },
                    onSave = viewModel::saveSmartPlaylist,
                    onDelete = viewModel::deleteSmartPlaylist,
                    modifier = Modifier.padding(padding),
                )

                EasyPodSection.Settings -> SettingsPage(
                    state = state,
                    onRefreshNow = viewModel::refreshAllFeeds,
                    onSetRefreshEnabled = viewModel::setAutomaticRefreshEnabled,
                    onSetInterval = viewModel::setRefreshInterval,
                    onSetWifiOnly = viewModel::setRefreshWifiOnly,
                    onSetChargingOnly = viewModel::setRefreshChargingOnly,
                    onSetAutoDownload = { enabled ->
                        if (enabled) {
                            withDownloadNotifications {
                                viewModel.setAutoDownloadEnabled(true)
                            }
                        } else {
                            viewModel.setAutoDownloadEnabled(false)
                        }
                    },
                    onSetDefaultMax = viewModel::setDefaultMaxDownloads,
                    onSetForwardSkip = viewModel::setForwardSkip,
                    onSetBackwardSkip = viewModel::setBackwardSkip,
                    onSetLoudnessBoost = viewModel::setLoudnessBoost,
                    onSetPauseOnDisconnect = viewModel::setPauseOnHeadsetDisconnect,
                    onSetDefaultSleep = viewModel::setDefaultSleepMinutes,
                    onExportBackup = {
                        backupExporter.launch("easypod-backup.zip")
                    },
                    onChooseRestore = {
                        backupImporter.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onDismissBackupAction = viewModel::dismissBackupAction,
                    onSaveSync = viewModel::saveSyncConfiguration,
                    onSyncNow = viewModel::syncNow,
                    onDismissSyncAction = viewModel::dismissSyncAction,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    if (showAddFeedDialog) {
        AddFeedDialog(
            onDismiss = { showAddFeedDialog = false },
            onAdd = { url ->
                showAddFeedDialog = false
                viewModel.addFeed(url)
            },
        )
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            defaultMinutes = state.library.playerSettings.defaultSleepMinutes,
            timerActive = state.library.persistedPlayback?.sleepTimerEndsAt != null,
            onDismiss = { showSleepDialog = false },
            onSet = { minutes ->
                showSleepDialog = false
                viewModel.setSleepTimer(minutes)
            },
            onCancelTimer = {
                showSleepDialog = false
                viewModel.cancelSleepTimer()
            },
        )
    }

    if (showNowPlaying && state.playback.title != null) {
        NowPlayingDialog(
            playback = state.playback,
            backwardSkipSeconds =
                state.library.playerSettings.backwardSkipSeconds,
            forwardSkipSeconds =
                state.library.playerSettings.forwardSkipSeconds,
            sleepTimerActive =
                state.library.persistedPlayback?.sleepTimerEndsAt != null,
            onDismiss = { showNowPlaying = false },
            onToggle = viewModel::togglePlayPause,
            onPrevious = viewModel::skipPrevious,
            onNext = viewModel::skipNext,
            onRewind = viewModel::rewind,
            onForward = viewModel::forward,
            onSeek = viewModel::seekTo,
            onSetSpeed = viewModel::setPlaybackSpeed,
            onSleep = {
                showNowPlaying = false
                showSleepDialog = true
            },
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Restore EasyPod backup?") },
            text = {
                Text(
                    "Subscriptions and episode state will merge with this library. " +
                        "Your current playlist will be replaced. Downloaded audio is " +
                        "not stored in EasyPod backups.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestoreUri = null
                        viewModel.restoreBackup(uri)
                    },
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun DrawerHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            text = "EASYPOD",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "Podcasts, on your terms",
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EpisodesPage(
    state: MainUiState,
    externalSearchQuery: String?,
    onExternalSearchConsumed: () -> Unit,
    onImport: () -> Unit,
    onDismissImportMessage: () -> Unit,
    onPlayEpisode: (EpisodeSummary) -> Unit,
    onToggleQueue: (EpisodeSummary) -> Unit,
    onToggleDownload: (EpisodeSummary) -> Unit,
    onSetPlayed: (String, Boolean) -> Unit,
    onSetLocked: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(EpisodeFilter.All) }
    var selectedEpisodeId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(externalSearchQuery) {
        externalSearchQuery?.let { incomingQuery ->
            query = incomingQuery
            onExternalSearchConsumed()
        }
    }
    val filteredEpisodes = state.library.episodes.filter { episode ->
        val matchesQuery = query.isBlank() ||
            episode.title.contains(query, ignoreCase = true) ||
            episode.feedTitle?.contains(query, ignoreCase = true) == true
        val matchesFilter = when (filter) {
            EpisodeFilter.All -> true
            EpisodeFilter.Unplayed -> !episode.played
            EpisodeFilter.Queued -> episode.inQueue
            EpisodeFilter.Downloaded -> episode.localDownloadPath != null
            EpisodeFilter.Locked -> episode.locked
        }
        matchesQuery && matchesFilter
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard("Feeds", state.library.feedCount, Modifier.weight(1f))
                StatCard("Episodes", state.library.episodeCount, Modifier.weight(1f))
                StatCard("Unplayed", state.library.unplayedCount, Modifier.weight(1f))
            }
        }

        item {
            MigrationCard(
                importState = state.importState,
                hasLibrary = state.library.feedCount > 0,
                onImport = onImport,
                onDismiss = onDismissImportMessage,
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search episodes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EpisodeFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
        }

        if (state.library.episodeCount == 0) {
            item {
                EmptyLibraryCard()
            }
        } else {
            item {
                Text(
                    "Recent episodes",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (filteredEpisodes.isEmpty()) {
                item {
                    Text(
                        "No episodes match this search and filter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filteredEpisodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    onPlay = { onPlayEpisode(episode) },
                    onToggleQueue = { onToggleQueue(episode) },
                    onToggleDownload = { onToggleDownload(episode) },
                    onInfo = { selectedEpisodeId = episode.id },
                )
            }
        }
    }

    selectedEpisodeId
        ?.let { selectedId ->
            state.library.episodes.firstOrNull { it.id == selectedId }
        }
        ?.let { episode ->
            EpisodeDetailDialog(
                episode = episode,
                onDismiss = { selectedEpisodeId = null },
                onPlay = { onPlayEpisode(episode) },
                onToggleQueue = { onToggleQueue(episode) },
                onToggleDownload = { onToggleDownload(episode) },
                onSetPlayed = { onSetPlayed(episode.id, it) },
                onSetLocked = { onSetLocked(episode.id, it) },
            )
        }
}

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MigrationCard(
    importState: ImportState,
    hasLibrary: Boolean,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (hasLibrary) "Legacy library migration" else "Bring your library with you",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Import a legacy .bpbak archive or SQLite database. EasyPod preserves " +
                    "subscriptions, episodes, played positions, categories, schedules, " +
                    "history, and SmartPlay definitions found in the database.",
            )

            when (importState) {
                ImportState.Idle -> Button(onClick = onImport) {
                    Text(if (hasLibrary) "Import another backup" else "Choose legacy backup")
                }

                ImportState.Running -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Checking and importing backup...")
                }

                is ImportState.Complete -> {
                    Text(
                        importState.summary,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Done") }
                }

                is ImportState.Failed -> {
                    Text(
                        importState.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onImport) { Text("Try another file") }
                        TextButton(onClick = onDismiss) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Your episode list is ready for a fresh start.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Add a feed URL or import OPML to begin. Podcast search is coming next.",
            )
        }
    }
}

@Composable
private fun FeedsPage(
    state: MainUiState,
    onAddFeed: () -> Unit,
    onImportOpml: () -> Unit,
    onExportOpml: () -> Unit,
    onDismissAction: () -> Unit,
    onSetAutoDownload: (String, Boolean) -> Unit,
    onCreateCategory: (String) -> Unit,
    onSetFeedCategory: (String, String, Boolean) -> Unit,
    onDeleteCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryFeedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateCategory by rememberSaveable { mutableStateOf(false) }
    val visibleFeeds = state.library.feeds.filter { feed ->
        selectedCategoryId == null ||
            selectedCategoryId in feed.categoryIdSet()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onAddFeed) { Text("Add feed") }
                OutlinedButton(onClick = onImportOpml) { Text("Import OPML") }
                OutlinedButton(onClick = onExportOpml) { Text("Export OPML") }
                OutlinedButton(onClick = { showCreateCategory = true }) {
                    Text("New category")
                }
            }
        }

        if (state.library.categories.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { selectedCategoryId = null },
                        label = { Text("All feeds") },
                    )
                    state.library.categories.forEach { category ->
                        FilterChip(
                            selected = selectedCategoryId == category.id,
                            onClick = { selectedCategoryId = category.id },
                            label = { Text("${category.name} (${category.feedCount})") },
                        )
                    }
                }
            }
        }

        item {
            when (val action = state.feedActionState) {
                FeedActionState.Idle -> Unit
                FeedActionState.Running -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Updating podcast library...")
                }
                is FeedActionState.Complete -> ActionMessage(
                    message = action.summary,
                    isError = false,
                    onDismiss = onDismissAction,
                )
                is FeedActionState.Failed -> ActionMessage(
                    message = action.message,
                    isError = true,
                    onDismiss = onDismissAction,
                )
            }
        }

        if (state.library.feeds.isEmpty()) {
            item {
                Card {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "No feeds yet",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Add a podcast feed URL or import an OPML subscription list.")
                    }
                }
            }
        } else {
            items(visibleFeeds, key = { it.id }) { feed ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        feed.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    feed.feedUrl?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    feed.categoryNames?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                onSetAutoDownload(feed.id, !feed.autoDownload)
                            },
                        ) {
                            Text(
                                if (feed.autoDownload) {
                                    "Auto-download on"
                                } else {
                                    "Auto-download off"
                                },
                            )
                        }
                        TextButton(onClick = { categoryFeedId = feed.id }) {
                            Text("Categories")
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    categoryFeedId
        ?.let { feedId -> state.library.feeds.firstOrNull { it.id == feedId } }
        ?.let { feed ->
            FeedCategoriesDialog(
                feed = feed,
                categories = state.library.categories,
                onDismiss = { categoryFeedId = null },
                onSetCategory = { categoryId, selected ->
                    onSetFeedCategory(feed.id, categoryId, selected)
                },
                onDeleteCategory = onDeleteCategory,
            )
        }

    if (showCreateCategory) {
        CreateCategoryDialog(
            onDismiss = { showCreateCategory = false },
            onCreate = {
                showCreateCategory = false
                onCreateCategory(it)
            },
        )
    }
}

@Composable
private fun FeedCategoriesDialog(
    feed: FeedSummary,
    categories: List<CategorySummary>,
    onDismiss: () -> Unit,
    onSetCategory: (String, Boolean) -> Unit,
    onDeleteCategory: (String) -> Unit,
) {
    val selectedIds = feed.categoryIdSet()
    var pendingDelete by remember { mutableStateOf<CategorySummary?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(feed.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Choose up to two categories.")
                if (categories.isEmpty()) {
                    Text("Create a category from the Feeds screen first.")
                }
                categories.forEach { category ->
                    val selected = category.id in selectedIds
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(category.name, modifier = Modifier.weight(1f))
                        Switch(
                            checked = selected,
                            enabled = selected || selectedIds.size < 2,
                            onCheckedChange = {
                                onSetCategory(category.id, it)
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "${category.name} category"
                            },
                        )
                        TextButton(onClick = { pendingDelete = category }) {
                            Text("Delete")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )

    pendingDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${category.name}?") },
            text = {
                Text("This removes the category from all ${category.feedCount} feeds.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDeleteCategory(category.id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun CreateCategoryDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Category name") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EpisodeRow(
    episode: EpisodeSummary,
    onPlay: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleDownload: () -> Unit,
    onInfo: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Column(
            modifier = Modifier.clickable(
                enabled = !episode.mediaUrl.isNullOrBlank(),
                onClick = onPlay,
            ),
        ) {
            Text(
                episode.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (episode.played) FontWeight.Normal else FontWeight.SemiBold,
            )
            episode.feedTitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(
                onClick = onPlay,
                enabled = !episode.mediaUrl.isNullOrBlank(),
            ) {
                Text("Play")
            }
            TextButton(
                onClick = onToggleQueue,
                enabled = !episode.mediaUrl.isNullOrBlank(),
            ) {
                Text(if (episode.inQueue) "Remove" else "Queue")
            }
            TextButton(
                onClick = onToggleDownload,
                enabled = !episode.mediaUrl.isNullOrBlank(),
            ) {
                Text(downloadActionLabel(episode.downloadState))
            }
            TextButton(onClick = onInfo) { Text("Info") }
        }
        val status = buildList {
            if (episode.played) add("Played")
            if (episode.locked) add("Locked")
            if (episode.localDownloadPath != null) add("Offline")
        }
        if (status.isNotEmpty()) {
            Text(
                status.joinToString("  |  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (episode.downloadState == DownloadState.RUNNING) {
            LinearProgressIndicator(
                progress = { episode.downloadProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EpisodeDetailDialog(
    episode: EpisodeSummary,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleDownload: () -> Unit,
    onSetPlayed: (Boolean) -> Unit,
    onSetLocked: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val details = episode.showNotes
        ?.takeIf(String::isNotBlank)
        ?: episode.description?.takeIf(String::isNotBlank)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(episode.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                episode.feedTitle?.let {
                    Text(it, fontWeight = FontWeight.SemiBold)
                }
                episode.publishedAt?.let {
                    Text(
                        DateFormat.getDateInstance().format(Date(it)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (episode.durationMs > 0) {
                    Text(
                        "Progress: ${formatDuration(episode.positionMs)} / " +
                            formatDuration(episode.durationMs),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onPlay,
                        enabled = !episode.mediaUrl.isNullOrBlank(),
                    ) { Text("Play") }
                    TextButton(onClick = onToggleQueue) {
                        Text(if (episode.inQueue) "Unqueue" else "Queue")
                    }
                    TextButton(
                        onClick = onToggleDownload,
                        enabled = !episode.mediaUrl.isNullOrBlank(),
                    ) { Text(downloadActionLabel(episode.downloadState)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onSetPlayed(!episode.played) }) {
                        Text(if (episode.played) "Mark unplayed" else "Mark played")
                    }
                    TextButton(onClick = { onSetLocked(!episode.locked) }) {
                        Text(if (episode.locked) "Unlock" else "Lock")
                    }
                }
                details?.let {
                    HorizontalDivider()
                    Text(plainEpisodeText(it))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            val shareUrl = episode.postUrl ?: episode.mediaUrl
            TextButton(
                enabled = shareUrl != null,
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "${episode.title}\n$shareUrl",
                                )
                            },
                            "Share episode",
                        ),
                    )
                },
            ) {
                Text("Share")
            }
        },
    )
}

@Composable
private fun ActionMessage(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun AddFeedDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add podcast feed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter an RSS or Atom feed URL.")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(url) },
                enabled = url.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PlaylistPage(
    queue: List<QueueEpisodeSummary>,
    onPlay: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${queue.size} queued",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onClear, enabled = queue.isNotEmpty()) {
                    Text("Clear")
                }
            }
        }
        if (queue.isEmpty()) {
            item {
                Card {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Your playlist is empty",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Queue episodes from the Episodes page to build a playlist.")
                    }
                }
            }
        } else {
            items(queue, key = { it.id }) { episode ->
                QueueRow(
                    episode = episode,
                    canMoveUp = episode.queuePosition > 0,
                    canMoveDown = episode.queuePosition < queue.lastIndex,
                    onPlay = { onPlay(episode.id) },
                    onMoveUp = { onMove(episode.id, -1) },
                    onMoveDown = { onMove(episode.id, 1) },
                    onRemove = { onRemove(episode.id) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    episode: QueueEpisodeSummary,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            episode.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        episode.feedTitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onPlay) { Text("Play") }
            TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("Up") }
            TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("Down") }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DownloadsPage(
    downloads: List<DownloadSummary>,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "${downloads.size} downloads",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        if (downloads.isEmpty()) {
            item {
                Card {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "No offline episodes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Download an episode to listen without a network connection.")
                    }
                }
            }
        } else {
            items(downloads, key = { it.episodeId }) { download ->
                DownloadRow(
                    download = download,
                    onRetry = { onRetry(download.episodeId) },
                    onCancel = { onCancel(download.episodeId) },
                    onDelete = { onDelete(download.episodeId) },
                )
            }
        }
    }
}

@Composable
private fun SmartPlayPage(
    playlists: List<SmartPlaylistSummary>,
    rules: List<SmartPlayRuleSummary>,
    feeds: List<FeedSummary>,
    categories: List<CategorySummary>,
    onLoad: (Long) -> Unit,
    onPlay: (Long) -> Unit,
    onSave: (Long?, String, List<SmartPlayRuleDraft>) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "SmartPlay",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Build an unplayed queue from ordered podcast rules.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        editingPlaylistId = null
                        editorOpen = true
                    },
                ) {
                    Text("New")
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                Card {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "No SmartPlay lists",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Create a rule to automatically build your playlist.")
                    }
                }
            }
        } else {
            items(playlists, key = { it.playlistId }) { playlist ->
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${playlist.ruleCount} " +
                                if (playlist.ruleCount == 1) "rule" else "rules",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { onPlay(playlist.playlistId) }) {
                                Text("Build & play")
                            }
                            OutlinedButton(onClick = { onLoad(playlist.playlistId) }) {
                                Text("Load")
                            }
                            TextButton(
                                onClick = {
                                    editingPlaylistId = playlist.playlistId
                                    editorOpen = true
                                },
                            ) {
                                Text("Edit")
                            }
                            TextButton(onClick = { onDelete(playlist.playlistId) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        val playlist = playlists.firstOrNull {
            it.playlistId == editingPlaylistId
        }
        SmartPlayEditorDialog(
            playlistId = editingPlaylistId,
            initialName = playlist?.name ?: "SmartPlay",
            initialRules = rules
                .filter { it.playlistId == editingPlaylistId }
                .sortedBy(SmartPlayRuleSummary::position),
            feeds = feeds,
            categories = categories,
            onDismiss = { editorOpen = false },
            onSave = { name, savedRules ->
                editorOpen = false
                onSave(editingPlaylistId, name, savedRules)
            },
        )
    }
}

@Composable
private fun SmartPlayEditorDialog(
    playlistId: Long?,
    initialName: String,
    initialRules: List<SmartPlayRuleSummary>,
    feeds: List<FeedSummary>,
    categories: List<CategorySummary>,
    onDismiss: () -> Unit,
    onSave: (String, List<SmartPlayRuleDraft>) -> Unit,
) {
    var name by remember(playlistId, initialName) { mutableStateOf(initialName) }
    var editedRules by remember(playlistId, initialRules) {
        mutableStateOf(
            initialRules.map {
                SmartPlayRuleDraft(
                    feedId = it.feedId,
                    categoryId = it.categoryId,
                    episodeCount = it.episodeCount,
                    mediaType = it.mediaType,
                    sortMode = it.sortMode,
                )
            }.ifEmpty { listOf(SmartPlayRuleDraft()) },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (playlistId == null) "New SmartPlay" else "Edit SmartPlay")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Rules run in order and skip episodes already selected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                editedRules.forEachIndexed { index, rule ->
                    SmartPlayRuleEditor(
                        index = index,
                        rule = rule,
                        feeds = feeds,
                        categories = categories,
                        canMoveUp = index > 0,
                        canMoveDown = index < editedRules.lastIndex,
                        canDelete = editedRules.size > 1,
                        onChange = { updated ->
                            editedRules = editedRules.toMutableList().also {
                                it[index] = updated
                            }
                        },
                        onMoveUp = {
                            editedRules = editedRules.toMutableList().also {
                                val moved = it.removeAt(index)
                                it.add(index - 1, moved)
                            }
                        },
                        onMoveDown = {
                            editedRules = editedRules.toMutableList().also {
                                val moved = it.removeAt(index)
                                it.add(index + 1, moved)
                            }
                        },
                        onDelete = {
                            editedRules = editedRules.toMutableList().also {
                                it.removeAt(index)
                            }
                        },
                    )
                }
                OutlinedButton(
                    onClick = {
                        editedRules = editedRules + SmartPlayRuleDraft()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add rule")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && editedRules.isNotEmpty(),
                onClick = { onSave(name, editedRules) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SmartPlayRuleEditor(
    index: Int,
    rule: SmartPlayRuleDraft,
    feeds: List<FeedSummary>,
    categories: List<CategorySummary>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onChange: (SmartPlayRuleDraft) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var sourceMenuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (index == 0) "Start with" else "Then play",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Text("Up")
                }
                TextButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Text("Down")
                }
                TextButton(onClick = onDelete, enabled = canDelete) {
                    Text("Delete")
                }
            }
            Text("Source", fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton(
                    onClick = { sourceMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(smartPlaySourceName(rule, feeds, categories))
                }
                DropdownMenu(
                    expanded = sourceMenuOpen,
                    onDismissRequest = { sourceMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All feeds") },
                        onClick = {
                            sourceMenuOpen = false
                            onChange(rule.copy(feedId = null, categoryId = null))
                        },
                    )
                    categories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text("Category: ${category.name}") },
                            onClick = {
                                sourceMenuOpen = false
                                onChange(
                                    rule.copy(
                                        feedId = null,
                                        categoryId = category.id,
                                    ),
                                )
                            },
                        )
                    }
                    feeds.sortedBy { it.title.lowercase() }.forEach { feed ->
                        DropdownMenuItem(
                            text = { Text(feed.title) },
                            onClick = {
                                sourceMenuOpen = false
                                onChange(
                                    rule.copy(
                                        feedId = feed.id,
                                        categoryId = null,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
            Text("Episodes", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 50)
                    .forEach { count ->
                        FilterChip(
                            selected = rule.episodeCount == count,
                            onClick = { onChange(rule.copy(episodeCount = count)) },
                            label = { Text(count.toString()) },
                        )
                    }
            }
            Text("Order", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    SmartPlayRuleEngine.SORT_LATEST to "Latest",
                    SmartPlayRuleEngine.SORT_OLDEST to "Oldest",
                    SmartPlayRuleEngine.SORT_RANDOM to "Random",
                ).forEach { (mode, label) ->
                    FilterChip(
                        selected = rule.sortMode == mode,
                        onClick = { onChange(rule.copy(sortMode = mode)) },
                        label = { Text(label) },
                    )
                }
            }
            Text("Media", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SmartPlayRuleEngine.MEDIA_ANY to "Any",
                    SmartPlayRuleEngine.MEDIA_AUDIO to "Audio",
                    SmartPlayRuleEngine.MEDIA_VIDEO to "Video",
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = rule.mediaType == type,
                        onClick = { onChange(rule.copy(mediaType = type)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

private fun smartPlaySourceName(
    rule: SmartPlayRuleDraft,
    feeds: List<FeedSummary>,
    categories: List<CategorySummary>,
): String = when {
    rule.feedId != null ->
        feeds.firstOrNull { it.id == rule.feedId }?.title ?: "Missing feed"

    rule.categoryId != null ->
        categories.firstOrNull { it.id == rule.categoryId }?.let {
            "Category: ${it.name}"
        } ?: "Missing category"

    else -> "All feeds"
}

@Composable
private fun SettingsPage(
    state: MainUiState,
    onRefreshNow: () -> Unit,
    onSetRefreshEnabled: (Boolean) -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetWifiOnly: (Boolean) -> Unit,
    onSetChargingOnly: (Boolean) -> Unit,
    onSetAutoDownload: (Boolean) -> Unit,
    onSetDefaultMax: (Int) -> Unit,
    onSetForwardSkip: (Int) -> Unit,
    onSetBackwardSkip: (Int) -> Unit,
    onSetLoudnessBoost: (Boolean) -> Unit,
    onSetPauseOnDisconnect: (Boolean) -> Unit,
    onSetDefaultSleep: (Int) -> Unit,
    onExportBackup: () -> Unit,
    onChooseRestore: () -> Unit,
    onDismissBackupAction: () -> Unit,
    onSaveSync: (
        Boolean,
        String,
        String,
        String?,
        Int,
        Boolean,
        Boolean,
    ) -> Unit,
    onSyncNow: (SyncResolution) -> Unit,
    onDismissSyncAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.library.automation
    val playerSettings = state.library.playerSettings
    val syncSettings = state.syncSettings
    var syncEnabled by rememberSaveable(syncSettings.enabled) {
        mutableStateOf(syncSettings.enabled)
    }
    var syncEndpoint by rememberSaveable(syncSettings.endpoint) {
        mutableStateOf(syncSettings.endpoint)
    }
    var syncUsername by rememberSaveable(syncSettings.username) {
        mutableStateOf(syncSettings.username)
    }
    var syncPassword by rememberSaveable { mutableStateOf("") }
    var syncInterval by rememberSaveable(syncSettings.intervalHours) {
        mutableStateOf(syncSettings.intervalHours)
    }
    var syncWifiOnly by rememberSaveable(syncSettings.wifiOnly) {
        mutableStateOf(syncSettings.wifiOnly)
    }
    var syncChargingOnly by rememberSaveable(syncSettings.chargingOnly) {
        mutableStateOf(syncSettings.chargingOnly)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                "Feed updates",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            SettingSwitch(
                title = "Automatic feed refresh",
                summary = "Refresh subscriptions in the background.",
                checked = settings.refreshEnabled,
                onCheckedChange = onSetRefreshEnabled,
            )
        }
        item {
            Text("Refresh interval", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1, 6, 12, 24).forEach { hours ->
                    FilterChip(
                        selected = settings.refreshIntervalHours == hours,
                        onClick = { onSetInterval(hours) },
                        label = { Text(if (hours == 1) "1 hour" else "$hours hours") },
                    )
                }
            }
        }
        item {
            SettingSwitch(
                title = "Wi-Fi only",
                summary = "Use an unmetered network for automatic work.",
                checked = settings.wifiOnly,
                onCheckedChange = onSetWifiOnly,
            )
        }
        item {
            SettingSwitch(
                title = "Only while charging",
                summary = "Wait for external power before automatic work.",
                checked = settings.chargingOnly,
                onCheckedChange = onSetChargingOnly,
            )
        }
        item {
            HorizontalDivider()
            Text(
                "Episode downloads",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        item {
            SettingSwitch(
                title = "Allow automatic downloads",
                summary = "Download new episodes for feeds that opt in.",
                checked = settings.autoDownloadEnabled,
                onCheckedChange = onSetAutoDownload,
            )
        }
        item {
            Text("Default new episodes per feed", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 5, 10).forEach { count ->
                    FilterChip(
                        selected = settings.defaultMaxDownloads == count,
                        onClick = { onSetDefaultMax(count) },
                        label = { Text(count.toString()) },
                    )
                }
            }
        }
        item {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        settings.lastStatus,
                        fontWeight = FontWeight.SemiBold,
                    )
                    settings.lastRunAt?.let {
                        Text(
                            DateFormat.getDateTimeInstance().format(Date(it)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    settings.lastMessage?.let { Text(it) }
                    Button(onClick = onRefreshNow) {
                        Text("Refresh all now")
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text(
                "Player",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        item {
            SkipIntervalSetting(
                title = "Rewind",
                selectedSeconds = playerSettings.backwardSkipSeconds,
                onSelected = onSetBackwardSkip,
            )
        }
        item {
            SkipIntervalSetting(
                title = "Forward",
                selectedSeconds = playerSettings.forwardSkipSeconds,
                onSelected = onSetForwardSkip,
            )
        }
        item {
            SettingSwitch(
                title = "Loudness boost",
                summary = "Apply an 8 dB boost for quiet recordings.",
                checked = playerSettings.loudnessBoostEnabled,
                onCheckedChange = onSetLoudnessBoost,
            )
        }
        item {
            SettingSwitch(
                title = "Pause when headphones disconnect",
                summary = "Prevent playback from switching unexpectedly to speakers.",
                checked = playerSettings.pauseOnHeadsetDisconnect,
                onCheckedChange = onSetPauseOnDisconnect,
            )
        }
        item {
            Text("Default sleep timer", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(15, 30, 60, 90).forEach { minutes ->
                    FilterChip(
                        selected = playerSettings.defaultSleepMinutes == minutes,
                        onClick = { onSetDefaultSleep(minutes) },
                        label = { Text("$minutes min") },
                    )
                }
            }
        }
        item {
            HorizontalDivider()
            Text(
                "Backup and restore",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        item {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Create a portable EasyPod backup of subscriptions, episode " +
                            "state, categories, SmartPlay rules, settings, and playlist. " +
                            "Downloaded audio stays on this device.",
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onExportBackup,
                            enabled = state.backupActionState != BackupActionState.Running,
                        ) {
                            Text("Export backup")
                        }
                        OutlinedButton(
                            onClick = onChooseRestore,
                            enabled = state.backupActionState != BackupActionState.Running,
                        ) {
                            Text("Restore backup")
                        }
                    }
                }
            }
        }
        item {
            when (val action = state.backupActionState) {
                BackupActionState.Idle -> Unit
                BackupActionState.Running -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Processing EasyPod backup...")
                }
                is BackupActionState.Complete -> ActionMessage(
                    message = action.summary,
                    isError = false,
                    onDismiss = onDismissBackupAction,
                )
                is BackupActionState.Failed -> ActionMessage(
                    message = action.message,
                    isError = true,
                    onDismiss = onDismissBackupAction,
                )
            }
        }
        item {
            HorizontalDivider()
            Text(
                "WebDAV sync",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        item {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Sync a validated EasyPod backup through your own HTTPS " +
                            "WebDAV server. Downloaded audio remains device-local.",
                    )
                    SettingSwitch(
                        title = "Automatic sync",
                        summary = "Periodically compare this device with the remote backup.",
                        checked = syncEnabled,
                        onCheckedChange = { syncEnabled = it },
                    )
                    OutlinedTextField(
                        value = syncEndpoint,
                        onValueChange = { syncEndpoint = it },
                        label = { Text("WebDAV backup file URL") },
                        supportingText = {
                            Text("Example: https://cloud.example/remote.php/dav/files/me/easypod.zip")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = syncUsername,
                        onValueChange = { syncUsername = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = syncPassword,
                        onValueChange = { syncPassword = it },
                        label = { Text("Password or app password") },
                        supportingText = {
                            Text(
                                if (syncSettings.hasPassword) {
                                    "Leave blank to keep the saved password."
                                } else {
                                    "Stored with an Android Keystore-backed key."
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Sync interval", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(6, 12, 24, 48).forEach { hours ->
                            FilterChip(
                                selected = syncInterval == hours,
                                onClick = { syncInterval = hours },
                                label = { Text("$hours hours") },
                            )
                        }
                    }
                    SettingSwitch(
                        title = "Wi-Fi only",
                        summary = "Require an unmetered network for automatic sync.",
                        checked = syncWifiOnly,
                        onCheckedChange = { syncWifiOnly = it },
                    )
                    SettingSwitch(
                        title = "Only while charging",
                        summary = "Wait for external power before automatic sync.",
                        checked = syncChargingOnly,
                        onCheckedChange = { syncChargingOnly = it },
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                onSaveSync(
                                    syncEnabled,
                                    syncEndpoint,
                                    syncUsername,
                                    syncPassword.takeIf(String::isNotEmpty),
                                    syncInterval,
                                    syncWifiOnly,
                                    syncChargingOnly,
                                )
                                syncPassword = ""
                            },
                            enabled = state.syncActionState != SyncActionState.Running,
                        ) {
                            Text("Save sync settings")
                        }
                        OutlinedButton(
                            onClick = { onSyncNow(SyncResolution.NORMAL) },
                            enabled =
                                syncSettings.endpoint.isNotBlank() &&
                                    state.syncActionState != SyncActionState.Running,
                        ) {
                            Text("Sync now")
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(syncSettings.lastStatus, fontWeight = FontWeight.SemiBold)
                    syncSettings.lastSyncAt?.let {
                        Text(
                            DateFormat.getDateTimeInstance().format(Date(it)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    syncSettings.lastMessage?.let { Text(it) }
                }
            }
        }
        item {
            when (val action = state.syncActionState) {
                SyncActionState.Idle -> Unit
                SyncActionState.Running -> Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("Syncing EasyPod...")
                }

                is SyncActionState.Complete -> ActionMessage(
                    message = action.summary,
                    isError = false,
                    onDismiss = onDismissSyncAction,
                )

                is SyncActionState.NeedsResolution -> Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(action.message)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    onSyncNow(SyncResolution.DOWNLOAD_AND_MERGE)
                                },
                            ) {
                                Text("Download and merge")
                            }
                            OutlinedButton(
                                onClick = {
                                    onSyncNow(SyncResolution.REPLACE_REMOTE)
                                },
                            ) {
                                Text("Replace remote")
                            }
                            TextButton(onClick = onDismissSyncAction) {
                                Text("Cancel")
                            }
                        }
                    }
                }

                is SyncActionState.Failed -> ActionMessage(
                    message = action.message,
                    isError = true,
                    onDismiss = onDismissSyncAction,
                )
            }
        }
    }
}

@Composable
private fun SkipIntervalSetting(
    title: String,
    selectedSeconds: Int,
    onSelected: (Int) -> Unit,
) {
    Text(title, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(10, 15, 30, 60).forEach { seconds ->
            FilterChip(
                selected = selectedSeconds == seconds,
                onClick = { onSelected(seconds) },
                label = { Text("$seconds sec") },
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                contentDescription = title
            },
        )
    }
}

@Composable
private fun DownloadRow(
    download: DownloadSummary,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            download.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val details = buildString {
            append(download.state.lowercase().replaceFirstChar(Char::uppercase))
            if (download.bytesDownloaded > 0) {
                append(" - ")
                append(formatBytes(download.bytesDownloaded))
                if (download.totalBytes > 0) {
                    append(" / ")
                    append(formatBytes(download.totalBytes))
                }
            }
        }
        Text(
            details,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (
            download.state == DownloadState.RUNNING ||
            download.state == DownloadState.QUEUED
        ) {
            val progress = if (download.totalBytes > 0) {
                download.bytesDownloaded.toFloat() / download.totalBytes
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        download.errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (download.state) {
                DownloadState.QUEUED,
                DownloadState.RUNNING,
                -> TextButton(onClick = onCancel) { Text("Cancel") }

                DownloadState.COMPLETE ->
                    TextButton(onClick = onDelete) { Text("Delete") }

                else -> {
                    TextButton(onClick = onRetry) { Text("Retry") }
                    TextButton(onClick = onDelete) { Text("Remove") }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun MiniPlayer(
    playback: PlaybackUiState,
    backwardSkipSeconds: Int,
    forwardSkipSeconds: Int,
    sleepTimerActive: Boolean,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = playback.title != null,
                    onClick = onOpen,
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    playback.title ?: "Nothing playing",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    when {
                        playback.isCasting ->
                            "Casting - ${playback.feedTitle ?: "EasyPod"}"

                        else -> playback.feedTitle
                            ?: "The player will stay within reach here"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            if (playback.title != null) {
                Text(
                    "${formatDuration(playback.positionMs)} / " +
                        formatDuration(playback.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayerButton(
                label = "Prev",
                onClick = onPrevious,
                enabled = playback.title != null,
            )
            MiniPlayerButton(
                label = "-$backwardSkipSeconds",
                onClick = onRewind,
                enabled = playback.title != null,
            )
            MiniPlayerButton(
                label = if (playback.isPlaying) "Pause" else "Play",
                onClick = onToggle,
                enabled = playback.title != null,
            )
            MiniPlayerButton(
                label = "+$forwardSkipSeconds",
                onClick = onForward,
                enabled = playback.title != null,
            )
            MiniPlayerButton(
                label = "Next",
                onClick = onNext,
                enabled = playback.hasNext,
            )
            MiniPlayerButton(
                label = "${playback.playbackSpeed}x",
                onClick = onSpeed,
                enabled = playback.title != null,
            )
            MiniPlayerButton(
                label = if (sleepTimerActive) "Sleep*" else "Sleep",
                onClick = onSleep,
                enabled = playback.title != null,
            )
        }
    }
}

@Composable
private fun RowScope.MiniPlayerButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(
            label,
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun NowPlayingDialog(
    playback: PlaybackUiState,
    backwardSkipSeconds: Int,
    forwardSkipSeconds: Int,
    sleepTimerActive: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSleep: () -> Unit,
) {
    val duration = playback.durationMs.coerceAtLeast(1L)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Now playing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    playback.title.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        playback.isCasting ->
                            "Casting - ${playback.feedTitle ?: "EasyPod"}"

                        else -> playback.feedTitle.orEmpty()
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = playback.positionMs.coerceIn(0L, duration).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatDuration(playback.positionMs))
                    Text(formatDuration(playback.durationMs))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onPrevious,
                        enabled = playback.hasPrevious,
                    ) {
                        Text("Previous")
                    }
                    Button(onClick = onToggle) {
                        Text(if (playback.isPlaying) "Pause" else "Play")
                    }
                    TextButton(
                        onClick = onNext,
                        enabled = playback.hasNext,
                    ) {
                        Text("Next")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = onRewind) {
                        Text("Back ${backwardSkipSeconds}s")
                    }
                    TextButton(onClick = onForward) {
                        Text("Forward ${forwardSkipSeconds}s")
                    }
                }
                Text("Playback speed", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
                        FilterChip(
                            selected =
                                kotlin.math.abs(
                                    playback.playbackSpeed - speed,
                                ) < 0.01f,
                            onClick = { onSetSpeed(speed) },
                            label = { Text("${speed}x") },
                        )
                    }
                }
                OutlinedButton(
                    onClick = onSleep,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (sleepTimerActive) "Change sleep timer" else "Sleep timer")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SleepTimerDialog(
    defaultMinutes: Int,
    timerActive: Boolean,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit,
    onCancelTimer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(defaultMinutes, 15, 30, 60, 90)
                    .distinct()
                    .sorted()
                    .forEach { minutes ->
                        TextButton(
                            onClick = { onSet(minutes) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("$minutes minutes")
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (timerActive) {
                TextButton(onClick = onCancelTimer) { Text("Cancel timer") }
            }
        },
    )
}

private fun downloadActionLabel(state: String?): String = when (state) {
    DownloadState.QUEUED -> "Cancel"
    DownloadState.RUNNING -> "Cancel"
    DownloadState.COMPLETE -> "Delete"
    DownloadState.FAILED -> "Retry"
    DownloadState.CANCELLED -> "Retry"
    else -> "Download"
}

private fun formatDuration(milliseconds: Long): String {
    if (milliseconds <= 0) return "0:00"
    val totalSeconds = milliseconds / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024L ->
        "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L ->
        "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

private fun plainEpisodeText(value: String): String =
    HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .trim()

private fun FeedSummary.categoryIdSet(): Set<String> =
    categoryIds.orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
