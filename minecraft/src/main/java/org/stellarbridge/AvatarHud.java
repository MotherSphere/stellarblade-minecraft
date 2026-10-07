package org.stellarbridge;

import net.minecraft.client.gui.DrawContext;

/**
 * Stellar Blade's player HUD drawn by Minecraft while EVE replaces the player: in place of the hearts, its dotted
 * health bar fed by Minecraft's health (three rows of dots, one Stellar Blade dot of 15 4K units to 3 GUI pixels, so
 * the bar fills the hearts' 81 x 9), absorption as its shield row, and higher up its Beta and Burst rows of cubes
 * streamed from Stellar Blade (200 points a cube, four quarters each, at most 16 and 20), then once unlocked a dotted
 * Tachy row from the streamed actor stats. Plain fills: the
 * game's gauge textures are white or grey squares. Render thread only.
 */
final class AvatarHud {
    static final int COLUMNS = 27, CUBE_POINTS = 200, BETA_CUBES = 16, BURST_CUBES = 20;
    // Stellar Blade's colors (its widgets' tints): health white, shield ADD1C4, Beta CCE9F4, Burst DD8874; unlit grey.
    static final int LIT = 0xFFFFFFFF, UNLIT = 0xFF6E6E6E, SHIELD = 0xFFADD1C4, BETA = 0xFFCCE9F4, BURST = 0xFFDD8874;
    static final int TACHY = 0xFFC9A0FF; // A placeholder tint, until compared with Stellar Blade's in game.
    // Stellar Blade's actor stats (ESBActorStatType): Tachy, MaxTachy, UnlockTachy.
    static final int TACHY_STAT = 22, MAX_TACHY_STAT = 23, UNLOCK_TACHY_STAT = 117;

    /** Tachy and its maximum from the actor stats (AvatarStream.actorStats()), or null while locked or unreadable. */
    static float[] tachy(float[] actor) {
        if (actor == null || !(actor[UNLOCK_TACHY_STAT] > 0)) return null;
        float cur = actor[TACHY_STAT], max = actor[MAX_TACHY_STAT];
        return Float.isFinite(cur) && Float.isFinite(max) && 0 <= cur && cur <= max && max <= 100000 ? new float[] {cur, max} : null;
    }

    /** Lit columns of a bar for a value out of a maximum: any value above zero lights at least one. */
    static int lit(float value, float max) {
        return value <= 0 || max <= 0 ? 0 : Math.min(COLUMNS, (int) Math.ceil(COLUMNS * value / max));
    }

    /** A gauge's cubes, its full cubes and the quarters lit in the next one. */
    static int[] cubes(float value, float max, int cap) {
        int cubes = Math.min((int) (max / CUBE_POINTS), cap), full = Math.min((int) (value / CUBE_POINTS), cubes);
        int quarters = full < cubes ? (int) (value % CUBE_POINTS / (CUBE_POINTS / 4f)) : 0;
        return new int[] {cubes, full, quarters};
    }

    /**
     * Vanilla's renderHealthBar arguments, by meaning: current is its 8th (ceil of the health, which yarn names
     * lastHealth), shown its 9th (the display value that lags a second behind changes, yarn's health), drawn as the
     * fading trail while blinking. Gauges are AvatarStream.gauges() or null, actor AvatarStream.actorStats() or null.
     */
    static void render(DrawContext context, int x, int y, int lines, float maxHealth, int current, int shown, int absorption,
                       boolean blinking, float[] gauges, float[] actor) {
        context.draw(() -> { // One GUI draw for every dot: fill() flushes on its own outside a draw callback.
            int now = lit(current, maxHealth), trail = blinking ? lit(shown, maxHealth) : now;
            for (int c = 0; c < COLUMNS; c++) {
                int color = c < now ? LIT : c < trail ? 0x80FFFFFF : UNLIT;
                for (int r = 0; r < 3; r++) dot(context, x + 3 * c, y + 3 * r, color);
            }
            // Vanilla's armor row sits above the heart rows (renderStatusBars); absorption adds a row, where the shield
            // row goes, else the shield row sits above the armor.
            int rows = (int) Math.ceil((maxHealth + absorption) / 20f), armor = y - (rows - 1) * lines - 10;
            if (absorption > 0) {
                int shield = lit(absorption, maxHealth), top = rows > 1 ? y - 3 : armor - 3;
                for (int c = 0; c < shield; c++) dot(context, x + 3 * c, top, SHIELD);
            }
            if (gauges == null) return;
            // Above the armor row and clear of vanilla's held item name (height - 59) and action bar (height - 72).
            int beta = Math.min(armor - (absorption > 0 && rows == 1 ? 3 : 0), y - 34) - 7;
            row(context, x, beta, cubes(gauges[0], gauges[1], BETA_CUBES), BETA);
            if (gauges[4] > 0) row(context, x, beta - 7, cubes(gauges[2], gauges[3], BURST_CUBES), BURST);
            float[] tachy = tachy(actor);
            if (tachy == null) return;
            int lit = lit(tachy[0], tachy[1]), top = beta - (gauges[4] > 0 ? 14 : 7);
            for (int c = 0; c < COLUMNS; c++) dot(context, x + 3 * c, top, c < lit ? TACHY : UNLIT);
        });
    }

    private static void row(DrawContext context, int x, int y, int[] cubes, int tint) {
        // Quarters light top-left, bottom-left, top-right, bottom-right, as the game's cube textures 01 to 04.
        int[][] quarters = {{0, 0}, {0, 3}, {3, 0}, {3, 3}};
        for (int k = 0; k < cubes[0]; k++)
            for (int q = 0; q < 4; q++)
                dot(context, x + 8 * k + quarters[q][0], y + quarters[q][1], k < cubes[1] || k == cubes[1] && q < cubes[2] ? tint : UNLIT);
    }

    private static void dot(DrawContext context, int x, int y, int color) { context.fill(x, y, x + 2, y + 2, color); }
}
