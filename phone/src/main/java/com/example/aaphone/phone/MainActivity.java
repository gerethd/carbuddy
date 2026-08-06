package com.example.aaphone.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
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
        // Explicit color/size/padding rather than relying on inherited theme
        // defaults -- confirmed working (2026-08-06 debug session: a deliberately
        // garish red/white/32sp version rendered fine), so kept explicit rather
        // than reverting to unset/theme-inherited values.
        statusView.setTextColor(Color.BLACK);
        statusView.setBackgroundColor(Color.WHITE);
        statusView.setTextSize(18f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(32, 32, 32, 32);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            statusView.setForceDarkAllowed(false);
        }
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

        // Everything past this point was previously uncaught: if it threw (e.g.
        // openAccessory() returning null), the exception propagated straight out
        // of onCreate/onNewIntent and crashed the activity -- silently, before
        // any of the status text below ever had a chance to show. That's the
        // most likely explanation if status updates ever appear to "do nothing".
        try {
            UsbAccessoryTransport transport = new UsbAccessoryTransport(this, accessory);

            statusView.setText("Opened accessory: " + accessory.getManufacturer() + " / " + accessory.getModel()
                + " (connected=" + transport.isConnected() + ") -- starting handshake...");

            new Thread(() -> runHandshake(transport), "aa-handshake").start();
        } catch (Exception e) {
            statusView.setText("Failed to open accessory: " + e);
        }
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
