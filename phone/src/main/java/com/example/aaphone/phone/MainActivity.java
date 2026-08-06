package com.example.aaphone.phone;

import android.app.Activity;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.TextView;

import com.example.aaphone.protocol.transport.UsbAccessoryTransport;

/**
 * Milestone M1 entry point. On receiving a {@code USB_ACCESSORY_ATTACHED}
 * intent (matched against res/xml/accessory_filter.xml), opens the accessory
 * file descriptor and hands it to the protocol layer.
 *
 * Currently this only proves the accessory opens — the handshake and channel
 * multiplexer are not wired in yet (see
 * {@link com.example.aaphone.protocol.handshake.AaHandshake} and
 * {@link com.example.aaphone.protocol.framing.ChannelMultiplexer} TODOs, and
 * docs/ROADMAP.md for sequencing).
 */
public class MainActivity extends Activity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setText("Waiting for head unit…");
        setContentView(statusView);
        handleAccessoryIntent();
    }

    @SuppressWarnings("deprecation") // getParcelableExtra(String) without a Class<T> is deprecated on API 33+
    private void handleAccessoryIntent() {
        UsbAccessory accessory = getIntent().getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if (accessory == null) {
            statusView.setText("No accessory in intent — launch via USB_ACCESSORY_ATTACHED");
            return;
        }

        UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
        if (!usbManager.hasPermission(accessory)) {
            statusView.setText("No permission for accessory '" + accessory.getModel() + "' — request it first");
            return;
        }

        UsbAccessoryTransport transport = new UsbAccessoryTransport(this, accessory);

        statusView.setText("Opened accessory: " + accessory.getManufacturer() + " / " + accessory.getModel()
            + " (connected=" + transport.isConnected() + ")");
        // TODO: transport -> AaHandshake -> ChannelMultiplexer wiring goes here (milestone M1).
    }
}
