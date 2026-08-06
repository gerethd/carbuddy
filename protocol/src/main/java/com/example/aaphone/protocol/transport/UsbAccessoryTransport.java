package com.example.aaphone.protocol.transport;

import android.content.Context;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Wired transport backed by the Android Open Accessory (AOA) file descriptor.
 *
 * NOTE: on stock Android, the AOA mode-switch handshake — the USB control
 * requests that tell the connected host to flip us into "accessory" mode — is
 * performed by the OS/kernel itself, not by app code. This class only opens
 * the accessory file descriptor once the system has already handed us a
 * {@link UsbAccessory} via the standard {@code USB_ACCESSORY_ATTACHED} intent
 * + permission flow (see phone module's {@code AndroidManifest.xml} /
 * {@code accessory_filter.xml}). It does not reimplement AOA at the byte
 * level — see
 * https://developer.android.com/develop/connectivity/usb/accessory for the
 * platform contract this relies on.
 */
public class UsbAccessoryTransport implements Transport {

    private final ParcelFileDescriptor parcelFileDescriptor;
    private final FileDescriptor fileDescriptor;
    private final FileInputStream input;
    private final FileOutputStream output;

    public UsbAccessoryTransport(Context context, UsbAccessory accessory) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        ParcelFileDescriptor descriptor = usbManager.openAccessory(accessory);
        if (descriptor == null) {
            throw new IllegalStateException(
                "UsbManager refused to open accessory '" + accessory.getModel() + "' — was permission granted?");
        }
        this.parcelFileDescriptor = descriptor;
        this.fileDescriptor = descriptor.getFileDescriptor();
        this.input = new FileInputStream(fileDescriptor);
        this.output = new FileOutputStream(fileDescriptor);
    }

    @Override
    public boolean isConnected() {
        return fileDescriptor.valid();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        try {
            return input.read(buffer, offset, length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        try {
            output.write(buffer, offset, length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
        output.close();
        parcelFileDescriptor.close();
    }
}
