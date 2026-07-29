package com.sultansgame.modmanager.platform.patch

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream

sealed interface PackageInstallSubmission {
    data class Submitted(val sessionId: Int) : PackageInstallSubmission
    data class Unavailable(val reason: String) : PackageInstallSubmission
    data class Failed(val reason: String) : PackageInstallSubmission
}

data class PackageInstallStatus(
    val sessionId: Int,
    val status: Int,
    val message: String?,
    val packageName: String?,
    val userActionIntent: Intent?,
) {
    val requiresUserAction: Boolean
        get() = status == PackageInstaller.STATUS_PENDING_USER_ACTION

    val succeeded: Boolean
        get() = status == PackageInstaller.STATUS_SUCCESS
}

class PackageInstallerGateway(private val context: Context) {
    fun canRequestInstalls(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

    fun statusReceiver(transactionId: String): IntentSender {
        val intent = Intent(context, PatchInstallReceiver::class.java)
            .setAction(PatchInstallReceiver.ACTION_INSTALL_RESULT)
            .setPackage(context.packageName)
            .putExtra(EXTRA_TRANSACTION_ID, transactionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            transactionId.hashCode(),
            intent,
            flags,
        ).intentSender
    }

    fun submit(
        transactionId: String,
        artifacts: List<File>,
        statusReceiver: IntentSender = statusReceiver(transactionId),
    ): PackageInstallSubmission {
        if (!canRequestInstalls()) {
            return PackageInstallSubmission.Unavailable("尚未授予安装未知应用权限。")
        }
        if (artifacts.isEmpty() || artifacts.any { !it.isFile }) {
            return PackageInstallSubmission.Failed("安装集合不完整。")
        }
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(GAME_PACKAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManagerCompat.INSTALL_REASON_USER)
            }
        }
        val sessionId = installer.createSession(parameters)
        return try {
            installer.openSession(sessionId).use { session ->
                artifacts.forEachIndexed { index, artifact ->
                    FileInputStream(artifact).use { input ->
                        session.openWrite("$index-${artifact.name}", 0, artifact.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }
                session.commit(statusReceiver)
            }
            PackageInstallSubmission.Submitted(sessionId)
        } catch (error: Throwable) {
            installer.abandonSession(sessionId)
            PackageInstallSubmission.Failed(error.message ?: "无法提交系统安装会话。")
        }
    }

    fun parseStatus(intent: Intent): PackageInstallStatus? {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0) return null
        return PackageInstallStatus(
            sessionId = sessionId,
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME),
            userActionIntent = intent.userActionIntent(),
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.userActionIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "transactionId"
        const val GAME_PACKAGE = "com.gametree.sultan.pd"
    }
}

private object PackageManagerCompat {
    const val INSTALL_REASON_USER = 4
}
