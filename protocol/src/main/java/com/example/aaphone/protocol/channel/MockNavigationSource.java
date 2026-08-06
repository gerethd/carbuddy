package com.example.aaphone.protocol.channel;

import java.util.List;

public class MockNavigationSource implements NavigationSource {
    @Override
    public List<NavigationEvent> updates() {
        return List.of(
            new NavigationEvent("Turn left onto Main St", 400, 12),
            new NavigationEvent("Continue straight", 1200, 10),
            new NavigationEvent("Arrive at destination", 0, 0)
        );
    }
}
