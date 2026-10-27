package org.rafikioutreach.pin.data

import android.app.Activity
import android.content.Context

/**
 * Where this build of RAFIKI Pin comes from, and how it updates (the `site` flavor: the APK on the website).
 * The app checks /app/version.json, downloads the newer APK itself and hands it to Android's installer
 * ([Updater]). The `play` flavor has its own Distro (src/play) that leaves updates to Google Play.
 */
object Distro {
    /** The address to get the app from (donor invites, "Having trouble?"). */
    const val APP_LINK = "https://pin.rafikioutreach.org/app/rafiki-pin.apk"

    /** Not used on the website build: newer versions are found through version.json (AppModel.checkUpdate). */
    suspend fun availableCode(ctx: Context): Int? = null

    /** Downloads the newer APK and installs it. */
    suspend fun start(act: Activity?, ctx: Context, url: String, sha256: String?) = Updater.run(ctx, url, sha256)

    /** Nothing to finish: Android's installer takes over. */
    fun finish(ctx: Context) {}
}
