package de.kai_morich.simple_usb_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.Priority;
import com.google.android.material.slider.RangeSlider;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * The UI portion of the app that is displayed while connected to a USB serial device
 * There's a lot of non-UI logic still in here that needs to be cleaned up and/or removed
 * entirely as we won't actually use it
 * */
@RequiresApi(api = Build.VERSION_CODES.O)
public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private final String PREFERENCE_FILE = "de.kai_morich.simple_usb_terminal.PREFERENCE_FILE_KEY";

    private void onSetupClicked(View view1) {
        // setup is not needed anymore, including it in Command.SCANNER_START is enough
//        send(BGapi.SCANNER_SET_MODE);
//        send(BGapi.SCANNER_SET_TIMING);
//        send(BGapi.CONNECTION_SET_PARAMETERS);
        //ToDo: use this function to change the baud rate; test the functionality
        //baudrate = ...

        final String[] baudRates = getResources().getStringArray(R.array.baud_rates);
        int pos = java.util.Arrays.asList(baudRates).indexOf(String.valueOf(baudRate));
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle("Baud rate");
        builder.setSingleChoiceItems(baudRates, pos, (dialog, item1) -> {
            baudRate = Integer.parseInt(baudRates[item1]);
            disconnect();
            connect();
            dialog.dismiss();
        });
        builder.create().show();
        //return true;

    }

    private void onStartClicked(View v) {
        send(BGapi.SCANNER_START);
    }

    private enum Connected {False, Pending, True}

    private final BroadcastReceiver broadcastReceiver;
    private int deviceId, portNum, baudRate;
    private UsbSerialPort usbSerialPort;
    private SerialService service;

    private TextView receiveText;

    private CircularProgressIndicator circularProgress;

    private TextView angleDisplayText;

    private TextView batteryDisplayText;
    private TextView rotationStateDisplayText;

    private TextView rotationMinDisplay;

    private TextView rotationMaxDisplay;
    private RangeSlider headingSlider;
    private BlePacket pendingPacket;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean truncate = true;
    private int rotatePeriod = 500;

    private SharedPreferences sharedPref;

    private static TerminalFragment instance;

    public static TerminalFragment getInstance(){
        return instance;
    }

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    connect(granted);
                }
            }
        };
        instance = this;
    }

    public static final String RECEIVE_HEADING_STATE = "TerminalFragment.RECEIVE_HEADING_STATE";
    public static final String RECEIVE_ROTATION_STATE = "TerminalFragment.RECEIVE_ROTATION_STATE";

    public static final String RECEIVE_BATTERY_VOLTAGE = "TerminalFragment.RECEIVE_BATTERY_VOLTAGE";

    public static final String BATTERY_VOLTAGE = "TerminalFragment.BATTERY_VOLTAGE";
    public static final String RECEIVE_ANGLE = "TerminalFragment.RECEIVE_ANGLE";
    public static final String GENERAL_PURPOSE_PRINT = "TerminalFragment.GENERAL_PURPOSE_PRINT";

    public static final String GENERAL_PURPOSE_STRING = "TerminalFragment.GENERAL_PURPOSE_STRING";

    private LocalBroadcastManager bManager;

    private BroadcastReceiver terminalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String s = null;
//            System.out.println(intent.getAction());
            if (intent.getAction().equals(RECEIVE_HEADING_STATE)){
                String state = intent.getStringExtra(RECEIVE_ROTATION_STATE);
                double angle = intent.getFloatExtra(RECEIVE_ANGLE, 0); //todo: why does 0.0 not work here?
                String formattedAngle = "Angle: " + String.format("%-7.1f", angle);
                angleDisplayText.setText(formattedAngle);
                rotationStateDisplayText.setText(state);
//                System.out.println("heading received: "+ angle);
            } else if (intent.getAction().equals(RECEIVE_BATTERY_VOLTAGE))  {
                System.out.println("receive battery voltage intent");
                double voltage = intent.getFloatExtra(BATTERY_VOLTAGE, 0);
                String formattedVoltage = "Batt Voltage: " + String.format("%-7.1f", voltage);
                batteryDisplayText.setText(formattedVoltage);
            }
            else if (intent.getAction().equals(GENERAL_PURPOSE_PRINT)) {
                s = intent.getExtras().getString(GENERAL_PURPOSE_STRING);
                System.out.println(s);
            }

            if(receiveText != null && s != null){
                receiveText.append(s+"\n");
            }
        }
    };

    //region Lifecycle

    /**
     * Inherited from Fragment. One of the first methods that the system will call
     * after the constructor. Retrieves the information about the device to connect to
     * that was sent over by the DevicesFragment
     * */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");

        bManager = LocalBroadcastManager.getInstance(getActivity().getApplicationContext());
        IntentFilter filter = new IntentFilter();
        filter.addAction(RECEIVE_HEADING_STATE);
        filter.addAction(GENERAL_PURPOSE_PRINT);
        bManager.registerReceiver(terminalReceiver, filter);
    }

    /**
     * Inherited from Fragment. Called by the system when the app gets closed
     * */
    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        bManager.unregisterReceiver(terminalReceiver);
        super.onDestroy();
    }

    /**
     * Inherited from Fragment. Called by the system
     * */
    @Override
    public void onStart() {
        System.out.println("Terminal Fragment onStart() called");

        super.onStart();
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);

        }

    /**
     * Inherited from Fragment. Called by the system. Unsubscribes from messages from the serial device
     * as this Fragment is no longer being displayed
     * */
    @Override
    public void onStop() {
        if (service != null && !getActivity().isChangingConfigurations())
            service.detach();

        SharedPreferences.Editor editor = sharedPref.edit();
        List<Float> values = headingSlider.getValues();
        editor.putFloat("heading_min", values.get(0))
                .putFloat("heading_max", values.get(1))
                .apply();


        Log.d("TerminalFragment", "Wrote from onStop: "+values.get(0)+", "+values.get(1));

        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);


        if (service != null) {
            try {
                service.attach(this);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        else
            //if startService is called before bind(), then the service lives indefinitely
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change

    }

    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {
        }
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB));
        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onPause() {
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        try {
            service.attach(this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }


    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        View setupBtn = view.findViewById(R.id.setup_btn);
        setupBtn.setOnClickListener(this::onSetupClicked);

        //setup angle display view
        angleDisplayText = view.findViewById(R.id.DisplayAngle);
        angleDisplayText.setText("Rotator Angle:");

        batteryDisplayText = view.findViewById(R.id.batteryVoltage);
        batteryDisplayText.setText("Batt Voltage:");

        //setup rotation state display view
        rotationStateDisplayText = view.findViewById(R.id.RotationStateDisplay);
        rotationStateDisplayText.setText("rotation State: ");

        circularProgress = view.findViewById(R.id.circularProgress);

        //start point is

        circularProgress.setRotation(195f);
        circularProgress.setProgress(90);


        View stopUploadBtn = view.findViewById(R.id.stop_upload_btn);
        stopUploadBtn.setOnClickListener(btn -> {
            Toast.makeText(getContext(), "click!", Toast.LENGTH_SHORT).show();
            Intent stopIntent = new Intent(getContext(), FirebaseService.ActionListener.class);
            stopIntent.setAction(FirebaseService.KEY_NOTIFICATION_STOP_ACTION);
            stopIntent.putExtra(FirebaseService.KEY_NOTIFICATION_ID, ServiceNotification.notificationId);
            FirebaseService.Companion.getInstance().sendBroadcast(stopIntent);
        });

        SwitchCompat stopMotorBtn = view.findViewById(R.id.stop_motor_btn);
        stopMotorBtn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Intent stopMotorIntent = new Intent(getContext(), SerialService.ActionListener.class);
            stopMotorIntent.setAction(SerialService.KEY_STOP_MOTOR_ACTION);
            stopMotorIntent.putExtra(SerialService.KEY_MOTOR_SWITCH_STATE, isChecked);
            SerialService.getInstance().sendBroadcast(stopMotorIntent);
        });

        headingSlider = view.findViewById(R.id.slider);
        //load the min/max from local storage
        sharedPref = getContext().getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE);
        float headingMin = sharedPref.getFloat("heading_min", /*default*/20.0f);
        float headingMax = sharedPref.getFloat("heading_max", /*default*/270.0f);
        circularProgress.setRotation( 180f + headingMin);
        circularProgress.setProgress((int) ((headingMax - headingMin)/3.6f));

        //load the min/max from the slider at start
//        float headingMin = 20.0f;
//        float headingMax = 270.0f;
        Log.d("TerminalFragment", "Loaded min/max: "+headingMin+", "+headingMax);
        headingSlider.setValues(Arrays.asList(headingMin, headingMax));
        headingSlider.addOnChangeListener((rangeSlider, value, fromUser) -> {
            Activity activity = getActivity();
            if(activity instanceof MainActivity){
                //broadcast the new values to SerialService
                Intent headingRangeIntent = new Intent(getContext(), SerialService.ActionListener.class);
                headingRangeIntent.setAction(SerialService.KEY_HEADING_RANGE_ACTION);
                // turns out List.ToArray() can only return Object[], so use a custom method for float[]
                float[] arr = listToArray(rangeSlider.getValues());

                rotationMinDisplay.setText(new StringBuilder().append("Min: ").append(String.format("%.0f", arr[0])).toString());
                rotationMaxDisplay.setText(new StringBuilder().append("Max: ").append(String.format("%.0f", arr[1])).toString());

                circularProgress.setRotation( 180f + arr[0]);
                circularProgress.setProgress((int) ((arr[1] - arr[0])/3.6f));

                headingRangeIntent.putExtra(SerialService.KEY_HEADING_RANGE_STATE, arr);
                SerialService.getInstance().sendBroadcast(headingRangeIntent);
            }
        });

        rotationMinDisplay = view.findViewById(R.id.RotationStateMin);
        rotationMinDisplay.setText(new StringBuilder().append("Min: ").append(headingMin).toString());

        rotationMaxDisplay = view.findViewById(R.id.RotationStateMax);
        rotationMaxDisplay.setText(new StringBuilder().append("Max: ").append(headingMax).toString());

        //broadcast the start values
        Intent headingRangeIntent = new Intent(getContext(), SerialService.ActionListener.class);
        headingRangeIntent.setAction(SerialService.KEY_HEADING_RANGE_ACTION);

        float[] arr = {headingMin, headingMax};

        headingRangeIntent.putExtra(SerialService.KEY_HEADING_RANGE_STATE, arr);
        SerialService.getInstance().sendBroadcast(headingRangeIntent);


        SwitchCompat toggleHeadingBtn = view.findViewById(R.id.heading_range_toggle);
        toggleHeadingBtn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Activity activity = getActivity();
            if(activity instanceof MainActivity){
                Intent headingMinAsMaxIntent = new Intent(getContext(), SerialService.ActionListener.class);
                headingMinAsMaxIntent.setAction(SerialService.KEY_HEADING_MIN_AS_MAX_ACTION);
                headingMinAsMaxIntent.putExtra(SerialService.KEY_HEADING_MIN_AS_MAX_STATE, isChecked);
                SerialService.getInstance().sendBroadcast(headingMinAsMaxIntent);
            }
            if(isChecked){
                headingSlider.setTrackActiveTintList(ColorStateList.valueOf(getResources().getColor(R.color.colorPrimary)));
                headingSlider.setTrackInactiveTintList(ColorStateList.valueOf(getResources().getColor(R.color.material_on_surface_disabled)));
            } else {
                headingSlider.setTrackActiveTintList(ColorStateList.valueOf(getResources().getColor(R.color.material_on_surface_disabled)));
                headingSlider.setTrackInactiveTintList(ColorStateList.valueOf(getResources().getColor(R.color.colorPrimary)));
            }
        });

        View startBtn = view.findViewById(R.id.start_btn);
        startBtn.setOnClickListener(this::onStartClicked);

        View stopBtn = view.findViewById(R.id.stop_btn);
        stopBtn.setOnClickListener(v -> send(BGapi.SCANNER_STOP));

        Spinner gps_priority = view.findViewById(R.id.gps_priority_spinner);
        String[] gps_options = {"Power Saving", "Balanced", "High Accuracy"};
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(getContext(), android.R.layout.simple_spinner_item, gps_options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gps_priority.setAdapter(adapter);
        gps_priority.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Activity activity = getActivity();
                if(activity instanceof MainActivity) {
                    switch(position){
                        case 0:
                            ((MainActivity) activity).updateLocationPriority(Priority.PRIORITY_LOW_POWER);
                            break;
                        case 1:
                            ((MainActivity) activity).updateLocationPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY);
                            break;
                        case 2:
                            ((MainActivity) activity).updateLocationPriority(Priority.PRIORITY_HIGH_ACCURACY);
                            break;
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                //do nothing
            }
        });

        //TODO switch to get the filename directly from FirebaseService
        receiveText.append("Writing to " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm_ss")) + "_log.txt" + "\n");

        return view;
    }

    /**
     * Inherited from Fragment. The Options menu is the 3 dots in the top right corner
     * */
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.truncate).setChecked(truncate);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.manualUpload) {
            FirebaseService.Companion.getInstance().uploadLog();
            return true;
        } else if (id == R.id.truncate) {
            truncate = !truncate;
            item.setChecked(truncate);
            return true;
        } else if (id == R.id.manualCW) {
            send(BGapi.ROTATE_CW);
            //SystemClock.sleep(500);
            //send(BGapi.ROTATE_STOP);
            return true;
        } else if (id == R.id.manualCCW) {
            send(BGapi.ROTATE_CCW);
            //SystemClock.sleep(500);
            //send(BGapi.ROTATE_STOP);
            return true;
        } else if (id == R.id.manualSlow) {
            send(BGapi.ROTATE_SLOW);
            return true;
        } else if (id == R.id.manualFast) {
            send(BGapi.ROTATE_FAST);
            return true;
        } else if (id == R.id.manualStop) {
            send(BGapi.ROTATE_STOP);
            return true;
        } else if (id == R.id.getAngle) {
            send(BGapi.GET_ANGLE);
            return true;
        }
        else if (id == R.id.editRotate) {
            //TODO actually change the period in SerialService
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("New Rotation Period UNUSED");

            final EditText input = new EditText(getContext());
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            builder.setView(input);

            builder.setPositiveButton("OK", (dialog, which) -> {
                rotatePeriod = Integer.parseInt(input.getText().toString());
                Toast.makeText(getContext(), "Set rotation period to " + rotatePeriod, Toast.LENGTH_SHORT).show();
            });
            builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
            builder.show();

            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    // endregion Lifecycle

    //region Serial

    public void connect() {
        connect(null);
    }

    /**
     * Do all the listing and check required to connect to the USB device using the details
     * that were passed when this Fragment was started
     * But some of this seems like duplicate logic from DevicesFragment, so this might be able
     * to be reduced
     * */
    private void connect(Boolean permissionGranted) {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values())
            if (v.getDeviceId() == deviceId)
                device = v;
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        //TODO: Non-UI logic - should not be in a UI class
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.getDevice())) {
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(Constants.INTENT_ACTION_GRANT_USB), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
        usbSerialPort = null;
    }

    /**
     * Send a String to the currently connected serial device. Returns immediately if no
     * device is connected. Additionally appends the sent information to the text on screen
     * */
    private void send(String str) {
        if (connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            //TODO: Non-UI logic - should not be in UI class
            String msg;
            byte[] data;
            StringBuilder sb = new StringBuilder();
            TextUtil.toHexString(sb, TextUtil.fromHexString(str));
//            TextUtil.toHexString(sb, newline.getBytes());
            msg = sb.toString();
            data = TextUtil.fromHexString(msg);
            if (BGapi.isCommand(str)) {
                msg = BGapi.getCommandName(str);
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.setLastCommand(str);
            service.write(data);
        } catch (SerialTimeoutException e) {
            status("write timeout: " + e.getMessage());
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    /**
     * Parse the bytes that were received from the serial device. If those bytes are recognized
     * as a message that is part of BGAPI, prints the message name rather than the bytes
     * If the message is a packet, parse it into a packet object
     * */
    private void receive(byte[] data) {
//        SpannableStringBuilder span = new SpannableStringBuilder("##"+TextUtil.toHexString(data)+"\n");
//        span.setSpan(new ForegroundColorSpan(Color.CYAN), 0, span.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//        receiveText.append(span);
        if (BGapi.isScanReportEvent(data)) {
            //original script recorded time, addr, rssi, channel, and data
            //TODO: Non-UI logic - should not be in UI class
            if (pendingPacket != null) {
                String msg = pendingPacket.toString();
                if (truncate) {
                    int length = msg.length();
                    if (length > msg.lastIndexOf('\n') + 40) {
                        length = msg.lastIndexOf('\n') + 40;
                    }
                    msg = msg.substring(0, length) + "…";
                }
                SpannableStringBuilder spn = new SpannableStringBuilder(msg + "\n\n");
                spn.setSpan(new ForegroundColorSpan(Color.MAGENTA), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                receiveText.append(spn);
            }
            if (data.length <= 21)
                return;

            pendingPacket = BlePacket.parsePacket(data);
        } else if (BGapi.isAngleOrBattResponse(data)) {
//            byte[] lastTwoBytes = new byte[2];
////             Extract the last 2 bytes
//            System.arraycopy(data, data.length - 2, lastTwoBytes, 0, 2); //data bytes are in 14th and 15th positions in the array
//
////             Extract the most significant 12 bits into an integer
//            int pot_bits = ((lastTwoBytes[0] & 0xFF) << 4) | ((lastTwoBytes[1] & 0xF0) >>> 4);
//
////             multiply by 1/2^12 (adc resolution)
//             float pot_voltage = (float) (pot_bits * 0.002);
//
////            converts voltage to angle based on calibrated min and max values
////            before min and max values have been run into and measured, uses pre-measured values
//            float pot_angle = (float) (((pot_voltage - 0.332) / (2.7 - 0.332)) * 360);
//
//            receiveText.append("Angle: " + pot_angle + '\t' +"voltage: " + pot_voltage + "\t" + "Hex: " + pot_bits + '\n');
//            receiveText.append("Got angle measurement\n");
        } else if(BGapi.isTemperatureResponse(data)){
            int temperature = data[data.length-2];
            SpannableStringBuilder tempSpan = new SpannableStringBuilder("Got temp: "+temperature+"\n");
            tempSpan.setSpan(new ForegroundColorSpan(Color.rgb(255, 120, 0)), 0, tempSpan.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(tempSpan);
        } else if (BGapi.isKnownResponse(data)) {
            String rsp = BGapi.getResponseName(data);
            if(rsp != null)
                receiveText.append(BGapi.getResponseName(data) + '\n');
        } else {
            //until the data has a terminator, assume packets that aren't a known header are data that was truncated
            if (pendingPacket != null)
                pendingPacket.appendData(data);
        }

        //If the text in receiveText is getting too large to be reasonable, cut it off
        if(receiveText.getText().length() > 8000){
            CharSequence text = receiveText.getText();
            int length = text.length();
            receiveText.setText(text.subSequence(length-2000, length));
        }

    }

    /**
     * Print to the textview in a different color so that it stands out
     * */
    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    //endregion

    //region SerialListener


    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
        //send setup and start commands after delay via custom Handler
        Handler handler = new Handler();
        //Runnable clickSetup = () -> onSetupClicked(null);
        //handler.postDelayed(clickSetup, 2500);
        Runnable clickStart = () -> onStartClicked(null);
        handler.postDelayed(clickStart, 2700);
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
//        status(Log.getStackTraceString(e));
        disconnect();
    }

    private float[] listToArray(List<Float> list){
        float[] toreturn = new float[list.size()];
        for(int i = 0; i < list.size(); i++){
            toreturn[i] = list.get(i);
        }
        return toreturn;
    }

}
