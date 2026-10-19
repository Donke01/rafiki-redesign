package org.rafikioutreach.pin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * English and Kiswahili. Every word on screen goes through t(english, kiswahili), so switching
 * the language changes the whole app at once.
 */
object Lang {
    var sw by mutableStateOf(false)
}

fun t(en: String, sw: String): String = if (Lang.sw) sw else en
