package com.smgray.easypod

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smgray.easypod.ui.EasyPodApp
import com.smgray.easypod.ui.EasyPodTheme

class MainActivity : ComponentActivity() {
    private var incomingIntent by mutableStateOf<Intent?>(null)
    private var incomingSearchQuery by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            incomingIntent = intent
        }

        val container = (application as EasyPodApplication).container
        setContent {
            EasyPodTheme {
                val viewModel: MainViewModel = viewModel(
                    factory = MainViewModel.factory(
                        repository = container.repository,
                        backupService = container.backupService,
                        importer = container.legacyBackupImporter,
                        feedIngestionService = container.feedIngestionService,
                        episodeDownloadManager = container.episodeDownloadManager,
                        feedRefreshManager = container.feedRefreshManager,
                        playbackConnection = container.playbackConnection,
                        syncManager = container.syncManager,
                    ),
                )
                val intentToHandle = incomingIntent
                LaunchedEffect(intentToHandle, viewModel) {
                    intentToHandle?.let {
                        val query = IncomingSearchIntents.queryFrom(it)
                        if (query != null) {
                            incomingSearchQuery = query
                        } else {
                            viewModel.handleIncomingIntent(it)
                        }
                        incomingIntent = null
                    }
                }
                EasyPodApp(
                    viewModel = viewModel,
                    externalEpisodeSearchQuery = incomingSearchQuery,
                    onExternalEpisodeSearchConsumed = {
                        incomingSearchQuery = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntent = intent
    }
}
