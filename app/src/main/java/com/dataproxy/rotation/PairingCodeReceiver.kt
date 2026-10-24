package com.dataproxy.rotation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.dataproxy.DataProxyApplication

class PairingCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        PairingNotification.cancel(context)

        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotification.KEY_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()

        val app = context.applicationContext as? DataProxyApplication ?: return
        app.rotationController.submitPairingCode(code)
    }
}
