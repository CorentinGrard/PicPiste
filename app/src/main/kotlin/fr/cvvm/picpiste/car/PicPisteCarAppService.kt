package fr.cvvm.picpiste.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import fr.cvvm.picpiste.repository

/** Entry point for Android Auto (navigation category, sideloaded). */
class PicPisteCarAppService : CarAppService() {
    // Private club app installed by hand: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = RadarSession()
}

class RadarSession : Session() {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = carContext.repository.acquire()
            override fun onDestroy(owner: LifecycleOwner) = carContext.repository.release()
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = RadarScreen(carContext)
}
