package org.stellarbridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.HoveredTooltipPositioner;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/**
 * EVE's equipment, opened from Minecraft's pause menu while EVE replaces the player, laid out as Stellar Blade's
 * equipment menu: its slots grouped as it groups them, the owned items for the selected slot, and EVE's live pose.
 * Stellar Blade owns everything: the screen asks the native adapter for the owned list or one equip at a time
 * (eve-wardrobe-request.txt) and shows its answer (eve-wardrobe.txt); the new look reaches the world and the preview
 * through the pose stream. Names, descriptions and icons come from the private avatar pack (wardrobe.txt,
 * hud.NAME.png). Pausing is vanilla's: Stellar Blade keeps running, EVE gets neutral input. Render thread only.
 */
final class WardrobeScreen extends Screen {
    static final long ANSWER = 5_000_000_000L; // An answer takes at most a second and a poll; past this none will come.
    private static final int HEADING = 0xFFBEC0C3, TITLE = AvatarHud.COOL, HOVER = 0xFFB8CDE8, TEXT = 0xFFDEDBDE, FRAME = 0xFF292C40,
            SILHOUETTE = 0xFF444444, HINT = 0xFF8A8A8A;
    private static final SystemToast.Type TOAST = new SystemToast.Type(4000L);
    private static long seq; // The adapter takes each session's requests in increasing SEQ; one counter serves every session.

    private final Screen parent;
    private final String session; // The entered link's, null without one.
    private final Path pack = AvatarView.packDirectory();
    private final Map<Long, Wardrobe.Item> catalog = Wardrobe.catalog(pack);
    private final Map<String, Boolean> packed = new HashMap<>(); // Mesh name: in the pack.
    private BridgeFiles files;
    private List<BridgeProtocol.WardrobeItem> owned = List.of();
    private Wardrobe.Layout layout;
    private ItemList list;
    private final List<SlotButton> slots = new ArrayList<>();
    private int selected = 6; // Index in Wardrobe.SLOTS: the nano suit.
    private long pending, pendingNanos; // SEQ awaiting its answer, 0 for none.
    private String pendingName, failure; // failure: the lang key that disables the screen (no answer, unreadable, ...).
    private boolean unreadable; // The last answer read while waiting did not parse.
    private double yaw; // Preview turn in degrees, by dragging.
    private boolean turning;

    WardrobeScreen(Screen parent) {
        super(Text.translatable("stellar_bridge.wardrobe"));
        this.parent = parent;
        var link = StellarBridge.renderingContext();
        session = link == null ? null : link.session();
    }

    /** The pause menu's "EVE's equipment" row under "Back to Game", while EVE replaces the player; the rows below move down. */
    static void initialize() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof GameMenuScreen menu) || !menu.shouldShowMenu() || !AvatarView.replaces(client.player)) return;
            List<ClickableWidget> buttons = Screens.getButtons(screen);
            ClickableWidget back = buttons.stream().filter(b -> b.getMessage().getContent() instanceof TranslatableTextContent t
                    && t.getKey().equals("menu.returnToGame")).findFirst().orElse(null);
            if (back == null) return; // Another mod rebuilt the menu: no row rather than a misplaced one.
            for (ClickableWidget b : buttons) if (b.getY() > back.getY()) b.setY(b.getY() + 24);
            buttons.add(buttons.indexOf(back) + 1, ButtonWidget.builder(Text.translatable("stellar_bridge.wardrobe"), b -> client.setScreen(new WardrobeScreen(screen)))
                    .dimensions(back.getX(), back.getY() + 24, back.getWidth(), 20).build());
        });
    }

    @Override
    protected void init() {
        layout = Wardrobe.layout(width, height);
        slots.clear();
        for (int i = 0; i < Wardrobe.SLOTS.size(); i++) {
            int[] at = layout.slot(i);
            slots.add(addDrawableChild(new SlotButton(i, at[0], at[1], layout.row())));
        }
        list = addDrawableChild(new ItemList(layout));
        refresh();
        if (files == null && failure == null) {
            if (session == null) failure = "not_linked";
            else {
                try {
                    files = new BridgeFiles(AvatarView.bridgeDirectory());
                } catch (IOException unavailable) {
                    failure = "not_linked";
                    LoggerFactory.getLogger("stellar_bridge").warn("Wardrobe: bridge directory unavailable: {}", unavailable.toString());
                }
                if (files != null) send("list", 0, null, null);
            }
        }
    }

    /** One request at a time; the entries stay disabled until its answer or ANSWER. */
    private void send(String op, int slot, String item, String name) {
        try {
            long next = seq + 1;
            files.write("eve-wardrobe-request.txt", BridgeProtocol.encodeWardrobeRequest(session, next, System.currentTimeMillis(), op, slot, item));
            seq = next;
            pending = next;
            pendingNanos = System.nanoTime();
            pendingName = name;
            unreadable = false;
        } catch (IOException | IllegalArgumentException failed) {
            toast(Text.translatable("stellar_bridge.wardrobe.result.failed"), Text.literal(String.valueOf(failed.getMessage())));
            LoggerFactory.getLogger("stellar_bridge").warn("Wardrobe request not written: {}", failed.toString());
        }
        refresh();
    }

    @Override
    public void tick() {
        if (pending == 0) return;
        BridgeProtocol.WardrobeAnswer answer = null;
        try {
            String text = files.read("eve-wardrobe.txt");
            if (text != null) answer = BridgeProtocol.parseWardrobe(text);
        } catch (IOException | IllegalArgumentException bad) {
            unreadable = true; // Another version, or not this answer: only a valid answer to this SEQ counts.
        }
        if (answer != null && answer.session().equals(session) && answer.seq() == pending) answered(answer);
        else if (System.nanoTime() - pendingNanos > ANSWER) {
            failure = unreadable ? "unreadable" : "no_answer";
            pending = 0;
            LoggerFactory.getLogger("stellar_bridge").info("Wardrobe request {}: {}", seq, unreadable ? "unreadable answer" : "no answer");
            refresh();
        }
    }

    private void answered(BridgeProtocol.WardrobeAnswer answer) {
        pending = 0;
        // A refusal before the bucket was found lists nothing: the last list stays.
        if (!answer.items().isEmpty() || answer.reason() == null) owned = answer.items();
        LoggerFactory.getLogger("stellar_bridge").info("Wardrobe request {}: {}{}, {} items", answer.seq(), answer.result(),
                answer.reason() == null ? "" : " " + answer.reason(), answer.items().size());
        Text name = pendingName == null ? Text.empty() : Text.literal(pendingName);
        switch (answer.result()) {
            case "listed" -> { }
            case "unchanged" -> toast(Text.translatable("stellar_bridge.wardrobe.result.unchanged"), Text.translatable("stellar_bridge.wardrobe.result.unchanged.detail"));
            case "refused" -> toast(Text.translatable("stellar_bridge.wardrobe.result.refused"), answer.reason().startsWith("fault-") ? Text.translatable("stellar_bridge.wardrobe.refused.fault", (Object[]) answer.reason().substring(6).split("-")) : Text.translatable("stellar_bridge.wardrobe.refused." + answer.reason()));
            default -> toast(Text.translatable("stellar_bridge.wardrobe.result." + answer.result()), name);
        }
        refresh();
    }

    private void toast(Text title, Text description) {
        SystemToast.show(client.getToastManager(), TOAST, title, description);
    }

    /** The slot buttons' tooltips and the selected slot's entries, after any change. */
    private void refresh() {
        if (list == null) return;
        for (SlotButton button : slots) button.setTooltip(Tooltip.of(button.describe()));
        Wardrobe.Slot slot = Wardrobe.SLOTS.get(selected);
        boolean occupied = owned.stream().anyMatch(i -> i.slot() == slot.type());
        list.set(Wardrobe.entries(owned, slot).stream().map(i -> new Row(i, slot, occupied)).toList());
    }

    private Wardrobe.Item row(long id) { return catalog == null ? null : catalog.get(id); }
    private String name(BridgeProtocol.WardrobeItem item) {
        Wardrobe.Item row = row(item.id());
        return (row == null ? "#" + item.id() : row.name()) + (item.category() == 3 ? " " + Text.translatable("stellar_bridge.wardrobe.level", item.level()).getString() : "");
    }
    /** The pack icon of an owned item, else its slot's empty silhouette. */
    private Identifier icon(BridgeProtocol.WardrobeItem item, Wardrobe.Slot slot) {
        Wardrobe.Item row = row(item.id());
        Identifier icon = row == null || row.icon() == null ? null : AvatarHud.texture(row.icon());
        return icon != null ? icon : AvatarHud.texture(slot.empty());
    }
    /** Whether a catalog row's meshes are all in the avatar pack; an item without meshes (gear, exospines) is. */
    private boolean packed(Wardrobe.Item row) {
        return row == null || row.meshes().stream().allMatch(m -> packed.computeIfAbsent(m, n -> Files.isRegularFile(pack.resolve(n + ".sbav"))));
    }
    private boolean usable() { return pending == 0 && failure == null && files != null; }

    private static void tinted(DrawContext context, Identifier id, int x, int y, int size, int color) {
        if (id == null) return;
        context.setShaderColor((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
        AvatarHud.blit(context, id, x, y, size);
        context.setShaderColor(1, 1, 1, 1);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Stellar Blade's black backdrop and footer shade; no blur, the fill hides the world.
        context.fill(0, 0, width, height, 0xE0000000);
        context.fillGradient(0, height - 40, width, height, 0x00000000, 0xCC000000);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        Wardrobe.Slot slot = Wardrobe.SLOTS.get(selected);
        context.drawText(textRenderer, title, Wardrobe.MARGIN, 6, 0xFFFFFFFF, true);
        int nameX = Math.max(layout.listX(), Wardrobe.MARGIN + textRenderer.getWidth(title) + Wardrobe.MARGIN); // The French title is wider than the slot column.
        context.drawText(textRenderer, slotName(selected), nameX, 6, TITLE, true);
        for (String group : Wardrobe.GROUPS)
            context.drawText(textRenderer, Text.translatable("stellar_bridge.wardrobe.group." + group), Wardrobe.MARGIN, layout.heading(group) + 1, HEADING, false);
        Text hints = Text.translatable("stellar_bridge.wardrobe.hints");
        context.drawText(textRenderer, hints, width - Wardrobe.MARGIN - textRenderer.getWidth(hints), height - 11, HINT, false);
        if (list.children().isEmpty() && failure == null && pending == 0)
            context.drawText(textRenderer, Text.translatable("stellar_bridge.wardrobe.nothing"), layout.listX() + 4, layout.top() + 6, HINT, false);

        // EVE whole, or her head and shoulders for the hair, earrings and glasses: presets framed offline with
        // AvatarRenderCheck (tile=WxH) at 960 x 540 and 320 x 240.
        boolean head = slot.type() == 8 || slot.type() == 10 || slot.type() == 11;
        int x0 = layout.previewX0(), x1 = layout.previewX1(), y0 = layout.top(), y1 = layout.previewY1();
        if (!AvatarView.drawPreview(context, x0, y0, x1, y1, head ? 1.0 : 2.8, head ? 1.45 : 0.85, yaw)) {
            Text none = Text.translatable("stellar_bridge.wardrobe.no_pose");
            context.drawText(textRenderer, none, (x0 + x1 - textRenderer.getWidth(none)) / 2, (y0 + y1) / 2, HINT, false);
        }
        // Two caption lines: the item under the mouse (else the selected one) or the screen's state, then its warning.
        Row shown = list.hovered() != null ? list.hovered() : list.getSelectedOrNull();
        Text first = failure != null ? Text.translatable("stellar_bridge.wardrobe." + failure)
                : pending != 0 ? Text.translatable("stellar_bridge.wardrobe.waiting") : shown == null ? Text.empty() : Text.literal(shown.name);
        Text second = failure == null && shown != null ? shown.warning() : null;
        context.drawText(textRenderer, textRenderer.trimToWidth(first.getString(), x1 - x0), x0, y1 + 2, failure != null ? TITLE : 0xFFFFFFFF, true);
        if (second != null) context.drawText(textRenderer, textRenderer.trimToWidth(second.getString(), x1 - x0), x0, y1 + 11, TITLE, false);
        if (list.hovered() != null) setTooltip(Tooltip.of(list.hovered().tooltip()), HoveredTooltipPositioner.INSTANCE, false);
    }

    private Text slotName(int index) {
        Wardrobe.Slot slot = Wardrobe.SLOTS.get(index);
        int number = (int) Wardrobe.SLOTS.subList(0, index).stream().filter(s -> s.key().equals(slot.key())).count() + 1;
        return Text.translatable("stellar_bridge.wardrobe.slot." + slot.key(), number);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        turning = button == 0 && mouseX >= layout.previewX0() && mouseX < layout.previewX1() && mouseY >= layout.top() && mouseY < layout.previewY1();
        return turning || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!turning) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        yaw = (yaw - deltaX * 1.5) % 360;
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        turning = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void close() { client.setScreen(parent); }

    /** One of Stellar Blade's mount slots: its worn item's icon, else its empty silhouette; selecting it lists its items. */
    private final class SlotButton extends ClickableWidget {
        final int index;
        SlotButton(int index, int x, int y, int size) {
            super(x, y, size, size, slotName(index));
            this.index = index;
        }
        BridgeProtocol.WardrobeItem worn() {
            int type = Wardrobe.SLOTS.get(index).type();
            return owned.stream().filter(i -> i.slot() == type).findFirst().orElse(null);
        }
        Text describe() {
            BridgeProtocol.WardrobeItem worn = worn();
            MutableText text = slotName(index).copy().append("\n");
            if (worn != null) return text.append(Text.literal(name(worn)).withColor(TEXT));
            boolean open = Wardrobe.equippable(Wardrobe.SLOTS.get(index).type(), false);
            return text.append(Text.translatable(open ? "stellar_bridge.wardrobe.empty" : "stellar_bridge.wardrobe.locked").withColor(TEXT));
        }
        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            Wardrobe.Slot slot = Wardrobe.SLOTS.get(index);
            int x = getX(), y = getY(), size = getWidth();
            context.fill(x, y, x + size, y + size, FRAME);
            BridgeProtocol.WardrobeItem worn = worn();
            if (worn != null) tinted(context, icon(worn, slot), x + 1, y + 1, size - 2, 0xFFFFFFFF);
            else if (Wardrobe.equippable(slot.type(), false)) tinted(context, AvatarHud.texture(slot.empty()), x + 1, y + 1, size - 2, SILHOUETTE);
            else tinted(context, AvatarHud.texture("EquipLock"), x + size / 4, y + size / 4, size / 2, SILHOUETTE);
            if (index == selected) context.drawBorder(x, y, size, size, 0xFFFFFFFF);
            else if (isHovered() || isFocused()) context.drawBorder(x, y, size, size, HOVER);
        }
        @Override
        public void onClick(double mouseX, double mouseY) {
            selected = index;
            list.setScrollAmount(0); // A slot's list opens at its top, the item worn there first.
            refresh();
        }
        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // ClickableWidget answers only the mouse; Tab focuses a slot, Enter or Space selects it.
            if (!active || !visible || keyCode != GLFW.GLFW_KEY_ENTER && keyCode != GLFW.GLFW_KEY_KP_ENTER && keyCode != GLFW.GLFW_KEY_SPACE) return false;
            onClick(0, 0);
            return true;
        }
        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) { appendDefaultNarrations(builder); }
    }

    private final class ItemList extends AlwaysSelectedEntryListWidget<Row> {
        ItemList(Wardrobe.Layout layout) {
            super(MinecraftClient.getInstance(), layout.listWidth(), layout.bottom() - layout.top(), layout.top(), layout.row());
            setX(layout.listX());
        }
        /**
         * New rows keep the selected and focused item: replaceEntries drops the selection, and a click's choose() rebuilds the rows before the list focuses the clicked one.
         * It keeps the scroll unclamped too: a shorter list (the exospines, glasses or earrings after a scrolled suit list) drew every row above its top.
         */
        void set(List<Row> entries) {
            Row was = getSelectedOrNull();
            Element focus = getFocused();
            replaceEntries(entries);
            refreshScroll();
            if (was != null) setSelected(current(was));
            if (focus instanceof Row) setFocused(focus);
        }
        /** This item's row among the current ones, null when the slot changed. */
        Row current(Row row) {
            return children().contains(row) ? row : children().stream().filter(r -> r.item.guid().equals(row.item.guid())).findFirst().orElse(null);
        }
        @Override
        public void setFocused(Element focused) { super.setFocused(focused instanceof Row row ? current(row) : focused); }
        Row hovered() { return getHoveredEntry(); }
        @Override public int getRowLeft() { return getX() + 2; }
        @Override public int getRowWidth() { return width - 10; }
        @Override protected int getScrollbarX() { return getRight() - 6; }
        @Override protected void drawMenuListBackground(DrawContext context) { context.fill(getX(), getY(), getRight(), getBottom(), 0x60141414); }
        @Override protected void drawHeaderAndFooterSeparators(DrawContext context) { }
        @Override protected void drawSelectionHighlight(DrawContext context, int y, int entryWidth, int entryHeight, int borderColor, int fillColor) { }
    }

    /** An owned item for the selected slot: click to equip it (or, in the earring and glasses slots, to remove the worn one). */
    private final class Row extends AlwaysSelectedEntryListWidget.Entry<Row> {
        final BridgeProtocol.WardrobeItem item;
        final Wardrobe.Slot slot;
        final Wardrobe.Item row;
        final String name, op;
        final boolean blocked, missing;

        Row(BridgeProtocol.WardrobeItem item, Wardrobe.Slot slot, boolean occupied) {
            this.item = item;
            this.slot = slot;
            row = row(item.id());
            name = name(item);
            blocked = row != null && row.maxBones() > Wardrobe.MAX_BONES; // Lua's pose table would stop updating EVE.
            missing = !packed(row);
            boolean here = item.slot() == slot.type();
            // ponytail: an item worn in another slot (a second gear or exospine) is not moved; Stellar Blade's menu does.
            op = here ? (Wardrobe.removable(slot.type()) ? "unequip" : null) : item.slot() != 0 || blocked
                    || !Wardrobe.equippable(slot.type(), occupied) ? null : "equip";
        }
        boolean enabled() { return op != null && usable(); }
        Text warning() {
            return blocked ? Text.translatable("stellar_bridge.wardrobe.blocked") : missing ? Text.translatable("stellar_bridge.wardrobe.missing")
                    : row == null ? Text.translatable("stellar_bridge.wardrobe.unknown") : null;
        }
        Text tooltip() {
            MutableText text = Text.literal(name);
            if (row != null && !row.description().isEmpty()) text.append("\n").append(Text.literal(row.description().replaceAll("<[^>]*>", "")).withColor(TEXT));
            Text state = item.slot() == slot.type() ? Text.translatable(op != null ? "stellar_bridge.wardrobe.remove" : "stellar_bridge.wardrobe.equipped")
                    : item.slot() != 0 ? Text.translatable("stellar_bridge.wardrobe.elsewhere") : warning();
            return state == null ? text : text.append("\n").append(state.copy().withColor(TITLE));
        }
        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            context.fill(x, y, x + entryWidth, y + entryHeight, FRAME);
            int alpha = enabled() || item.slot() == slot.type() ? 0xFF000000 : 0x70000000;
            tinted(context, icon(item, slot), x + 1, y + 1, entryHeight - 2, alpha | (row == null || row.icon() == null ? SILHOUETTE : 0xFFFFFF) & 0xFFFFFF);
            int badge = item.slot() != 0 ? Math.min(9, entryHeight) : 0, textX = x + entryHeight + 3;
            String shown = textRenderer.trimToWidth(name, x + entryWidth - textX - badge - 4);
            context.drawText(textRenderer, shown.equals(name) ? name : textRenderer.trimToWidth(name, x + entryWidth - textX - badge - 4 - textRenderer.getWidth("…")) + "…",
                    textX, y + (entryHeight - 8) / 2, alpha | (missing || blocked ? HINT : 0xFFFFFF) & 0xFFFFFF, false);
            if (badge > 0) { // Stellar Blade's orange "E", or its colour with a letter.
                int bx = x + entryWidth - badge - 2, by = y + 2;
                Identifier mark = AvatarHud.texture("icon_equipment_color");
                if (mark != null) tinted(context, mark, bx, by, badge, 0xFFFFFFFF);
                else {
                    context.fill(bx, by, bx + badge, by + badge, TITLE);
                    context.drawText(textRenderer, "E", bx + 2, by + 1, 0xFFFFFFFF, false);
                }
            }
            if (this == list.getSelectedOrNull()) context.drawBorder(x, y, entryWidth, entryHeight, 0xFFFFFFFF);
            else if (hovered) context.drawBorder(x, y, entryWidth, entryHeight, HOVER);
        }
        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) choose();
            return true; // Selected either way, as Stellar Blade's list moves its focus.
        }
        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode != GLFW.GLFW_KEY_ENTER && keyCode != GLFW.GLFW_KEY_KP_ENTER && keyCode != GLFW.GLFW_KEY_SPACE) return false;
            choose();
            return true;
        }
        private void choose() {
            if (enabled()) send(op, slot.type(), op.equals("equip") ? item.guid() : null, name);
        }
        @Override
        public Text getNarration() { return Text.literal(name); }
    }
}
