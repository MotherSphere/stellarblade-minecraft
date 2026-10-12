package org.stellarbridge;

import java.util.ArrayList;
import java.util.List;

/**
 * /stellar play and the Play as EVE key, tick by tick against a scripted client: every step from nothing, each step
 * already done skipped, a refusal or a timeout stopping it with one line, and a press while it runs ignored.
 */
public final class PlaySequenceTest {
    private static final long S = 1_000_000_000L;

    /** A client whose link, Stellar Blade state and placement the test sets; it records what the sequence did. */
    private static final class Fake implements PlaySequence.Client {
        boolean server = true, placed, playing;
        int revision = -1;
        String state = "idle", refusal, startRefusal;
        final List<String> did = new ArrayList<>(), said = new ArrayList<>();
        public boolean server() { return server; }
        public String start() {
            if (startRefusal == null) did.add("start");
            return startRefusal;
        }
        public int revision() { return revision; }
        public String state() { return state; }
        public String request(String action) {
            if (refusal == null) did.add(action);
            return refusal;
        }
        public boolean placed() { return placed; }
        public boolean playing() { return playing; }
        public void play() { did.add("play"); playing = true; }
        public void say(String line) { said.add(line); }
    }

    private static BridgeLink.Answer answer(String action, String result, String message) {
        return new BridgeLink.Answer(action, result, "Stellar Blade " + action + " " + result + ": " + message);
    }

    public static void main(String[] args) {
        // From nothing: start, the first terrain, import, enter, EVE's position, play; each waits for the one before.
        Fake fake = new Fake();
        PlaySequence play = new PlaySequence(fake);
        assert play.begin(0) && play.running() && fake.did.equals(List.of("start"));
        play.tick(null, S);
        fake.revision = 0; // Linked, no terrain yet.
        play.tick(null, 2 * S);
        assert fake.did.equals(List.of("start")) : "Import waits for the first terrain revision";
        assert !play.begin(2 * S) && fake.did.equals(List.of("start")) && fake.said.isEmpty() : "A press while it runs does nothing";
        fake.revision = 1;
        play.tick(null, 3 * S);
        assert fake.did.equals(List.of("start", "import"));
        play.tick(null, 4 * S);
        play.tick(answer("import", "ok", "Imported."), 5 * S);
        assert fake.did.equals(List.of("start", "import", "enter")) : fake.did;
        fake.state = "ready";
        play.tick(answer("enter", "ok", "Entered."), 6 * S);
        play.tick(null, 7 * S);
        assert !fake.did.contains("play") : "Play waits for EVE's position";
        fake.state = "entered";
        fake.placed = true;
        play.tick(null, 8 * S);
        assert fake.did.equals(List.of("start", "import", "enter", "play")) && !play.running() : fake.did;
        assert fake.said.equals(List.of("Stellar Blade import ok: Imported.", "Stellar Blade enter ok: Entered.")) : fake.said;

        // Already playing: nothing to do.
        assert play.begin(9 * S) && !play.running() && fake.did.size() == 4 && fake.said.get(2).contains("already");

        // Linked and imported: enter only; linked and entered: play at once.
        fake = new Fake();
        fake.revision = 3;
        fake.state = "ready";
        play = new PlaySequence(fake);
        play.begin(0);
        play.tick(null, S);
        assert fake.did.equals(List.of("enter")) : fake.did;
        fake = new Fake();
        fake.revision = 3;
        fake.state = "entered";
        fake.placed = true;
        play = new PlaySequence(fake);
        play.begin(0);
        play.tick(null, S);
        assert fake.did.equals(List.of("play")) && !play.running() : fake.did;

        // A refused import stops with Stellar Blade's message; nothing else is sent.
        fake = new Fake();
        fake.revision = 1;
        play = new PlaySequence(fake);
        play.begin(0);
        play.tick(null, S);
        play.tick(answer("import", "refused", "Minecraft terrain names another session."), 2 * S);
        assert !play.running() && fake.did.equals(List.of("import"))
                && fake.said.equals(List.of("Play as EVE stopped: Stellar Blade import refused: Minecraft terrain names another session."))
                : fake.said;

        // A request the link will not send (another one pending) stops it too.
        fake = new Fake();
        fake.revision = 1;
        fake.refusal = "Waiting for Stellar Blade's answer to /stellar import.";
        play = new PlaySequence(fake);
        play.begin(0);
        play.tick(null, S);
        assert !play.running() && fake.said.equals(List.of("Play as EVE stopped: " + fake.refusal)) : fake.said;

        // Timeouts: no terrain, or no position after enter, within WAIT; one line each.
        fake = new Fake();
        play = new PlaySequence(fake);
        play.begin(0);
        play.tick(null, PlaySequence.WAIT);
        assert play.running() && fake.said.isEmpty();
        play.tick(null, PlaySequence.WAIT + 1);
        assert !play.running() && fake.said.size() == 1 && fake.said.get(0).startsWith("Play as EVE stopped:") : fake.said;
        fake = new Fake();
        fake.revision = 1;
        fake.state = "entered";
        play = new PlaySequence(fake);
        play.begin(0);
        play.tick(null, S);
        play.tick(null, S + PlaySequence.WAIT);
        assert play.running() && fake.said.isEmpty() : "Each step has its own WAIT";
        play.tick(null, S + PlaySequence.WAIT + 1);
        assert !play.running() && !fake.did.contains("play") && fake.said.size() == 1 : fake.said;
        // A sent import whose answer never comes (the link ended or was replaced, so BridgeLink's ANSWER never fires).
        fake = new Fake();
        fake.revision = 1;
        play = new PlaySequence(fake);
        play.begin(0);
        play.tick(null, S);
        assert fake.did.equals(List.of("import")) : fake.did;
        play.tick(null, S + PlaySequence.WAIT);
        assert play.running() && fake.said.isEmpty() : "Stellar Blade's answer gets the whole WAIT";
        play.tick(null, S + PlaySequence.WAIT + 1);
        assert !play.running() && fake.did.equals(List.of("import"))
                && fake.said.equals(List.of("Play as EVE stopped: no answer from Stellar Blade to import within 10 s.")) : fake.said;

        // No server mod: one line, no link request.
        fake = new Fake();
        fake.server = false;
        play = new PlaySequence(fake);
        assert play.begin(0) && !play.running() && fake.did.isEmpty() && fake.said.size() == 1 : fake.said;

        // A player the server would refuse (dead, spectator, riding, off a full block): its wording, no link request.
        fake = new Fake();
        fake.startRefusal = "Stand on a full block floor before starting the bridge.";
        play = new PlaySequence(fake);
        assert play.begin(0) && !play.running() && fake.did.isEmpty()
                && fake.said.equals(List.of("Play as EVE stopped: " + fake.startRefusal)) : fake.said;

        // A disconnect cancels it.
        fake = new Fake();
        play = new PlaySequence(fake);
        play.begin(0);
        play.cancel();
        assert !play.running() && play.begin(S);
        System.out.println("PlaySequenceTest passed");
    }
}
