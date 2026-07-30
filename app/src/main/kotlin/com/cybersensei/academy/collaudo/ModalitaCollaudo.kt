package com.cybersensei.academy.collaudo

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The switch that makes every padlock in the school stop counting.
 *
 * It exists for one person and one purpose: whoever is building this app has to be able to look
 * at a case of level 3 without first studying a hundred lessons, and there is no other way to
 * check a screen that only opens after months of work.
 *
 * Two decisions worth writing down.
 *
 * It opens doors and **nothing else**. It never marks a lesson done, never awards a trophy,
 * never passes a level: the student's record stays exactly what it was, so the progression is
 * still being tested every time the switch goes back off. A tester mode that also faked
 * progress would hide precisely the bugs it exists to find.
 *
 * It lives here, in the app module, on top of ordinary preferences rather than in the school's
 * records. That is deliberate: this whole file is meant to be deleted before 1.0, and something
 * stored in the database would need a migration to add and another one to take away. Deleting
 * this costs one file and the handful of places that read it.
 */
@Singleton
class ModalitaCollaudo @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _attiva = MutableStateFlow(preferences.getBoolean(CHIAVE, false))

    /** Watched by the screens, so flipping the switch redraws the path without a restart. */
    val attiva: StateFlow<Boolean> = _attiva.asStateFlow()

    /** Read where a flow would be awkward — inside a coroutine that is already computing. */
    val accesa: Boolean get() = _attiva.value

    fun imposta(valore: Boolean) {
        preferences.edit().putBoolean(CHIAVE, valore).apply()
        _attiva.value = valore
    }

    private companion object {
        const val FILE = "cybersensei.collaudo"
        const val CHIAVE = "lucchetti_aperti"
    }
}
