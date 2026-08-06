package com.example.aaphone.protocol.handshake;

import com.example.aaphone.protocol.transport.Transport;

/**
 * Performs the AA control-channel handshake: protocol version negotiation,
 * followed by an SSL/TLS-style key exchange using a self-signed certificate
 * (there is no CA chain validation against a real root here — the scheme is
 * closer to opportunistic encryption than a trust hierarchy).
 *
 * Do not hand-roll this from general SSL/TLS knowledge: AA wraps the
 * handshake in its own control-channel message types and version-negotiation
 * preamble. Port this from aasdk's SSL/handshake state machine
 * (https://github.com/f1xpl/aasdk) to get it byte-accurate — see
 * docs/design/0003-aasdk-protocol-notes.md for what's confirmed so far
 * (memory-BIO-based OpenSSL, not a plain socket) and what's still open
 * (exact cert/key, whether AUTH_COMPLETE is sent plain or encrypted).
 */
public class AaHandshake {

    private final Transport transport;

    public AaHandshake(Transport transport) {
        this.transport = transport;
    }

    public boolean performHandshake() {
        throw new UnsupportedOperationException(
            "Version request/response, SSL handshake messages, auth complete — port from aasdk");
    }
}
