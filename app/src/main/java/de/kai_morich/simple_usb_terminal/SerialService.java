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
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hoho.android.usbserial.driver.SerialTimeoutException;

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

//    private enum RotationState {
//        IN_BOUNDS_CW,
//        IN_BOUNDS_CCW,
//        RETURNING_TO_BOUNDS_CW,
//        RETURNING_TO_BOUNDS_CCW,
//    }

    private enum NewRotationState {
        MOVING_RIGHT,

        STOPPED_RIGHT,

        MOVING_LEFT,

        STOPPED_LEFT,

        TOO_FAR_RIGHT,

        TOO_FAR_LEFT,

        STOPPED,
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

    private TerminalFragment terminalFragment;
    private long messageDelay = 300; /*0.3 s*/
    private long motorRotateTime = 1000; /*1 s*/
    private long motorSleepTime = 100;  /*.5 s*/
//    private int motorSpeed = 4; /* degrees per second */
//    private double ExpectedAngleDistance = (motorRotateTime / 1000) * motorSpeed;
//    private double ErrorWindow = ExpectedAngleDistance + (2 * (motorRotateTime / 1000));
//    private RotationState rotationState = RotationState.IN_BOUNDS_CW;

    private NewRotationState newRotationState = NewRotationState.STOPPED;
    private static double minAngle = 0.0; // or -50?
    private static double maxAngle = 450.0;
    public double currentHeading = 0.0;
    private static double headingMin = 75.0;
    private static double headingMax = 395.0;
    private static boolean treatHeadingMinAsMax = false;
    //in degrees, if the last time the motor moved less than this amount,
    // we assume the motor has stopped us and it is time to turn around
    private static boolean isMotorRunning = true;

    private final long temperatureInterval = 300000; /*5 min*/
    private Handler temperatureHandler;

    private BlePacket pendingPacket;
    private byte[] pendingBytes = null;
    private static SerialService instance;
    private TerminalFragment TFragJustToSend;
    private int pot_bits;
    private float pot_voltage;
    public static float pot_angle;
    private double minVoltage = 0.0;
    private double maxVoltage = 0.0;
    private double degreesPerVolt;
    private int rotatorLimitReachedCounter = 0;


    private boolean rotateLock = false;

    private int outOfBoundsCounter = 0;
    private int rotationCounter = 0;
    private int stoppedCounter = 0;
    private final int OUT_OF_BOUNDS_COUNTER_THRESHOLD = 3; //number of repeated measurements outside bounds before reversing direction
    static int ROTATION_COUNTER_THRESHOLD = 6; // rotate time = this * motorSleepTime
    static int STOPPED_COUNTER_THRESHOLD = 25; // stopped time = this * motorSleepTime

    private String updated_VtoA_message = ""; // screen message when voltage to angle interpretation is updated
    private String outOfBoundsMessage = "";
    private String randomDebuggingText = "";


    //Button Identifiers for detecting when buttons have been pressed
    public static final String KEY_STOP_MOTOR_ACTION = "SerialService.stopMotorAction";
    public static final String KEY_MOTOR_SWITCH_STATE = "SerialService.motorSwitchState";
    public static final String KEY_HEADING_RANGE_ACTION = "SerialService.headingRangeAction";
    public static final String KEY_HEADING_RANGE_STATE = "SerialService.headingRangeState";
    public static final String KEY_HEADING_MIN_AS_MAX_ACTION = "SerialService.headingRangePositiveAction";
    public static final String KEY_HEADING_MIN_AS_MAX_STATE = "SerialService.headingRangePositiveState";

    public static SerialService getInstance() {
        return instance;
    }



    private void rotationStateTransition(double current_angle) {

        currentHeading = current_angle;
        String rotateDirectionCommand = null;

        //if the angle value is outside the possible range, just ignore it completely
        if (current_angle < 0 || current_angle > 500 ) {
            //some error handling
            //todo: should track this in some way?
        } else {
            //state transition logic
            switch (newRotationState) {

                case MOVING_RIGHT:
                    if (rotationCounter > ROTATION_COUNTER_THRESHOLD) {
                        rotationCounter = 0;
                        newRotationState = NewRotationState.STOPPED_RIGHT;
                    }
                    break;

                case STOPPED_RIGHT:
                    print_to_terminal(newRotationState.name());
                    if (current_angle > headingMax) {
                        newRotationState = NewRotationState.TOO_FAR_RIGHT;
                        stoppedCounter = 0;
                    } else if (stoppedCounter > STOPPED_COUNTER_THRESHOLD) {
                        stoppedCounter = 0;
                        newRotationState = NewRotationState.MOVING_RIGHT;
                    }
                    break;

                case TOO_FAR_RIGHT:
                    //if our out-of-bounds measurement was an error, go back to rotating right
                    //todo: should we increment outOfBoundsCounter if a bad reading causes us to go back?
                    if (current_angle < headingMax) {
                        newRotationState = NewRotationState.MOVING_RIGHT;
                    } else {
                        if (outOfBoundsCounter > OUT_OF_BOUNDS_COUNTER_THRESHOLD) {
                            newRotationState = NewRotationState.MOVING_LEFT;
                            outOfBoundsCounter = 0;
                        }
                    }
                    break;

                case MOVING_LEFT:
                    if (rotationCounter > ROTATION_COUNTER_THRESHOLD) {
                        rotationCounter = 0;
                        newRotationState = NewRotationState.STOPPED_LEFT;
                    }
                    break;

                case STOPPED_LEFT:
                    print_to_terminal(newRotationState.name());
                    if (current_angle < headingMin) {
                        newRotationState = NewRotationState.TOO_FAR_LEFT;
                        stoppedCounter = 0;
                    } else if (stoppedCounter > STOPPED_COUNTER_THRESHOLD) {
                        stoppedCounter = 0;
                        newRotationState = NewRotationState.MOVING_LEFT;
                    }
                    break;

                case TOO_FAR_LEFT:
                    if (current_angle > headingMin) {
                        newRotationState = NewRotationState.MOVING_LEFT;
                    } else {
                        if (outOfBoundsCounter > OUT_OF_BOUNDS_COUNTER_THRESHOLD) {
                            newRotationState = NewRotationState.MOVING_RIGHT;
                            outOfBoundsCounter = 0;
                        }
                    }
                    break;

                case STOPPED:
                    if (rotateLock) {
                        //do some check
                    } else {
                        newRotationState = NewRotationState.MOVING_LEFT;
                    }
                    break;

            }
        }

        //State Action Logic



        //Right is Clockwise and left is Counterclockwise. You think of it as traversing a number line, with 0 degrees
        //on the left and 445 degrees on the right.
        switch (newRotationState) {
            case MOVING_RIGHT:
                rotateDirectionCommand = BGapi.ROTATE_CW;
                rotationCounter++;
                break;

            case MOVING_LEFT:
                rotateDirectionCommand = BGapi.ROTATE_CCW;
                rotationCounter++;
                break;

            case TOO_FAR_RIGHT:

            case TOO_FAR_LEFT:
                outOfBoundsCounter++;

            case STOPPED_RIGHT:

            case STOPPED_LEFT:
                stoppedCounter++;
                rotateDirectionCommand = BGapi.ROTATE_STOP;
                break;

            case STOPPED:
                rotateDirectionCommand = BGapi.ROTATE_STOP;
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + newRotationState);
        }
        if (rotateDirectionCommand != null) {
            send(rotateDirectionCommand);
        }

//        print_to_terminal(newRotationState.name());

    }

    //todo: figure out how to just pass things directly to the terminalFragment to print
    private void print_to_terminal(String str) {
        Intent intent = new Intent(TerminalFragment.RECEIVE_HEADING_STATS);
        intent.putExtra(TerminalFragment.RECEIVE_HEADING_EXTRA, str);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }


    // The packaged code sample that moves the motor and checks if it is time to turn around
    private final Runnable rotateRunnable = new Runnable() {

        @Override
        public void run() {

            if (connected) {

                send(BGapi.GET_ANGLE);

//                try {
//                    write(TextUtil.fromHexString(BGapi.GET_ANGLE));
//                } catch (IOException e) {
//                    throw new RuntimeException(e);
//                }


//                double oldHeading = pot_angle;
//                double oldVoltage = pot_voltage;

//                TFragJustToSend = TerminalFragment.getInstance();


////                if (rotationState == RotationState.IN_BOUNDS_CW || rotationState == RotationState.RETURNING_TO_BOUNDS_CW)
//                    rotateCommand = BGapi.ROTATE_CW;
//                else
//                    rotateCommand = BGapi.ROTATE_CCW;

//                TFragJustToSend.send(rotateCommand);

//                SystemClock.sleep(messageDelay); //todo: is this blocking? If so, should not be, wow
//                TFragJustToSend.send(BGapi.ROTATE_FAST);
//                SystemClock.sleep(motorRotateTime);
//                TFragJustToSend.send(BGapi.ROTATE_STOP);
//                SystemClock.sleep(messageDelay);
//
//                TFragJustToSend.send(BGapi.GET_ANGLE);
//                SystemClock.sleep(messageDelay);
//                currentHeading = pot_angle;
//
//                // Here's a filter!
//                double differenceVoltage = Math.abs(pot_voltage - oldVoltage);
//                if (minVoltage != 0 && maxVoltage != 0) { // Have minV and maxV been calculated?
//                    if (differenceVoltage >= ((maxVoltage - minVoltage) / 12)) {
//                        currentHeading = oldHeading;
//                    }
//                } else {
//                    if (differenceVoltage >= (2.6 / 12)) { // 2.6 is what maxV - minV should be close to
//                        currentHeading = oldHeading;
//                    }
//                }
//
//
////                //ask for the current angle
////                String angleQuery = BGapi.GET_ANGLE;
////                write(TextUtil.fromHexString(angleQuery)); //send data to chip to get angle data
////                SystemClock.sleep(messageDelay);
////
////                //determine whether to rotate clockwise or counter clockwise
////                double oldHeading = SensorHelper.getHeading();
////                String rotateCommand;
////                if(rotationState == RotationState.IN_BOUNDS_CW || rotationState == RotationState.RETURNING_TO_BOUNDS_CW)
////                    rotateCommand = BGapi.ROTATE_CW;
////                else
////                    rotateCommand = BGapi.ROTATE_CCW;
////
////                //command motor to turn how we want
////                String rotationSpeed = BGapi.ROTATE_FAST;
////
////                write(TextUtil.fromHexString(rotationSpeed));
////                SystemClock.sleep(messageDelay);
////                write(TextUtil.fromHexString(rotateCommand));
////                SystemClock.sleep(motorRotateTime);
////                write(TextUtil.fromHexString(BGapi.ROTATE_STOP));
////                SystemClock.sleep(messageDelay);
////
////                //determine new rotation state
////                double currentHeading = SensorHelper.getHeading();
//
////                if (treatHeadingMinAsMax) {
////                    if (Math.abs(oldVoltage - pot_voltage) < 0.00115) {
////                        rotatorLimitReachedCounter++;
////                        updated_VtoA_message = updated_VtoA_message + "\nStopped rotating " + rotatorLimitReachedCounter + " time(s)";
////
////                        if (rotatorLimitReachedCounter == 5) {
////                            rotatorLimitReachedCounter = 0;
////
////                            if (rotateCommand == BGapi.ROTATE_CW) {
////                                maxVoltage = pot_voltage;
////                                updated_VtoA_message = updated_VtoA_message + "\nMax Voltage Updated";
////                                rotationState = RotationState.IN_BOUNDS_CCW;
////                            } else {
////                                minVoltage = pot_voltage;
////                                updated_VtoA_message = updated_VtoA_message + "\nMin Voltage Updated";
////                                rotationState = RotationState.IN_BOUNDS_CW;
////                            }
////
////                            if (!(minVoltage == 0.0) && !(maxVoltage == 0.0)) {
////                                degreesPerVolt = 450 / (maxVoltage - minVoltage);
////                                updated_VtoA_message = updated_VtoA_message + "\nVoltage-Angle Interpretation Updated";
////                            }
////                        }
////                    } else {
////                        rotatorLimitReachedCounter = 0;
////                        updated_VtoA_message = "";
////                    }
////                }
//
//                switch (rotationState) {
//                    case IN_BOUNDS_CW: // 0<--|====== >-> ====|-->360
//                        // turn around once we pass the max
//                        if (OutsideUpperBound(currentHeading)) {
//                            outOfBoundsCounter ++;
//                            outOfBoundsMessage = outOfBoundsMessage + "\nout of bounds " + outOfBoundsCounter + "time(s)";
//                            if (outOfBoundsCounter >= 3) {
//                                rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;
//                                outOfBoundsCounter = 0;
//                                outOfBoundsMessage = outOfBoundsMessage + "\nturned around";
//                            }
//                        } else if (OutsideLowerBound(currentHeading)) {             // if it gets off, make sure it knows it's outside bounds
//                            rotationState = RotationState.RETURNING_TO_BOUNDS_CW;   // and set it on a course towards what is most likely the nearest bound
//                        } else {
//                            outOfBoundsCounter = 0;
//                            outOfBoundsMessage = "";
//                        }
//                        break;
//                    case IN_BOUNDS_CCW: // 0<--|== <-< ======|-->360
//                        // turn around once we pass the min
//                        if(OutsideLowerBound(currentHeading)) {
//                            outOfBoundsCounter ++;
//                            outOfBoundsMessage = outOfBoundsMessage + "\nout of bounds " + outOfBoundsCounter + "time(s)";
//                            if (outOfBoundsCounter >= 3) {
//                                rotationState = RotationState.RETURNING_TO_BOUNDS_CW;
//                                outOfBoundsCounter = 0;
//                                outOfBoundsMessage = outOfBoundsMessage + "\nturned around";
//                            }
//                        } else if (OutsideUpperBound(currentHeading)) {             // if it gets off, make sure it knows it's outside bounds
//                            rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;  // and set it on a course towards what is most likely the nearest bound
//                        } else {
//                            outOfBoundsCounter = 0;
//                            outOfBoundsMessage = "";
//                        }
//                        break;
//                    case RETURNING_TO_BOUNDS_CW: // 0<-- >-> |========|-->360
//                        // set to back in bounds after passing the min
//                        //   and continue CW
//                        if(InsideBounds(currentHeading)) {
//                            rotationState = RotationState.IN_BOUNDS_CW;
//                        } else if (OutsideUpperBound(currentHeading)) {             // if it gets off, make sure it knows it's outside the other bound
//                            rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;  // and set it on a course towards what is most likely the nearest bound
//                        } else {
//                            outOfBoundsMessage = "";
//                        }
//                        break;
//                    case RETURNING_TO_BOUNDS_CCW: // 0<--|======| <-< -->360
//                        // set back to in bounds after passing the max
//                        //   and continue CCW
//                        if(InsideBounds(currentHeading)) {
//                            rotationState = RotationState.IN_BOUNDS_CCW;
//                        } else if (OutsideLowerBound(currentHeading)) {             // if it gets off, make sure it knows it's outside the other bound
//                            rotationState = RotationState.RETURNING_TO_BOUNDS_CW;   // and set it on a course towards what is most likely the nearest bound
//                        } else {
//                            outOfBoundsMessage = "";
//                        }
//                        break;
//                }


                //print current info to screen


                //Only print the heading at the end of each movement
                if (rotationCounter == ROTATION_COUNTER_THRESHOLD) {
                    String headingInfo = "\ncurrentHeading: " + currentHeading
//                            + "\noldHeading: " + oldHeading
                            + "\nmin: " + headingMin + "\nmax: " + headingMax
//                            + "\nminAsMax: " + treatHeadingMinAsMax
                            + "\nstate: " + newRotationState
                            + "\nminV: " + minVoltage
                            + "\nmaxV: " + maxVoltage
                            + "\nAperV: " + degreesPerVolt
                            + "\nVoltage: " + pot_voltage
                            + "\nBits: " + pot_bits
                            + updated_VtoA_message
                            + outOfBoundsMessage
                            + randomDebuggingText;
                    Intent intent = new Intent(TerminalFragment.RECEIVE_HEADING_STATS);
                    intent.putExtra(TerminalFragment.RECEIVE_HEADING_EXTRA, headingInfo);
                    LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);

//                //save rotation status to firebase log
//                FirebaseService.Companion.getServiceInstance().appendHeading(
//                        currentHeading, headingMin, headingMax, treatHeadingMinAsMax, oldHeading, rotationState.toString());
                    //save rotation status to firebase log
//                    FirebaseService.Companion.getServiceInstance().appendHeading(
//                            currentHeading, headingMin, headingMax, treatHeadingMinAsMax, newRotationState.toString());
                }
            }

            //As long as we are to continue moving, schedule this method to be run again
            if (isMotorRunning) {
                motorHandler.postDelayed(this, motorSleepTime);
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
            if( heading > headingMin && heading < headingMax ) {return true; } else { return false; }
        }

    };

    public void send(String str) {
        try {
            String msg;
            byte[] data;
            StringBuilder sb = new StringBuilder();
            TextUtil.toHexString(sb, TextUtil.fromHexString(str));
//            TextUtil.toHexString(sb, newline.getBytes());
            msg = sb.toString();
            data = TextUtil.fromHexString(msg);
            write(data);
        } catch (SerialTimeoutException e) {
//            status("write timeout: " + e.getMessage();
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

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
//                randomDebuggingText = "";
//                randomDebuggingText = randomDebuggingText + "WE GOT A PACKET!!!!!!";
                String length_str = String.valueOf(data.length);
                print_to_terminal(length_str);
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
//                print_to_terminal(String.valueOf(pendingBytes.length));

            } else if (BGapi.isAngleResponse(data)) {
                byte[] lastTwoBytes = new byte[2];
                // Extract the last 2 bytes
                System.arraycopy(data, 13, lastTwoBytes, 0, 2); //data bytes are in 14th and 15th positions in the array

                // Extract the most significant 12 bits into an integer
                pot_bits = ((lastTwoBytes[0] & 0xFF) << 4) | ((lastTwoBytes[1] & 0xF0) >>> 4);

                // multiply by 1/2^12 (adc resolution)
                pot_voltage = (float) (pot_bits * 0.002);

                //converts voltage to angle based on calibrated min and max values
                //before min and max values have been run into and measured, uses pre-measured values
                if (minVoltage == 0 || maxVoltage == 0) {
                    pot_angle = (float) (((pot_voltage - 0.332) / (2.7 - 0.332)) * 360);
                } else {
                    pot_angle = (float) ((pot_voltage - minVoltage) * degreesPerVolt);
                }

                rotationStateTransition(pot_angle);


                //call rotation state logic


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
                //and if not, try to add it to the end of pending packet
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
                    //check if autorotate is selected in UI
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