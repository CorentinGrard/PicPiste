package fr.cvvm.picpiste.ui

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import fr.cvvm.picpiste.render.Colors
import fr.cvvm.picpiste.render.Palette
import fr.cvvm.picpiste.repository
import fr.cvvm.picpiste.useLanguageOf

class MainActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        repository.onPermissionsChanged()
    }

    /** An airfield file another app asked us to open (shared by another driver). */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The activity's configuration carries the per-app language: recreated when it changes.
        useLanguageOf(resources.configuration)
        enableEdgeToEdge()
        // Used as a dashboard in the cart: keep the screen on while visible.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Right colours from the first frame, before the theme setting is collected.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        Palette.current = if (repository.prefs.theme.value.isDark(systemDark)) Colors.DARK else Colors.LIGHT
        if (savedInstanceState == null) incoming.value = airfieldUri(intent)
        setContent { PhoneApp(repository, incoming.value) { incoming.value = null } }
        askPermissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        airfieldUri(intent)?.let { incoming.value = it }
    }

    private fun airfieldUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }

    override fun onStart() {
        super.onStart()
        repository.acquire()
    }

    override fun onStop() {
        repository.release()
        super.onStop()
    }
}
