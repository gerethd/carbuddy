package com.example.aaphone.protocol.channel;

/**
 * Messaging channel — scaffolded shape only.
 *
 * Milestone M1: backed by {@link MockMessagingSource} so the channel path
 * can be validated without real notifications.
 *
 * CORRECTION (see docs/design/0003-aasdk-protocol-notes.md): there is no
 * messaging channel type anywhere in the real protocol. Real Android Auto's
 * message popups/canned-reply UI are rendered as pixels into the video
 * stream with interaction via the input channel. This class's "send
 * IncomingMessage over a dedicated channel" design does not correspond to
 * anything real and needs to be redesigned as part of M4 (task #9) around
 * rendering into {@link VideoChannel}'s output and interpreting
 * {@link InputChannel} taps, keeping {@link MessagingSource} (a real
 * {@code NotificationListenerService}) as the actual content source.
 */
public class MessagingChannel implements Channel {

    private final MessagingSource source;

    public MessagingChannel() {
        this(new MockMessagingSource());
    }

    public MessagingChannel(MessagingSource source) {
        this.source = source;
    }

    @Override
    public int getId() {
        return ChannelId.MESSAGING;
    }

    @Override
    public void onOpen() {
        throw new UnsupportedOperationException(
            "Subscribe to source.incoming() and forward each IncomingMessage as a framed message");
    }

    @Override
    public void onMessage(byte[] payload) {
        throw new UnsupportedOperationException(
            "Parse inbound reply message and dispatch to source.sendReply(...)");
    }

    @Override
    public void onClose() {
        throw new UnsupportedOperationException("Unsubscribe from source");
    }
}
