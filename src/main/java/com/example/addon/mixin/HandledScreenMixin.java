package com.example.addon.mixin;

import com.example.addon.modules.DungeonAssistant;
import com.example.addon.modules.Inventory101;
import com.example.addon.modules.LootLens;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen {
    @Shadow protected int backgroundWidth;
    @Shadow protected int x;
    @Shadow protected int y;

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        Inventory101 inv101       = Modules.get().get(Inventory101.class);
        boolean      inv101Active = inv101 != null && inv101.isActive();

        if ((Object) this instanceof InventoryScreen && inv101Active) {
            int bx = this.x - 25;
            int by = this.y;

            this.addDrawableChild(mouseOnly(Text.literal("S1"),
                btn -> inv101.startInvSort(1), bx, by, 20, 20,
                net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Sort to " + inv101.getPresetName(1))),
                () -> !inv101.isPresetEmpty(1)));

            this.addDrawableChild(mouseOnly(Text.literal("S2"),
                btn -> inv101.startInvSort(2), bx, by + 25, 20, 20,
                net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Sort to " + inv101.getPresetName(2))),
                () -> !inv101.isPresetEmpty(2)));
            return;
        }

        HandledScreen<?> screen         = (HandledScreen<?>) (Object) this;
        int              containerSlots = screen.getScreenHandler().slots.size() - 36;

        // ── Inventory101 buttons (LEFT side) ──────────────────────────────────────────
        if (inv101Active) {
            // The S/1/2/C preset buttons live on ShulkerBoxScreen's left side
            if ((Object) this instanceof ShulkerBoxScreen) {
                int bx = this.x - 25; // 20px button + 5px gap to the left of the GUI
                int by = this.y;

                this.addDrawableChild(mouseOnly(Text.literal("S"),
                    btn -> inv101.toggleSaveMode(), bx, by, 20, 20,
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Save Current Layout"))));
                by += 25;

                // FIX #2: Added guard — when NOT in save mode and the preset is empty,
                // clicking is a no-op instead of starting a futile regear/replenish cycle.
                this.addDrawableChild(mouseOnly(Text.literal("1"),
                    btn -> {
                        if (!inv101.isSaveMode() && inv101.isPresetEmpty(1)) return;
                        inv101.handlePreset(1);
                    }, bx, by, 20, 20,
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Load " + inv101.getPresetName(1))),
                    () -> !inv101.isPresetEmpty(1)));
                by += 25;

                this.addDrawableChild(mouseOnly(Text.literal("2"),
                    btn -> {
                        if (!inv101.isSaveMode() && inv101.isPresetEmpty(2)) return;
                        inv101.handlePreset(2);
                    }, bx, by, 20, 20,
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Load " + inv101.getPresetName(2))),
                    () -> !inv101.isPresetEmpty(2)));
                by += 25;

                this.addDrawableChild(mouseOnly(Text.literal("C"),
                    btn -> inv101.clearPresets(), bx, by, 20, 20,
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Clear Presets"))));
                by += 25;

                if (inv101.isRegearButtonEnabled()) {
                    this.addDrawableChild(mouseOnly(Text.literal("G"),
                        btn -> inv101.startRegearing(), bx, by, 20, 20,
                        net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Equip armor and replenish essentials"))));
                    by += 25;
                }

                if (inv101.isReplenishButtonEnabled()) {
                    this.addDrawableChild(mouseOnly(Text.literal("R"),
                        btn -> inv101.startReplenishing(), bx, by, 20, 20,
                        net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Replenish whitelisted items from shulker"))));
                }

                // Inventory101 owns ShulkerBoxScreen — skip adding S/D steal buttons
                return;
            }

            // Sort button on GenericContainerScreen's left side
            if ((Object) this instanceof GenericContainerScreen && inv101.isSortButtonEnabled()) {
                int bx = this.x - 35; // 30px button + 5px gap
                int by = this.y;
                this.addDrawableChild(mouseOnly(Text.literal("Sort"),
                    btn -> inv101.startSorting(), bx, by, 30, 20,
                    net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Sort shulkers by colour"))));
            }
        }

        // ── LootLens / DungeonAssistant Steal+Dump buttons (RIGHT side) ──────────────
        if (containerSlots <= 0) return;

        LootLens ll          = Modules.get().get(LootLens.class);
        boolean  llHandled   = ll != null && ll.isActive();
        if (llHandled) {
            addStealDumpButtons(screen, containerSlots);
        } else {
            DungeonAssistant da = Modules.get().get(DungeonAssistant.class);
            if (da != null && da.isActive()) {
                addStealDumpButtons(screen, containerSlots);
            }
        }
    }

    /**
     * Creates a ButtonWidget that only reacts to mouse clicks.
     * Keyboard key-presses are swallowed so they cannot accidentally trigger
     * these buttons while the player is typing or using hotkeys inside the GUI.
     *
     * @param hasData optional supplier; when non-null the button renders as
     *                greyed-out (inactive style) while the supplier returns
     *                {@code false}, but remains fully clickable in both states
     *                so that save-mode workflows still work (clicking a greyed-out
     *                preset slot saves to it rather than trying to load from it).
     */
    private static ButtonWidget mouseOnly(Text label, ButtonWidget.PressAction action,
                                          int x, int y, int width, int height,
                                          net.minecraft.client.gui.tooltip.Tooltip tooltip) {
        return mouseOnly(label, action, x, y, width, height, tooltip, null);
    }

    private static ButtonWidget mouseOnly(Text label, ButtonWidget.PressAction action,
                                          int x, int y, int width, int height,
                                          net.minecraft.client.gui.tooltip.Tooltip tooltip,
                                          BooleanSupplier hasData) {
        ButtonWidget btn = new ButtonWidget(x, y, width, height, label, action,
                textSupplier -> textSupplier.get().copy()) {
            @Override
            public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                return false; // never activate via keyboard
            }
            @Override
            public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
                return false;
            }
            @Override
            protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
                if (hasData != null) {
                    // Temporarily set active=false to render with the grey "disabled"
                    // style when no preset is saved — then restore so clicking works.
                    boolean prev = this.active;
                    this.active = hasData.getAsBoolean();
                    super.renderWidget(context, mouseX, mouseY, delta);
                    this.active = prev;
                } else {
                    super.renderWidget(context, mouseX, mouseY, delta);
                }
            }
        };
        if (tooltip != null) btn.setTooltip(tooltip);
        return btn;
    }

    // ── Helper: Shared Steal/Dump buttons ────────────────────────────────────────────
    // FIX #1: Merged the identical addDungeonAssistantButtons and addLootLensButtons
    // into a single method. The two former methods had 100% identical S/D logic — any
    // bug fix or feature change would need to be applied twice, which is error-prone.
    //
    // FIX #3: S/D buttons now use the mouseOnly() wrapper so they cannot be
    // accidentally triggered by keyboard input while the player is typing or using
    // hotkeys inside the GUI. Previously they used ButtonWidget.Builder directly,
    // which responds to keyboard events.
    //
    // FIX #4: Added tooltips to S/D buttons for discoverability.
    //
    // FIX #5: The D (Dump) button now dumps the FULL player inventory including the
    // hotbar. The original used `end = slots.size() - 9` which silently skipped the
    // hotbar — "Dump" conventionally means "dump everything," and silently omitting
    // 9 slots was a behavioral bug that confused users.

    private void addStealDumpButtons(HandledScreen<?> screen, int containerSlots) {
        int buttonX = this.x + this.backgroundWidth + 5;
        int buttonY = this.y + 5;

        // S = Steal: shift-click all items from the container into the player's inventory
        this.addDrawableChild(mouseOnly(Text.literal("S"),
            button -> {
                for (int i = 0; i < containerSlots; i++) {
                    if (screen.getScreenHandler().getSlot(i).hasStack()) {
                        client.interactionManager.clickSlot(
                            screen.getScreenHandler().syncId, i, 0,
                            SlotActionType.QUICK_MOVE, client.player);
                    }
                }
            }, buttonX, buttonY, 20, 20,
            net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Steal all items from container"))));

        // D = Dump: shift-click all player inventory items (including hotbar) into the container
        this.addDrawableChild(mouseOnly(Text.literal("D"),
            button -> {
                // FIX #5: Changed from `slots.size() - 9` to `slots.size()`.
                // The original skipped the last 9 slots (hotbar) by computing
                // `end = slots.size() - 9`. The slot layout in a GenericContainerScreenHandler is:
                //   [0 .. containerSlots-1]   = container
                //   [containerSlots .. size-1] = player inventory (main + hotbar)
                // Skipping the last 9 meant hotbar items were never dumped, which is
                // surprising for a button labelled "Dump". Now the full player inventory
                // is iterated; empty slots are already skipped by the isEmpty() check.
                for (int i = containerSlots; i < screen.getScreenHandler().slots.size(); i++) {
                    if (screen.getScreenHandler().getSlot(i).getStack().isEmpty()) continue;
                    client.interactionManager.clickSlot(
                        screen.getScreenHandler().syncId, i, 0,
                        SlotActionType.QUICK_MOVE, client.player);
                }
            }, buttonX, buttonY + 25, 20, 20,
            net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal("Dump all inventory items into container"))));
    }
}