package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.lifecycleScope
import com.example.ui.TurntableApp
import com.example.ui.TurntableViewModel
import com.example.ui.theme.TurntableTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: TurntableViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOAuthIntent(intent)

        setContent {
            TurntableTheme {
                TurntableApp(
                    viewModel = viewModel,
                    onLaunchSpotifyAuth = ::launchSpotifyAuthorization,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForegroundStarted()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onForegroundStopped()
    }

    private fun launchSpotifyAuthorization() {
        lifecycleScope.launch {
            val authUri = viewModel.prepareSpotifyAuthUri()
            if (authUri == null) {
                return@launch
            }
            val customTabs = CustomTabsIntent.Builder().build()
            runCatching {
                customTabs.launchUrl(this@MainActivity, authUri)
            }.onFailure {
                val browserIntent = Intent(Intent.ACTION_VIEW, authUri)
                runCatching { startActivity(browserIntent) }
            }
        }
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme == "turntable" && data.host == "spotify-callback") {
            viewModel.handleAuthCallback(data)
            intent.data = null
        }
    }
}
