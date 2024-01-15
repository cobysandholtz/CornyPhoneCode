package de.kai_morich.simple_usb_terminal

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

/**
 * A class that serves as a wrapper for connecting and uploading files
 * to Firebase Cloud Storage
 *
 * TODO: keep track of how many files we create and start to delete some of them
 * */
@RequiresApi(Build.VERSION_CODES.O)
class FirebaseService : Service() {
    private var currentNotification: ServiceNotification? = null

    // A wakelock is what helps us prevent this service from getting put to sleep
    private var wakeLock: PowerManager.WakeLock? = null

    // A Handler serves to run bits of code for us when we want it to
    private var handler: Handler? = null
    private lateinit var timeoutRunnable: Runnable
    // How long, in ms, we should wait in between uploading the log
    private val uploadDelay = 10.minutes.inWholeMilliseconds

    private var logFw: FileWriter? = null
    private var logFile: File? = null

    private var temperatureFw: FileWriter? = null
    private var temperatureFile: File? = null

    private var headingFw: FileWriter? = null
    private var headingFile: File? = null

    companion object {
        private val TAG = FirebaseService::class.java.simpleName
        var instance: FirebaseService? = null
        private const val NOTIFICATION_ID = 3956

        const val KEY_NOTIFICATION_ID = "notificationID"
        const val KEY_NOTIFICATION_STOP_ACTION =
            "de.kai_morich.simple_usb_terminal.NOTIFICATION_STOP"

        fun getServiceInstance(): FirebaseService {
            if (instance == null) {
                instance = FirebaseService()
            }
            return instance!!
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Firebase Service onCreate()")


        val path = applicationContext.getExternalFilesDir(null)
        logFile = File(path,getDateTime() + "_log.txt")
        logFw = FileWriter(logFile)

        temperatureFile = File(path,getDateTime() + "_templog.txt")
        temperatureFw = FileWriter(temperatureFile)

        headingFile = File(path,getDateTime() + "_headings.txt")
        headingFw = FileWriter(headingFile)
    }

    /**
     * Called by the system when another part of this app calls startService or startForegroundService
     * Starts the wakelock and creates the notification the system requires of us, then starts
     * the handler for regularly uploading
     * */
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d(TAG, "starting foreground service")
        startWakeLock()
        try {
            currentNotification = ServiceNotification(this, NOTIFICATION_ID, false)
            currentNotification!!.setNotification(this, "Terminal Upload Service", "Currently Running", R.mipmap.ic_launcher)
            startForeground(NOTIFICATION_ID, currentNotification!!.notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground process " + e.message)
        }
        startHandler()
        return START_REDELIVER_INTENT
    }

    @SuppressLint("WakelockTimeout")
    /* Studio is mad that we don't provide a timeout, but
     *    in this case it's on purpose
     */
    /**
     * Retrieve a wakelock from the system to tell it "Don't put this to sleep"
     * */
    private fun startWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        wakeLock?.acquire()
    }

    /**
     * Start the repeating loop inside the handler that regularly calls uploadLog()
     * */
    private fun startHandler() {
//        Toast.makeText(applicationContext, "Starting handler", Toast.LENGTH_SHORT).show();
        val looper = Looper.myLooper()
        looper.let {
            handler = Handler(looper!!)

            timeoutRunnable = Runnable {
                System.out.println("About to upload log")
                uploadLog()
//                Toast.makeText(applicationContext, "Handler fired", Toast.LENGTH_SHORT).show();
                //after uploadDelay milliseconds, run the code inside timeoutRunnable again
                handler?.postDelayed(timeoutRunnable, uploadDelay)
            }
            handler?.postDelayed(timeoutRunnable, uploadDelay)
        }
    }

    /**
     * Stop uploading the logs
     * */
    private fun stopHandler() {
//        Toast.makeText(applicationContext, "Stopping handler", Toast.LENGTH_SHORT).show();
        try {
            //TODO is this actually stopping it?
            handler!!.looper.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop handler " + e.message)
        }
    }

    /**
     * Called by the system, hopefully never
     * */
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        stopHandler()
        if (wakeLock != null) wakeLock!!.release()
        stopService(Intent(applicationContext, FirebaseService::class.java))
    }

    /**
     * Inherited from Service
     * Unused
     * */
    override fun onBind(intent: Intent): IBinder? {
        Log.i(TAG, "onBind")
        return null
    }

    /**
     * Uploads the provided File [file] to remote Firebase Storage under the directory
     * log/*deviceName*/*filename*
     * */
    private fun uploadFile(file: File, dir: String) {
        Log.i(TAG, "uploadFile()")
        val storageRef = FirebaseStorage.getInstance().reference
        val uri = Uri.fromFile(file)
        //build the reference of where we want to put the file in Firebase
        val fileRef = storageRef.child(
            "$dir/"
                    + Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
                    + "/" + uri.lastPathSegment
        )
        fileRef.putFile(uri)
            .addOnSuccessListener {
                Toast.makeText(applicationContext, "Upload Success $dir", Toast.LENGTH_SHORT).show()
                Log.i(TAG,"Upload success for $fileRef")
            }
            .addOnFailureListener {
//                Toast.makeText(applicationContext, it.message, Toast.LENGTH_SHORT).show()
                Log.i(TAG,"Upload failure for $fileRef")
            }
    }

    /**
     * Uploads a basic .txt file as a test to Firebase under the directory
     * test/*deviceName*/YYYY-MM-DD_hh:mm:ss_[origin].txt
     * */
    fun testUpload(origin: String) {
        Log.i(TAG, "testUpload()")
        val storageRef = FirebaseStorage.getInstance().reference
        val path = getExternalFilesDir(null)
        val file = File(path, "file.txt")
        try {
            val fw = FileWriter(file)
            fw.write("some text")
            fw.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val fileRef = storageRef.child(
            "test/"
                    + Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
                    + "/"
                    + getDateTime()
                    + "_"
                    + origin
                    + ".txt"
        )
        fileRef.putFile(Uri.fromFile(file))
            .addOnSuccessListener {
                Toast.makeText(applicationContext, "Test Upload Success", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Adds [csv] as a new line on the end of the current file
     * */
    fun appendFile(csv: String) {
        //synchronized to prevent the off chance that we try to write to a file that is currently
        // being uploaded
        synchronized(this) {
            logFw?.write(csv)
        }
    }

    /**
     * Uploads the log file in whatever its current state is to Firebase,
     * and creates a new FileWriter.
     * Note that files use the timestamp of when they were created, not when they were uploaded
     * */
    fun uploadLog() {
        Log.i(TAG, "upload log called")
        synchronized(this) {
            //close the FileWriter
            logFw?.close()
            //upload the log
            // the ?.let syntax is Kotlin shorthand for a null check
            logFile?.let { uploadFile(it, "log") }
            //create new File + FileWriter
            val path = applicationContext.getExternalFilesDir(null)
            logFile = File(path,getDateTime() + "_log.txt")
            logFw = FileWriter(logFile)

            temperatureFw?.close()
            temperatureFile?.let { uploadFile(it, "geckoTemp") }
            temperatureFile = File(path, getDateTime() + "_templog.txt")
            temperatureFw = FileWriter(temperatureFile)

            headingFw?.close()
            headingFile?.let { uploadFile(it, "headings") }
            headingFile = File(path, getDateTime() + "_headings.txt")
            headingFw = FileWriter(headingFile)
            headingFw!!.write("datetime,headingCurrent,headingMin,headingMax,headingMinAsMax,headingOld\n")
        }
    }

    fun appendTemp(temp: Int) {
        synchronized(this) {
            temperatureFw?.write(getDateTime() + "," + temp + "\n")
        }
    }

//    fun appendHeading(
//            potHeading: Double,
//            magHeading: FloatArray,
//            headingMin: Double,
//            headingMax: Double,
//            treatHeadingMinAsMax: Boolean,
//            oldHeading: Double,
//            state: String
//    ) {
//        synchronized(this) {
//            headingFw?.write("${getDateTime()},$potHeading, ${magHeading.joinToString(",")}, $headingMin,$headingMax,$treatHeadingMinAsMax,$oldHeading,$state\n")
//        }
//    }

    fun appendHeading(csv: String) {
        synchronized(this) {
            headingFw?.write(csv)
        }
    }

    private fun getDateTime(): String{
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm_ss"))
    }

    /**
     * A custom BroadcastReceiver subclass that receives intents from the persistent notification
     * to stop this service and stop uploading
     * */
    class ActionListener : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && intent.action != null) {
                if (intent.action.equals(KEY_NOTIFICATION_STOP_ACTION)) {
                    context?.let {
                        context.stopService(Intent(context, FirebaseService::class.java))
                        Log.i(TAG, "Stopped FirebaseService")
                        val notificationManager =
                            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val notificationId = intent.getIntExtra(KEY_NOTIFICATION_ID, -1)
                        if (notificationId != -1) {
                            notificationManager.cancel(notificationId)
                        }
                    }
                }
            }
        }

    }


}