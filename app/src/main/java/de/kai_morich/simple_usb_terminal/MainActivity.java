package de.kai_morich.simple_usb_terminal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;

@RequiresApi(api = Build.VERSION_CODES.O)
public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private StorageReference storageRef;
    private LocationHelper locationHelper;

    DevicePolicyManager mDPM;

    MyDeviceAdminReceiver deviceAdminReceiver;

    //used to identify the device admin receiver in the permission request intent (?)
    ComponentName mDeviceAdminReceiver;

    private FirebaseAuth mAuth;

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
        //following line will keep the screen active
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        //setup toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);

        //start service to get GPS and headings
        startService(new Intent(this, SensorHelper.class));

        //stop firebase worker (from testing)
//        WorkerWrapper.checkWorkerStatus(getApplicationContext());
//        WorkerWrapper.stopFireBaseWorker(getApplicationContext());
//        WorkerWrapper.checkWorkerStatus(getApplicationContext());


//        WorkerWrapper.startFirebaseWorker(getApplicationContext());
        WorkerWrapper.startSerialWorker(getApplicationContext());

        //start firebase service
        startService(new Intent(this, FirebaseService.class));

        //initialize Device Policy Manager (used for periodic restarts)
        mDPM = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mDeviceAdminReceiver = new ComponentName(this, MyDeviceAdminReceiver.class);

        System.out.println("Package name: " + this.getPackageName());

        //next steps: create a button or something in device fragment (or terminal fragment)
        //that triggers the request permissions activity for device administrator priveleges

//        if(mDPM.isDeviceOwnerApp(this.getPackageName())) {
//            System.out.println("mDPM is successful device administrator");
//        }


        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mDeviceAdminReceiver);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Some extra text explaining why we're asking for device admin permission");
        startActivity(intent);



        locationPermissionRequest.launch(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });



       locationHelper = new LocationHelper(this);
       locationHelper.startLocationUpdates();

        FirebaseStorage storage = FirebaseStorage.getInstance();
        storageRef = storage.getReference();

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
            if (terminal != null) {
                terminal.status("USB device detected");
                terminal.connect();
                //this might be the problem
            }
        }
        super.onNewIntent(intent);
    }

    @Override
    public void onDestroy(){
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
