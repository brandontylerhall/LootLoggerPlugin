package com.lootlogger;

import com.google.inject.Provides;
import com.lootlogger.data.*;
import com.lootlogger.io.LootWriter;

import com.lootlogger.util.InventoryProcessor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@PluginDescriptor(name = "Loot to JSON")
public class LootLoggerPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private LootLoggerConfig config;

    @Provides
    LootLoggerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(LootLoggerConfig.class);
    }

    @Inject
    private ItemManager itemManager;

    @Inject
    private java.util.concurrent.ScheduledExecutorService executor;

    private String sessionId;
    private LootWriter lootWriter;

    // DEBUG VARIABLES //
    private int lastActiveAnimation = -1;
    private String lastMenuOptionClicked = "";
    private String lastMenuTargetClicked = "";
    private Item[] previousInventory = new Item[28];

    public void gameMsg(String msg) {
        if (!config.showChatMessages()) {
            return;
        }
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
    }

    private static final java.util.Map<Integer, String> SOURCE_MAP = java.util.Map.ofEntries(
            java.util.Map.entry(879, "Woodcutting"), // Bronze axe
            java.util.Map.entry(877, "Woodcutting"), // Iron axe
            java.util.Map.entry(875, "Woodcutting"), // Steel axe
            java.util.Map.entry(625, "Mining"),      // Bronze pick
            java.util.Map.entry(626, "Mining"),      // Iron pick
            java.util.Map.entry(627, "Mining"),      // Steel pick
            java.util.Map.entry(621, "Small Net Fishing")
    );

    // =========================
    //    START UP / SHUT DOWN
    // =========================
    @Override
    protected void startUp() throws Exception {
        sessionId = java.util.UUID.randomUUID().toString();
        lootWriter = new LootWriter();
        lootWriter.init();

        for (int i = 0; i < 28; i++) {
            previousInventory[i] = new Item(-1, 0);
        }
    }

    @Override
    public void shutDown() throws Exception {
        if (lootWriter != null) {
            lootWriter.flush();
            lootWriter.close();
        }
    }

    @Schedule(period = 10, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void submitBatch() {
        if (lootWriter != null) {
            lootWriter.flush();
        }
    }

    // =========================
    //      DEBUG COMMAND
    // =========================
    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        if (event.getCommand().equals("status")) {
            int currentAnim = client.getLocalPlayer().getAnimation();
            WorldPoint wp = client.getLocalPlayer().getWorldLocation();

            gameMsg("--- DEBUG STATUS ---");
            gameMsg(String.format("Current Animation: %d", currentAnim));
            gameMsg(String.format("Last Saved Animation: %d", lastActiveAnimation));
            gameMsg(String.format("Location: %d, %d, %d", wp.getX(), wp.getY(), wp.getPlane()));
            gameMsg(String.format("Last menu option: %s", lastMenuOptionClicked));
        }
    }

    // =========================
    //      NPC LOOT EVENT
    // =========================
    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        NPC npc = event.getNpc();
        String sourceName = npc.getName();
        WorldPoint wp = npc.getWorldLocation();

        List<DroppedItem> items = event.getItems().stream()
                .map(item -> new DroppedItem(item.getId(), itemManager.getItemComposition(item.getId()).getName(), item.getQuantity(), (itemManager.getItemPrice(item.getId() * item.getQuantity())), (itemManager.getItemComposition(item.getId()).getHaPrice() * item.getQuantity())))
                .collect(java.util.stream.Collectors.toList());

        GameEvent record = GameEvent.builder()
                .sessionId(sessionId)
                .eventType("NPC_DROP")
                .category("Combat")
                .source(sourceName)
                .x(wp.getX())
                .y(wp.getY())
                .plane(wp.getPlane())
                .regionId(wp.getRegionID())
                .items(items)
                .build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    // =========================
    //    ANIMATION & MENU EVENTS
    // =========================
    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() != client.getLocalPlayer()) return;
        int animId = client.getLocalPlayer().getAnimation();
        if (animId != -1) {
            lastActiveAnimation = animId;
        }

        if (config.debugMessages()) {
            gameMsg(String.format("Last animation ID: %d", lastActiveAnimation));
            gameMsg(String.format("Current animation ID: %d", client.getLocalPlayer().getAnimation()));
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        lastMenuOptionClicked = event.getMenuOption();
        lastMenuTargetClicked = Text.removeTags(event.getMenuTarget()); // Strips color codes
    }

    // =========================
    //    INVENTORY PROCESSING
    // =========================
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();

        if (event.getContainerId() != 93) return;

        ItemContainer bankContainer = client.getItemContainer(95);
        boolean isBanking = (bankContainer != null);
        Item[] currentInventory = event.getItemContainer().getItems();

        // 1. Build the State Package
        PlayerState state = PlayerState.builder()
                .isBanking(isBanking)
                .menuOption(lastMenuOptionClicked)
                .menuTarget(lastMenuTargetClicked)
                .currentAnim(client.getLocalPlayer().getAnimation())
                .lastAnim(lastActiveAnimation)
                .justCastSpell(false)   // Ready to be hooked up later
                .justFiredRanged(false) // Ready to be hooked up later
                .combatTarget("None")   // Ready to be hooked up later
                .build();

        // 2. Process with the State Package
        List<InventoryEvent> events = InventoryProcessor.invProcess(previousInventory, currentInventory, state, itemManager);

        // 3. Translate to GameEvents
        for (InventoryEvent invEvent : events) {
            int itemId = invEvent.itemId;
            int qty = invEvent.qty;
            String name = itemManager.getItemComposition(itemId).getName();

            if (invEvent.actionType == ActionType.EQUIP) {
                gameMsg(String.format("Equipped: %s", itemManager.getItemComposition(itemId).getName()));
                continue;
            }

            if (invEvent.actionType == ActionType.UNEQUIP) {
                gameMsg(String.format("Unequipped: %s", itemManager.getItemComposition(itemId).getName()));
                continue;
            }

            String category = "Misc";
            String sourceName = "None";
            String eventTypeStr = invEvent.actionType.name();

            // Map the ActionType to your Frontend's UI Categories
            switch (invEvent.actionType) {
                case GATHER_GAIN:
                    category = "Skilling";
                    sourceName = (lastActiveAnimation != -1) ? SOURCE_MAP.getOrDefault(lastActiveAnimation, invEvent.targetName) : invEvent.targetName;
                    break;
                case SKILLING_CONSUME:
                    category = "Skilling";
                    sourceName = invEvent.targetName;
                    break;
                case BANK_DEPOSIT:
                case BANK_WITHDRAWAL:
                    category = "Banking";
                    sourceName = "Bank";
                    break;
                case SPELL_CAST:
                case RANGED_FIRE:
                case COMBAT_CONSUME:
                    category = "Combat";
                    sourceName = invEvent.targetName;
                    break;
                case TAKE:
                    category = "Misc";
                    sourceName = "Pickup";
                    break;
                case DROP:
                case DESTROY:
                case CONSUME:
                    category = "Misc";
                    break;
            }

            // Only log Consumes/Drops if your config allows it
            if ((invEvent.actionType == ActionType.CONSUME || invEvent.actionType == ActionType.DROP) && !config.logConsumables()) {
                continue;
            }

            List<DroppedItem> eventItems = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

            GameEvent record = GameEvent.builder()
                    .sessionId(sessionId)
                    .eventType(eventTypeStr)
                    .category(category)
                    .source(sourceName)
                    .target(invEvent.targetName)
                    .x(wp.getX())
                    .y(wp.getY())
                    .plane(wp.getPlane())
                    .regionId(wp.getRegionID())
                    .items(eventItems)
                    .build();

            executor.execute(() -> lootWriter.queueRecord(record));
        }

        previousInventory = currentInventory.clone();
    }
}