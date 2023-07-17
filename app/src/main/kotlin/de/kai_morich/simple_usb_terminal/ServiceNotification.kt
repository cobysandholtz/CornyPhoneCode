package de.kai_morich.simple_usb_terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * A class that handles creating notifications. Adapted not very well from another online source
 *
 *
 * TODO: unify use of notifications throughout app
 * */
class ServiceNotification @JvmOverloads constructor (
    private var context: Context,
    private val mId: Int,
    runningInBackground: Boolean = false
) {
    private var notificationBuilder: NotificationCompat.Builder? = null
    var notification: Notification? = null
    private var notificationPendingIntent: PendingIntent? = null
    private var notificationManager: NotificationManager? = null

    companion object {
        private val TAG : String = Notification::class.java.simpleName
        private val  CHANNEL_ID = TAG
        var notificationIcon = 0
        var notificationText: String? = null
        const val notificationStopRequestCode = 23
        const val notificationId = 74
    }

    init {
        notification = if(runningInBackground) {
            setNotification(context, "Text Content")
        } else {
            setNotification(context, "Firebase Never Ending Service", "Text content", R.mipmap.ic_launcher)
        }
    }

    //the method that can be called to update the notification
    fun setNotification(context: Context, title: String?, text: String?, icon: Int): Notification {
        val notification: Notification
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        //OREO
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            //create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            val name: CharSequence = "Permanent ServiceNotification"
            val importance: Int = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val description = "I would like to receive travel alerts and notifications for:"
            channel.description = description
            notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            if(notificationManager != null){
                notificationManager!!.createNotificationChannel(channel)
            }

            val stopNotificationIntent = Intent(context, FirebaseService.ActionListener::class.java)
            stopNotificationIntent.action = FirebaseService.KEY_NOTIFICATION_STOP_ACTION
            stopNotificationIntent.putExtra(FirebaseService.KEY_NOTIFICATION_ID, notificationId)
            val pendingStopNotificationIntent = PendingIntent.getBroadcast(
                context,
                notificationStopRequestCode,
                stopNotificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )


            notification = notificationBuilder!!
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(notificationPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(R.mipmap.ic_launcher, "Stop Uploading", pendingStopNotificationIntent)
                .build()
        } else {
            notification = NotificationCompat.Builder(context, "channelID")
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(
                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) NotificationManager.IMPORTANCE_MIN
                    else NotificationManager.IMPORTANCE_MIN
                )
                .setContentIntent(notificationPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        }
        this.notification = notification
        return notification
    }

    private fun setNotification(context: Context, text: String?): Notification {
        return setNotification(context, "Notification Title", text, R.mipmap.ic_launcher)
    }
}