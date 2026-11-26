package app.momentmal.blocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.momentmal.MainActivity
import app.momentmal.R
import app.momentmal.container

/** Device admin with no policies: its only purpose is that uninstalling requires deactivating it first. */
class AdminReceiver : DeviceAdminReceiver()

object Strict {
    fun admin(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun isAdminActive(context: Context) =
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(context))

    fun requestAdminIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Schutz vor dem schnellen Deinstallieren: Um Moment mal zu entfernen, musst du das erst hier ausschalten – " +
                "und das kostet im strengen Modus 30 Fragen.",
        )

    /** Applies relaxations whose day has come; when strict mode ends, drop the device admin too. */
    suspend fun commitDue(context: Context) {
        if (context.container.blockerPrefs.commitPending()) {
            runCatching { context.getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(admin(context)) }
        }
    }

    /**
     * A relaxing change in strict mode: remember it, then ask for 30 questions.
     * On completion it becomes pending and applies from tomorrow.
     */
    suspend fun requestRelax(context: Context, change: StagedChange) {
        context.container.blockerPrefs.stage(change)
        context.startActivity(
            Intent(context, ChallengeActivity::class.java)
                .putExtra(ChallengeActivity.EXTRA_PKG, context.packageName)
                .putExtra(ChallengeActivity.EXTRA_KEY, StrictRules.KEY_CHANGE)
                .putExtra(ChallengeActivity.EXTRA_REQUIRED, StrictRules.CONFIRM_QUESTIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Opening Android settings from our own setup screen shouldn't trigger the guard. */
    suspend fun allowSettingsBriefly(context: Context) =
        context.container.blockerPrefs.grantGrace(StrictRules.KEY_SETTINGS, minutes = 3)

    // ---------------------------------------------------------------- watchdog

    private const val CHANNEL = "protection"
    private const val NOTIF_ID = 7

    fun checkProtection(context: Context, enabled: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (enabled) { nm.cancel(NOTIF_ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Schutz", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Moment mal ist aus")
            .setContentText("Die Bedienungshilfe ist deaktiviert – Instagram & Co. sind gerade frei.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }
}

/** Every 15 min: is the accessibility service still on? Also applies due pending changes. */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val enabled = BlockerService.isEnabled(ctx)
        if (!enabled) ctx.container.blockerPrefs.recordProtectionOff()
        Strict.checkProtection(ctx, enabled)
        Strict.commitDue(ctx)
        return Result.success()
    }
}
