package io.roadmoment.passed;

/** Volatile state the service publishes and the activity polls. Holds no transcript history. */
final class Status {
    static volatile boolean running, driving, micBlocked;
    static volatile boolean listenWhileParked; // demo/emulator: no movement, still listen
    static volatile String service = "대기", speech = "", heard = "", lastSaid = "", source = "";
    static volatile double speedMps, db = -90;
    static volatile int fixes;
    static volatile long reactionMs = Reaction.DEFAULT_MS;
    static volatile Dialog.Ask ask;          // question on screen, null when none
    static volatile String hold;             // why a question is waiting ("곧 우회전"), null when not waiting
    static volatile String offer;            // saved place offered as a stop, null when none
    static volatile java.util.List<String> choices; // saves read out to pick from ("오늘 찜한 곳"), null when none
    static volatile String offerWhy;         // why it is offered ("찜한 곳 근처예요", "목적지 근처 찜한 곳")
    static volatile boolean passenger;       // front passenger seat occupied (Vehicle SDK), so the screen may be used
    static volatile boolean locationWaiting; // no fix yet
    private Status() {}
}
