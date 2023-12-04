package de.kai_morich.simple_usb_terminal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A custom implementation of CoroutineWorker for starting an instance of SerialService
 * in a way that is less likely to get put to sleep by the system
 * */
@RequiresApi(Build.VERSION_CODES.O)
class SerialWorker (private val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    companion object {
        private var TAG = this::class.simpleName
        private var NOTIFICATION_ID = 9973
    }

    /**
     * Inherited from CoroutineWorker
     * Starts a new instance of SerialService, if one does not already exist
     * */
    override suspend fun doWork(): ListenableWorker.Result {
        //do not launch if the service is already alive
        if(SerialService.getInstance() == null){
            withContext(Dispatchers.IO){
                val trackerServiceIntent = Intent(context, SerialService::class.java)
                ServiceNotification.notificationText = "do not close the app, please"
                ServiceNotification.notificationIcon = R.mipmap.ic_launcher
                Log.i(TAG, "Launching tracker")
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                    context.startForegroundService(trackerServiceIntent)
                } else {
                    context.startService(trackerServiceIntent)
                }
            }
        }

        return Result.success()
    }

    /**
     * Required by the system, because reasons
     * */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ServiceNotification.notificationText = "do not close the app, please"
        ServiceNotification.notificationIcon = R.mipmap.ic_launcher
        val notification = ServiceNotification(context, NOTIFICATION_ID, true)
        return ForegroundInfo(NOTIFICATION_ID, notification.notification!!)
    }
}