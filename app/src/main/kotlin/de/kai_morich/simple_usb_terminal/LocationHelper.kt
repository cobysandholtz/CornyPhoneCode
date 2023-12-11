package de.kai_morich.simple_usb_terminal

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.TimeUnit

private const val TAG: String = "LocationHelper"

class LocationHelper constructor(private val context: Context) {

    private val _receivingLocationUpdates: MutableLiveData<Boolean> = MutableLiveData(false)

    // The main hook into google's location api
    private val fusedLocationProviderClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // Configure parameters according to our location needs
    private var locationRequest: LocationRequest = LocationRequest.Builder(TimeUnit.MINUTES.toMillis(5)).apply {
        // Sets the desired interval for active location updates. This is inexact
//        setInterval(TimeUnit.MINUTES.toMillis(5))
//
//        // sets the fastest rate for active location updates. Will never be faster
          setMinUpdateIntervalMillis(TimeUnit.MINUTES.toMillis(1))
//
//        //sets the max time between when location is reported
          setMaxUpdateDelayMillis(TimeUnit.MINUTES.toMillis(1))
//
//        //TODO: test how different the levels of power/accuracy change
//
//        // set our preference concerning power/accuracy
//        // 4 Options:
//        // +----------------------------------+------------------------------------------------------------------------------+
//        // | PRIORITY_BALANCED_POWER_ACCURACY | Tradeoff between accuracy and power usage                                    |
//        // | PRIORITY_HIGH_ACCURACY           | Favors highly accurate locations at the possible expense of extra power      |
//        // | PRIORITY_LOW_POWER               | Favors low power usage at the expense of location accuracy                   |
//        // | PRIORITY_PASSIVE                 | Ensure no extra power usage, only receives location as other clients request |
//        // +----------------------------------+------------------------------------------------------------------------------+
        setPriority(Priority.PRIORITY_LOW_POWER)

        // other parameters of LocationRequest that may be worth messing with:
        // smallestDisplacement, expirationTime
    }.build()

    fun changePriority(priority: Int) {
        locationRequest = LocationRequest.Builder(locationRequest).setPriority(priority).build()
        startLocationUpdates()
    }


    // configure where we want the system to send the result of our request
    private val locationUpdatePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, LocationBroadcastReceiver::class.java)
        intent.action = LocationBroadcastReceiver.ACTION_PROCESS_UPDATES
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    @SuppressLint("MissingPermission") // Location checks are always performed before this method is called
    fun startLocationUpdates() {
        Log.d(TAG, "startLocationUpdates()")
        _receivingLocationUpdates.value = true

        Toast.makeText(
            context,
            "Starting GPS, Priority: " + locationRequest.priority,
            Toast.LENGTH_SHORT
        ).show()

        fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationUpdatePendingIntent).apply {
            addOnSuccessListener { Toast.makeText(context, "GPS success", Toast.LENGTH_SHORT).show() }
        }
    }

    fun stopLocationUpdates(){
        Log.d(TAG, "stopLocationUpdates()")
        _receivingLocationUpdates.value = false
        fusedLocationProviderClient.removeLocationUpdates(locationUpdatePendingIntent)
    }
}