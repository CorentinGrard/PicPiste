package fr.cvvm.picpiste

import android.app.Application
import fr.cvvm.picpiste.data.TrafficRepository

class PicPisteApp : Application() {
    val repository: TrafficRepository by lazy { TrafficRepository(this) }
}

val android.content.Context.repository: TrafficRepository
    get() = (applicationContext as PicPisteApp).repository
