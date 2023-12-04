package de.kai_morich.simple_usb_terminal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// maybe try PeriodicWorker instead
/**
 * A wrapper class for starting an instance of FirebaseService that makes it less likely
 * to get put to sleep by the system
 * */
@RequiresApi(Build.VERSION_CODES.O)
class FirebaseWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        private var TAG = this::class.simpleName
        private var NOTIFICATION_ID = 9973
    }

    /**
     * Inherited form CoroutineWorker.
     * Starts a new instance of FirebaseService if one does not already
     *
     * //todo: instantiating the firebaseService doesn't seem like the actual work that needs to be done in the background, unless there
     * //is a lot of overhead associated with connecting and authenticating. Shouldn't the uploading itself be done in the background?
     * */
    override suspend fun doWork(): Result {
        //do not launch if the service is already alive
        if(FirebaseService.instance == null){
            withContext(Dispatchers.IO){
                val trackerServiceIntent = Intent(context, FirebaseService::class.java)
                ServiceNotification.notificationText = "do not close the app, please"
                ServiceNotification.notificationIcon = R.mipmap.ic_launcher
                Log.i(TAG, "Launching tracker")
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                    context.startForegroundService(trackerServiceIntent)
                } else {
                    context.startService(trackerServiceIntent)
                }
            }
        } else {
            FirebaseService.instance?.uploadLog()
        }
        return Result.success()
    }

    /**
     * Required by the system because reasons
     * */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ServiceNotification.notificationText = "do not close the app, please"
        ServiceNotification.notificationIcon = R.mipmap.ic_launcher
        val notification = ServiceNotification(context, NOTIFICATION_ID, true)
        return ForegroundInfo(NOTIFICATION_ID, notification.notification!!)
    }
}