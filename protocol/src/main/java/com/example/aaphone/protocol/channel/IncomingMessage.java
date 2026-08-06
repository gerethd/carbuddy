package com.example.aaphone.protocol.channel;

import java.util.Objects;

public final class IncomingMessage {
    private final String sender;
    private final String preview;

    public IncomingMessage(String sender, String preview) {
        this.sender = sender;
        this.preview = preview;
    }

    public String getSender() {
        return sender;
    }

    public String getPreview() {
        return preview;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IncomingMessage)) return false;
        IncomingMessage that = (IncomingMessage) o;
        return Objects.equals(sender, that.sender) && Objects.equals(preview, that.preview);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender, preview);
    }

    @Override
    public String toString() {
        return "IncomingMessage{sender='" + sender + "', preview='" + preview + "'}";
    }
}
