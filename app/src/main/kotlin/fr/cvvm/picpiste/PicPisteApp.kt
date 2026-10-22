package fr.cvvm.picpiste

import android.app.Application
import android.content.res.Configuration
import fr.cvvm.picpiste.core.i18n.Texts
import fr.cvvm.picpiste.data.DdbRefreshWorker
import fr.cvvm.picpiste.data.TrafficRepository

class PicPisteApp : Application() {
    val repository: TrafficRepository by lazy { TrafficRepository(this) }

    override fun onCreate() {
        super.onCreate()
        useLanguageOf(resources.configuration)
        DdbRefreshWorker.schedule(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        useLanguageOf(newConfig)
    }
}

val android.content.Context.repository: TrafficRepository
    get() = (applicationContext as PicPisteApp).repository

/**
 * Core texts (statuses, alerts, voice) in the language Android picked for the app: French on a
 * French phone, English otherwise, like the string resources. Also follows Android's per-app
 * language setting.
 */
fun useLanguageOf(config: Configuration) {
    Texts.current = Texts.forLanguage(config.locales[0].language)
}
