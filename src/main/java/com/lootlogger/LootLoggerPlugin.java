package com.lootlogger;

import com.google.inject.Provides;

import com.lootlogger.data.*;
import com.lootlogger.io.LootWriter;
import com.lootlogger.util.InventoryProcessor;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.util.Text;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.VarPlayer;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    private ConfigManager configManager;

    @Inject
    private ClientThread clientThread;

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

    // DEBUG & STATE VARIABLES //
    private int lastActiveAnimation = -1;
    private String lastMenuOptionClicked = "";
    private String lastMenuTargetClicked = "";
    private String lockedSkillingTarget = "Unknown";
    private String lockedCombatTarget = "Unknown";
    private String lockedManualSpell = "";
    private int lastAutocastVarp = -1;
    private String lastDialogueNpc = "NPC / Dialogue";
    private List<DroppedItem> tickGainedItems = new ArrayList<>();
    private List<DroppedItem> tickLostItems = new ArrayList<>();
    private final Map<String, Integer> activeQuestTicks = new HashMap<>();
    private int previousQuestPoints = -1;
    private String lastFinishedQuest = "Quest Reward"; // The shared memory variable!
    private int lastDialogueTick = 0;

    // --- SHOPPING TRACKERS ---
    private boolean isShopOpen = false;
    private boolean isMonsterExamineOpen = false;
    private String lockedShopTarget = "Unknown Shop";
    private String currentShopName = "Unknown Shop";

    private int previousBoostedHp = -1;

    private Item[] previousInventory = new Item[28];
    private Item[] previousEquipment = new Item[14];

    private final Map<Skill, Integer> previousXpMap = new EnumMap<>(Skill.class);
    private final Map<Quest, QuestState> questStates = new EnumMap<>(Quest.class);
    private int questSyncTicks = 0;


    public void gameMsg(String msg) {
        if (!config.showChatMessages()) return;
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
    }

    @Override
    protected void startUp() throws Exception {
        sessionId = java.util.UUID.randomUUID().toString();
        lootWriter = new LootWriter();
        lootWriter.init();
        previousQuestPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);

        for (int i = 0; i < 28; i++) previousInventory[i] = new Item(-1, 0);
        for (int i = 0; i < 14; i++) previousEquipment[i] = new Item(-1, 0);

        previousXpMap.clear();
        questStates.clear();
        lastAutocastVarp = -1;
        previousBoostedHp = -1;
        isShopOpen = false;

        if (client.getGameState() == GameState.LOGGED_IN) {
            seedXpMap();
            seedQuestMap();
            questSyncTicks = 3; // NEW: Start the 9-second grace period
            lastAutocastVarp = client.getVarpValue(108);
            previousBoostedHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        }
        log.info("[LL Debug] Plugin Started.");
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

    private void seedQuestMap() {
        for (Quest quest : Quest.values()) {
            questStates.put(quest, quest.getState(client));
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            seedXpMap();
            seedQuestMap();
            questSyncTicks = 3; // NEW: Start the 9-second grace period
            lastAutocastVarp = client.getVarpValue(108);
            previousBoostedHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
            previousQuestPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);
        } else {
            // Pause checking if they hop worlds or log out
            questSyncTicks = 0;
        }
    }

    private void checkQuestProgress() {
        // --- NEW: Grace Period Logic ---
        if (questSyncTicks > 0) {
            questSyncTicks--;
            if (questSyncTicks == 0) {
                seedQuestMap(); // Re-seed with the true, synced server values
            }
            return; // Skip checking until the sync is complete
        }

        // --- Standard Quest Logic ---
        for (Quest quest : Quest.values()) {
            QuestState currentState = quest.getState(client);
            QuestState previousState = questStates.get(quest);
            String questName = quest.getName();

            // --- NEW: In-Game Tick Counter ---
            if (currentState == QuestState.IN_PROGRESS) {
                int ticks = activeQuestTicks.getOrDefault(questName, -1);

                // If it's not in memory yet, try loading it from RuneLite's saved config
                if (ticks == -1) {
                    try {
                        String saved = configManager.getConfiguration("lootlogger", "qticks_" + quest.getId());
                        ticks = saved != null ? Integer.parseInt(saved) : 0;
                    } catch (Exception e) {
                        ticks = 0;
                    }
                }

                ticks++;
                activeQuestTicks.put(questName, ticks);

                // Save to hard drive every 100 ticks (1 minute) so it persists across logouts
                if (ticks % 100 == 0) {
                    configManager.setConfiguration("lootlogger", "qticks_" + quest.getId(), ticks);
                }
            }

            // --- Standard State Change Logic ---
            if (previousState != null && currentState != previousState) {
                questStates.put(quest, currentState);

                if (currentState == QuestState.IN_PROGRESS || currentState == QuestState.FINISHED) {
                    String stateStr = currentState.name();

                    if (currentState == QuestState.FINISHED) {
                        lastFinishedQuest = questName;
                    }

                    Player localPlayer = client.getLocalPlayer();
                    int x = 0, y = 0, plane = 0, regionId = 0;
                    if (localPlayer != null) {
                        WorldPoint wp = localPlayer.getWorldLocation();
                        x = wp.getX(); y = wp.getY(); plane = wp.getPlane(); regionId = wp.getRegionID();
                    }

                    // Grab the total accumulated ticks for this quest (default to 0 if it was pre-plugin)
                    int finalTicks = activeQuestTicks.getOrDefault(questName, 0);

                    GameEvent questEvent = GameEvent.builder()
                            .sessionId(sessionId)
                            .eventType("QUEST_PROGRESS")
                            .category("Quests")
                            .source(questName)
                            .target(stateStr)
                            .skill("None")
                            .x(x).y(y).plane(plane).regionId(regionId)
                            .note("In-Game Ticks: " + finalTicks) // Inject ticks into the payload!
                            .build();

                    executor.execute(() -> lootWriter.queueRecord(questEvent));
                }
            }
        }
    }

    private String getActiveShopName() {
        int[] primaryLocations = {1, 2, 78};
        for (int id : primaryLocations) {
            Widget w = client.getWidget(300, id);
            if (w != null && w.getText() != null && !w.getText().isEmpty()) {
                String clean = Text.removeTags(w.getText()).trim();
                if (clean.length() > 3 && !clean.equalsIgnoreCase("Close") && !clean.contains("Value check")) {
                    return clean;
                }
            }

            if (w != null && w.getChildren() != null) {
                for (Widget child : w.getChildren()) {
                    if (child != null && child.getText() != null && !child.getText().isEmpty()) {
                        String clean = Text.removeTags(child.getText()).trim();
                        if (clean.length() > 3 && !clean.equalsIgnoreCase("Close") && !clean.contains("Value check")) {
                            return clean;
                        }
                    }
                }
            }
        }

        String fallback = lockedShopTarget.trim();
        if (fallback.isEmpty() || fallback.equals("Unknown Shop")) return "Unknown Shop";

        if (!fallback.toLowerCase().contains("shop") && !fallback.toLowerCase().contains("store")) {
            return fallback + " Shop";
        }
        return fallback;
    }

    private List<DroppedItem> getShopItems() {
        List<DroppedItem> stockItems = new ArrayList<>();

        Widget itemGrid = client.getWidget(300, 16);
        if (itemGrid != null && itemGrid.getChildren() != null) {
            for (Widget itemWidget : itemGrid.getChildren()) {
                int itemId = itemWidget.getItemId();
                int qty = itemWidget.getItemQuantity();

                if (itemId > 0) {
                    int canonicalId = itemManager.canonicalize(itemId);
                    String name = itemManager.getItemComposition(canonicalId).getName();
                    stockItems.add(new DroppedItem(
                            canonicalId, name, qty,
                            itemManager.getItemPrice(canonicalId) * qty,
                            itemManager.getItemComposition(canonicalId).getHaPrice() * qty,
                            itemManager.getItemComposition(canonicalId).getPrice() * qty
                    ));
                }
            }
        }

        if (stockItems.isEmpty()) {
            ItemContainer shopContainer = client.getItemContainer(3);
            if (shopContainer != null) {
                for (Item item : shopContainer.getItems()) {
                    if (item.getId() != -1) {
                        int canonicalId = itemManager.canonicalize(item.getId());
                        String name = itemManager.getItemComposition(canonicalId).getName();
                        int qty = item.getQuantity();
                        stockItems.add(new DroppedItem(
                                canonicalId, name, qty,
                                itemManager.getItemPrice(canonicalId) * qty,
                                itemManager.getItemComposition(canonicalId).getHaPrice() * qty,
                                itemManager.getItemComposition(canonicalId).getPrice() * qty
                        ));
                    }
                }
            }
        }

        return stockItems;
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        checkQuestProgress(); // NEW

        previousBoostedHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        lockedManualSpell = "";

        // --- SHOPPING LOGIC ---
        Widget shopIndicator = client.getWidget(300, 0);
        boolean shopCurrentlyOpen = (shopIndicator != null && !shopIndicator.isHidden());

        if (shopCurrentlyOpen && !isShopOpen) {
            List<DroppedItem> stockItems = getShopItems();
            if (!stockItems.isEmpty()) {
                isShopOpen = true;
                currentShopName = getActiveShopName();

                log.info("[LL Debug] Shop Snapshot Triggered. Extracted {} unique items for: {}", stockItems.size(), currentShopName);

                Player localPlayer = client.getLocalPlayer();
                int x = 0, y = 0, plane = 0, regionId = 0;
                if (localPlayer != null) {
                    WorldPoint wp = localPlayer.getWorldLocation();
                    x = wp.getX();
                    y = wp.getY();
                    plane = wp.getPlane();
                    regionId = wp.getRegionID();
                }

                GameEvent snapshot = GameEvent.builder()
                        .sessionId(sessionId)
                        .eventType("SHOP_SNAPSHOT")
                        .category("Shopping")
                        .source(currentShopName)
                        .target("None")
                        .skill("None")
                        .x(x).y(y).plane(plane).regionId(regionId)
                        .items(stockItems)
                        .build();

                executor.execute(() -> lootWriter.queueRecord(snapshot));
            }
        } else if (!shopCurrentlyOpen && isShopOpen) {
            isShopOpen = false;
        }

        // --- MONSTER EXAMINE LOGIC ---
        // Group ID 522 is the Monster Examine interface in OSRS
        boolean examineCurrentlyOpen = false;
        for (int i = 0; i < 10; i++) {
            Widget w = client.getWidget(522, i);
            if (w != null && !w.isHidden()) {
                examineCurrentlyOpen = true;
                break;
            }
        }

        // Cache the last speaking NPC's name for dialogue rewards
        Widget npcNameWidget = client.getWidget(231, 4);
        if (npcNameWidget != null && !npcNameWidget.isHidden() && npcNameWidget.getText() != null) {
            String cleanName = net.runelite.client.util.Text.removeTags(npcNameWidget.getText());
            if (!cleanName.isEmpty()) {
                lastDialogueNpc = cleanName;
            }
        }

        if (examineCurrentlyOpen && !isMonsterExamineOpen) {
            List<String> widgetTexts = new ArrayList<>();

            // Scrape the text from the interface
            for (int i = 0; i < 50; i++) {
                widgetTexts.addAll(extractAllText(client.getWidget(522, i)));
            }

            // TICK RACE CONDITION FIX: Wait until the server actually sends the stat text!
            boolean hasData = false;
            for (String text : widgetTexts) {
                String lower = text.toLowerCase();
                if (lower.contains("aggressive") || lower.contains("defensive") || lower.contains("hitpoints")) {
                    hasData = true;
                    break;
                }
            }

            if (hasData) {
                isMonsterExamineOpen = true;
                String targetMonster = (lockedCombatTarget != null && !lockedCombatTarget.isEmpty() && !lockedCombatTarget.equals("None"))
                        ? lockedCombatTarget
                        : "Unknown Monster";

                log.info("[LL Debug] Monster Examine Data Extracted for: " + targetMonster);
                gameMsg("[LL Debug] Scraped live stats for: " + targetMonster);

                Player localPlayer = client.getLocalPlayer();
                int x = 0, y = 0, plane = 0, regionId = 0;
                if (localPlayer != null) {
                    WorldPoint wp = localPlayer.getWorldLocation();
                    x = wp.getX();
                    y = wp.getY();
                    plane = wp.getPlane();
                    regionId = wp.getRegionID();
                }

                GameEvent examineEvent = GameEvent.builder()
                        .sessionId(sessionId)
                        .eventType("MONSTER_EXAMINE")
                        .category("Bestiary")
                        .source(targetMonster)
                        .target("None")
                        .skill("Magic")
                        .x(x).y(y).plane(plane).regionId(regionId)
                        // Shove the entire text array into the note column separated by |
                        .note(String.join("|", widgetTexts))
                        .build();

                executor.execute(() -> lootWriter.queueRecord(examineEvent));
            }
        } else if (!examineCurrentlyOpen && isMonsterExamineOpen) {
            isMonsterExamineOpen = false;
        }

        // --- TICK CLEANUP ---
        tickGainedItems.clear();
        tickLostItems.clear();

        // --- NEW: Quest Point Tracker (Moved here to fix Race Condition) ---
        int currentQp = client.getVarpValue(VarPlayer.QUEST_POINTS);

        if (previousQuestPoints != -1 && currentQp > previousQuestPoints) {
            int qpGained = currentQp - previousQuestPoints;
            previousQuestPoints = currentQp;

            log.info("[LL Debug] Gained {} Quest Points!", qpGained);
            gameMsg("[LL Debug] Logged Quest Point Gain: " + qpGained + " from " + lastFinishedQuest);

            Player localPlayer = client.getLocalPlayer();
            int x = 0, y = 0, plane = 0, regionId = 0;
            if (localPlayer != null) {
                WorldPoint wp = localPlayer.getWorldLocation();
                x = wp.getX();
                y = wp.getY();
                plane = wp.getPlane();
                regionId = wp.getRegionID();
            }

            List<DroppedItem> virtualQpItem = new ArrayList<>();
            virtualQpItem.add(new DroppedItem(-1, "Quest point", qpGained, 0, 0, 0));

            GameEvent qpEvent = GameEvent.builder()
                    .sessionId(sessionId)
                    .eventType("DIALOGUE_REWARD")
                    .category("Quests")
                    .source(lastFinishedQuest) // Now perfectly synced!
                    .target("None")
                    .skill("None")
                    .x(x).y(y).plane(plane).regionId(regionId)
                    .items(virtualQpItem)
                    .build();

            executor.execute(() -> lootWriter.queueRecord(qpEvent));

        } else if (previousQuestPoints == -1 || currentQp < previousQuestPoints) {
            previousQuestPoints = currentQp;
        }
    }

    private List<String> extractAllText(Widget w) {
        List<String> texts = new ArrayList<>();
        if (w == null) return texts;

        if (w.getText() != null && !w.getText().trim().isEmpty()) {
            String clean = Text.removeTags(w.getText()).trim();
            if (!clean.isEmpty()) {
                texts.add(clean);
            }
        }

        if (w.getDynamicChildren() != null) {
            for (Widget child : w.getDynamicChildren()) texts.addAll(extractAllText(child));
        }
        if (w.getStaticChildren() != null) {
            for (Widget child : w.getStaticChildren()) texts.addAll(extractAllText(child));
        }
        if (w.getNestedChildren() != null) {
            for (Widget child : w.getNestedChildren()) texts.addAll(extractAllText(child));
        }

        return texts;
    }

    private String getStandardAutocastSpell(int varp) {
        switch (varp) {
            case 3:
                return "Wind Strike";
            case 5:
                return "Water Strike";
            case 7:
                return "Earth Strike";
            case 9:
                return "Fire Strike";
            case 11:
                return "Wind Bolt";
            case 13:
                return "Water Bolt";
            case 15:
                return "Earth Bolt";
            case 17:
                return "Fire Bolt";
            case 19:
                return "Wind Blast";
            case 21:
                return "Water Blast";
            case 23:
                return "Earth Blast";
            case 25:
                return "Fire Blast";
            case 27:
                return "Wind Wave";
            case 29:
                return "Water Wave";
            case 31:
                return "Earth Wave";
            case 33:
                return "Fire Wave";
            case 35:
                return "Wind Surge";
            case 37:
                return "Water Surge";
            case 39:
                return "Earth Surge";
            case 41:
                return "Fire Surge";
            default:
                return "Unknown Autocast (" + varp + ")";
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        int currentVarp = client.getVarpValue(108);
        if (currentVarp != lastAutocastVarp) {
            lastAutocastVarp = currentVarp;
        }
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

        // Magic override logic...
        if (skill == Skill.MAGIC) {
            if (!lockedManualSpell.isEmpty()) {
                targetSource = lockedManualSpell;
            } else {
                int autoCastVarp = client.getVarpValue(108);
                if (autoCastVarp > 0) {
                    targetSource = getStandardAutocastSpell(autoCastVarp);
                } else {
                    targetSource = "Generic Magic";
                }
            }
        }

        // NEW: The Intelligent Skilling Override
        if (!isCombatSkill) {
            // If we have no idea what we are doing, OR if the memory is stale...
            if (targetSource == null || targetSource.equals("Unknown") || targetSource.isEmpty()) {

                // 1. UI Check: Is a Quest Complete scroll open? (Widget 277)
                if (client.getWidget(277, 0) != null && !client.getWidget(277, 0).isHidden()) {
                    targetSource = "Quest Reward";
                }
                // 2. UI Check: Is an Item/Lamp Sprite box open? (Widget 193)
                else if (client.getWidget(193, 0) != null && !client.getWidget(193, 0).isHidden()) {
                    targetSource = "XP Lamp / Reward";
                }
                // 3. Inventory Diff Check: Did we consume a resource? (Cooking, Fletching)
                else if (!tickLostItems.isEmpty()) {
                    targetSource = tickLostItems.get(0).getName();
                }
                // 4. Inventory Diff Check: Did we gain a resource? (Thieving, Mining empty-handed)
                else if (!tickGainedItems.isEmpty()) {
                    targetSource = tickGainedItems.get(0).getName();
                }
                // 5. Absolute Fallback
                else {
                    targetSource = "Activity";
                }
            }
        }

        // Clean up arrow strings if they still exist
        if (targetSource != null && targetSource.contains("->")) {
            String[] parts = targetSource.split("->");
            targetSource = parts[parts.length - 1].trim();
        }

        // One last safety net so we never log "Unknown" or empty strings to Supabase again
        String finalSource = (targetSource != null && !targetSource.equals("Unknown") && !targetSource.isEmpty())
                ? targetSource
                : "Activity";

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
                .source(finalSource) // FIX: Use our thoroughly checked variable
                .skill(skill.getName())
                .xpGained(xpDelta)
                .x(x).y(y).plane(plane).regionId(regionId)
                .build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        lastMenuOptionClicked = Text.removeTags(event.getMenuOption());
        String rawTarget = Text.removeTags(event.getMenuTarget());
        lastMenuTargetClicked = rawTarget.replaceAll("\\s*\\(level-\\d+\\)", "").trim();

        String opt = lastMenuOptionClicked.toLowerCase();

        if (opt.contains("trade") || opt.contains("talk-to") || opt.contains("buy") || opt.contains("sell")) {
            lockedShopTarget = lastMenuTargetClicked;
        }

        if (opt.contains("mine") || opt.contains("chop") || opt.contains("cut") ||
                opt.contains("net") || opt.contains("lure") || opt.contains("bait") ||
                opt.contains("cage") || opt.contains("harpoon") || opt.contains("fish")) {
            lockedSkillingTarget = lastMenuTargetClicked;
        } else if (opt.equals("cast")) {
            if (lastMenuTargetClicked.contains("->")) {
                String[] parts = lastMenuTargetClicked.split("->");
                lockedManualSpell = parts[0].trim();
                lockedCombatTarget = parts[1].trim();
            } else {
                lockedManualSpell = lastMenuTargetClicked.trim();
                lockedCombatTarget = "None";
            }
        } else if (opt.contains("attack")) {
            lockedCombatTarget = lastMenuTargetClicked;
            lockedManualSpell = "";
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

        int combatLevel = npc.getCombatLevel();

        List<DroppedItem> items = event.getItems().stream()
                .map(item -> new DroppedItem(
                        item.getId(),
                        itemManager.getItemComposition(item.getId()).getName(),
                        item.getQuantity(),
                        (itemManager.getItemPrice(item.getId()) * item.getQuantity()),
                        (itemManager.getItemComposition(item.getId()).getHaPrice() * item.getQuantity()),
                        (itemManager.getItemComposition(item.getId()).getPrice() * item.getQuantity())
                ))
                .collect(java.util.stream.Collectors.toList());

        GameEvent record = GameEvent.builder()
                .sessionId(sessionId)
                .eventType("NPC_DROP")
                .category("Combat")
                .source(npc.getName())
                .skill("None")
                .x(wp.getX())
                .y(wp.getY())
                .plane(wp.getPlane())
                .regionId(wp.getRegionID())
                .npcLevel(combatLevel)
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

    private void logRangedFire(int itemId, int qty) {
        String name = itemManager.getItemComposition(itemId).getName();
        List<DroppedItem> eventItems = List.of(new DroppedItem(
                itemId, name, qty,
                (itemManager.getItemPrice(itemId) * qty),
                (itemManager.getItemComposition(itemId).getHaPrice() * qty),
                (itemManager.getItemComposition(itemId).getPrice() * qty)
        ));

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
                .eventType("RANGED_FIRE")
                .category("Combat")
                .source(lockedCombatTarget != null && !lockedCombatTarget.isEmpty() ? lockedCombatTarget : "Enemy")
                .target(lockedCombatTarget != null && !lockedCombatTarget.isEmpty() ? lockedCombatTarget : "Enemy")
                .skill("Ranged")
                .x(x)
                .y(y)
                .plane(plane)
                .regionId(regionId)
                .items(eventItems)
                .build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    private void logAmmoPickup(int itemId, int qty) {
        String name = itemManager.getItemComposition(itemId).getName();
        List<DroppedItem> eventItems = List.of(new DroppedItem(
                itemId, name, qty,
                (itemManager.getItemPrice(itemId) * qty),
                (itemManager.getItemComposition(itemId).getHaPrice() * qty),
                (itemManager.getItemComposition(itemId).getPrice() * qty)
        ));

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
                .eventType("TAKE")
                .category("Misc")
                .source("Pickup")
                .target("None")
                .skill("Ranged")
                .x(x)
                .y(y)
                .plane(plane)
                .regionId(regionId)
                .items(eventItems)
                .build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() == InventoryID.INVENTORY.getId()) {
            ItemContainer container = event.getItemContainer();
            Item[] currentItems = container.getItems();

            List<DroppedItem> gainedItems = new ArrayList<>();
            List<DroppedItem> lostItems = new ArrayList<>(); // Track what we spent

            Map<Integer, Integer> currentCounts = new HashMap<>();
            for (Item item : currentItems) {
                if (item.getId() != -1) {
                    currentCounts.put(item.getId(), currentCounts.getOrDefault(item.getId(), 0) + item.getQuantity());
                }
            }

            Map<Integer, Integer> previousCounts = new HashMap<>();
            for (Item item : previousInventory) {
                if (item.getId() != -1) {
                    previousCounts.put(item.getId(), previousCounts.getOrDefault(item.getId(), 0) + item.getQuantity());
                }
            }

            // --- Detect Gained Items ---
            for (Map.Entry<Integer, Integer> entry : currentCounts.entrySet()) {
                int id = entry.getKey();
                int qty = entry.getValue();
                int prevQty = previousCounts.getOrDefault(id, 0);
                if (qty > prevQty) {
                    int diff = qty - prevQty;
                    DroppedItem item = createDroppedItem(id, diff);
                    gainedItems.add(item);
                    tickGainedItems.add(item); // NEW: Push to tick memory
                }
            }

            // --- Detect Lost Items ---
            for (Map.Entry<Integer, Integer> entry : previousCounts.entrySet()) {
                int id = entry.getKey();
                int prevQty = entry.getValue();
                int currentQty = currentCounts.getOrDefault(id, 0);
                if (currentQty < prevQty) {
                    int diff = prevQty - currentQty;
                    DroppedItem item = createDroppedItem(id, diff);
                    lostItems.add(item);
                    tickLostItems.add(item); // NEW: Push to tick memory
                }
            }

            // --- UI Check ---
            boolean isDialogueOpen = false;
            int[] dialogGroups = {231, 217, 193, 229, 277};
            for (int groupId : dialogGroups) {
                Widget w = client.getWidget(groupId, 0);
                if (w != null && !w.isHidden()) {
                    isDialogueOpen = true;
                    break;
                }
            }

            // Update memory if dialogue is open right now
            if (isDialogueOpen) {
                lastDialogueTick = client.getTickCount();
            }

            // Did we have a dialogue open within the last 3 ticks? (Protects against spacebar mashing)
            boolean wasDialogueRecentlyOpen = (client.getTickCount() - lastDialogueTick) <= 3;

            // Check for Shop/Bank/GE
            boolean isShopInterfaceOpen = (client.getWidget(300, 0) != null && !client.getWidget(300, 0).isHidden());
            boolean isBankOpen = (client.getWidget(12, 0) != null && !client.getWidget(12, 0).isHidden());
            boolean isGeOpen = (client.getWidget(465, 0) != null && !client.getWidget(465, 0).isHidden());

            // Use the memory check instead of the strict real-time check
            if ((wasDialogueRecentlyOpen || isShopInterfaceOpen) && !isBankOpen && !isGeOpen && !gainedItems.isEmpty()) {

                String eventType = isShopInterfaceOpen ? "SHOP_TRANSACTION" : "DIALOGUE_REWARD";

                // If the Quest complete scroll was open recently, it's a quest reward
                boolean isQuestReward = (client.getWidget(277, 0) != null && !client.getWidget(277, 0).isHidden())
                        || (lastFinishedQuest != null && !lastFinishedQuest.equals("Quest Reward"));

                String category = isShopInterfaceOpen ? "Shopping" : (isQuestReward ? "Quests" : "NPC Interaction");
                String source = isShopInterfaceOpen ? currentShopName : (category.equals("Quests") ? lastFinishedQuest : lastDialogueNpc);

                log.info("[LL Debug] {} detected from {}", eventType, source);
                gameMsg("[LL Debug] Logged " + eventType + " from " + source);

                Player localPlayer = client.getLocalPlayer();
                WorldPoint wp = localPlayer.getWorldLocation();

                GameEvent transaction = GameEvent.builder()
                        .sessionId(sessionId)
                        .eventType(eventType)
                        .category(category)
                        .source(source)
                        .target("None")
                        .skill("None")
                        .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                        .items(gainedItems)
                        .note("Spent: " + formatLostItems(lostItems))
                        .build();

                executor.execute(() -> lootWriter.queueRecord(transaction));
            }

            // Save state for next check
            for (int i = 0; i < 28; i++) {
                previousInventory[i] = i < currentItems.length ? currentItems[i] : new Item(-1, 0);
            }
        }
    }

    // Helper to clean up the code
    private DroppedItem createDroppedItem(int id, int qty) {
        String name = itemManager.getItemComposition(id).getName();
        int ge = itemManager.getItemPrice(id) * qty;
        int ha = itemManager.getItemComposition(id).getHaPrice() * qty;
        int base = itemManager.getItemComposition(id).getPrice() * qty;
        return new DroppedItem(id, name, qty, ge, ha, base);
    }

    private String formatLostItems(List<DroppedItem> lost) {
        if (lost.isEmpty()) return "Nothing";
        StringBuilder sb = new StringBuilder();
        for (DroppedItem i : lost) {
            sb.append(i.getQty()).append("x ").append(i.getName()).append(" ");
        }
        return sb.toString().trim();
    }

    private void sendXpRecord(String skillName, int xp) {
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();
        GameEvent record = GameEvent.builder()
                .sessionId(sessionId)
                .eventType("XP_GAIN")
                .category("Combat")
                .source("Activity")
                .skill(skillName)
                .x(wp.getX()).y(wp.getY()).plane(wp.getPlane())
                .items(List.of(new DroppedItem(0, skillName, xp, 0, 0, 0)))
                .build();
        executor.execute(() -> lootWriter.queueRecord(record));
    }
}