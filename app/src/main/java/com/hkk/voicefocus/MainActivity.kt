package com.hkk.voicefocus

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hkk.voicefocus.processing.ProcessingService
import com.hkk.voicefocus.ui.FocusApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FocusApp() }
        if (savedInstanceState == null) receive(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receive(intent) }
    override fun onStop() { repository.player.pause(); super.onStop() }
    @Suppress("DEPRECATION")
    private fun receive(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && !repository.task.value.active) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { ProcessingService.start(this, "import", uri = it) }
        }
    }
}
