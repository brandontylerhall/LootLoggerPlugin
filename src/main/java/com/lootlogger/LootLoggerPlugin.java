package com.lootlogger;

import com.google.inject.Provides;
import com.lootlogger.data.DroppedItem;
import com.lootlogger.data.GameEvent;
import com.lootlogger.io.LootWriter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
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
import java.util.*;

@Slf4j
@PluginDescriptor(name = "Loot to JSON")
public class LootLoggerPlugin extends Plugin {
    @Inject private Client client;
    @Inject private LootLoggerConfig config;
    @Inject private ItemManager itemManager;
    @Inject private ConfigManager configManager;
    @Inject private ClientThread clientThread;
    @Inject private java.util.concurrent.ScheduledExecutorService executor;

    @Provides
    LootLoggerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(LootLoggerConfig.class);
    }

    private String sessionId;
    private LootWriter lootWriter;

    // --- STATE VARIABLES ---
    private int lastActiveAnimation = -1;
    private String lastMenuOptionClicked = "";
    private String lastMenuTargetClicked = "";
    private String lockedSkillingTarget = "Unknown";
    private String lockedCombatTarget = "Unknown";
    private String lockedManualSpell = "";
    private int lastAutocastVarp = -1;
    private String lastDialogueNpc = "NPC / Dialogue";

    private final Map<String, Integer> activeQuestTicks = new HashMap<>();
    private int previousQuestPoints = -1;
    private String lastFinishedQuest = "Quest Reward";
    private int lastDialogueTick = 0;

    // --- NET DIFF ENGINE VARIABLES ---
    private Map<Integer, Integer> previousNetItems = new HashMap<>();
    private List<DroppedItem> lastNetGained = new ArrayList<>();
    private List<DroppedItem> lastNetLost = new ArrayList<>();

    private boolean hpInitialized = false;
    private int previousBoostedHp = -1;

    // --- UI TRACKERS ---
    private boolean isShopOpen = false;
    private boolean isMonsterExamineOpen = false;
    private String lockedShopTarget = "Unknown Shop";
    private String currentShopName = "Unknown Shop";

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

        previousNetItems.clear();
        previousXpMap.clear();
        questStates.clear();
        lastAutocastVarp = -1;
        hpInitialized = false;
        isShopOpen = false;

        if (client.getGameState() == GameState.LOGGED_IN) {
            seedXpMap();
            seedQuestMap();
            questSyncTicks = 3;
            lastAutocastVarp = client.getVarpValue(108);
        }
        log.info("[LL Debug] Plugin Started.");
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
        if (lootWriter != null) lootWriter.flush();
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
            questSyncTicks = 3;
            lastAutocastVarp = client.getVarpValue(108);
            previousQuestPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);

            hpInitialized = false; // Forces safe HP calibration on next tick
            previousNetItems.clear(); // Prevents fake item diffs on login
        } else {
            questSyncTicks = 0;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        // Safely calibrate HP on the very first tick after login/startup
        if (!hpInitialized) {
            previousBoostedHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
            hpInitialized = true;
        }

        checkQuestProgress();
        processNetItemDiff();

        lockedManualSpell = ""; // Clear manual cast at end of tick

        // --- SHOPPING LOGIC ---
        Widget shopIndicator = client.getWidget(300, 0);
        boolean shopCurrentlyOpen = (shopIndicator != null && !shopIndicator.isHidden());

        if (shopCurrentlyOpen && !isShopOpen) {
            List<DroppedItem> stockItems = getShopItems();
            if (!stockItems.isEmpty()) {
                isShopOpen = true;
                currentShopName = getActiveShopName();
                log.info("[LL Debug] Shop Snapshot Triggered for: {}", currentShopName);
                queueEvent("SHOP_SNAPSHOT", "Shopping", currentShopName, "None", stockItems, 0, "");
            }
        } else if (!shopCurrentlyOpen && isShopOpen) {
            isShopOpen = false;
        }

        // --- MONSTER EXAMINE LOGIC ---
        boolean examineCurrentlyOpen = false;
        for (int i = 0; i < 10; i++) {
            Widget w = client.getWidget(522, i);
            if (w != null && !w.isHidden()) {
                examineCurrentlyOpen = true;
                break;
            }
        }

        Widget npcNameWidget = client.getWidget(231, 4);
        if (npcNameWidget != null && !npcNameWidget.isHidden() && npcNameWidget.getText() != null) {
            String cleanName = Text.removeTags(npcNameWidget.getText());
            if (!cleanName.isEmpty()) lastDialogueNpc = cleanName;
        }

        if (examineCurrentlyOpen && !isMonsterExamineOpen) {
            List<String> widgetTexts = new ArrayList<>();
            for (int i = 0; i < 50; i++) widgetTexts.addAll(extractAllText(client.getWidget(522, i)));

            boolean hasData = widgetTexts.stream().map(String::toLowerCase)
                    .anyMatch(t -> t.contains("aggressive") || t.contains("defensive") || t.contains("hitpoints"));

            if (hasData) {
                isMonsterExamineOpen = true;
                String targetMonster = (lockedCombatTarget != null && !lockedCombatTarget.isEmpty() && !lockedCombatTarget.equals("None")) ? lockedCombatTarget : "Unknown Monster";
                queueEvent("MONSTER_EXAMINE", "Bestiary", targetMonster, "Magic", new ArrayList<>(), 0, String.join("|", widgetTexts));
            }
        } else if (!examineCurrentlyOpen && isMonsterExamineOpen) {
            isMonsterExamineOpen = false;
        }

        // --- QUEST POINT TRACKER ---
        int currentQp = client.getVarpValue(VarPlayer.QUEST_POINTS);
        if (previousQuestPoints != -1 && currentQp > previousQuestPoints) {
            int qpGained = currentQp - previousQuestPoints;
            previousQuestPoints = currentQp;
            queueEvent("DIALOGUE_REWARD", "Quests", lastFinishedQuest, "None", List.of(new DroppedItem(-1, "Quest point", qpGained, 0, 0, 0)), 0, "");
        } else if (previousQuestPoints == -1 || currentQp < previousQuestPoints) {
            previousQuestPoints = currentQp;
        }

        // CRITICAL FIX: Sync HP at the very end of the tick to capture real damage taken between eats
        previousBoostedHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
    }

    /**
     * THE NET-DIFF ENGINE
     */
    private void processNetItemDiff() {
        Map<Integer, Integer> currentNetItems = new HashMap<>();

        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv != null) {
            for (Item item : inv.getItems()) {
                if (item.getId() != -1) currentNetItems.put(item.getId(), currentNetItems.getOrDefault(item.getId(), 0) + item.getQuantity());
            }
        }

        ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
        if (eq != null) {
            for (Item item : eq.getItems()) {
                if (item.getId() != -1) currentNetItems.put(item.getId(), currentNetItems.getOrDefault(item.getId(), 0) + item.getQuantity());
            }
        }

        if (previousNetItems.isEmpty() && !currentNetItems.isEmpty()) {
            previousNetItems = currentNetItems;
            return;
        }

        List<DroppedItem> netGained = new ArrayList<>();
        List<DroppedItem> netLost = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : currentNetItems.entrySet()) {
            int id = entry.getKey();
            int qty = entry.getValue();
            int prev = previousNetItems.getOrDefault(id, 0);
            if (qty > prev) netGained.add(createDroppedItem(id, qty - prev));
        }
        for (Map.Entry<Integer, Integer> entry : previousNetItems.entrySet()) {
            int id = entry.getKey();
            int prev = entry.getValue();
            int current = currentNetItems.getOrDefault(id, 0);
            if (prev > current) netLost.add(createDroppedItem(id, prev - current));
        }

        lastNetGained = netGained;
        lastNetLost = netLost;

        // Push the sync BEFORE early exits to prevent bank chaos!
        previousNetItems = currentNetItems;

        // CRITICAL FIX: Ignore changes made while banking or depositing
        boolean isBankOpen = client.getWidget(12, 0) != null && !client.getWidget(12, 0).isHidden();
        boolean isGeOpen = client.getWidget(465, 0) != null && !client.getWidget(465, 0).isHidden();
        boolean isDepositBoxOpen = client.getWidget(192, 0) != null && !client.getWidget(192, 0).isHidden();
        if (isBankOpen || isGeOpen || isDepositBoxOpen) return;

        // Calculate total HP change for this tick
        int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        int tickHpHealed = Math.max(0, currentHp - previousBoostedHp);
        boolean healLogged = false; // Combo-eat interceptor

        // --- 1. PROCESS LOSSES (Consumables, Runes, Ammo) ---
        if (!netLost.isEmpty()) {
            for (DroppedItem lostItem : netLost) {
                ItemComposition comp = itemManager.getItemComposition(lostItem.getId());
                String nameLower = comp != null ? comp.getName().toLowerCase() : "";

                // Dynamically check client cache to see if item has an Eat/Drink option
                boolean isConsumable = false;
                if (comp != null && comp.getInventoryActions() != null) {
                    for (String action : comp.getInventoryActions()) {
                        if (action != null && (action.equalsIgnoreCase("Eat") || action.equalsIgnoreCase("Drink"))) {
                            isConsumable = true;
                            break;
                        }
                    }
                }

                if (isConsumable) {
                    // Give the total HP healed to the first item processed. 0 for the combo-eaten item.
                    int assignedHeal = healLogged ? 0 : tickHpHealed;
                    healLogged = true;
                    queueEvent("CONSUME", "Combat", "Activity", "Hitpoints", List.of(lostItem), assignedHeal, "");
                }
                else if (nameLower.endsWith(" rune")) {
                    String target = (lockedCombatTarget != null && !lockedCombatTarget.equals("None")) ? lockedCombatTarget : "Enemy";
                    queueEvent("SPELL_CAST", "Combat", target, "Magic", List.of(lostItem), 0, "");
                }
                else if (nameLower.contains("arrow") || nameLower.contains("bolt") || nameLower.contains("dart") || nameLower.contains("javelin") || nameLower.contains("throwing") || nameLower.contains("chinchompa")) {
                    String target = (lockedCombatTarget != null && !lockedCombatTarget.equals("None")) ? lockedCombatTarget : "Enemy";
                    queueEvent("RANGED_FIRE", "Combat", target, "Ranged", List.of(lostItem), 0, "");
                }
            }
        }

        // --- 2. PROCESS GAINS (Loot, Shop, Quests) ---
        if (!netGained.isEmpty()) {
            boolean isDialogueOpen = false;
            int[] dialogGroups = {231, 217, 193, 229, 277};
            for (int groupId : dialogGroups) {
                Widget w = client.getWidget(groupId, 0);
                if (w != null && !w.isHidden()) {
                    isDialogueOpen = true;
                    break;
                }
            }
            if (isDialogueOpen) lastDialogueTick = client.getTickCount();
            boolean wasDialogueRecentlyOpen = (client.getTickCount() - lastDialogueTick) <= 3;

            String opt = lastMenuOptionClicked != null ? lastMenuOptionClicked.toLowerCase() : "";

            if (isShopOpen || (client.getWidget(300, 0) != null && !client.getWidget(300, 0).isHidden())) {
                queueEvent("SHOP_TRANSACTION", "Shopping", currentShopName, "None", netGained, 0, "Spent: " + formatLostItems(netLost));
            } else if (wasDialogueRecentlyOpen) {
                boolean isQuestReward = (client.getWidget(277, 0) != null && !client.getWidget(277, 0).isHidden()) || (lastFinishedQuest != null && !lastFinishedQuest.equals("Quest Reward"));
                String category = isQuestReward ? "Quests" : "NPC Interaction";
                String source = category.equals("Quests") ? lastFinishedQuest : lastDialogueNpc;
                queueEvent("DIALOGUE_REWARD", category, source, "None", netGained, 0, "Spent: " + formatLostItems(netLost));
            } else if (opt.equals("take")) {
                queueEvent("TAKE", "Misc", "Pickup", "None", netGained, 0, "");
            }
        }
    }

    // --- REUSABLE EVENT DISPATCHER ---
    private void queueEvent(String eventType, String category, String source, String skill, List<DroppedItem> items, int hpHealed, String note) {
        Player localPlayer = client.getLocalPlayer();
        int x = 0, y = 0, plane = 0, regionId = 0;
        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            x = wp.getX(); y = wp.getY(); plane = wp.getPlane(); regionId = wp.getRegionID();
        }

        GameEvent event = GameEvent.builder()
                .sessionId(sessionId)
                .eventType(eventType)
                .category(category)
                .source(source)
                .target("None")
                .skill(skill)
                .x(x).y(y).plane(plane).regionId(regionId)
                .items(items)
                .hpHealed(hpHealed)
                .note(note)
                .build();

        executor.execute(() -> lootWriter.queueRecord(event));
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        int currentXp = event.getXp();

        if (!previousXpMap.containsKey(skill) || previousXpMap.get(skill) == 0) {
            previousXpMap.put(skill, currentXp);
            return;
        }

        int xpDelta = currentXp - previousXpMap.get(skill);
        previousXpMap.put(skill, currentXp);
        if (xpDelta <= 0) return;

        boolean isCombatSkill = skill == Skill.ATTACK || skill == Skill.STRENGTH ||
                skill == Skill.DEFENCE || skill == Skill.RANGED ||
                skill == Skill.MAGIC || skill == Skill.HITPOINTS;

        String category = isCombatSkill ? "Combat" : "Skilling";
        String targetSource = isCombatSkill ? lockedCombatTarget : lockedSkillingTarget;

        if (skill == Skill.MAGIC) {
            if (!lockedManualSpell.isEmpty()) targetSource = lockedManualSpell;
            else {
                int autoCastVarp = client.getVarpValue(108);
                targetSource = autoCastVarp > 0 ? getStandardAutocastSpell(autoCastVarp) : "Generic Magic";
            }
        }

        if (!isCombatSkill) {
            if (targetSource == null || targetSource.equals("Unknown") || targetSource.isEmpty()) {
                if (client.getWidget(277, 0) != null && !client.getWidget(277, 0).isHidden()) targetSource = "Quest Reward";
                else if (client.getWidget(193, 0) != null && !client.getWidget(193, 0).isHidden()) targetSource = "XP Lamp / Reward";
                else if (!lastNetLost.isEmpty()) targetSource = lastNetLost.get(0).getName();
                else if (!lastNetGained.isEmpty()) {
                    String itemName = lastNetGained.get(0).getName().toLowerCase();
                    targetSource = (itemName.contains("key") || itemName.contains("bone")) ? "Activity" : lastNetGained.get(0).getName();
                } else targetSource = "Activity";
            }
        }

        if (targetSource != null && targetSource.contains("->")) {
            String[] parts = targetSource.split("->");
            targetSource = parts[parts.length - 1].trim();
        }

        String finalSource = (targetSource != null && !targetSource.equals("Unknown") && !targetSource.isEmpty()) ? targetSource : "Activity";
        queueEvent("XP_GAIN", category, finalSource, skill.getName(), new ArrayList<>(), 0, "XP Gained: " + xpDelta);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        lastMenuOptionClicked = Text.removeTags(event.getMenuOption());
        lastMenuTargetClicked = Text.removeTags(event.getMenuTarget()).replaceAll("\\s*\\(level-\\d+\\)", "").trim();

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
    public void onNpcLootReceived(NpcLootReceived event) {
        List<DroppedItem> items = new ArrayList<>();
        event.getItems().forEach(item -> items.add(createDroppedItem(item.getId(), item.getQuantity())));
        queueEvent("NPC_DROP", "Combat", event.getNpc().getName(), "None", items, 0, "");
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() == client.getLocalPlayer() && client.getLocalPlayer().getAnimation() != -1) {
            lastActiveAnimation = client.getLocalPlayer().getAnimation();
        }
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        int currentVarp = client.getVarpValue(108);
        if (currentVarp != lastAutocastVarp) lastAutocastVarp = currentVarp;
    }

    // --- HELPER METHODS ---
    private void checkQuestProgress() {
        if (questSyncTicks > 0) {
            questSyncTicks--;
            if (questSyncTicks == 0) seedQuestMap();
            return;
        }

        for (Quest quest : Quest.values()) {
            QuestState currentState = quest.getState(client);
            QuestState previousState = questStates.get(quest);
            String questName = quest.getName();

            if (currentState == QuestState.IN_PROGRESS) {
                int ticks = activeQuestTicks.getOrDefault(questName, -1);
                if (ticks == -1) {
                    try {
                        String saved = configManager.getConfiguration("lootlogger", "qticks_" + quest.getId());
                        ticks = saved != null ? Integer.parseInt(saved) : 0;
                    } catch (Exception e) { ticks = 0; }
                }
                ticks++;
                activeQuestTicks.put(questName, ticks);
                if (ticks % 100 == 0) configManager.setConfiguration("lootlogger", "qticks_" + quest.getId(), ticks);
            }

            if (previousState != null && currentState != previousState) {
                questStates.put(quest, currentState);
                if (currentState == QuestState.IN_PROGRESS || currentState == QuestState.FINISHED) {
                    if (currentState == QuestState.FINISHED) lastFinishedQuest = questName;
                    int finalTicks = activeQuestTicks.getOrDefault(questName, 0);
                    queueEvent("QUEST_PROGRESS", "Quests", questName, "None", new ArrayList<>(), 0, "In-Game Ticks: " + finalTicks);
                }
            }
        }
    }

    private DroppedItem createDroppedItem(int id, int qty) {
        ItemComposition comp = itemManager.getItemComposition(id);
        String name = comp != null ? comp.getName() : "Unknown";
        int ge = itemManager.getItemPrice(id) * qty;
        int ha = comp != null ? comp.getHaPrice() * qty : 0;
        int base = comp != null ? comp.getPrice() * qty : 0;
        return new DroppedItem(id, name, qty, ge, ha, base);
    }

    private String formatLostItems(List<DroppedItem> lost) {
        if (lost.isEmpty()) return "Nothing";
        StringBuilder sb = new StringBuilder();
        for (DroppedItem i : lost) sb.append(i.getQty()).append("x ").append(i.getName()).append(" ");
        return sb.toString().trim();
    }

    private String getActiveShopName() {
        int[] primaryLocations = {1, 2, 78};
        for (int id : primaryLocations) {
            Widget w = client.getWidget(300, id);
            if (w != null && w.getText() != null && !w.getText().isEmpty()) {
                String clean = Text.removeTags(w.getText()).trim();
                if (clean.length() > 3 && !clean.equalsIgnoreCase("Close") && !clean.contains("Value check")) return clean;
            }
        }
        return lockedShopTarget.trim().isEmpty() ? "Unknown Shop" : lockedShopTarget + (lockedShopTarget.toLowerCase().contains("shop") ? "" : " Shop");
    }

    private List<DroppedItem> getShopItems() {
        List<DroppedItem> stockItems = new ArrayList<>();
        Widget itemGrid = client.getWidget(300, 16);
        if (itemGrid != null && itemGrid.getChildren() != null) {
            for (Widget itemWidget : itemGrid.getChildren()) {
                if (itemWidget.getItemId() > 0) {
                    int canonicalId = itemManager.canonicalize(itemWidget.getItemId());
                    stockItems.add(createDroppedItem(canonicalId, itemWidget.getItemQuantity()));
                }
            }
        }
        return stockItems;
    }

    private List<String> extractAllText(Widget w) {
        List<String> texts = new ArrayList<>();
        if (w == null) return texts;
        if (w.getText() != null && !w.getText().trim().isEmpty()) {
            String clean = Text.removeTags(w.getText()).trim();
            if (!clean.isEmpty()) texts.add(clean);
        }
        if (w.getDynamicChildren() != null) for (Widget child : w.getDynamicChildren()) texts.addAll(extractAllText(child));
        if (w.getStaticChildren() != null) for (Widget child : w.getStaticChildren()) texts.addAll(extractAllText(child));
        if (w.getNestedChildren() != null) for (Widget child : w.getNestedChildren()) texts.addAll(extractAllText(child));
        return texts;
    }

    private String getStandardAutocastSpell(int varp) {
        switch (varp) {
            case 3: return "Wind Strike"; case 5: return "Water Strike"; case 7: return "Earth Strike"; case 9: return "Fire Strike";
            case 11: return "Wind Bolt"; case 13: return "Water Bolt"; case 15: return "Earth Bolt"; case 17: return "Fire Bolt";
            case 19: return "Wind Blast"; case 21: return "Water Blast"; case 23: return "Earth Blast"; case 25: return "Fire Blast";
            case 27: return "Wind Wave"; case 29: return "Water Wave"; case 31: return "Earth Wave"; case 33: return "Fire Wave";
            case 35: return "Wind Surge"; case 37: return "Water Surge"; case 39: return "Earth Surge"; case 41: return "Fire Surge";
            default: return "Unknown Autocast (" + varp + ")";
        }
    }
}