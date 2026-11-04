package com.example.util

import java.util.Locale

/**
 * La langue de l'application : français, anglais ou allemand.
 *
 * Elle suit la langue du téléphone (ou, depuis Android 13, celle choisie pour
 * l'application dans les réglages d'Android, voir `res/xml/locales_config.xml`). Une
 * autre langue de téléphone retombe sur le français, qui est aussi la langue des
 * textes par défaut (`res/values`) : sans cette règle, les dates et les nombres
 * suivraient la langue du téléphone pendant que les textes restent en français.
 *
 * Ne jamais écrire `Locale.FRANCE` pour formater quelque chose d'affiché : c'était la
 * règle du temps où l'application n'existait qu'en français.
 */
object AppLocale {
    private val SUPPORTED = setOf("fr", "en", "de")

    /** La locale à utiliser pour dates, nombres et recherche de lieux. */
    val current: Locale
        get() {
            val device = Locale.getDefault()
            return if (device.language in SUPPORTED) device else Locale.FRANCE
        }
}
