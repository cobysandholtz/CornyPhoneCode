package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Service that serves as our end of communication with the Gecko device. Because I forked this
 * from a separate demo app to get a jump start, this class probably can be narrowed down.
 * <p>
 * The queues serve as a buffer for the messages received during the times where a socket
 * has been connected to a device, but no UI elements have subscribed to receive those
 * messages yet. As a workaround for now, received packets are parsed here and sent to
 * FirebaseService to one of its methods
 * <p>
 * This means there is some duplicate logic going on in FirebaseService and TerminalFragment,
 * and most likely remains the messiest part of this app. One way to fix this might be to
 * adapt this to have a list of SerialListeners that can subscribe via attach()
 * <p>
 * <p>
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class SerialService extends Service implements SerialListener {

    private enum RotationState {
        IN_BOUNDS_CW,
        IN_BOUNDS_CCW,
        RETURNING_TO_BOUNDS_CW,
        RETURNING_TO_BOUNDS_CCW,
    }

    class SerialBinder extends Binder {
        SerialService getService() {
            return SerialService.this;
        }
    }

    private enum QueueType {Connect, ConnectError, Read, IoError}

    private static class QueueItem {
        QueueType type;
        byte[] data;
        Exception e;

        QueueItem(QueueType type, byte[] data, Exception e) {
            this.type = type;
            this.data = data;
            this.e = e;
        }
    }

    private LocalDateTime lastHeadingTime;
    private final Handler mainLooper;
    private Handler motorHandler;
    private final IBinder binder;
    private final Queue<QueueItem> queue1, queue2;

    // The representation of the actual connection
    private SerialSocket socket;
    // The object that wants to be forwarded the events from this service
    private SerialListener uiFacingListener;
    private boolean connected;

    // rotation variables
    private final long motorRotateTime = 500; /*.5 s*/
    private final long motorSleepTime = 10000; /*10 s*/
    private RotationState rotationState = RotationState.IN_BOUNDS_CW;
    private static double headingMin = 0.0;
    private static double headingMax = 360.0;
    private static boolean treatHeadingMinAsMax = false;
    //in degrees, if the last time the motor moved less than this amount,
    // we assume the motor has stopped us and it is time to turn around
    private static boolean isMotorRunning = true;

    private final long temperatureInterval = 300000; /*5 min*/
    private Handler temperatureHandler;

    private Handler batteryCheckHandler;

    private BlePacket pendingPacket;
    private byte[] pendingBytes = null;
    private static SerialService instance;

    public static float potAngle = 0.0f;

    //adding to this pushes out the oldest element if the buffer is full, allowing for time series averaging and other functions
    public AngleMeasSeries angleMeasSeries = new AngleMeasSeries(5);

    public static float lastBatteryVoltage = 0.0f;

    private static int  phoneCharge = 0;

    public static String lastCommand;

    public static Boolean shouldbeMoving = false;

    private static long lastEventTime = -1;

    public static final String KEY_STOP_MOTOR_ACTION = "SerialService.stopMotorAction";
    public static final String KEY_MOTOR_SWITCH_STATE = "SerialService.motorSwitchState";
    public static final String KEY_HEADING_RANGE_ACTION = "SerialService.headingRangeAction";
    public static final String KEY_HEADING_RANGE_STATE = "SerialService.headingRangeState";
    public static final String KEY_HEADING_MIN_AS_MAX_ACTION = "SerialService.headingRangePositiveAction";
    public static final String KEY_HEADING_MIN_AS_MAX_STATE = "SerialService.headingRangePositiveState";

    public static SerialService getInstance() {
        return instance;
    }

    public static float getBatteryVoltage() { return lastBatteryVoltage; }

    public static float getPhoneChargePercent() { return phoneCharge; }

    public static float getPotAngle() {
        return potAngle;
    }

    /**
     * Creates an intent with the input string and passes it to Terminal Fragment, which then prints it
     *
     */
    void print_to_terminal(String input) {
        Intent intent = new Intent(TerminalFragment.GENERAL_PURPOSE_PRINT);
        intent.putExtra(TerminalFragment.GENERAL_PURPOSE_STRING, input);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    void send_heading_intent() {
        Intent intent = new Intent(TerminalFragment.RECEIVE_HEADING_STATE);
        intent.putExtra(TerminalFragment.RECEIVE_ROTATION_STATE, rotationState.toString());
        intent.putExtra(TerminalFragment.RECEIVE_ANGLE, potAngle);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    void send_battery_intent() {
        Intent intent = new Intent(TerminalFragment.RECEIVE_BATTERY_VOLTAGE);
        intent.putExtra(TerminalFragment.BATTERY_VOLTAGE, lastBatteryVoltage);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    /**Used to set lastCommand so that the tracker doesn't automatically stop commands sent via UI
     *
     * @param str the command that TerminalFragment is about to send to the gecko
     */
    void setLastCommand(String str) {
        if (str.equals(BGapi.ROTATE_CCW) || str.equals(BGapi.ROTATE_CW) || str.equals(BGapi.ROTATE_STOP)) {
            lastCommand = str;
        }
    }

    static SerialService.RotationState lastRotationState = null;
    /**
     * Checks if the state of the rotate state machine has changed since last time, and if it has,
     * prints the state and decision variables for debugging.
     *
     */
    void rotateRunnable_statePrint(SerialService.RotationState newRotationState) {
        if (lastRotationState == null || lastRotationState != newRotationState) {
            String headingInfo = "currentHeading: "+ potAngle
                    + "\nmin: "+headingMin+"\nmax: "+headingMax
                    + "\nminAsMax: "+treatHeadingMinAsMax
                    + "\nstate: "+rotationState ;

            print_to_terminal(headingInfo);
        }
        lastRotationState = newRotationState;
    }

    // The packaged code sample that moves the motor and checks if it is time to turn around

    private final Runnable rotateRunnable = new Runnable() {

        @Override
        public void run() {

            try {
                if (connected) {

                    double oldHeading = SensorHelper.getMagnetometerReadingSingleDim();
                    String rotateCommand = BGapi.ROTATE_STOP;
                    if (rotationState == RotationState.IN_BOUNDS_CW || rotationState == RotationState.RETURNING_TO_BOUNDS_CW)
                        rotateCommand = BGapi.ROTATE_CW;
                    else
                        rotateCommand = BGapi.ROTATE_CCW;

                    lastCommand = rotateCommand;

                    //start rotation
                    write(TextUtil.fromHexString(rotateCommand));
                    shouldbeMoving = true;

                    //wait motorRotateTime, then stop rotation
                    motorHandler.postDelayed(() -> {
                        try {
                            shouldbeMoving = false;
                            write(TextUtil.fromHexString(BGapi.ROTATE_STOP));
                            lastCommand = BGapi.ROTATE_STOP;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, motorRotateTime);


                    //previous code to swivel the motor indefinitely
//                    double currentHeading = SensorHelper.getHeading(); //+180;
                    double currentHeading = potAngle;

                    //valid range goes around 0, such as 90->120
                    //where ---- is out of bounds and ==== is in bounds,
                    //and <-< or >-> marks the current heading and direction
                    // 0<----|========|----->360
                    switch (rotationState) {
                        case IN_BOUNDS_CW: // 0<--|====== >-> ====|-->360
                            // turn around once we pass the max
                            if (OutsideUpperBound(currentHeading)) {
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;
                            } else if (OutsideLowerBound(currentHeading)) {             // if it gets off, make sure it knows it's outside bounds
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CW;   // and set it on a course towards what is most likely the nearest bound
                            }
                            break;
                        case IN_BOUNDS_CCW: // 0<--|== <-< ======|-->360
                            // turn around once we pass the min
                            if (OutsideLowerBound(currentHeading)) {
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CW;
                            } else if (OutsideUpperBound(currentHeading)) {             // if it gets off, make sure it knows it's outside bounds
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;  // and set it on a course towards what is most likely the nearest bound
                            }
                            break;
                        case RETURNING_TO_BOUNDS_CW: // 0<-- >-> |========|-->360
                            // set to back in bounds after passing the min
                            //   and continue CW
                            if (InsideBounds(currentHeading)) {
                                rotationState = RotationState.IN_BOUNDS_CW;
                            } else if (OutsideUpperBound(currentHeading)) {             // if it gets off, make sure it knows it's outside the other bound
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;  // and set it on a course towards what is most likely the nearest bound
                            }
                            break;
                        case RETURNING_TO_BOUNDS_CCW: // 0<--|======| <-< -->360
                            // set back to in bounds after passing the max
                            //   and continue CCW
                            if (InsideBounds(currentHeading)) {
                                rotationState = RotationState.IN_BOUNDS_CCW;
                            } else if (OutsideLowerBound(currentHeading)) {             // if it gets off, make sure it knows it's outside the other bound
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CW;   // and set it on a course towards what is most likely the nearest bound
                            }
                            break;
                    }

//                    System.out.println("About to write headings to firebase service companion");



                    if (lastHeadingTime == null) {
                        lastHeadingTime = LocalDateTime.now();
                    }

                    String headingStr = String.join(", ",
                            lastHeadingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss")),
                            String.valueOf(currentHeading),
                            Arrays.toString(SensorHelper.getMagnetometerReadingThreeDim()),
                            String.valueOf(headingMin),
                            String.valueOf(headingMax),
                            String.valueOf(treatHeadingMinAsMax),
                            String.valueOf(oldHeading),
                            rotationState.toString(),
                            "\n"
                    );

                    FirebaseService.Companion.getServiceInstance().appendHeading(headingStr);
//                    System.out.println("Wrote headings to firebase service companion: " + headingStr);

                }

                rotateRunnable_statePrint(rotationState);

                //As long as we are to continue moving, schedule this method to be run again
                if (isMotorRunning) {
                    motorHandler.postDelayed(this, motorSleepTime);
                }
            } catch (IOException e) {
                Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }


        }

        // for treatHeadingMinAsMax == false
        private boolean InsideBounds(double heading) {
            return (heading <= headingMax && heading >= headingMin);
        }

        private boolean OutsideLowerBound(double heading) {
            return (heading >= 0 && heading < headingMin);
        }

        private boolean OutsideUpperBound(double heading) {
            return (heading > headingMax && heading < 360);
        }

        // for treatHeadingMinAsMax == true
        private boolean InsideUpperBound(double heading) {
            return (heading >= headingMax && heading < 360);
        }

        private boolean InsideLowerBound(double heading) {
            return (heading >= 0 && heading <= headingMin);
        }

        private boolean OutsideBounds(double heading) {
            return (heading > headingMin && heading < headingMax);
        }

    };

    private final Runnable temperatureRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                write(TextUtil.fromHexString(BGapi.GET_TEMP));
                Toast.makeText(getApplicationContext(), "Asked for temp", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
            temperatureHandler.postDelayed(this, temperatureInterval);
        }
    };

    private static BatteryManager bm;

    private final Runnable batteryCheckRunnable = new Runnable() { //written by GPT 3.5 with prompts from Coby's code

        @Override
        public void run() {
            bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            phoneCharge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            print_to_terminal("Read Phone battery level: " + phoneCharge);
            System.out.print("Battery level " +  String.valueOf(phoneCharge) + "\n");

            Log.d("BatteryLevel", String.valueOf(phoneCharge));

            batteryCheckHandler.postDelayed(batteryCheckRunnable, 60 * 1000); //delay
        }
    };


    /**
     * Lifecycle
     */
    public SerialService() {
        mainLooper = new Handler(Looper.getMainLooper());
        binder = new SerialBinder();
        queue1 = new LinkedList<>();
        queue2 = new LinkedList<>();

        instance = this;

        startRotationHandler();
        startTemperatureHandler();
        startBatteryCheckHandler();
    }

    /**
     * Called by the system when another part of this app calls startService()
     * Shows the notification that is required by the system to signal that we will be
     * using constant access to system resources and sensors
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        createNotification();

        return START_STICKY;
    }

    /**
     * Create the Handler that will regularly call the code in rotateRunnable
     */
    private void startRotationHandler() {
        Looper looper = Looper.myLooper();
        if (looper != null) {
            motorHandler = new Handler(looper);
            motorHandler.post(rotateRunnable);
        }
    }

    private void startTemperatureHandler() {
        Looper looper = Looper.myLooper();
        if (looper != null) {
            temperatureHandler = new Handler(looper);
            temperatureHandler.postDelayed(temperatureRunnable, 5000);
        }
    }

    private void startBatteryCheckHandler() {
        Looper looper = Looper.myLooper();
        if (looper != null) {
            batteryCheckHandler = new Handler(looper);
            batteryCheckHandler.post(batteryCheckRunnable);
        }
    }

    /**
     * Called by the system hopefully never since the app should never die
     */
    @Override
    public void onDestroy() {
        cancelNotification();
        disconnect();
        super.onDestroy();
    }

    /**
     * Inherited from Service
     * Called when a Fragment or Activity tries to bind to this service
     * in order to communicate with it
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    //endregion

    //region API

    /**
     * Called by TerminalFragment after it has created a SerialSocket and given it the details
     * necessary to open a connection to a USB serial device
     * Connects to the device
     */
    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
    }

    /**
     * Disconnect from the USB serial device and remove the socket
     */
    public void disconnect() {
        connected = false; // ignore data,errors while disconnecting
        cancelNotification();
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    /**
     * Write data to the USB serial device through the socket
     * Throws IOException if not currently connected to a device
     */
    public void write(byte[] data) throws IOException {
        if (!connected)
            throw new IOException("not connected");
        socket.write(data);
    }

    /**
     * Subscribe to any serial events that occur from the connected device
     * May immediately send events that were queued since last connection
     * <p>
     * This method is expected to be used by UI elements i.e. TerminalFragment
     */
    public void attach(SerialListener listener) throws IOException {
        //Not entirely sure why this is necessary
        if (Looper.getMainLooper().getThread() != Thread.currentThread())
            throw new IllegalArgumentException("not in main thread");
        cancelNotification();
        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized (this) {
            this.uiFacingListener = listener;
        }
        // queue1 will contain all events that posted in the time between disconnecting and detaching
        for(QueueItem item : queue1) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.data); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        // queue2 will contain all events that posted after detaching
        for(QueueItem item : queue2) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.data); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        queue1.clear();
        queue2.clear();
    }

    public void detach() {
        if (connected)
            createNotification();
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        uiFacingListener = null;
    }

    /**
     * Creates and configures the constant notification required by the system
     * Then shows this notification and promotes this service to a ForegroundService
     */
    private void createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW);
            nc.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(nc);
        }

        Intent disconnectIntent = new Intent()
                .setAction(Constants.INTENT_ACTION_DISCONNECT);
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent restartIntent = new Intent()
                .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent restartPendingIntent = PendingIntent.getActivity(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(socket != null ? "Connected to " + socket.getName() : "Background Service")
                .setContentIntent(restartPendingIntent)
                .setOngoing(true)
                .addAction(new NotificationCompat.Action(R.drawable.ic_clear_white_24dp, "Disconnect", disconnectPendingIntent));
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both drawables used here, are created as .png instead of .xml
        Notification notification = builder.build();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private void cancelNotification() {
        stopForeground(true);
    }

    //endregion

    //region SerialListener

    /**
     * Each of these methods either forwards the event on to the listener that subscribed via
     * attach(), or queues it to be forwarded when a listener becomes available again
     * <p>
     * With the exception of onSerialRead, which also parses the data and sends packets
     * to FirebaseService
     */

    public void onSerialConnect() {
        if (connected) {
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            uiFacingListener.onSerialConnect();
                        } else {
                            queue1.add(new QueueItem(QueueType.Connect, null, null));
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.Connect, null, null));
                }
            }
        }
    }

    public void onSerialConnectError(Exception e) {
        if (connected) {
            FirebaseService.Companion.getInstance().appendFile(e.getMessage() + "\n");
            FirebaseService.Companion.getInstance().appendFile(Log.getStackTraceString(e) + "\n");
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            uiFacingListener.onSerialConnectError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.ConnectError, null, e));
                            cancelNotification();
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.ConnectError, null, e));
                    cancelNotification();
                    disconnect();
                }
            }
        }
    }

    public void onSerialRead(byte[] data) throws IOException {
        if (connected) {
            //TODO find a more organized way to do this parsing

            // parse here to determine if it should be sent to FirebaseService too
            if (BGapi.isScanReportEvent(data)) {
                //this is the beginning of a new report event, therefore we assume that
                // if a packet is pending, it is complete and save it before parsing the most
                // recent data
                if (pendingPacket != null) {
                    FirebaseService.Companion.getServiceInstance().appendFile(pendingPacket.toCSV());
                }

                BlePacket temp = BlePacket.parsePacket(data);
                //did the new data parse successfully?
                if (temp != null) {
                    //Yes - save the packet
                    pendingPacket = temp;
                } else {
                    //No - save the raw bytes
                    pendingBytes = data;
                }

            } else if (BGapi.isAngleOrBattResponse(data)) {
//                System.out.print("isAngleOrBattResponse()");

                if (data[data.length - 1] == (byte) 0xFF) {
                    byte[] lastTwoBytes = new byte[2];
//             Extract the last 2 bytes
                    System.arraycopy(data, data.length - 3, lastTwoBytes, 0, 2); //data bytes are in 14th and 15th positions in the array

//             Extract the most significant 12 bits into an integer
                    int pot_bits = ((lastTwoBytes[0] & 0xFF) << 4) | ((lastTwoBytes[1] & 0xF0) >>> 4);

//             multiply by 1/2^12 (adc resolution)
                    float pot_voltage = (float) (pot_bits * 0.002);

                    //voltage scales from 0.037 to 2.98 across 450 degrees of rotation (need measurements for angle extent on either side
                    //angle should be ((angle_voltage - 0.037) / (2.98 - 0.028) * 450) - some_offset
                    //with the offset depending on how we want to deal with wrapping around 0
                    // so we can keep
                    potAngle = (float) (((pot_voltage - 0.332) / (2.7 - 0.332)) * 360);

                    Boolean isMoving = angleMeasSeries.addMeasurementFiltered(potAngle);
                    //stop rotation in event of an erroneous input
                    if (isMoving && !shouldbeMoving) {
                        write(TextUtil.fromHexString(BGapi.ROTATE_STOP));
                        System.out.println("Stopped Erroneous Rotation");
                    }


                    lastHeadingTime = LocalDateTime.now();

                    //send the angle and rotation state to terminal fragment to be displayed onscreen
                    send_heading_intent();

                //get battery voltage
                } else if (data[data.length - 1] == (byte) 0xF0) {

                    byte[] lastTwoBytes = new byte[2];
//              Extract the last 2 bytes
                    System.arraycopy(data, data.length - 3, lastTwoBytes, 0, 2); //data bytes are in 14th and 15th positions in the array

//              Extract the most significant 12 bits into an integer
                    int batt_bits = ((lastTwoBytes[0] & 0xFF) << 4) | ((lastTwoBytes[1] & 0xF0) >>> 4);

//              multiply by 1/2^12 (adc resolution)
                    float batt_voltage = ((float) (batt_bits * 0.002)) * 6; //multiply by 6 for voltage divider

                    lastBatteryVoltage = batt_voltage;

                    System.out.print("Battery voltage was " + batt_voltage + "\n");
//                    send_battery_intent();
                } else {
                    System.out.print("ERROR: incorrect gecko reading flag\n");
                    System.out.print("ERROR: got gecko reading that did not have correct type flag, flag was " + data[data.length - 1] + "\n");
                }

                //** CLOCKWISE = higher voltage, counterclockwise = lower voltage

                //where incrementing degrees takes you clockwise:
                // 2.96V is about 405 degrees (really right on tbh)
                //2.7V is 360 degrees
                //1.5V is 180 degrees
                //0.322V is 0 degrees
                //0.037V is about -45 degrees

            } else if (BGapi.isTemperatureResponse(data)) {
                //parse and store somewhere (FirebaseService?)
                int temp = data[data.length - 2];
                FirebaseService.Companion.getServiceInstance().appendTemp(temp);
            } else if ("message_system_boot".equals(BGapi.getResponseName(data))) {
                //TODO: this is definitely just a bandaid for the real problem of the gecko rebooting
                //the gecko mysteriously reset, so resend the setup and start commands
                try {
//                    write(TextUtil.fromHexString(BGapi.SCANNER_SET_MODE));
//                    write(TextUtil.fromHexString(BGapi.SCANNER_SET_TIMING));
//                    write(TextUtil.fromHexString(BGapi.CONNECTION_SET_PARAMETERS));
                    write(TextUtil.fromHexString(BGapi.SCANNER_START));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if("message_rotate_ccw_rsp".equals(BGapi.getResponseName(data))) {
              if (lastCommand == null || !lastCommand.equals(BGapi.ROTATE_CCW)) {
                  if (lastEventTime < 0) {
                      lastEventTime = System.currentTimeMillis();
                      System.out.print("ERROR: unexpected " + BGapi.getResponseName(data) +  " rsp received for the first time\n");
                  } else {
                      long timeElapsed = System.currentTimeMillis() - lastEventTime;
                      lastEventTime = System.currentTimeMillis();
                      System.out.print("ERROR: unexpected " + BGapi.getResponseName(data) +  " received after " + timeElapsed/1000 + " seconds\n");
                  }
                  write(TextUtil.fromHexString(BGapi.ROTATE_STOP));
              }
            }
            else if (!BGapi.isKnownResponse(data)) {
                //If the data isn't any kind of thing we can recognize, assume it's incomplete

                //If there's already partial data waiting
                if (pendingBytes != null) {
                    //add this data to the end of it
                    pendingBytes = appendByteArray(pendingBytes, data);

                    //and try to parse it again
                    BlePacket temp = BlePacket.parsePacket(pendingBytes);
                    if (temp != null) {
                        pendingPacket = temp;
                        pendingBytes = null;
                    }
                }
                //and it not, try to add it to the end of pending packet
                else if (pendingPacket != null) {
                    //todo: instead of just appending random data, check what it is (consider max possible length of packet)
                    //we should never be appending data to an already finsihed packet
                    pendingPacket.appendData(data);
                }
            }

            //original content of method
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            try {
                                uiFacingListener.onSerialRead(data);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            queue1.add(new QueueItem(QueueType.Read, data, null));
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.Read, data, null));
                }
            }
        }
    }

    /**
     * Given two byte arrays a,b, returns a new byte array that has appended b to the end of a
     **/
    private byte[] appendByteArray(byte[] a, byte[] b) {
        byte[] temp = new byte[a.length + b.length];
        System.arraycopy(a, 0, temp, 0, a.length);
        System.arraycopy(b, 0, temp, a.length, b.length);
        return temp;
    }

    public void onSerialIoError(Exception e) {
        if (connected) {
            FirebaseService.Companion.getServiceInstance().appendFile(e.getMessage() + "\n");
            FirebaseService.Companion.getServiceInstance().appendFile(Log.getStackTraceString(e) + "\n");
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            uiFacingListener.onSerialIoError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.IoError, null, e));
                            cancelNotification();
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.IoError, null, e));
                    cancelNotification();
                    disconnect();
                }
            }
        }
    }

    /**
     * A custom BroadcastReceiver that can receive intents from the switch button in TerminalFragment
     * and toggles motor rotation
     * TODO: find a way to interrupt an already scheduled handler so that the
     * motor stops immediately on the switch being pushed
     * (It currently only stops after the next time it rotates)
     */
    public static class ActionListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.getAction() != null) {
                if (intent.getAction().equals(KEY_STOP_MOTOR_ACTION)) {
                    isMotorRunning = intent.getBooleanExtra(KEY_MOTOR_SWITCH_STATE, false);
                    if (isMotorRunning) {
                        SerialService.getInstance().startRotationHandler();
                    }
                } else if (intent.getAction().equals(KEY_HEADING_RANGE_ACTION)) {
                    float[] headingLimits = intent.getFloatArrayExtra(KEY_HEADING_RANGE_STATE);
                    if (headingLimits != null && headingLimits.length == 2) {
                        headingMin = headingLimits[0];
                        headingMax = headingLimits[1];
                    }
                } else if (intent.getAction().equals(KEY_HEADING_MIN_AS_MAX_ACTION)) {
                    treatHeadingMinAsMax = !intent.getBooleanExtra(KEY_HEADING_MIN_AS_MAX_STATE, false);
                }
            }
        }
    }

}

