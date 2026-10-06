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

/** Minecraft owns menus and hardware events; only this explicit session forwards controller states. */
public final class ControlBridge {
    private static final int[] BUTTON = {0, 0, 0, 0, 0x1000, 0x2000, 0x40, 0x4000, 0x100,
            0x8000, 0x200, 0x10000, 0x20000, 0x80, 0x1};
    private static final boolean[] held = new boolean[15];
    private static final Set<Integer> scanCodes = new HashSet<>();
    private static final Set<InputUtil.Key> blocked = new HashSet<>();
    private static KeyBinding mode;
    private static final KeyBinding[] extra = new KeyBinding[6];
    private static ControlState state;
    private static ControlFiles files;
    private static boolean build;
    private static double lookX, lookY, gain = 2500;
    // Avatar control: the pose stream gates input and Minecraft's camera steers Stellar Blade's (yaw only).
    private static boolean avatar;
    private static long publishedNanos;
    static final double STEER_DEGREES = 45; // Yaw error that turns Stellar Blade's camera at full stick.
    private static String failure = "";

    static void initialize() {
        String category = "key.categories.stellar_bridge";
        mode = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.stellar_bridge.mode", GLFW.GLFW_KEY_B, category));
        String[] names = {"y", "rb", "lt", "rt", "r3", "dpad_up"};
        for (int i = 0; i < extra.length; i++) extra[i] = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.stellar_bridge." + names[i], InputUtil.UNKNOWN_KEY.getCode(), category));
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
    }

    static void stop() {
        avatar = false;
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
        state.neutral("release physical controls before resuming");
        lookX = lookY = 0;
        publish();
    }

    static void build(boolean enabled) {
        if (state == null || build == enabled) return;
        build = enabled;
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
                extra[0], extra[1], extra[2], extra[3], extra[4], extra[5]};
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
        if (state.active()) apply(now);
        publish();
        lookX = lookY = 0;
        state.axes(axes());
    }

    private static void apply(long time) {
        if (!frameReady()) { suspend(); return; }
        state.axes(axes());
        for (int i = 4; i < held.length; i++) state.button(BUTTON[i], !build && held[i], time);
    }

    /** Each avatar render frame: refresh the steering and publish at most every 20 ms, the adapter's read period. */
    static void avatarFrame() {
        if (state == null || !avatar || !state.active() || !windowOwned()) return;
        state.axes(axes());
        if (System.nanoTime() - publishedNanos >= 20_000_000L) publish();
    }

    /**
     * Avatar steering: the left stick rotated from Minecraft's camera yaw into Stellar Blade's controller yaw, and a
     * right stick X proportional to the remaining yaw error. Returns left X, left Y, right X.
     */
    static int[] steer(double minecraftYaw, double unrealYaw, int lx, int ly) {
        double error = Math.IEEEremainder(minecraftYaw + 90 - unrealYaw, 360); // Minecraft yaw + 90 is Unreal yaw.
        double radians = Math.toRadians(error), cos = Math.cos(radians), sin = Math.sin(radians);
        int x = (int) Math.round(Math.clamp(lx * cos + ly * sin, -32767, 32767));
        int y = (int) Math.round(Math.clamp(-lx * sin + ly * cos, -32767, 32767));
        int turn = Math.abs(error) < 1 ? 0 : (int) Math.round(Math.clamp(error / STEER_DEGREES, -1, 1) * 32767);
        return new int[] {x, y, turn};
    }

    private static ControlState.Axes axes() {
        int x = build ? 0 : (held[3] ? 1 : 0) - (held[2] ? 1 : 0);
        int y = build ? 0 : (held[0] ? 1 : 0) - (held[1] ? 1 : 0);
        int scale = x != 0 && y != 0 ? 23170 : 32767;
        if (avatar) {
            var player = MinecraftClient.getInstance().player;
            float[] rotation = AvatarView.controllerRotation();
            if (player == null || rotation == null) return new ControlState.Axes(x * scale, y * scale, 0, 0);
            int[] steered = steer(player.getYaw(), rotation[1], x * scale, y * scale);
            return new ControlState.Axes(steered[0], steered[1], steered[2], 0);
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
