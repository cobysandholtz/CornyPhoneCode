package de.kai_morich.simple_usb_terminal

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * A wrapper class that makes it easier to start a given service
 * */
class WorkerWrapper {
    companion object {
        @JvmStatic fun startFirebaseWorker(context: Context){
            val constraints = Constraints.Builder().build()
//            val request = OneTimeWorkRequestBuilder<FirebaseWorker>()
//                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
//                .setConstraints(constraints)
//                .build()
//            WorkManager.getInstance(context).enqueue(request)
            val periodicWorker = PeriodicWorkRequestBuilder<FirebaseWorker>(15,
                TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("FirebaseService",
                ExistingPeriodicWorkPolicy.KEEP, periodicWorker)
        }
        @JvmStatic fun startSerialWorker(context: Context){
            val constraints = Constraints.Builder().build()
            val request = OneTimeWorkRequestBuilder<SerialWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

    }
}