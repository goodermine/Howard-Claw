package au.howardagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device booted, starting Howard services")

            // Start gateway service
            val gatewayIntent = Intent(context, GatewayService::class.java)
            context.startForegroundService(gatewayIntent)

            // Start inference service
            val inferenceIntent = Intent(context, InferenceService::class.java)
            context.startForegroundService(inferenceIntent)
        }
    }
}
