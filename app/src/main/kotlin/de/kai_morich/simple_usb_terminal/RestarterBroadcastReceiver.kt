package de.kai_morich.simple_usb_terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * A custom BroadcastReceiver subclass that receives intents for when
 * the system has rebooted and restarts the services this app needs
 * */
class RestarterBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        startWorker(context)
    }

    private fun startWorker(context: Context) {
        val constraints = Constraints.Builder().build()
        var request = OneTimeWorkRequestBuilder<FirebaseWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueue(request)

        request = OneTimeWorkRequestBuilder<SerialWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}