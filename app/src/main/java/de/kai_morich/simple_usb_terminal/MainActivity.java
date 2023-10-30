package de.kai_morich.simple_usb_terminal;

import static java.util.concurrent.TimeUnit.MINUTES;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.StrictMode;
import android.provider.Settings;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;
import androidx.work.PeriodicWorkRequest;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.OnTokenCanceledListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

@RequiresApi(api = Build.VERSION_CODES.O)
public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private StorageReference storageRef;
    private LocationHelper locationHelper;

    ActivityResultLauncher<String[]> locationPermissionRequest =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                        Boolean fineLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                        Boolean coarseLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);
                        if (fineLocationGranted != null && fineLocationGranted) {
                            // Precise location access granted.
                            Toast.makeText(this, "Correct Location Permissions", Toast.LENGTH_SHORT).show();
                        } else if (coarseLocationGranted != null && coarseLocationGranted) {
                            // Only approximate location access granted.
                            Toast.makeText(this, "Bad Location Permissions", Toast.LENGTH_SHORT).show();
                        } else {
                            // No location access granted.
                            Toast.makeText(this, "No Location Permissions", Toast.LENGTH_SHORT).show();
                        }
                    }
            );


    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //helps with debugging "Resource Failed to Close" log message (which turned out to be too arcane to be worth worrying about)
        // see https://stackoverflow.com/questions/66620246/a-resource-failed-to-call-close
//        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
//                .detectLeakedClosableObjects()
//                .build());

        //following line will keep the screen active
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // creates a ui element toolbar and sets the action
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // adds listener for when you press the back button and it needs to decide where to go
        getSupportFragmentManager().addOnBackStackChangedListener(this);

        // starts service to inteface with magnetometer and accelerometer
        startService(new Intent(this, SensorHelper.class));

        // starts processes for firebase uploading and starting serial service
        WorkerWrapper.startFirebaseWorker(getApplicationContext());
        WorkerWrapper.startSerialWorker(getApplicationContext());


        locationPermissionRequest.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });

        // passes gps location around to where it is needed
       locationHelper = new LocationHelper(this);
       locationHelper.startLocationUpdates();

        FirebaseStorage storage = FirebaseStorage.getInstance();
        storageRef = storage.getReference();

        //Create new device fragment if one doesn't exist
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main_activity, menu);
        return true;
    }

    //TODO re-add support for changing GPS settings
//    @Override
//    public boolean onOptionsItemSelected(MenuItem item) {
//        int id = item.getItemId();
//        if (id == R.id.gps_period) {
//            Toast.makeText(getApplicationContext(), "Clicked GPS Period option", Toast.LENGTH_SHORT).show();
//            AlertDialog.Builder builder = new AlertDialog.Builder(this);
//            builder.setTitle("New GPS Period");
//
//            final EditText input = new EditText(getApplicationContext());
//            input.setInputType(InputType.TYPE_CLASS_TEXT);
//            builder.setView(input);
//
//            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
//                @Override
//                public void onClick(DialogInterface dialog, int which) {
//                    gpsTimer.cancel();
//                    gpsPeriod = Integer.parseInt(input.getText().toString());
//                    Toast.makeText(getApplicationContext(), "Set GPS period to " + gpsPeriod, Toast.LENGTH_SHORT).show();
//
//                    locationRequest = new CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setMaxUpdateAgeMillis(gpsPeriod - 10).build();
//
//                    gpsTimer = new Timer();
//                    gpsTimer.schedule(new TimerTask() {
//                        @SuppressLint("MissingPermission")
//                        @Override
//                        public void run() {
//                            fusedLocationClient.getCurrentLocation(locationRequest, new CancellationToken() {
//                                @SuppressLint("MissingPermission")
//                                @NonNull
//                                @Override
//                                public CancellationToken onCanceledRequested(@NonNull OnTokenCanceledListener onTokenCanceledListener) {
//                                    return null;
//                                }
//
//                                @Override
//                                public boolean isCancellationRequested() {
//                                    return false;
//                                }
//                            }).addOnSuccessListener(newLocation -> {
//                                location = newLocation;
//                            });
//                        }
//                    }, 0, gpsPeriod);
//                }
//            });
//            builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
//                @Override
//                public void onClick(DialogInterface dialog, int which) {
//                    dialog.cancel();
//                }
//            });
//            Toast.makeText(getApplicationContext(), "built popup", Toast.LENGTH_SHORT).show();
//            try {
//                builder.show();
//            } catch (Exception e) {
//                Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
//                e.printStackTrace();
//            }
//            Toast.makeText(getApplicationContext(), "showed popup", Toast.LENGTH_SHORT).show();
//            return true;
//        } else {
//            return super.onOptionsItemSelected(item);
//        }
//    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }

    @Override
    public void onDestroy(){
        //todo: why are these commented out?
//        stopService(new Intent(this, FirebaseService.class));
//        stopService(new Intent(this, SerialService.class));
        super.onDestroy();
    }

    public void updateLocationPriority(int priority){
        locationHelper.changePriority(priority);
    }


    public void uploadFile(File file) {
        Uri uri = Uri.fromFile(file);
        StorageReference fileRef = storageRef.child("log/"
                +Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME)
                +"/"+uri.getLastPathSegment());
        fileRef.putFile(uri);
    }

}
