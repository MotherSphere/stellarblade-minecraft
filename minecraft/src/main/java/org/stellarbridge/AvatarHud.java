package org.stellarbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import org.slf4j.LoggerFactory;

/**
 * Stellar Blade's player HUD drawn by Minecraft while EVE replaces the player, in Stellar Blade's layout: bottom left its
 * Tachy ring, heal and util item column and its BU, BE, HP and SH rows; bottom right its dash slot and, while aiming, the
 * drone's ammo. Minecraft's health feeds the dotted HP bar and its absorption a gold block after Stellar Blade's shield
 * blocks; Beta and Burst cubes (200 points a cube, four quarters each, at most 16 and 20), Tachy and the shield come
 * from the streamed gauges and actor stats, the items from Lua's HUD file. Drawn in panel pixels (one Stellar Blade dot
 * tile of 15 4K units to 3), each a whole number j of screen pixels: the GUI scale where the panel fits left of the
 * offhand slot, else fewer, never below half. Plain fills, with Stellar Blade's textures from the private avatar pack
 * (hud.NAME.png) when present, else vanilla items. Render thread only.
 */
public final class AvatarHud {
    static final int CUBE_POINTS = 200, BETA_CUBES = 16, BURST_CUBES = 20;
    // Stellar Blade's colors (its widgets' tints): health white, shield ADD1C4, Beta CCE9F4, Burst DD8874; unlit grey.
    static final int LIT = 0xFFFFFFFF, UNLIT = 0xFF6E6E6E, SHIELD = 0xFFADD1C4, BETA = 0xFFCCE9F4, BURST = 0xFFDD8874;
    // Tk_Idle_Ring2's core and glow, the tint of ProgressBar_CoolTime, cooltime_frame's red; gold for absorption.
    static final int ABSORB = 0xFFF2C94C, TACHY = 0xFFB08670, GLOW = 0x60FF7800, RING = 0xD0555555, COOL = 0xFFDD8B37,
            DASH_ARC = 0xFFC02727, COUNT = 0xFFD7D7D7;
    // The panel's margins (GUI pixels), its label and gauge columns and column top (panel pixels), vanilla's chat bottom.
    static final int LEFT = 4, BOTTOM = 2, LABEL_X = 42, GAUGE_X = 60, COLUMN_TOP = 66, CHAT = 40;
    static final int BU = -32, BE = -24, HP = -16, SH = -5; // Row tops, panel pixels above the panel's bottom edge.
    // Stellar Blade's actor stats (ESBActorStatType): Shield, MaxShield, ShieldBlock, Tachy, MaxTachy, UnlockTachy.
    static final int SHIELD_STAT = 7, MAX_SHIELD_STAT = 8, SHIELD_BLOCK_STAT = 11, TACHY_STAT = 22, MAX_TACHY_STAT = 23,
            UNLOCK_TACHY_STAT = 117;
    // The labels as 5 x 5 glyphs (bit 24 top-left, row-major), standing in for Stellar Blade's font.
    private static final String LETTERS = "BUEHPS";
    private static final int[] GLYPHS = {0x1E8FA3E, 0x118C62E, 0x1F87A1F, 0x118FE31, 0x1E8FA10, 0x0F8383E};
    private static final Map<String, Identifier> textures = new HashMap<>(); // By pack name; null when the pack has none.
    private static final Map<String, ItemStack> stacks = new HashMap<>();
    private static int lift, width, height, scale, panel, columns; // The last render's.

    /** Tachy and its maximum from the actor stats (AvatarStream.actorStats()), or null while locked or unreadable. */
    static float[] tachy(float[] actor) {
        if (actor == null || !(actor[UNLOCK_TACHY_STAT] > 0)) return null;
        float cur = actor[TACHY_STAT], max = actor[MAX_TACHY_STAT];
        return Float.isFinite(cur) && Float.isFinite(max) && 0 <= cur && cur <= max && max <= 100000 ? new float[] {cur, max} : null;
    }

    /**
     * Stellar Blade's shield row from the actor stats: blocks shown (at most 5), lit blocks and the last lit one's share;
     * perBlock = MaxShield / ShieldBlock (integers), lit = (Shield - 1) / perBlock + 1. Null when unread or implausible.
     */
    static float[] shield(float[] actor) {
        if (actor == null) return null;
        float cur = actor[SHIELD_STAT], max = actor[MAX_SHIELD_STAT], block = actor[SHIELD_BLOCK_STAT];
        if (!Float.isFinite(cur) || !Float.isFinite(max) || !Float.isFinite(block) || block < 1 || cur < 0 || cur > max
                || max > 100000 || (int) max < (int) block) return null;
        int blocks = Math.min((int) block, 5), per = (int) max / (int) block;
        int lit = cur <= 0 ? 0 : Math.min((int) ((cur - 1) / per) + 1, blocks);
        return new float[] {blocks, lit, lit == 0 ? 0 : Math.min(1, (cur - (lit - 1) * per) / per)};
    }

    /** Lit columns of a bar for a value out of a maximum: any value above zero lights at least one. */
    static int lit(float value, float max, int columns) {
        return value <= 0 || max <= 0 ? 0 : Math.min(columns, (int) Math.ceil(columns * value / max));
    }

    /** A gauge's cubes, its full cubes and the quarters lit in the next one. */
    static int[] cubes(float value, float max, int cap) {
        int cubes = Math.min((int) (max / CUBE_POINTS), cap), full = Math.min((int) (value / CUBE_POINTS), cubes);
        int quarters = full < cubes ? (int) (value % CUBE_POINTS / (CUBE_POINTS / 4f)) : 0;
        return new int[] {cubes, full, quarters};
    }

    /**
     * Screen pixels per panel pixel and HP columns for a scaled width and GUI scale: the panel (gauges from x = 60, its
     * widest row that far past them) fits up to 2 GUI pixels before the offhand slot at width / 2 - 120, at most the GUI
     * scale, never below half of it; the HP bar is shortened to the room left, at least 10 columns. ponytail: at that
     * floor, late-game rows on the auto scales and 4:3 windows reach the offhand slot while one is held; one GUI scale
     * lower fixes it.
     */
    static int[] layout(int width, int guiScale, int wanted, int widest) {
        int corner = (width / 2 - 122 - LEFT) * guiScale;
        int j = Math.clamp(corner / (GAUGE_X + widest), (guiScale + 1) / 2, guiScale);
        return new int[] {j, Math.min(wanted, Math.max(10, (corner / j - GAUGE_X + 1) / 3))};
    }

    /**
     * The widest row past x = 60 (panel pixels) for the cube and shield block counts, at least 23 HP columns. The shield
     * row keeps room for the gold absorption block, which comes and goes in play; from the maxima, which change only with
     * upgrades, the scale does not flicker.
     */
    static int widest(int betaCubes, int burstCubes, int blocks) {
        return Math.max(Math.max(8 * Math.max(betaCubes, burstCubes) - 3, 36 * blocks + 33), 68);
    }

    /**
     * Stellar Blade's HP bar length in columns, 346 + 90 units a Body level (15 a column). ponytail: the level is
     * estimated from the max HP (2000, then 500 a level), so gear's extra HP reads high; stream it once confirmed in game.
     */
    static int hpColumns(float maxHp) {
        return (346 + 90 * Math.clamp(Math.round((maxHp - 2000) / 500), 0, 9)) / 15;
    }

    /** Whether a pixel at (dx, dy) from a ring's centre lies in the share swept clockwise from 12 o'clock. */
    static boolean arc(float dx, float dy, float share) {
        return (Math.atan2(dx, -dy) + 2 * Math.PI) % (2 * Math.PI) < share * 2 * Math.PI;
    }

    /** The chat's bottom edge above the screen's bottom, in GUI pixels: over the panel's column, never below vanilla's. */
    static int chatBottom(int j, int guiScale) {
        return Math.max(CHAT, BOTTOM + (COLUMN_TOP * j + guiScale - 1) / guiScale + 2);
    }

    /** What the chat rises by (ChatHudMixin): the last render's lift while this HUD shows this frame, else 0. */
    public static int chatLift() {
        var client = MinecraftClient.getInstance();
        return client.player != null && !client.options.hudHidden && client.interactionManager != null
                && client.interactionManager.hasStatusBars() && AvatarView.replaces(client.player) ? lift : 0;
    }

    static String status() {
        return scale == 0 ? " HUD: not drawn yet." : String.format(" HUD: %dx%d @%d, panel %d/%d, HP %d columns.", width,
                height, scale, panel, scale, columns);
    }

    /**
     * Vanilla's renderHealthBar arguments, by meaning: current is its 8th (ceil of the health, which yarn names
     * lastHealth), shown its 9th (the display value that lags a second behind changes, yarn's health), drawn as the
     * fading trail while blinking. Gauges are AvatarStream.gauges() or null, actor AvatarStream.actorStats() or null.
     */
    static void render(DrawContext context, float maxHealth, int current, int shown, int absorption, boolean blinking,
                       float[] gauges, float[] actor) {
        var client = MinecraftClient.getInstance();
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight(), g = (int) client.getWindow().getScaleFactor();
        int[] beta = gauges == null ? null : cubes(gauges[0], gauges[1], BETA_CUBES);
        int[] burst = gauges == null || !(gauges[4] > 0) ? null : cubes(gauges[2], gauges[3], BURST_CUBES);
        float[] shield = shield(actor), tachy = tachy(actor);
        int blocks = shield == null ? 0 : (int) shield[0];
        int[] fit = layout(w, g, hpColumns(AvatarStats.current().maxHp()), widest(beta == null ? 0 : beta[0], burst == null ? 0 : burst[0], blocks));
        width = w; height = h; scale = g; panel = fit[0]; columns = fit[1];
        lift = chatBottom(panel, g) - CHAT;
        float f = (float) panel / g;
        AvatarItems.Hud hud = AvatarHudFile.latest();
        AvatarItems.Slot heal = hud == null ? null : hud.heal(), util = hud == null ? null : hud.util();
        boolean cup = heal != null && heal.kind().equals("tumbler");
        var matrices = context.getMatrices();

        matrices.push(); // Bottom left.
        matrices.translate(LEFT, h - BOTTOM, 0);
        matrices.scale(f, f, 1);
        Identifier base = tachy == null ? null : texture("Tk_Idle_Base2"), potion = cup ? texture("potion") : null;
        if (base != null) blit(context, base, 5, -66, 22);
        if (potion != null) {
            context.setShaderColor(1, 1, 1, 0.85f);
            blit(context, potion, 8, -40, 16);
            context.setShaderColor(1, 1, 1, 1);
        }
        context.draw(() -> { // One GUI draw for every fill: fill() flushes on its own outside a draw callback.
            if (tachy != null) ring(context, 16, -55, base == null ? 0x60777777 : 0, 10.5f, tachy[0] / tachy[1], TACHY, base == null ? RING : 0, GLOW);
            if (cup) {
                if (potion == null) for (int[] r : new int[][] {{11, -39, 12, -25}, {22, -39, 23, -25}, {11, -26, 23, -25}, {9, -39, 11, -38}})
                    context.fill(r[0], r[1], r[2], r[3], 0xCCFFFFFF);
                // The liquid shows count / max, the used part at Stellar Blade's used-bar alpha.
                int level = heal.max() > 0 ? Math.round(11f * Math.min(heal.count(), heal.max()) / heal.max()) : heal.count() > 0 ? 11 : 0;
                context.fill(13, -37, 21, -26 - level, 0x26FFFFFF);
                context.fill(13, -26 - level, 21, -26, 0xCCFFFFFF);
            }
            if (util != null && util.cooldown() > 0) bar(context, 8, 1 - util.cooldown());
            if (burst != null) { label(context, "BU", BU); row(context, BU, burst, BURST); }
            if (beta != null) { label(context, "BE", BE); row(context, BE, beta, BETA); }
            label(context, "HP", HP + 2);
            int now = lit(current, maxHealth, columns), trail = blinking ? lit(shown, maxHealth, columns) : now;
            for (int c = 0; c < columns; c++) {
                int color = c < now ? LIT : c < trail ? 0x80FFFFFF : UNLIT;
                for (int r = 0; r < 3; r++) dot(context, GAUGE_X + 3 * c, HP + 3 * r, color);
            }
            label(context, "SH", SH);
            for (int b = 0; b < blocks; b++)
                dots(context, b, b < shield[1] - 1 ? 11 : b == shield[1] - 1 ? lit(shield[2], 1, 11) : 0, SHIELD, UNLIT);
            if (absorption > 0) dots(context, blocks, lit(absorption, maxHealth, 11), ABSORB, 0);
        });
        if (base != null) {
            Identifier icon = texture("Tk_Idle_Icon");
            if (icon != null) blit(context, icon, 5, -66, 22);
        }
        if (heal != null) {
            if (!cup) icon(context, heal.kind(), 8, -40);
            text(context, Integer.toString(heal.count()), 25, -32, COUNT); // Stellar Blade shows the count only.
            text(context, ControlBridge.keyHint(true), 1, -35, 0xCCD7D7D7);
        }
        if (util != null) {
            icon(context, util.kind(), 8, -19);
            if (!util.kind().equals("other") || util.count() > 1) text(context, Integer.toString(util.count()), 25, -11, COUNT);
            text(context, ControlBridge.keyHint(false), 1, -14, 0xCCD7D7D7);
        }
        matrices.translate(0, 0, 200); // Over the items, where vanilla draws its own cooldown.
        if (heal != null && !heal.usable()) context.fill(8, -40, 24, -24, 0x80000000);
        if (util != null && !util.usable()) context.fill(8, -19, 24, -3, 0x80000000);
        matrices.pop();

        matrices.push(); // Bottom right, Stellar Blade's side for its dash slot.
        matrices.translate(w - LEFT, h - BOTTOM, 0);
        matrices.scale(f, f, 1);
        boolean dash = hud != null && !Float.isNaN(hud.dash()), ammo = hud != null && hud.ammoAlias() != null && ControlBridge.aiming();
        Identifier frame = dash ? texture("skill_hud_dashframe") : null;
        if (frame != null) blit(context, frame, -20, -20, 20);
        context.draw(() -> {
            if (dash && (frame == null || hud.dash() < 1))
                ring(context, -10, -10, frame == null ? 0xC0353535 : 0, 10, hud.dash(), hud.dash() < 1 ? DASH_ARC : RING, RING, 0);
            if (ammo && hud.ammoReady() < 1) bar(context, -62, hud.ammoReady());
        });
        if (dash) {
            Identifier icon = texture("Skill_Icon_Dash");
            if (icon != null) blit(context, icon, -15, -15, 10);
            else context.drawItem(stacks.computeIfAbsent("dash", AvatarItems::icon), -18, -18);
        }
        if (ammo) { // Stellar Blade shows its bullet slots while aiming.
            icon(context, hud.ammoAlias(), -62, -19);
            text(context, Integer.toString(hud.ammo()), -45, -11, COUNT);
        }
        matrices.pop();
    }

    /** A ring around (cx, cy): a disc inside radius 8, a band to radius band lit clockwise by share, a 1-px glow past the lit band; 0 skips. */
    private static void ring(DrawContext context, int cx, int cy, int disc, float band, float share, int on, int off, int glow) {
        int reach = (int) band + 2;
        for (int y = cy - reach; y < cy + reach; y++)
            for (int x = cx - reach; x < cx + reach; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy, d = (float) Math.hypot(dx, dy);
                boolean lit = arc(dx, dy, share);
                int color = d < 8 ? disc : d < band ? lit ? on : off : d < band + 1 && lit ? glow : 0;
                if (color != 0) context.fill(x, y, x + 1, y + 1, color);
            }
    }

    /** A cooldown bar under the 16-pp cell at x: a dark base, then the ready share in Stellar Blade's cool-time orange. */
    private static void bar(DrawContext context, int x, float ready) {
        context.fill(x, -2, x + 16, 0, 0x80000000);
        context.fill(x, -2, x + Math.round(16 * ready), 0, COOL);
    }

    /** A label's glyphs, 7 pp apart, white over Stellar Blade's outline colour one pixel down and right. */
    private static void label(DrawContext context, String word, int y) {
        for (int shadow = 1; shadow >= 0; shadow--)
            for (int k = 0; k < word.length(); k++) {
                int mask = GLYPHS[LETTERS.indexOf(word.charAt(k))];
                for (int bit = 0; bit < 25; bit++) {
                    if ((mask >> (24 - bit) & 1) == 0) continue;
                    int x = LABEL_X + 7 * k + bit % 5 + shadow, top = y + bit / 5 + shadow;
                    context.fill(x, top, x + 1, top + 1, shadow == 1 ? 0xCC3C3C3C : 0xFFFFFFFF);
                }
            }
    }

    private static void row(DrawContext context, int y, int[] cubes, int tint) {
        // Quarters light top-left, bottom-left, top-right, bottom-right, as the game's cube textures 01 to 04.
        int[][] quarters = {{0, 0}, {0, 3}, {3, 0}, {3, 3}};
        for (int k = 0; k < cubes[0]; k++)
            for (int q = 0; q < 4; q++)
                dot(context, GAUGE_X + 8 * k + quarters[q][0], y + quarters[q][1], k < cubes[1] || k == cubes[1] && q < cubes[2] ? tint : UNLIT);
    }

    /** Shield block b: 11 columns of 2 dots, the first lit ones in color, the others unlit (0 skips them). */
    private static void dots(DrawContext context, int b, int lit, int color, int unlit) {
        for (int c = 0; c < 11; c++)
            for (int r = 0; r < 2; r++) if (c < lit || unlit != 0) dot(context, GAUGE_X + 36 * b + 3 * c, SH + 3 * r, c < lit ? color : unlit);
    }

    private static void dot(DrawContext context, int x, int y, int color) { context.fill(x, y, x + 2, y + 2, color); }

    private static void text(DrawContext context, String text, int x, int y, int color) {
        if (text != null) context.drawText(MinecraftClient.getInstance().textRenderer, text, x, y, color, true);
    }

    /** Stellar Blade's icon for an item kind or bullet alias from the pack, else a vanilla item standing in; 16 pp at (x, y). */
    private static void icon(DrawContext context, String kind, int x, int y) {
        Identifier id = texture(switch (kind) {
            case "tumbler" -> "Icon_PotionR";
            case "potion" -> "Icon_Potion";
            case "lingering" -> "Icon_PotionSmall";
            case "field" -> "HealGrenade";
            case "shock" -> "Grenade";
            case "pulse" -> "PluseGrenade";
            case "mine" -> "TrapGrenade";
            case "sonic" -> "FlashGrenade";
            case "pump" -> "WbPump";
            case "other" -> "Icon_Camera"; // Stellar Blade's Camera tool, the util item of the user's screenshot.
            case "Bullet_Explosive" -> "bullet_icon_02";
            case "Bullet_Missile" -> "bullet_icon_03";
            case "Bullet_Railgun" -> "bullet_icon_04";
            case "Bullet_Scatter" -> "bullet_icon_05";
            default -> "bullet_icon_01"; // Bullet_Normal, Bullet_Nikke and unknown bullets.
        });
        if (id != null) blit(context, id, x, y, 16);
        else context.drawItem(stacks.computeIfAbsent(kind, AvatarItems::icon), x, y);
    }

    static void blit(DrawContext context, Identifier id, int x, int y, int size) {
        RenderSystem.enableBlend(); // GUI fills and text turn blending off after them.
        RenderSystem.defaultBlendFunc();
        context.drawTexture(id, x, y, size, size, 0, 0, size, size, size, size);
    }

    /** The avatar pack's hud.NAME.png, loaded once with linear filtering, or null when absent or unreadable. */
    static Identifier texture(String name) {
        if (textures.containsKey(name)) return textures.get(name);
        Identifier id = null;
        Path file = AvatarView.packDirectory().resolve("hud." + name + ".png");
        if (Files.isRegularFile(file)) {
            try (var input = Files.newInputStream(file)) {
                var texture = new NativeImageBackedTexture(NativeImage.read(input));
                texture.setFilter(true, false);
                Identifier key = Identifier.of("stellar_bridge", "hud/" + name.toLowerCase(Locale.ROOT));
                MinecraftClient.getInstance().getTextureManager().registerTexture(key, texture);
                id = key;
            } catch (IOException | RuntimeException unreadable) {
                LoggerFactory.getLogger("stellar_bridge").warn("EVE HUD texture {} unreadable: {}", file, unreadable.toString());
            }
        }
        textures.put(name, id);
        return id;
    }
}
