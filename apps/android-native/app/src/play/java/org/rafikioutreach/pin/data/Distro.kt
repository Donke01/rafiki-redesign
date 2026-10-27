package org.rafikioutreach.pin.data

import android.app.Activity
import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where this build of RAFIKI Pin comes from, and how it updates (the `play` flavor: Google Play).
 * Google Play doesn't allow an app to update itself, so there is no download-and-install here and no
 * REQUEST_INSTALL_PACKAGES permission. Newer versions come from Play's in-app updates, the flexible flow:
 * Play downloads the update in the background while the person keeps working, then one tap restarts the app.
 * The progress shows in the same Me › Update card as the website build ([Updater.state]).
 * Without Google Play (tests, phones without the Play Store) every call quietly does nothing.
 */
object Distro {
    /** The address to get the app from (donor invites, "Having trouble?"). */
    const val APP_LINK = "https://play.google.com/store/apps/details?id=org.rafikioutreach.pin"

    @Volatile private var mgr: AppUpdateManager? = null
    @Volatile private var info: AppUpdateInfo? = null
    private fun manager(ctx: Context): AppUpdateManager? = mgr ?: runCatching { AppUpdateManagerFactory.create(ctx.applicationContext) }.getOrNull()?.also { mgr = it; it.registerListener(listener) }

    private val listener = InstallStateUpdatedListener { s ->
        Updater.setState(when (s.installStatus()) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> Updater.State.Downloading(s.bytesDownloaded(), s.totalBytesToDownload())
            InstallStatus.DOWNLOADED -> Updater.State.Downloaded
            InstallStatus.INSTALLING -> Updater.State.Installing
            InstallStatus.FAILED -> Updater.State.Failed("install")
            InstallStatus.CANCELED -> Updater.State.Failed("aborted")
            else -> Updater.State.Idle
        })
    }

    /** Asks Google Play for a newer version: its version code, 0 when there is none, null when Play couldn't be asked. */
    suspend fun availableCode(ctx: Context): Int? {
        val m = manager(ctx) ?: return null
        val i = runCatching { withTimeoutOrNull(15_000) { m.requestAppUpdateInfo() } }.getOrNull() ?: return null
        info = i
        // an update already downloaded (the app was closed before the restart): offer the restart again
        if (i.installStatus() == InstallStatus.DOWNLOADED) Updater.setState(Updater.State.Downloaded)
        val ok = (i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) ||
            i.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS || i.installStatus() == InstallStatus.DOWNLOADED
        return if (ok) i.availableVersionCode() else 0
    }

    /** Starts Play's flexible update: Play asks once, then downloads in the background. [url] and [sha256] are the website's and not used. */
    suspend fun start(act: Activity?, ctx: Context, url: String, sha256: String?) {
        val m = manager(ctx) ?: run { Updater.setState(Updater.State.Failed("store")); return }
        if (Updater.state.value is Updater.State.Downloaded) { finish(ctx); return }
        val i = runCatching { withTimeoutOrNull(15_000) { m.requestAppUpdateInfo() } }.getOrNull() ?: info
        if (act == null || i == null || !i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) { Updater.setState(Updater.State.Failed("store")); return }
        Updater.setState(Updater.State.Downloading(0, 0))
        runCatching { m.startUpdateFlow(i, act, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
            .addOnSuccessListener { r -> if (r != Activity.RESULT_OK && Updater.state.value is Updater.State.Downloading) Updater.setState(Updater.State.Idle) }
            .addOnFailureListener { Updater.setState(Updater.State.Failed("store")) } }
            .onFailure { Updater.setState(Updater.State.Failed("store")) }
    }

    /** The update is downloaded: Play installs it and restarts the app. */
    fun finish(ctx: Context) { Updater.setState(Updater.State.Installing); runCatching { manager(ctx)?.completeUpdate() }.onFailure { Updater.setState(Updater.State.Failed("install")) } }
}
