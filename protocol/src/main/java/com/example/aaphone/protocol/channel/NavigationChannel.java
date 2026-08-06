package com.example.aaphone.protocol.channel;

/**
 * Navigation status channel. Sends turn-by-turn guidance events to the head
 * unit (maneuver icon, distance, street name, ETA, etc.).
 *
 * Milestone M1: emits {@link MockNavigationSource} data so the channel +
 * framing + handshake path can be validated end-to-end against a real head
 * unit before any real navigation engine is wired in.
 *
 * Milestone M3 (see docs/ROADMAP.md) replaces {@link MockNavigationSource}
 * with a real turn-by-turn source — e.g. driving Android's
 * {@code Geocoder}/routing APIs directly, or hosting a map/routing engine
 * in-process, since we are no longer able to lean on a third-party nav app
 * the way Google's real AA app does via the Car App Library's navigation
 * templates. Unlike media/messaging (see docs/design/0003-aasdk-protocol-notes.md),
 * navigation is confirmed to be a real first-class wire channel, so this
 * class's shape stays valid — only the source changes.
 */
public class NavigationChannel implements Channel {

    private final NavigationSource source;

    public NavigationChannel() {
        this(new MockNavigationSource());
    }

    public NavigationChannel(NavigationSource source) {
        this.source = source;
    }

    @Override
    public int getId() {
        return ChannelId.NAVIGATION_STATUS;
    }

    @Override
    public void onOpen() {
        throw new UnsupportedOperationException(
            "Subscribe to source.updates() and forward each NavigationEvent as a framed message");
    }

    @Override
    public void onMessage(byte[] payload) {
        // Navigation status is currently phone -> head unit only; no inbound
        // messages are expected on this channel in the base protocol.
    }

    @Override
    public void onClose() {
        throw new UnsupportedOperationException("Unsubscribe from source");
    }
}
