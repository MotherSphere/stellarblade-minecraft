package org.stellarbridge;

/**
 * /stellar play and the Play as EVE key in one gesture: the link (/stellar start) and its first terrain, Stellar Blade's
 * import and enter (/stellar import and enter), then, once EVE's position places the player, /stellaravatar play. Each
 * tick advances it as far as it can, skipping what is already done; the first refusal or timeout stops it with one chat
 * line. A press while it runs is ignored. Client thread only.
 */
final class PlaySequence {
    /** How long each step may wait: the link and its first terrain, Stellar Blade's answer (BridgeLink's own ANSWER is shorter), EVE's position. */
    static final long WAIT = 10_000_000_000L;

    /** The client as the sequence sees and drives it. */
    interface Client {
        /** Whether the server runs Stellar Bridge. */
        boolean server();
        /** Sends /stellar start's link request. */
        void start();
        /** The link's terrain revision: -1 without a link, 0 before its first terrain. */
        int revision();
        /** EVE's state in Stellar Blade for the link: idle, ready (imported) or entered. */
        String state();
        /** Sends a session request (import or enter); returns why it was not sent, or null. */
        String request(String action);
        /** Whether EVE's position places the player (StellarBridge.renderingContext() is not null). */
        boolean placed();
        /** Whether EVE already plays (/stellaravatar play ran). */
        boolean playing();
        /** Runs /stellaravatar play. */
        void play();
        void say(String line);
    }

    private enum Step { LINK, IMPORT, ENTER, PLACE }
    private final Client client;
    private Step step; // null while not running.
    private boolean sent; // This step's session request awaits its answer.
    private long since; // When this step began (System.nanoTime).

    PlaySequence(Client client) { this.client = client; }

    boolean running() { return step != null; }

    /** The key or /stellar play: false while it already runs, else it starts (or says why it has nothing to do). */
    boolean begin(long nanos) {
        if (step != null) return false;
        if (client.playing()) {
            client.say("EVE is already playing.");
            return true;
        }
        if (client.revision() < 0) {
            if (!client.server()) {
                client.say("This server does not run Stellar Bridge; the link needs the mod on the server too.");
                return true;
            }
            client.start();
        }
        next(Step.LINK, nanos);
        return true;
    }

    /** A disconnect: it stops without a word. */
    void cancel() { step = null; }

    /** Each client tick, with Stellar Blade's answer to a session request when one came (shown here while it runs). */
    void tick(BridgeLink.Answer answer, long nanos) {
        while (step != null && advance(answer, nanos)) answer = null;
    }

    /** One step: true once it is done and the next one began. */
    private boolean advance(BridgeLink.Answer answer, long nanos) {
        switch (step) {
            case LINK -> { if (client.revision() <= 0) return waiting(nanos, "no terrain from the link"); }
            case IMPORT, ENTER -> {
                String action = step == Step.IMPORT ? "import" : "enter", state = client.state();
                if (sent) {
                    if (answer == null) return waiting(nanos, "no answer from Stellar Blade to " + action);
                    if (!answer.ok()) return stop(answer.chat());
                    client.say(answer.chat());
                } else if (!state.equals("entered") && !(step == Step.IMPORT && state.equals("ready"))) {
                    String refused = client.request(action);
                    if (refused != null) return stop(refused);
                    sent = true;
                    return false;
                }
            }
            case PLACE -> {
                if (!client.placed()) return waiting(nanos, "EVE's position did not reach Minecraft");
                step = null;
                client.play();
                return false;
            }
        }
        next(Step.values()[step.ordinal() + 1], nanos);
        return true;
    }

    private void next(Step following, long nanos) {
        step = following;
        sent = false;
        since = nanos;
    }

    private boolean waiting(long nanos, String why) {
        return nanos - since > WAIT && stop(why + " within " + WAIT / 1_000_000_000L + " s.");
    }

    private boolean stop(String why) {
        step = null;
        client.say("Play as EVE stopped: " + why);
        return false;
    }
}
