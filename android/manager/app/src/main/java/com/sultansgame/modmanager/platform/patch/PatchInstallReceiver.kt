package com.sultansgame.modmanager.platform.patch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class PatchInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        PatchInstallResults.emit(intent)
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.sultansgame.modmanager.action.PATCH_INSTALL_RESULT"
    }
}

internal object PatchInstallResults {
    private val mutableResults = MutableSharedFlow<Intent>(extraBufferCapacity = 8)
    val results = mutableResults.asSharedFlow()

    fun emit(intent: Intent) {
        mutableResults.tryEmit(Intent(intent))
    }
}
