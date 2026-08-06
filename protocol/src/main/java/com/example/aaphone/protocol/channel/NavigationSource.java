package com.example.aaphone.protocol.channel;

import java.util.List;

public interface NavigationSource {
    List<NavigationEvent> updates();
}
