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
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    private static final Map<Integer, String> SKILL_MAP = Map.ofEntries(
            Map.entry(879, "Woodcutting"),
            Map.entry(877, "Woodcutting"),
            Map.entry(875, "Woodcutting"),
            Map.entry(625, "Mining"),
            Map.entry(626, "Mining"),
            Map.entry(627, "Mining"),
            Map.entry(6756, "Mining"),
            Map.entry(6752, "Mining"),
            Map.entry(621, "Fishing")
    );

    private String sessionId;
    private LootWriter lootWriter;

    private int lastActiveAnimation = -1;
    private String lastMenuOptionClicked = "";
    private String lastMenuTargetClicked = "";
    private String lockedSkillingTarget = "Unknown";
    private String lockedCombatTarget = "Unknown";
    private String lockedMagicSpell = ""; // NEW: Explicitly track the spell cast
    private Item[] previousInventory = new Item[28];

    private final Map<Skill, Integer> previousXpMap = new EnumMap<>(Skill.class);

    public void gameMsg(String msg) {
        if (!config.showChatMessages()) return;
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
    }

    @Override
    protected void startUp() throws Exception {
        sessionId = java.util.UUID.randomUUID().toString();
        lootWriter = new LootWriter();
        lootWriter.init();

        for (int i = 0; i < 28; i++) {
            previousInventory[i] = new Item(-1, 0);
        }

        previousXpMap.clear();

        if (client.getGameState() == GameState.LOGGED_IN) {
            seedXpMap();
        }
    }

    @Override
    public void shutDown() throws Exception {
        if (lootWriter != null) {
            lootWriter.flush();
            lootWriter.close();
        }
        previousXpMap.clear();
    }

    @Schedule(period = 10, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void submitBatch() {
        if (lootWriter != null) {
            lootWriter.flush();
        }
    }

    private void seedXpMap() {
        for (Skill skill : Skill.values()) {
            previousXpMap.put(skill, client.getSkillExperience(skill));
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            seedXpMap();
        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        if (event.getCommand().equals("status")) {
            gameMsg("Status printed to console/logs.");
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        NPC npc = event.getNpc();
        WorldPoint wp = npc.getWorldLocation();

        List<DroppedItem> items = event.getItems().stream()
                .map(item -> new DroppedItem(item.getId(), itemManager.getItemComposition(item.getId()).getName(), item.getQuantity(), (itemManager.getItemPrice(item.getId() * item.getQuantity())), (itemManager.getItemComposition(item.getId()).getHaPrice() * item.getQuantity())))
                .collect(java.util.stream.Collectors.toList());

        GameEvent record = GameEvent.builder()
                .sessionId(sessionId)
                .eventType("NPC_DROP")
                .category("Combat")
                .source(npc.getName())
                .x(wp.getX())
                .y(wp.getY())
                .plane(wp.getPlane())
                .regionId(wp.getRegionID())
                .items(items)
                .build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() != client.getLocalPlayer()) return;
        int animId = client.getLocalPlayer().getAnimation();
        if (animId != -1) lastActiveAnimation = animId;
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        int currentXp = event.getXp();

        if (!previousXpMap.containsKey(skill) || previousXpMap.get(skill) == 0) {
            previousXpMap.put(skill, currentXp);
            return;
        }

        int previousXp = previousXpMap.get(skill);
        int xpDelta = currentXp - previousXp;
        previousXpMap.put(skill, currentXp);

        if (xpDelta <= 0) return;

        boolean isCombatSkill = skill == Skill.ATTACK || skill == Skill.STRENGTH ||
                skill == Skill.DEFENCE || skill == Skill.RANGED ||
                skill == Skill.MAGIC || skill == Skill.HITPOINTS;

        String category = isCombatSkill ? "Combat" : "Skilling";
        String targetSource = isCombatSkill ? lockedCombatTarget : lockedSkillingTarget;

        // NEW: If Magic XP, set the source to the spell cast (e.g., "Fire Strike")
        if (skill == Skill.MAGIC && !lockedMagicSpell.isEmpty()) {
            targetSource = lockedMagicSpell;
        } else if (targetSource != null && targetSource.contains("->")) {
            String[] parts = targetSource.split("->");
            targetSource = parts[parts.length - 1].trim();
        }

        Player localPlayer = client.getLocalPlayer();
        int x = 0, y = 0, plane = 0, regionId = 0;
        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            x = wp.getX();
            y = wp.getY();
            plane = wp.getPlane();
            regionId = wp.getRegionID();
        }

        GameEvent record = GameEvent.builder()
                .sessionId(sessionId)
                .eventType("XP_GAIN")
                .category(category)
                .source(targetSource != null && !targetSource.isEmpty() ? targetSource : "Activity")
                .skill(skill.getName())
                .xpGained(xpDelta)
                .x(x)
                .y(y)
                .plane(plane)
                .regionId(regionId)
                .build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        lastMenuOptionClicked = event.getMenuOption();
        String rawTarget = Text.removeTags(event.getMenuTarget());
        lastMenuTargetClicked = rawTarget.replaceAll("\\s*\\(level-\\d+\\)", "").trim();

        String opt = lastMenuOptionClicked.toLowerCase();

        if (opt.contains("mine") || opt.contains("chop") || opt.contains("cut") ||
                opt.contains("net") || opt.contains("lure") || opt.contains("bait") ||
                opt.contains("cage") || opt.contains("harpoon") || opt.contains("fish")) {
            lockedSkillingTarget = lastMenuTargetClicked;
        }
        // NEW: Break "Cast Spell -> Enemy" into two separate fields
        else if (opt.contains("cast")) {
            if (lastMenuTargetClicked.contains("->")) {
                String[] parts = lastMenuTargetClicked.split("->");
                lockedMagicSpell = parts[0].trim();
                lockedCombatTarget = parts[1].trim();
            } else {
                lockedMagicSpell = lastMenuTargetClicked.trim();
                lockedCombatTarget = "None"; // It was a teleport or self-cast
            }
        }
        // Track melee/ranged targets and clear the spell cache
        else if (opt.contains("attack")) {
            lockedCombatTarget = lastMenuTargetClicked;
            lockedMagicSpell = "";
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();

        if (event.getContainerId() != 93) return;

        ItemContainer bankContainer = client.getItemContainer(95);
        boolean isBanking = (bankContainer != null);
        Item[] currentInventory = event.getItemContainer().getItems();

        PlayerState state = PlayerState.builder()
                .isBanking(isBanking)
                .menuOption(lastMenuOptionClicked)
                .menuTarget(lastMenuTargetClicked)
                .currentAnim(client.getLocalPlayer().getAnimation())
                .lastAnim(lastActiveAnimation)
                .justCastSpell(false)
                .justFiredRanged(false)
                .combatTarget(lockedCombatTarget)
                .build();

        List<InventoryEvent> events = InventoryProcessor.invProcess(previousInventory, currentInventory, state, itemManager);

        for (InventoryEvent invEvent : events) {
            int itemId = invEvent.itemId;
            int qty = invEvent.qty;
            String name = itemManager.getItemComposition(itemId).getName();

            String category = "Misc";
            String sourceName = "None";
            String eventTypeStr = invEvent.actionType.name();
            String skillName = null;

            switch (invEvent.actionType) {
                case GATHER_GAIN:
                    category = "Skilling";
                    skillName = SKILL_MAP.getOrDefault(lastActiveAnimation, "Unknown");
                    sourceName = (lockedSkillingTarget != null && !lockedSkillingTarget.isEmpty()) ? lockedSkillingTarget : "Unknown Source";
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

            List<DroppedItem> eventItems = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

            GameEvent record = GameEvent.builder()
                    .sessionId(sessionId)
                    .eventType(eventTypeStr)
                    .category(category)
                    .source(sourceName)
                    .target(invEvent.targetName)
                    .skill(skillName)
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