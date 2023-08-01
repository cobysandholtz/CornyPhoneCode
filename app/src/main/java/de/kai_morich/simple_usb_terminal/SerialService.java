package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
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
    private long motorRotateTime = 1500; /*1.5 s*/
    private long motorSleepTime = 100;  /*0.1 s*/
    private long messageDelay = 250; /*0.25*/
    private RotationState rotationState = RotationState.IN_BOUNDS_CW;
    private static double minAngle = 0.0; // or -50?
    private static double maxAngle = 450.0;
    private static double headingMin = 0.0;
    private static double headingMax = 360.0;
    private static boolean treatHeadingMinAsMax = false;
    //in degrees, if the last time the motor moved less than this amount,
    // we assume the motor has stopped us and it is time to turn around
    private static boolean isMotorRunning = true;

    private final long temperatureInterval = 300000; /*5 min*/
    private Handler temperatureHandler;

    private BlePacket pendingPacket;
    private byte[] pendingBytes = null;
    private static SerialService instance;
    public static float pot_angle;

    public static final String KEY_STOP_MOTOR_ACTION = "SerialService.stopMotorAction";
    public static final String KEY_MOTOR_SWITCH_STATE = "SerialService.motorSwitchState";
    public static final String KEY_HEADING_RANGE_ACTION = "SerialService.headingRangeAction";
    public static final String KEY_HEADING_RANGE_STATE = "SerialService.headingRangeState";
    public static final String KEY_HEADING_MIN_AS_MAX_ACTION = "SerialService.headingRangePositiveAction";
    public static final String KEY_HEADING_MIN_AS_MAX_STATE = "SerialService.headingRangePositiveState";

    public static SerialService getInstance() {
        return instance;
    }


    // The packaged code sample that moves the motor and checks if it is time to turn around
    private final Runnable rotateRunnable = new Runnable() {

        @Override
        public void run() {

            try {
                if (connected) {
                    //ask for the current angle
                    String angleQuery = BGapi.GET_ANGLE;
                    write(TextUtil.fromHexString(angleQuery)); //send data to chip to get angle data
                    SystemClock.sleep(messageDelay);

                    //determine whether to rotate clockwise or counter clockwise
                    double oldHeading = SensorHelper.getHeading();
                    String rotateCommand;
                    if(rotationState == RotationState.IN_BOUNDS_CW || rotationState == RotationState.RETURNING_TO_BOUNDS_CW)
                        rotateCommand = BGapi.ROTATE_CW;
                    else
                        rotateCommand = BGapi.ROTATE_CCW;

                    //command motor to turn how we want
                    String rotationSpeed = BGapi.ROTATE_FAST;

                    write(TextUtil.fromHexString(rotationSpeed));
                    SystemClock.sleep(messageDelay);
                    write(TextUtil.fromHexString(rotateCommand));
                    SystemClock.sleep(motorRotateTime);
                    write(TextUtil.fromHexString(BGapi.ROTATE_STOP));
                    SystemClock.sleep(messageDelay);

                    //determine new rotation state
                    double currentHeading = SensorHelper.getHeading();
                    if (treatHeadingMinAsMax) { //valid range goes through 0, such as 270->30
                        //where --- is out of bounds, ==== is in bounds,
                        //and >-> or <-< marks the current heading and direction
                        // looks like: 0<====|-------|=====>360
                        switch (rotationState) { //switch on what state we used to make the previous rotation
                            case IN_BOUNDS_CW: //0<=== >-> ==|-----|====>360
                                // turn around once we pass the min
                                if (OutsideBounds(currentHeading)) {
                                    rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;
                                }
                                break;
                            case IN_BOUNDS_CCW: // 0<====|----|==== <-< ===>360
                                // turn back around once we pass the max
                                if (OutsideBounds(currentHeading)) {
                                    rotationState = RotationState.RETURNING_TO_BOUNDS_CW;
                                }
                                break;
                            case RETURNING_TO_BOUNDS_CW: // 0<===|--- >-> |====>360
                                // set to back in bounds after passing the max
                                //   and continue CW
                                if (InsideUpperBound(currentHeading)) {
                                    rotationState = RotationState.IN_BOUNDS_CW;
                                } else if (InsideLowerBound(currentHeading)) {     // if for some reason it gets off,
                                    rotationState = RotationState.IN_BOUNDS_CCW;   // make sure it knows it's inside bounds
                                }
                                break;
                            case RETURNING_TO_BOUNDS_CCW: // 0<===| <-< ---|====>360
                                // set back in bounds after passing the min
                                if (InsideLowerBound(currentHeading)) {
                                    rotationState = RotationState.IN_BOUNDS_CCW;
                                } else if (InsideUpperBound(currentHeading)) {     // if for some reason it gets off,
                                    rotationState = RotationState.IN_BOUNDS_CW;    //make sure it knows it's inside bounds
                                }
                                break;
                        }
                    } else { //valid range goes around 0, such as 90->120
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
                                if(OutsideLowerBound(currentHeading)) {
                                    rotationState = RotationState.RETURNING_TO_BOUNDS_CW;
                                } else if (OutsideUpperBound(currentHeading)) {             // if it gets off, make sure it knows it's outside bounds
                                    rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;  // and set it on a course towards what is most likely the nearest bound
                                }
                                break;
                            case RETURNING_TO_BOUNDS_CW: // 0<-- >-> |========|-->360
                                // set to back in bounds after passing the min
                                //   and continue CW
                                if(InsideBounds(currentHeading)) {
                                    rotationState = RotationState.IN_BOUNDS_CW;
                                } else if (OutsideUpperBound(currentHeading)) {             // if it gets off, make sure it knows it's outside the other bound
                                    rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;  // and set it on a course towards what is most likely the nearest bound
                                }
                                break;
                            case RETURNING_TO_BOUNDS_CCW: // 0<--|======| <-< -->360
                                // set back to in bounds after passing the max
                                //   and continue CCW
                                if(InsideBounds(currentHeading)) {
                                    rotationState = RotationState.IN_BOUNDS_CCW;
                                } else if (OutsideLowerBound(currentHeading)) {             // if it gets off, make sure it knows it's outside the other bound
                                    rotationState = RotationState.RETURNING_TO_BOUNDS_CW;   // and set it on a course towards what is most likely the nearest bound
                                }
                                break;
                        }
                    }

                    //print current info to screen
                    String headingInfo = "currentHeading: "+ currentHeading
                                + "\nmin: " + headingMin + "\nmax: " + headingMax
                                + "\nminAsMax: " + treatHeadingMinAsMax
                                + "\nstate: " + rotationState ;
                    Intent intent = new Intent(TerminalFragment.RECEIVE_HEADING_STATS);
                    intent.putExtra(TerminalFragment.RECEIVE_HEADING_EXTRA, headingInfo);
                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);

                    //save rotation status to firebase log
                    FirebaseService.Companion.getServiceInstance().appendHeading(
                            currentHeading, headingMin, headingMax, treatHeadingMinAsMax, oldHeading, rotationState.toString());
                }

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
            if(heading <= headingMax && heading >= headingMin) { return true; } else { return false; }
        }

        private boolean OutsideLowerBound(double heading) {
            if(heading >= minAngle && heading < headingMin) {return true; } else { return false; }
        }

        private boolean OutsideUpperBound(double heading) {
            if(heading > headingMax && heading < maxAngle) {return true; } else { return false; }
        }

        // for treatHeadingMinAsMax == true
        private boolean InsideUpperBound(double heading) {
            if(heading >= headingMax && heading < maxAngle) {return true; } else { return false; }
        }

        private boolean InsideLowerBound(double heading) {
            if(heading >= minAngle && heading <= headingMin) {return true; } else { return false; }
        }

        private boolean OutsideBounds(double heading) {
            if(heading > headingMin && heading < headingMax) {return true; } else { return false; }
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

    /**
     * Lifecycle
     */
    public SerialService() {
        mainLooper = new Handler(Looper.getMainLooper());
        binder = new SerialBinder();
        queue1 = new LinkedList<>();
        queue2 = new LinkedList<>();

        instance = this;

        startMotorHandler();
        startTemperatureHandler();
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
    private void startMotorHandler() {
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
    public void attach(SerialListener listener) {
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

    public void onSerialRead(byte[] data) {
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

            } else if (BGapi.isAngleResponse(data)) {
                //todo: this comes in from the gecko bigendian, might need to swap around
                String truncData = "";
                int pot_int;
                for(int i = data.length - 4; i < data.length; i++) {
                    truncData += String.format("%02X", data[i]);
                    //pot_int += (pot_int << 8) + (data[i] & 0xFF); // didn't work?
                }
                Long pot_long = Long.parseLong(truncData, 16);
                pot_int =  Integer.reverseBytes(pot_long.intValue());

                float pot_voltage = Float.intBitsToFloat(pot_int);

                pot_angle = (float) (((pot_voltage - 0.332) / (2.7 - 0.332)) * 360);
                //pot_angle = (float) (((pot_voltage - 0.332) / (3.3 - 0.332)) * 360);



                //float angle_voltage = data[data.length - 4]; //last 4 bytes of response contain voltage payload
                //pot_angle = (float) (((angle_voltage - 0.332) / (2.7 - 0.332)) * 360);

                //voltage scales from 0.037 to 2.98 across 450 degrees of rotation (need measurements for angle extent on either side
                //angle should be ((angle_voltage - 0.037) / (2.98 - 0.028) * 450) - some_offset
                //with the offset depending on how we want to deal with wrapping around 0

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
            } else if (!BGapi.isKnownResponse(data)) {
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
                    pendingPacket.appendData(data);
                }
            }

            //original content of method
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            uiFacingListener.onSerialRead(data);
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
                        SerialService.getInstance().startMotorHandler();
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