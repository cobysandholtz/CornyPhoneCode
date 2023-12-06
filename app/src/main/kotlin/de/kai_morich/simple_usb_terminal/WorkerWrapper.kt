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

        @JvmStatic fun stopFireBaseWorker(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("FirebaseService")
        }

        @JvmStatic fun startSerialWorker(context: Context){
            val constraints = Constraints.Builder().build()
            val request = OneTimeWorkRequestBuilder<SerialWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        @JvmStatic fun checkWorkerStatus(context: Context) {
//            Operation myOp = WorkManager.getInstance(context).pruneWork()
            val workManager = WorkManager.getInstance(context)

            // Get the WorkInfo for each worker
            val firebaseWorkInfo = getWorkInfoById(workManager, "FirebaseService")
            val serialWorkInfo = getWorkInfoById(workManager, "SerialService")

            // Print the statuses using Log
            Log.d("WM-WorkerWrapper", "Firebase Worker Status: ${firebaseWorkInfo?.state}")
            Log.d("WM-WorkerWrapper", "Serial Worker Status: ${serialWorkInfo?.state}")

        }

        // Helper function to get WorkInfo by work request ID
        private fun getWorkInfoById(workManager: WorkManager, workRequestId: String): WorkInfo? {
            return try {
                val workInfos = workManager.getWorkInfosForUniqueWork(workRequestId).get()
                if (workInfos.isNotEmpty()) {
                    workInfos[0]
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }



    }
}