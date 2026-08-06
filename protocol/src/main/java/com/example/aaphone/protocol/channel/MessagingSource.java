package com.example.aaphone.protocol.channel;

import java.util.List;

public interface MessagingSource {
    List<IncomingMessage> incoming();

    void sendReply(String originalSender, String replyText);
}
