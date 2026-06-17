package com.smgray.easypod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smgray.easypod.ui.EasyPodApp
import com.smgray.easypod.ui.EasyPodTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                EasyPodApp(viewModel)
            }
        }
    }
}
