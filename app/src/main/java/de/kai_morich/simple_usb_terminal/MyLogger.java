package de.kai_morich.simple_usb_terminal;

import android.util.Log;
import android.widget.TextView;

public class MyLogger {
    // Define log levels
    public static final int LOG_VERBOSE = 10;
    public static final int LOG_DEBUG = 20;
    public static final int LOG_INFO = 30;
    public static final int LOG_WARNING = 40;
    public static final int LOG_ERROR = 50;

    // Set the default log level
    private static int logLevel = LOG_VERBOSE;

    // Method to set the log level
    public static void setLogLevel(int level) {
        logLevel = level;
    }

    // Log a message to a TextView for the specified log level
    public static void log(TextView textView, int logLevel, String message) {
        if (logLevel >= MyLogger.logLevel) {
            textView.append(message + "\n");
        }
    }

    public static void log_v(TextView textView, String message) {
        log(textView, LOG_VERBOSE, message);
    }

    public static void log_d(TextView textView, String message) {
        log(textView, LOG_DEBUG, message);
    }

    public static void log_i(TextView textView, String message) {
        log(textView, LOG_INFO, message);
    }

    public static void log_w(TextView textView, String message) {
        log(textView, LOG_WARNING, message);
    }

    public static void log_e(TextView textView, String message) {
        log(textView, LOG_ERROR, message);
    }
}