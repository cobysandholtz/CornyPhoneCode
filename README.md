# BLE Receiver

This Android app provides a line-oriented terminal / console for devices with a serial / UART interface connected with a USB-to-serial-converter that has been adapted
to specifically work with Silicon Labs BGM220x devices using BGAPI


## Features

- buttons for setup, start, and stop scanner commands
- permission handling on device connection
- foreground service to buffer receive data while the app is rotating, in background, ...

## Credits

The app uses the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library.
The app is a fork of [SimpleUsbTerminal](https://github.com/kai-morich/SimpleUsbTerminal)

