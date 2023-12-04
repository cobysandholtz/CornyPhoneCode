package de.kai_morich.simple_usb_terminal

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit


/**
 * A wrapper class that makes it easier to start a given service
 * */
class WorkerWrapper {

    companion object {
        @JvmStatic fun startFirebaseWorker(context: Context){
//            val constraints = Constraints.Builder().build()
//            val request = OneTimeWorkRequestBuilder<FirebaseWorker>() //todo: when uncommented, the firebase worker actually launches, but then we seem to have two instances, only one of which has auth tokens?
//                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
//                .setConstraints(constraints)
//                .build()
//            WorkManager.getInstance(context).enqueue(request)
            Log.w("WM-WorkerWrapper", "startFirebaseWorker()");
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