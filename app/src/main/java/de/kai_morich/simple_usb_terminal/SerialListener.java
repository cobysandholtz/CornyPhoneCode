package de.kai_morich.simple_usb_terminal;

import java.io.IOException;

interface SerialListener {
    void onSerialConnect      ();
    void onSerialConnectError (Exception e);
    void onSerialRead         (byte[] data) throws IOException;
    void onSerialIoError      (Exception e);
}
