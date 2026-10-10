package org.stellarbridge;

import java.util.List;
import java.util.regex.Pattern;

/** Bounded text messages shared with the UE4SS Lua mod. */
public final class BridgeProtocol {
    private static final long MAX_TIME = 9007199254740991L;
    private static final Pattern SESSION = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final Pattern UNSIGNED = Pattern.compile("[0-9]+");
    /** Merged collision boxes in one terrain snapshot; a full packet must also fit in 64 KiB. */
    static final int MAX_BOXES = 1024;
    /** How far from the bridge origin, in blocks, the terrain window, EVE and the mob walls may go. */
    static final double MAX_REACH = 2048;

    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    public record Terrain(String session, int seq, long time, String dimension,
        double originX, double originY, double originZ, Box bounds, List<Box> boxes) {}
    public record Eve(String session, int seq, long time, String state, int ack, double x, double y, double z) {}

    private BridgeProtocol() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }
    private static boolean bounded(double value, double limit) {
        return Double.isFinite(value) && Math.abs(value) <= limit;
    }
    private static long unsigned(String value, long minimum, long maximum) {
        require(matches(UNSIGNED,value), "Invalid integer");
        try {
            long number = Long.parseLong(value);
            require(number >= minimum && number <= maximum, "Integer outside limits");
            return number;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Integer outside limits", failure);
        }
    }
    private static double decimal(String value, double limit) {
        require(matches(DECIMAL,value), "Invalid decimal");
        double number = Double.parseDouble(value);
        require(bounded(number,limit), "Number outside limits");
        return number;
    }
    private static String[] fields(String line, String name, int count) {
        String[] fields = line.split(" ",-1);
        require(fields.length == count && fields[0].equals(name), "Invalid " + name + " line");
        for (String field : fields) require(!field.isEmpty(), "Empty field");
        return fields;
    }
    private static void checkBox(Box box) {
        require(box != null && bounded(box.minX,MAX_REACH) && bounded(box.minY,MAX_REACH) && bounded(box.minZ,MAX_REACH)
            && bounded(box.maxX,MAX_REACH) && bounded(box.maxY,MAX_REACH) && bounded(box.maxZ,MAX_REACH)
            && box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ, "Invalid box");
    }
    private static Box fixed(Box box) {
        return box == null ? null : new Box(fixed(box.minX), fixed(box.minY), fixed(box.minZ), fixed(box.maxX), fixed(box.maxY), fixed(box.maxZ));
    }
    private static double fixed(double value) { return Double.isFinite(value) ? Math.round(value * 1e4) / 1e4 : value; }
    private static void appendBox(StringBuilder text, String tag, Box box) {
        text.append(tag).append(' ').append(box.minX).append(' ').append(box.minY).append(' ').append(box.minZ)
            .append(' ').append(box.maxX).append(' ').append(box.maxY).append(' ').append(box.maxZ).append('\n');
    }

    static final List<String> COMBAT_EVENTS = List.of("perfect_dodge", "perfect_parry", "parry", "guard", "guard_strong", "hit", "hit_strong");

    /** A combat event for Stellar Blade: the defense at an impact and the attacker's Unreal yaw in degrees (NaN: none). */
    public static String encodeCombat(String session, long seq, long time, String event, double yaw) {
        require(matches(SESSION, session), "Invalid session");
        require(seq > 0 && seq <= MAX_TIME && time >= 0 && time <= MAX_TIME, "Invalid sequence or timestamp");
        require(COMBAT_EVENTS.contains(event), "Invalid combat event");
        require(Double.isNaN(yaw) || yaw >= -180 && yaw <= 180, "Invalid yaw");
        return "SBMC_COMBAT 2\nSESSION " + session + "\nSEQ " + seq + "\nTIME " + time + "\nEVENT " + event + " "
                + (Double.isNaN(yaw) ? "none" : String.format(java.util.Locale.ROOT, "%.1f", yaw)) + "\nEND " + seq + "\n";
    }

    /**
     * Minecraft's lock-on target for Stellar Blade: its centre relative to the bridge origin in blocks (Minecraft X, Y, Z),
     * or null once released.
     */
    public static String encodeLock(String session, long seq, long time, double[] target) {
        require(matches(SESSION, session), "Invalid session");
        require(seq > 0 && seq <= MAX_TIME && time >= 0 && time <= MAX_TIME, "Invalid sequence or timestamp");
        require(target == null || target.length == 3 && bounded(target[0], 4096) && bounded(target[1], 4096) && bounded(target[2], 4096),
                "Invalid target");
        return "SBMC_LOCK 1\nSESSION " + session + "\nSEQ " + seq + "\nTIME " + time + "\nTARGET "
                + (target == null ? "none" : String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f", target[0], target[1], target[2]))
                + "\nEND " + seq + "\n";
    }

    static final int MOBS = 8;

    /**
     * Minecraft's living entities around EVE for Stellar Blade's walls: at most MOBS boxes relative to the bridge origin in
     * blocks (Minecraft axes, three decimals, within ±MAX_REACH), nearest first.
     */
    public static String encodeMobs(String session, long seq, long time, List<Box> mobs) {
        require(matches(SESSION, session), "Invalid session");
        require(seq > 0 && seq <= MAX_TIME && time >= 0 && time <= MAX_TIME, "Invalid sequence or timestamp");
        require(mobs != null && mobs.size() <= MOBS, "Invalid mob count");
        StringBuilder text = new StringBuilder("SBMC_MOBS 2\nSESSION ").append(session).append("\nSEQ ").append(seq)
            .append("\nTIME ").append(time).append("\nCOUNT ").append(mobs.size()).append('\n');
        for (Box box : mobs) {
            checkBox(box);
            // Lua refuses an empty box; three decimals keep a box this thick.
            require(box.maxX - box.minX >= 0.01 && box.maxY - box.minY >= 0.01 && box.maxZ - box.minZ >= 0.01, "Box too thin");
            text.append(String.format(java.util.Locale.ROOT, "MOB %.3f %.3f %.3f %.3f %.3f %.3f\n",
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
        return text.append("END ").append(seq).append('\n').toString();
    }

    static final List<String> REQUEST_ACTIONS = List.of("import", "enter", "return");
    static final List<String> REQUEST_RESULTS = List.of("ok", "failed", "refused");
    public record RequestAck(String session, long seq, String action, String result, String message) {}

    /** A session request for Stellar Blade: its F5 (import), F8 (enter) or F11 (return) action, run once per SEQ. */
    public static String encodeRequest(String session, long seq, long time, String action) {
        require(matches(SESSION, session), "Invalid session");
        require(seq > 0 && seq <= MAX_TIME && time >= 0 && time <= MAX_TIME, "Invalid sequence or timestamp");
        require(REQUEST_ACTIONS.contains(action), "Invalid request action");
        return "SBMC_REQUEST 1\nSESSION " + session + "\nSEQ " + seq + "\nTIME " + time + "\nACTION " + action + "\nEND " + seq + "\n";
    }

    /** Stellar Blade's answer to a session request: ok, failed or refused, with its one-line message. */
    public static RequestAck parseRequestAck(String text) {
        require(text != null && text.length() <= 1024 && text.endsWith("\n"), "Invalid packet size or ending");
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            require(value == '\n' || value >= 32 && value <= 126, "Packet must contain printable ASCII and LF");
        }
        String[] lines = text.split("\n", -1);
        require(lines.length == 8 && lines[0].equals("SBMC_REQUEST_ACK 1") && lines[7].isEmpty(), "Invalid request answer framing");
        String session = fields(lines[1], "SESSION", 2)[1];
        require(matches(SESSION, session), "Invalid session");
        long seq = unsigned(fields(lines[2], "SEQ", 2)[1], 1, MAX_TIME);
        String action = fields(lines[3], "ACTION", 2)[1], result = fields(lines[4], "RESULT", 2)[1];
        require(REQUEST_ACTIONS.contains(action) && REQUEST_RESULTS.contains(result), "Invalid action or result");
        require(lines[5].startsWith("MESSAGE ") && lines[5].length() > 8 && lines[5].length() <= 408, "Invalid message");
        require(unsigned(fields(lines[6], "END", 2)[1], 1, MAX_TIME) == seq, "Footer sequence mismatch");
        return new RequestAck(session, seq, action, result, lines[5].substring(8));
    }

    static final List<String> WARDROBE_OPS = List.of("list", "equip", "unequip");
    static final List<String> WARDROBE_RESULTS = List.of("listed", "equipped", "unequipped", "unchanged");
    static final List<String> WARDROBE_REFUSALS = List.of("not-playing", "not-ready", "tachy", "fusion", "combat", "slot", "not-owned");
    /** A structured exception the adapter contained: the step it ran, the exception code, then for a start fault the instruction's RVA and the data address. */
    static final Pattern WARDROBE_FAULT = Pattern.compile("fault-(owner|tachy|fusion|combat|slot|item|equip|unequip|list|done)-[0-9a-f]{8}(-[0-9a-f]{8}-[0-9a-f]{8})?");
    static final int MAX_WARDROBE_ITEMS = 1024;
    /** ESBItemCategory of the wardrobe: Gear, ExoSpine, NanoSuit, Hair, FaceAccessory, EarRing, Drone. */
    static final List<Integer> WARDROBE_CATEGORIES = List.of(2, 3, 4, 21, 22, 23, 28);
    private static final Pattern GUID = Pattern.compile("[0-9a-f]{32}");
    /** One owned item: ItemTable ID, ESBItemCategory, SavedGuid (32 hex), StatLevel, MatVarIndex and the slot it is worn in, or 0. */
    public record WardrobeItem(long id, int category, String guid, int level, int variant, int slot) {}
    /** The adapter's answer: RESULT's first word, the refusal reason or null, and the owned list (empty for an early refusal). */
    public record WardrobeAnswer(String session, long seq, long time, String result, String reason, List<WardrobeItem> items) {}

    /**
     * A wardrobe request for the native adapter: list (slot 0, no item), equip (slot 1-17 and a SavedGuid, not all zero) or
     * unequip (slot 1-17, no item); the adapter takes each SEQ once.
     */
    public static String encodeWardrobeRequest(String session, long seq, long time, String op, int slot, String item) {
        require(matches(SESSION, session), "Invalid session");
        require(seq > 0 && seq <= 999999999999999L && time >= 0 && time <= MAX_TIME, "Invalid sequence or timestamp");
        boolean guid = matches(GUID, item) && !item.equals("0".repeat(32));
        require(op.equals("list") ? slot == 0 && item == null : slot >= 1 && slot <= 17 && (op.equals("equip") ? guid
                : op.equals("unequip") && item == null), "Invalid wardrobe operation");
        return "SBMC_WARDROBE_REQUEST 1\nSESSION " + session + "\nSEQ " + seq + "\nTIME " + time + "\nOP " + op + "\nSLOT " + slot
                + "\nITEM " + (item == null ? "-" : item) + "\nEND " + seq + "\n";
    }

    /** The adapter's eve-wardrobe.txt (SBMC_WARDROBE 1); another version, a bad footer or a COUNT mismatch is refused. */
    public static WardrobeAnswer parseWardrobe(String text) {
        require(text != null && text.length() <= 65536 && text.endsWith("\n"), "Invalid packet size or ending");
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            require(value == '\n' || value >= 32 && value <= 126, "Packet must contain printable ASCII and LF");
        }
        String[] lines = text.split("\n", -1);
        require(lines.length >= 8 && lines[0].equals("SBMC_WARDROBE 1") && lines[lines.length - 1].isEmpty(), "Invalid wardrobe framing");
        String session = fields(lines[1], "SESSION", 2)[1];
        require(matches(SESSION, session), "Invalid session");
        long seq = unsigned(fields(lines[2], "SEQ", 2)[1], 1, 999999999999999L), time = unsigned(fields(lines[3], "TIME", 2)[1], 0, MAX_TIME);
        String[] result = lines[4].split(" ", -1);
        require(result[0].equals("RESULT") && (result.length == 2 && WARDROBE_RESULTS.contains(result[1])
                || result.length == 3 && result[1].equals("refused") && (WARDROBE_REFUSALS.contains(result[2]) || matches(WARDROBE_FAULT, result[2]))),
                "Invalid wardrobe result");
        int count = (int) unsigned(fields(lines[5], "COUNT", 2)[1], 0, MAX_WARDROBE_ITEMS);
        require(lines.length == count + 8, "Wardrobe COUNT mismatch");
        List<WardrobeItem> items = new java.util.ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            String[] item = fields(lines[6 + k], "ITEM", 7);
            int category = (int) unsigned(item[2], 0, 28);
            require(WARDROBE_CATEGORIES.contains(category) && matches(GUID, item[3]), "Invalid wardrobe item");
            items.add(new WardrobeItem(unsigned(item[1], 0, 0xFFFFFFFFL), category, item[3],
                    (int) unsigned(item[4], 0, 9999), (int) unsigned(item[5], 0, 99), (int) unsigned(item[6], 0, 17)));
        }
        require(unsigned(fields(lines[6 + count], "END", 2)[1], 1, 999999999999999L) == seq, "Footer sequence mismatch");
        return new WardrobeAnswer(session, seq, time, result.length == 2 ? result[1] : "refused", result.length == 3 ? result[2] : null, List.copyOf(items));
    }

    public static String encodeTerrain(Terrain terrain) {
        require(terrain != null && matches(SESSION,terrain.session), "Invalid session");
        require(terrain.seq > 0 && terrain.time >= 0 && terrain.time <= MAX_TIME, "Invalid sequence or timestamp");
        require(matches(DIMENSION,terrain.dimension), "Invalid dimension");
        require(bounded(terrain.originX,30000000) && bounded(terrain.originY,4096)
            && bounded(terrain.originZ,30000000), "Invalid origin");
        checkBox(terrain.bounds);
        require(terrain.boxes != null && terrain.boxes.size() <= MAX_BOXES, "Invalid box count");
        // Fixed precision (a ten-thousandth of a block): randomly offset shapes (bamboo, dripstone) keep the whole box
        // budget inside one packet. A box that rounding collapses is refused.
        List<Box> boxes = terrain.boxes.stream().map(BridgeProtocol::fixed).toList();
        require(new java.util.HashSet<>(boxes).size() == boxes.size(), "Duplicate box");
        StringBuilder text = new StringBuilder("SBMC 3 TERRAIN\nSESSION ").append(terrain.session)
            .append("\nSEQ ").append(terrain.seq).append("\nTIME ").append(terrain.time)
            .append("\nWORLD ").append(terrain.dimension).append(' ').append(terrain.originX)
            .append(' ').append(terrain.originY).append(' ').append(terrain.originZ).append('\n');
        Box b = terrain.bounds;
        appendBox(text,"BOUNDS",b);
        text.append("COUNT ").append(terrain.boxes.size()).append('\n');
        for (Box box : boxes) {
            checkBox(box);
            require(box.minX >= b.minX && box.minY >= b.minY && box.minZ >= b.minZ
                && box.maxX <= b.maxX && box.maxY <= b.maxY && box.maxZ <= b.maxZ, "Box outside region");
            // Relative to the window's minimum corner: lines stay as short far from the origin as near it. The reader
            // adds the corner back and rounds to the same ten-thousandth.
            appendBox(text,"BOX",fixed(new Box(box.minX - b.minX, box.minY - b.minY, box.minZ - b.minZ,
                box.maxX - b.minX, box.maxY - b.minY, box.maxZ - b.minZ)));
        }
        text.append("END ").append(terrain.seq).append('\n');
        require(text.length() <= 65536, "Packet too large");
        return text.toString();
    }

    public static Eve parseEve(String text) {
        require(text != null && text.length() <= 65536 && text.endsWith("\n"), "Invalid packet size or ending");
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            require(value == '\n' || value >= 32 && value <= 126, "Packet must contain printable ASCII and LF");
        }
        String[] lines = text.split("\n",-1);
        require(lines.length == 8 && lines[0].equals("SBMC 2 EVE") && lines[7].isEmpty(), "Invalid EVE framing");
        String session = fields(lines[1],"SESSION",2)[1];
        require(matches(SESSION,session), "Invalid session");
        int seq = (int) unsigned(fields(lines[2],"SEQ",2)[1],1,Integer.MAX_VALUE);
        long time = unsigned(fields(lines[3],"TIME",2)[1],0,MAX_TIME);
        String[] state = fields(lines[4],"STATE",3);
        require(state[1].equals("ready") || state[1].equals("entered") || state[1].equals("idle"), "Invalid state");
        int ack = (int) unsigned(state[2],1,Integer.MAX_VALUE);
        String[] position = fields(lines[5],"POS",4);
        double x = decimal(position[1],MAX_REACH), y = decimal(position[2],MAX_REACH), z = decimal(position[3],MAX_REACH);
        require(unsigned(fields(lines[6],"END",2)[1],1,Integer.MAX_VALUE) == seq, "Footer sequence mismatch");
        return new Eve(session,seq,time,state[1],ack,x,y,z);
    }

    public static boolean fresh(long time, long now) {
        return time >= 0 && time <= MAX_TIME && now >= 0 && now <= MAX_TIME && now - time >= -2 && now - time <= 5;
    }
}
