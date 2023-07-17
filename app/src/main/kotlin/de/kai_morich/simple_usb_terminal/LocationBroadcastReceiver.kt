package de.kai_morich.simple_usb_terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import com.google.android.gms.location.LocationResult

class LocationBroadcastReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("LocationReceiver", "onReceive")

        if(intent.action == ACTION_PROCESS_UPDATES){
            if(LocationResult.hasResult(intent)){
                currentLocation = LocationResult.extractResult(intent)!!.lastLocation
            }
        }
    }

    companion object {
        var currentLocation: Location? = null
        const val ACTION_PROCESS_UPDATES = "de.kai_morich.simple_usb_terminal.LocationBroadcastReceiver.ACTION_PROCESS_UPDATES"
    }
}