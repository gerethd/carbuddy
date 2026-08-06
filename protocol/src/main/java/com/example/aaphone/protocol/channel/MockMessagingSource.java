package com.example.aaphone.protocol.channel;

import java.util.List;

public class MockMessagingSource implements MessagingSource {

    @Override
    public List<IncomingMessage> incoming() {
        return List.of(new IncomingMessage("Alex", "Running 10 min late"));
    }

    @Override
    public void sendReply(String originalSender, String replyText) {
        // No-op in the mock source.
    }
}
