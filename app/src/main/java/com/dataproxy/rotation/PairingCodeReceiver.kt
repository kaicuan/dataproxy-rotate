package com.dataproxy.rotation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.dataproxy.DataProxyApplication

class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = results.getCharSequence(PairingNotification.KEY_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (code.isEmpty()) return

        val app = context.applicationContext as? DataProxyApplication ?: return
        app.rotationController.submitPairingCode(code)
    }
}
