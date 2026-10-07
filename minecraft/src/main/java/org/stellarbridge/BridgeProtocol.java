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
        require(box != null && bounded(box.minX,32) && bounded(box.minY,32) && bounded(box.minZ,32)
            && bounded(box.maxX,32) && bounded(box.maxY,32) && bounded(box.maxZ,32)
            && box.minX < box.maxX && box.minY < box.maxY && box.minZ < box.maxZ, "Invalid box");
    }
    private static void appendBox(StringBuilder text, String tag, Box box) {
        text.append(tag).append(' ').append(box.minX).append(' ').append(box.minY).append(' ').append(box.minZ)
            .append(' ').append(box.maxX).append(' ').append(box.maxY).append(' ').append(box.maxZ).append('\n');
    }

    static final List<String> COMBAT_EVENTS = List.of("perfect_dodge", "perfect_parry", "parry", "guard", "guard_strong", "hit");

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
     * blocks (Minecraft axes, three decimals, within ±32), nearest first.
     */
    public static String encodeMobs(String session, long seq, long time, List<Box> mobs) {
        require(matches(SESSION, session), "Invalid session");
        require(seq > 0 && seq <= MAX_TIME && time >= 0 && time <= MAX_TIME, "Invalid sequence or timestamp");
        require(mobs != null && mobs.size() <= MOBS, "Invalid mob count");
        StringBuilder text = new StringBuilder("SBMC_MOBS 1\nSESSION ").append(session).append("\nSEQ ").append(seq)
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

    public static String encodeTerrain(Terrain terrain) {
        require(terrain != null && matches(SESSION,terrain.session), "Invalid session");
        require(terrain.seq > 0 && terrain.time >= 0 && terrain.time <= MAX_TIME, "Invalid sequence or timestamp");
        require(matches(DIMENSION,terrain.dimension), "Invalid dimension");
        require(bounded(terrain.originX,30000000) && bounded(terrain.originY,4096)
            && bounded(terrain.originZ,30000000), "Invalid origin");
        checkBox(terrain.bounds);
        require(terrain.boxes != null && terrain.boxes.size() <= 256, "Invalid box count");
        StringBuilder text = new StringBuilder("SBMC 1 TERRAIN\nSESSION ").append(terrain.session)
            .append("\nSEQ ").append(terrain.seq).append("\nTIME ").append(terrain.time)
            .append("\nWORLD ").append(terrain.dimension).append(' ').append(terrain.originX)
            .append(' ').append(terrain.originY).append(' ').append(terrain.originZ).append('\n');
        appendBox(text,"BOUNDS",terrain.bounds);
        text.append("COUNT ").append(terrain.boxes.size()).append('\n');
        for (Box box : terrain.boxes) {
            checkBox(box);
            require(box.minX >= terrain.bounds.minX && box.minY >= terrain.bounds.minY && box.minZ >= terrain.bounds.minZ
                && box.maxX <= terrain.bounds.maxX && box.maxY <= terrain.bounds.maxY && box.maxZ <= terrain.bounds.maxZ,
                "Box outside region");
            appendBox(text,"BOX",box);
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
        require(lines.length == 8 && lines[0].equals("SBMC 1 EVE") && lines[7].isEmpty(), "Invalid EVE framing");
        String session = fields(lines[1],"SESSION",2)[1];
        require(matches(SESSION,session), "Invalid session");
        int seq = (int) unsigned(fields(lines[2],"SEQ",2)[1],1,Integer.MAX_VALUE);
        long time = unsigned(fields(lines[3],"TIME",2)[1],0,MAX_TIME);
        String[] state = fields(lines[4],"STATE",3);
        require(state[1].equals("ready") || state[1].equals("entered") || state[1].equals("idle"), "Invalid state");
        int ack = (int) unsigned(state[2],1,Integer.MAX_VALUE);
        String[] position = fields(lines[5],"POS",4);
        double x = decimal(position[1],64), y = decimal(position[2],64), z = decimal(position[3],64);
        require(unsigned(fields(lines[6],"END",2)[1],1,Integer.MAX_VALUE) == seq, "Footer sequence mismatch");
        return new Eve(session,seq,time,state[1],ack,x,y,z);
    }

    public static boolean fresh(long time, long now) {
        return time >= 0 && time <= MAX_TIME && now >= 0 && now <= MAX_TIME && now - time >= -2 && now - time <= 5;
    }
}
