package fr.cvvm.picpiste.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import fr.cvvm.picpiste.repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** "Posés": today's landings, most recent first (car list template). */
class LandedScreen(carContext: CarContext) : Screen(carContext) {
    private val repo = carContext.repository
    private var job: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Lists may only be refreshed now and then: only when the content changes.
                job = lifecycleScope.launch {
                    repo.snapshot
                        .map { s -> s.landed.map { "${it.address}${it.landedText}${(s.timeMs - (it.landedAtMs ?: 0)) / 60_000}" } }
                        .distinctUntilChanged()
                        .collect { invalidate() }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                job?.cancel()
            }
        })
    }

    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val snap = repo.snapshot.value
        val list = ItemList.Builder().setNoItemsMessage("Aucun atterrissage pour l'instant")
        for (a in snap.landed.take(6)) {
            val ago = a.landedAtMs?.let { (snap.timeMs - it) / 60_000 }?.let { if (it < 1) "à l'instant" else "il y a $it min" } ?: ""
            val who = listOfNotNull(a.identity.registration, a.identity.model).joinToString(" · ")
            list.addItem(
                Row.Builder()
                    .setTitle("${a.identity.label} — ${a.landedText ?: "posé"}")
                    .addText(listOf(who, ago).filter { it.isNotEmpty() }.joinToString(" · "))
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Posés")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
