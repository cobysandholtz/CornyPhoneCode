package de.kai_morich.simple_usb_terminal;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialProber;

/**
 * add devices here, that are not known to DefaultProber
 *
 * if the App should auto start for these devices, also
 * add IDs to app/src/main/res/xml/usb_device_filter.xml
 */
class CustomProber {

    //ensures that the usb-serial-for-android library knows how to communicate with the Gecko
    static UsbSerialProber getCustomProber() {
        ProbeTable customTable = new ProbeTable();
        customTable.addProduct(0x16d0, 0x087e, CdcAcmSerialDriver.class); // e.g. Digispark CDC
        customTable.addProduct(0x1366, 0x0105, CdcAcmSerialDriver.class); // SiLabs BGM220x
        customTable.addProduct(0x2A03, 0x0043, CdcAcmSerialDriver.class); // Arduino Uno
        customTable.addProduct(0x2341, 0x0001, CdcAcmSerialDriver.class); // Arduino Uno SA?
        return new UsbSerialProber(customTable);
    }

}
