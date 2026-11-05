package io.roadmoment.passed;

/**
 * Decides whether now is a bad moment to ask the driver something. Questions wait while the turn signal
 * is on, a turn is close, or the car is entering a zone that needs full attention. Signals go stale so
 * a lost update cannot hold questions forever.
 */
public final class Safety {
    static final double TURN_NEAR_M = 150, CAUTION_NEAR_M = 200;
    static final long STALE_MS = 15000;
    /** Caution point types (NaviHelper CautionPointType names) that call for full attention. */
    static final java.util.Set<String> ATTENTION = new java.util.HashSet<>(java.util.Arrays.asList(
            "SCHOOL_ZONE", "RAILROAD_CROSSING", "ACCIDENT_PRONE_AREA", "WILDLIFE_CROSSING", "FALLING_ROCK_AREA",
            "DOWNHILL_CAUTION_AREA", "NARROW_ROAD", "SLIPPERY_ROAD_ALERT", "WRONG_WAY_DRIVING_ALERT", "HIGHWAY_CONSTRUCTION"));

    private boolean signalOn; private long signalAt;
    private double turnM = Double.MAX_VALUE; private long turnAt;
    private double cautionM = Double.MAX_VALUE; private long cautionAt; private String caution = "";

    public synchronized void turnSignal(boolean on, long now) { signalOn = on; signalAt = now; }

    /** Distance to the next turn instruction, or a negative value when there is none. */
    public synchronized void nextTurn(double meters, long now) { turnM = meters < 0 ? Double.MAX_VALUE : meters; turnAt = now; }

    /** Nearest caution point ahead; ignored unless its type needs attention. */
    public synchronized void caution(String type, double meters, long now) {
        if (type == null || !ATTENTION.contains(type)) { cautionM = Double.MAX_VALUE; cautionAt = now; caution = ""; return; }
        cautionM = meters; cautionAt = now; caution = type;
    }

    /** Null when it is fine to ask; otherwise a short reason for the status line. */
    public synchronized String busy(long now) {
        if (signalOn && now - signalAt < 60000) return "방향지시등 켜짐"; // the signal reports again when it turns off
        if (turnM <= TURN_NEAR_M && now - turnAt < STALE_MS) return "곧 회전";
        if (cautionM <= CAUTION_NEAR_M && now - cautionAt < STALE_MS) return "주의 구간";
        return null;
    }
}
