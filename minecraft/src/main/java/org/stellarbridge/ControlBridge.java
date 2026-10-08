package org.stellarbridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Minecraft owns menus and hardware events; only this explicit session forwards controller states. In EVE mode the
 * keys follow Stellar Blade's keyboard defaults (its DefaultInput.ini) and press their controller equivalents: movement
 * on the left stick (walk key: a light tilt), jump A, sneak B (dodge), EVE sprint L3, attack X (light), use Y (heavy),
 * the inventory key LB (guard, parry), hotbar keys 1 to 4 the Beta skills (LB, then X, Y, A or B) or with the burst key
 * the Burst skills (RB instead of LB), drone aim LT (attack then fires with RT), heal D-pad up, item D-pad down,
 * interact RT, scan Back, lock-on R3 and Tachy mode L3 with R3. Minecraft actions sharing those keys wait for build mode.
 */
public final class ControlBridge {
    private static final int LB = 0x100, RB = 0x200, X = 0x4000, Y = 0x8000, A = 0x1000, B = 0x2000, L3 = 0x40, R3 = 0x80,
            LT = 0x10000, RT = 0x20000, UP = 0x1, DOWN = 0x2, BACK = 0x20;
    // Per binding index (bindings()): movement (0-3) and walk shape the stick; skills (16-19) are sequenced by buttons();
    // Minecraft's own sprint key (6) does nothing in EVE mode, where the EVE sprint key sprints.
    private static final int[] BUTTON = {0, 0, 0, 0, A, B, 0, X, Y,
            Y, RB, LT, RT, R3, UP, LB, 0, 0, 0, 0, L3, 0, LT, 0, UP, DOWN, RT, BACK, R3, L3 | R3};
    static final int DODGE = 5, ATTACK = 7, EXTRA_R3 = 13, GUARD = 15, SKILLS = 16, WALK = 21, AIM = 22, BURST = 23, LOCK = 28, TACHY = 29;
    static final double WALK_TILT = 0.35; // Stick deflection while the walk key is held: Stellar Blade walks.
    private static final int[] SKILL_FACE = {X, Y, A, B}; // BetaSkill1..4: face left, top, bottom, right.
    static final long SKILL_DELAY = 40_000_000L; // LB leads a skill's face button by 40 ms, as a player's thumb would.
    private static final boolean[] held = new boolean[30];
    private static final long[] heldSince = new long[30];
    private static final boolean[] wasHeld = new boolean[30];
    private static final KeyBinding[] eve = new KeyBinding[10];
    private static final Set<Integer> scanCodes = new HashSet<>();
    private static final Set<InputUtil.Key> blocked = new HashSet<>();
    private static KeyBinding mode;
    private static final KeyBinding[] extra = new KeyBinding[6];
    private static ControlState state;
    private static ControlFiles files;
    private static boolean build;
    private static double lookX, lookY, gain = 2500;
    // Avatar control: the pose stream gates input; the adapter turns Stellar Blade's camera toward Minecraft's through
    // look input, with right-stick steering as the fallback when it does not.
    private static boolean avatar;
    private static long publishedNanos;
    private static double unwrappedYaw = Double.NaN, lastUnrealYaw; // Stellar Blade's controller yaw, turns counted.
    static final double STEER_DEGREES = 45; // Yaw error that turns Stellar Blade's camera at full stick.
    private static String failure = "";

    static void initialize() {
        String category = "key.categories.stellar_bridge";
        mode = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.stellar_bridge.mode", GLFW.GLFW_KEY_B, category));
        String[] names = {"y", "rb", "lt", "rt", "r3", "dpad_up"};
        for (int i = 0; i < extra.length; i++) extra[i] = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.stellar_bridge." + names[i], InputUtil.UNKNOWN_KEY.getCode(), category));
        // Stellar Blade's keyboard defaults, by physical key (an AZERTY heal key A is Minecraft's Q position).
        String[] eveNames = {"sprint", "walk", "aim", "burst", "heal", "item", "interact", "scan", "lock_on", "tachy"};
        int[] eveKeys = {GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_Q,
                GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, GLFW.GLFW_KEY_T};
        for (int i = 0; i < eve.length; i++) eve[i] = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.stellar_bridge." + eveNames[i],
                eveNames[i].equals("lock_on") ? InputUtil.Type.MOUSE : InputUtil.Type.KEYSYM, eveKeys[i], category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    static void start(Path directory, String run, String session) throws IOException {
        stop();
        files = new ControlFiles(directory);
        state = new ControlState(run, session);
        build = false;
        failure = "";
        rememberHeld();
        files.publish(state.packet(System.currentTimeMillis()));
        clearMinecraftActions(true);
    }

    /** Like start, but gated by the avatar pose stream instead of captured images. */
    static void startAvatar(Path directory, String run, String session) throws IOException {
        start(directory, run, session);
        avatar = true;
        resetSteering();
    }

    static void resetSteering() { unwrappedYaw = Double.NaN; }

    static void stop() {
        avatar = false;
        AvatarLock.release();
        AvatarCombat.inputs(Long.MIN_VALUE, Long.MIN_VALUE, false);
        if (state != null) {
            state.stop();
            publish();
            clearMinecraftActions(true);
        }
        state = null;
        files = null;
        blocked.clear();
        lookX = lookY = 0;
    }

    public static void suspend() {
        if (state == null) return;
        rememberHeld();
        AvatarCombat.inputs(Long.MIN_VALUE, Long.MIN_VALUE, false);
        state.neutral("release physical controls before resuming");
        lookX = lookY = 0;
        publish();
    }

    static void build(boolean enabled) {
        if (state == null || build == enabled) return;
        build = enabled;
        if (enabled) AvatarLock.release();
        suspend();
        clearMinecraftActions(!enabled);
    }
    private static void clearMinecraftActions(boolean stopItemUse) {
        var client = MinecraftClient.getInstance();
        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.cancelBlockBreaking();
            if (stopItemUse && client.player.isUsingItem()) client.interactionManager.stopUsingItem(client.player);
        }
    }
    static boolean build() { return build; }
    /** Whether EVE's aim key aims her drone (EVE mode, forwarding active): Stellar Blade shows its bullet slots then. */
    static boolean aiming() { return held[AIM] && !build && state != null && state.active(); }
    /** The bound key of EVE's heal or item key for the HUD's hints when its name is one character (A on AZERTY), else null. */
    static String keyHint(boolean heal) {
        String key = eve[heal ? 4 : 5].getBoundKeyLocalizedText().getString();
        return key.length() == 1 ? key : null;
    }
    static void gain(double value) {
        if (!Double.isFinite(value) || value < 1 || value > 32767) throw new IllegalArgumentException("Look gain must be between 1 and 32767");
        gain = value;
        lookX = lookY = 0;
    }
    static String status() {
        return state == null ? "Minecraft input forwarding is off."
                : "EVE input" + (avatar ? " (avatar)" : "") + ": " + (build ? "build" : "EVE") + "; " + state.reason()
                        + "; look gain " + gain + (failure.isEmpty() ? "" : "; " + failure);
    }

    private static KeyBinding[] bindings() {
        var options = MinecraftClient.getInstance().options;
        return new KeyBinding[] {options.forwardKey, options.backKey, options.leftKey, options.rightKey,
                options.jumpKey, options.sneakKey, options.sprintKey, options.attackKey, options.useKey,
                extra[0], extra[1], extra[2], extra[3], extra[4], extra[5], options.inventoryKey,
                options.hotbarKeys[0], options.hotbarKeys[1], options.hotbarKeys[2], options.hotbarKeys[3],
                eve[0], eve[1], eve[2], eve[3], eve[4], eve[5], eve[6], eve[7], eve[8], eve[9]};
    }

    /**
     * Controller buttons for held bindings (indices as bindings()) held since the given nanosecond times. A skill holds
     * LB and adds its face button once held for SKILL_DELAY.
     */
    static int buttons(boolean[] held, long[] since, long nowNanos) {
        int mask = 0;
        for (int i = 4; i < held.length; i++) if (held[i] && (i < SKILLS || i >= SKILLS + SKILL_FACE.length)) mask |= BUTTON[i];
        if (held[AIM] && held[ATTACK]) mask = mask & ~X | RT; // Aiming the drone, attack fires.
        for (int k = 0; k < SKILL_FACE.length; k++) {
            if (!held[SKILLS + k]) continue;
            mask |= held[BURST] ? RB : LB;
            if (nowNanos - since[SKILLS + k] >= SKILL_DELAY) mask |= SKILL_FACE[k];
        }
        return mask;
    }

    /**
     * In EVE mode the inventory key, hotbar 1 to 4 and the EVE keys drive EVE: drop the queued presses of every Minecraft
     * action bound to one of those keys (inventory, hotbar, drop, swap hands, pick block and the like).
     */
    public static void consumeEveKeys() {
        if (state == null || build) return;
        var options = MinecraftClient.getInstance().options;
        Set<InputUtil.Key> eveKeys = new HashSet<>();
        eveKeys.add(KeyBindingHelper.getBoundKeyOf(options.inventoryKey));
        for (int k = 0; k < SKILL_FACE.length; k++) eveKeys.add(KeyBindingHelper.getBoundKeyOf(options.hotbarKeys[k]));
        for (KeyBinding binding : eve) eveKeys.add(KeyBindingHelper.getBoundKeyOf(binding));
        eveKeys.remove(InputUtil.UNKNOWN_KEY);
        for (KeyBinding binding : options.allKeys) {
            if (binding.getCategory().equals("key.categories.stellar_bridge") || !eveKeys.contains(KeyBindingHelper.getBoundKeyOf(binding))) continue;
            while (binding.wasPressed()) { }
        }
    }

    public static void keyboard(long window, int key, int scan, int action, int modifiers) {
        var client = MinecraftClient.getInstance();
        if (mode == null || window != client.getWindow().getHandle()) return;
        if (action == GLFW.GLFW_RELEASE) scanCodes.remove(scan);
        else if (action == GLFW.GLFW_PRESS) scanCodes.add(scan);
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;
        boolean down = action == GLFW.GLFW_PRESS;
        if (!down && client.isWindowFocused()) {
            blocked.remove(InputUtil.Type.KEYSYM.createFromCode(key));
            blocked.remove(InputUtil.Type.SCANCODE.createFromCode(scan));
        }
        var keys = bindings();
        for (int i = 0; i < keys.length; i++) if (keys[i].matchesKey(key, scan)) held[i] = down;
        if (state != null && down && modifiers == 0 && mode.matchesKey(key, scan) && windowOwned()) {
            build(!build);
            if (client.player != null) client.player.sendMessage(net.minecraft.text.Text.literal(
                    build ? "Build mode: Minecraft owns clicks; EVE movement is held." : "EVE mode: Minecraft inputs drive the native controller."), true);
        }
        if (state != null && state.active() && windowOwned()) apply(System.currentTimeMillis());
        if (avatar) publish(); // Avatar mode has no image to wait for: send edges at once.
    }

    public static void mouseButton(long window, int button, int action) {
        var client = MinecraftClient.getInstance();
        if (mode == null || window != client.getWindow().getHandle()
                || action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;
        boolean down = action == GLFW.GLFW_PRESS;
        if (!down && client.isWindowFocused()) blocked.remove(InputUtil.Type.MOUSE.createFromCode(button));
        var keys = bindings();
        for (int i = 0; i < keys.length; i++) if (keys[i].matchesMouse(button)) held[i] = down;
        if (state != null && down && mode.matchesMouse(button) && windowOwned()) build(!build);
        if (state != null && state.active() && windowOwned()) apply(System.currentTimeMillis());
        if (avatar) publish();
    }

    /** Called only at vanilla's sensitivity/invert-Y adjusted gameplay look invocation. */
    public static boolean mouseLook(double x, double y) {
        if (state == null || !windowOwned()) return false;
        if (avatar) return false; // Minecraft turns its own camera; axes() steers Stellar Blade toward it.
        if (state.active() && frameReady()) {
            lookX = Math.clamp(lookX + x, -1000, 1000);
            lookY = Math.clamp(lookY - y, -1000, 1000); // XInput up is positive.
            state.axes(axes());
        }
        return true;
    }

    public static boolean suppressAttack() { return suppress(false); }
    public static boolean suppressUse() { return suppress(true); }
    static void alignPresentedLook() {
        if (state == null || !state.active() || !windowOwned()) return;
        var camera = CaptureView.controlCamera(state.run(), state.session(), System.nanoTime());
        if (camera == null) return;
        var player = MinecraftClient.getInstance().player;
        player.setYaw(camera.yaw());
        player.setPitch(camera.pitch());
    }
    public static void suppressVanillaMovement(net.minecraft.client.input.Input input) {
        clearMovement(input, state == null || build);
    }
    static void clearMovement(net.minecraft.client.input.Input input, boolean keepSneak) {
        input.movementForward = input.movementSideways = 0;
        input.pressingForward = input.pressingBack = input.pressingLeft = input.pressingRight = input.jumping = false;
        if (!keepSneak) input.sneaking = false;
    }
    private static boolean suppress(boolean use) {
        if (state == null) return false;
        var client = MinecraftClient.getInstance();
        if (client.currentScreen != null || client.getOverlay() != null) return false;
        var options = client.options;
        return !build || blocked.contains(KeyBindingHelper.getBoundKeyOf(use ? options.useKey : options.attackKey));
    }

    private static void tick() {
        if (state == null) return;
        var keys = bindings();
        boolean allReleased = true;
        for (int i = 0; i < keys.length; i++) {
            held[i] = physical(KeyBindingHelper.getBoundKeyOf(keys[i]));
            allReleased &= !held[i];
        }
        allReleased &= blocked.isEmpty() && !physical(KeyBindingHelper.getBoundKeyOf(mode));
        long now = System.currentTimeMillis(), nanos = System.nanoTime();
        try { state.acknowledge(files.readAck(), now, nanos); }
        catch (IOException | IllegalArgumentException error) { state.unavailable("native input acknowledgement unavailable"); }
        boolean allowed = windowOwned() && frameReady();
        state.eligibility(allowed, allReleased, now, nanos);
        if (!state.active()) AvatarCombat.inputs(Long.MIN_VALUE, Long.MIN_VALUE, false); // No stale guard or dodge.
        if (state.active()) apply(now);
        publish();
        lookX = lookY = 0;
        state.axes(axes());
    }

    private static void apply(long time) {
        if (!frameReady()) { suspend(); return; }
        state.axes(axes());
        long nanos = System.nanoTime();
        for (int i = 0; i < held.length; i++) {
            if (held[i] && !wasHeld[i]) {
                heldSince[i] = nanos;
                if (i == LOCK && avatar && !build) AvatarLock.toggle(); // Minecraft's entities have no Stellar Blade lock-on.
            }
            wasHeld[i] = held[i];
        }
        int mask = build ? 0 : buttons(held, heldSince, nanos);
        if (avatar && held[LOCK] && !held[EXTRA_R3]) mask &= ~R3; // Stellar Blade's own lock-on would recenter its camera.
        // The dodge and guard keys' latest presses, for Minecraft's damage to meet at the impact.
        if (!build) AvatarCombat.inputs(heldSince[DODGE], heldSince[GUARD], held[GUARD]);
        for (int bit : new int[] {A, B, L3, X, Y, RB, LT, RT, R3, UP, DOWN, BACK, LB}) state.button(bit, (mask & bit) != 0, time);
    }

    /** Each avatar render frame: refresh the steering and publish at most every 20 ms, the adapter's read period. */
    static void avatarFrame() {
        if (state == null || !avatar || !state.active() || !windowOwned()) return;
        apply(System.currentTimeMillis()); // Also presses a skill's face button once its delay has passed.
        if (System.nanoTime() - publishedNanos >= 20_000_000L) publish();
    }

    /**
     * Yaw error from Stellar Blade's controller to Minecraft's camera (Minecraft yaw + 90 is Unreal yaw), with Stellar
     * Blade's yaw unwrapped across samples. During a spin faster than Stellar Blade can turn, the error keeps its sign
     * instead of flipping at 180 degrees; more than one full turn behind is dropped, since it ends at the same view.
     */
    static double yawError(double minecraftYaw, double unrealYaw) {
        double desired = minecraftYaw + 90;
        if (Double.isNaN(unwrappedYaw)) unwrappedYaw = desired - Math.IEEEremainder(desired - unrealYaw, 360); // Shortest start.
        else unwrappedYaw += Math.IEEEremainder(unrealYaw - lastUnrealYaw, 360);
        lastUnrealYaw = unrealYaw;
        double error = desired - unwrappedYaw;
        if (Math.abs(error) >= 360) {
            double turns = Math.copySign(360 * Math.floor(Math.abs(error) / 360), error);
            unwrappedYaw += turns;
            error -= turns;
        }
        return error;
    }

    /**
     * Avatar steering for a yaw error: the left stick rotated into Stellar Blade's camera frame by the actual angle,
     * and a right stick X proportional to the error. Returns left X, left Y, right X.
     */
    static int[] steer(double error, int lx, int ly) {
        double radians = Math.toRadians(Math.IEEEremainder(error, 360)), cos = Math.cos(radians), sin = Math.sin(radians);
        int x = (int) Math.round(Math.clamp(lx * cos + ly * sin, -32767, 32767));
        int y = (int) Math.round(Math.clamp(-lx * sin + ly * cos, -32767, 32767));
        int turn = Math.abs(error) < 1 ? 0 : (int) Math.round(Math.clamp(error / STEER_DEGREES, -1, 1) * 32767);
        return new int[] {x, y, turn};
    }

    private static ControlState.Axes axes() {
        int x = build ? 0 : (held[3] ? 1 : 0) - (held[2] ? 1 : 0);
        int y = build ? 0 : (held[0] ? 1 : 0) - (held[1] ? 1 : 0);
        int scale = (int) Math.round((x != 0 && y != 0 ? 23170 : 32767) * (held[WALK] && !build ? WALK_TILT : 1));
        if (avatar) {
            var player = MinecraftClient.getInstance().player;
            float[] rotation = AvatarView.controllerRotation();
            if (player == null || rotation == null) return new ControlState.Axes(x * scale, y * scale, 0, 0);
            int[] steered = steer(yawError(player.getYaw(), rotation[1]), x * scale, y * scale);
            // While the adapter turns the camera through look input, the right stick stays neutral.
            return new ControlState.Axes(steered[0], steered[1], AvatarView.lookApplied() ? 0 : steered[2], 0);
        }
        return new ControlState.Axes(x * scale, y * scale,
                (int) Math.clamp(Math.round(lookX * gain), -32767, 32767),
                (int) Math.clamp(Math.round(lookY * gain), -32767, 32767));
    }
    private static boolean physical(InputUtil.Key key) {
        if (key.equals(InputUtil.UNKNOWN_KEY)) return false;
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        if (key.getCategory() == InputUtil.Type.MOUSE) return GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
        if (key.getCategory() == InputUtil.Type.SCANCODE) return scanCodes.contains(key.getCode());
        return InputUtil.isKeyPressed(window, key.getCode());
    }
    private static void rememberHeld() {
        var keys = bindings();
        for (int i = 0; i < keys.length; i++) {
            InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(keys[i]);
            if (!key.equals(InputUtil.UNKNOWN_KEY) && (held[i] || physical(key))) blocked.add(key);
        }
        var key = KeyBindingHelper.getBoundKeyOf(mode);
        if (physical(key)) blocked.add(key);
    }
    private static boolean windowOwned() {
        var client = MinecraftClient.getInstance();
        return client.player != null && client.world != null && client.isWindowFocused() && client.currentScreen == null
                && client.getOverlay() == null && client.mouse.isCursorLocked()
                && !InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_F3);
    }
    private static boolean frameReady() {
        return state != null && (avatar ? AvatarView.controlReady(state.run(), state.session(), System.nanoTime())
                : CaptureView.controlFrameReady(state.run(), state.session(), System.nanoTime()));
    }
    private static void publish() {
        if (state == null || files == null) return;
        try { files.publish(state.packet(System.currentTimeMillis())); failure = ""; publishedNanos = System.nanoTime(); }
        catch (IOException | IllegalArgumentException error) {
            state.unavailable("controller publication failed");
            failure = error.getMessage(); // Native's independent lease still releases even when this write fails.
        }
    }
}
