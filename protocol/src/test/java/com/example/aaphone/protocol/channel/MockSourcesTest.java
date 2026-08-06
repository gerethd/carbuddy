package com.example.aaphone.protocol.channel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Covers the mock content sources — the only logic in this module that isn't
 * a TODO stub pending protocol verification (see ROADMAP.md).
 */
public class MockSourcesTest {

    @Test
    public void mockNavigationSourceEmitsArrivalAsFinalEvent() {
        List<NavigationEvent> events = new MockNavigationSource().updates();

        assertTrue(!events.isEmpty());
        NavigationEvent last = events.get(events.size() - 1);
        assertEquals("Arrive at destination", last.getInstruction());
        assertEquals(0, last.getDistanceMeters());
    }

    @Test
    public void mockMediaSourceStartsPlayingAndRespondsToPause() {
        MockMediaSource source = new MockMediaSource();
        assertTrue(source.nowPlaying().isPlaying());

        source.onPlaybackControl(PlaybackCommand.PAUSE);
        assertFalse(source.nowPlaying().isPlaying());

        source.onPlaybackControl(PlaybackCommand.PLAY);
        assertTrue(source.nowPlaying().isPlaying());
    }

    @Test
    public void mockMessagingSourceReturnsSeededIncomingMessage() {
        List<IncomingMessage> messages = new MockMessagingSource().incoming();

        assertEquals(1, messages.size());
        assertEquals("Alex", messages.get(0).getSender());
    }
}
