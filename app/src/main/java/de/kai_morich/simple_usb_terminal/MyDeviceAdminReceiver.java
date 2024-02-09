package de.kai_morich.simple_usb_terminal;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class MyDeviceAdminReceiver extends DeviceAdminReceiver {

    boolean isEnabled = false;

    void showToast(Context context, String msg) {
        String status = "status good I guess";
        Toast.makeText(context, status, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        isEnabled = true;
        showToast(context, "Device Administrator Status Enabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        isEnabled = false;
    }

    public boolean isEnabled() {
        return isEnabled;
    }
}
