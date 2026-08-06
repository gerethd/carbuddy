package com.example.aaphone.phone;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.TextView;

import com.example.aaphone.protocol.handshake.AaHandshake;
import com.example.aaphone.protocol.transport.UsbAccessoryTransport;

/**
 * Milestone M1 entry point. On receiving a {@code USB_ACCESSORY_ATTACHED}
 * intent (matched against res/xml/accessory_filter.xml), opens the accessory
 * file descriptor, runs the handshake, and reports progress.
 *
 * The channel multiplexer isn't wired in yet past the handshake (see
 * {@link com.example.aaphone.protocol.framing.ChannelMultiplexer}'s TODOs
 * and docs/ROADMAP.md for sequencing) — this only proves the handshake
 * itself completes against a real head unit.
 *
 * IMPORTANT: this activity is declared {@code singleTask} (see
 * AndroidManifest.xml) and overrides {@link #onNewIntent}, so a re-attach
 * while the app is already running lands on the same instance instead of
 * being silently ignored — a bare {@code onCreate}-only implementation only
 * ever reads the intent it was first created with, so a later attach intent
 * (app already open, or the OS reusing the existing task) would carry the
 * accessory extra straight past a display that never gets asked to update.
 *
 * The handshake runs on a background thread — every step in
 * {@link AaHandshake#performHandshake()} is a blocking read, and doing that
 * on the UI thread would freeze the app and risk an ANR.
 */
public class MainActivity extends Activity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setText("Waiting for head unit…");
        setContentView(statusView);
        handleAccessoryIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAccessoryIntent(intent);
    }

    @SuppressWarnings("deprecation") // getParcelableExtra(String) without a Class<T> is deprecated on API 33+
    private void handleAccessoryIntent(Intent intent) {
        UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
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
            + " (connected=" + transport.isConnected() + ") -- starting handshake...");

        new Thread(() -> runHandshake(transport), "aa-handshake").start();
    }

    private void runHandshake(UsbAccessoryTransport transport) {
        try {
            AaHandshake handshake = new AaHandshake(transport);
            boolean success = handshake.performHandshake();
            runOnUiThread(() -> statusView.setText(
                success ? "Handshake complete -- head unit authenticated us (AUTH_COMPLETE=OK)"
                    : "Handshake ran but head unit reported AUTH_COMPLETE=FAIL"));
            // TODO: hand `handshake.getSslEngine()` + transport to ChannelMultiplexer once
            // it's real (task #4), so encrypted post-handshake traffic can flow.
        } catch (Exception e) {
            runOnUiThread(() -> statusView.setText("Handshake failed: " + e));
        }
    }
}
