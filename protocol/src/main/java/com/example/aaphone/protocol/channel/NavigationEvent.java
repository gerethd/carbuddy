package com.example.aaphone.protocol.channel;

import java.util.Objects;

public final class NavigationEvent {
    private final String instruction;
    private final int distanceMeters;
    private final int etaMinutes;

    public NavigationEvent(String instruction, int distanceMeters, int etaMinutes) {
        this.instruction = instruction;
        this.distanceMeters = distanceMeters;
        this.etaMinutes = etaMinutes;
    }

    public String getInstruction() {
        return instruction;
    }

    public int getDistanceMeters() {
        return distanceMeters;
    }

    public int getEtaMinutes() {
        return etaMinutes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NavigationEvent)) return false;
        NavigationEvent that = (NavigationEvent) o;
        return distanceMeters == that.distanceMeters
            && etaMinutes == that.etaMinutes
            && Objects.equals(instruction, that.instruction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instruction, distanceMeters, etaMinutes);
    }

    @Override
    public String toString() {
        return "NavigationEvent{instruction='" + instruction + "', distanceMeters=" + distanceMeters
            + ", etaMinutes=" + etaMinutes + '}';
    }
}
