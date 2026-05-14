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
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.util.Text;

import javax.inject.Inject;
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

    // --- SHOPPING TRACKERS ---
    private boolean isShopOpen = false;
    private String lockedShopTarget = "Unknown Shop";
    private String currentShopName = "Unknown Shop";

    private int previousBoostedHp = -1;

    private Item[] previousInventory = new Item[28];
    private Item[] previousEquipment = new Item[14];

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

        for (int i = 0; i < 28; i++) previousInventory[i] = new Item(-1, 0);
        for (int i = 0; i < 14; i++) previousEquipment[i] = new Item(-1, 0);

        previousXpMap.clear();
        lastAutocastVarp = -1;
        previousBoostedHp = -1;
        isShopOpen = false;

        if (client.getGameState() == GameState.LOGGED_IN) {
            seedXpMap();
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

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            seedXpMap();
            lastAutocastVarp = client.getVarpValue(108);
            previousBoostedHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
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
        previousBoostedHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        lockedManualSpell = "";

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
            log.info("[LL Debug] Shop closed. Resetting isShopOpen flag.");
            isShopOpen = false;
        }
    }

    private String getStandardAutocastSpell(int varp) {
        switch (varp) {
            case 3: return "Wind Strike";
            case 5: return "Water Strike";
            case 7: return "Earth Strike";
            case 9: return "Fire Strike";
            case 11: return "Wind Bolt";
            case 13: return "Water Bolt";
            case 15: return "Earth Bolt";
            case 17: return "Fire Bolt";
            case 19: return "Wind Blast";
            case 21: return "Water Blast";
            case 23: return "Earth Blast";
            case 25: return "Fire Blast";
            case 27: return "Wind Wave";
            case 29: return "Water Wave";
            case 31: return "Earth Wave";
            case 33: return "Fire Wave";
            case 35: return "Wind Surge";
            case 37: return "Water Surge";
            case 39: return "Earth Surge";
            case 41: return "Fire Surge";
            default: return "Unknown Autocast (" + varp + ")";
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
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();

        if (event.getContainerId() == 94) {
            Item[] currentEquipment = event.getItemContainer().getItems();
            int[] slotsToCheck = {3, 13};

            for (int slot : slotsToCheck) {
                Item oldItem = previousEquipment.length > slot ? previousEquipment[slot] : new Item(-1, 0);
                Item newItem = currentEquipment.length > slot ? currentEquipment[slot] : new Item(-1, 0);

                int oldQty = oldItem.getId() != -1 ? oldItem.getQuantity() : 0;
                int newQty = newItem.getId() != -1 ? newItem.getQuantity() : 0;

                if (oldItem.getId() != -1 && (newItem.getId() == oldItem.getId() || newItem.getId() == -1) || (newItem.getId() != -1 && oldItem.getId() == newItem.getId())) {
                    int diff = oldQty - newQty;
                    int idToCheck = oldItem.getId() != -1 ? oldItem.getId() : newItem.getId();
                    String itemName = itemManager.getItemComposition(idToCheck).getName().toLowerCase();
                    boolean isActuallyAmmo = InventoryProcessor.isRuneOrAmmo(itemName);

                    if (isActuallyAmmo) {
                        if (diff > 0 && diff <= 2) {
                            logRangedFire(idToCheck, diff);
                        } else if (diff < 0 && "Take".equalsIgnoreCase(lastMenuOptionClicked)) {
                            logAmmoPickup(idToCheck, -diff);
                        }
                    }
                }
            }
            previousEquipment = currentEquipment.clone();
            return;
        }

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
                .shopName(currentShopName)
                .build();

        List<InventoryEvent> events = InventoryProcessor.invProcess(previousInventory, currentInventory, state, itemManager);

        int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        int hpDiff = currentHp - previousBoostedHp;
        int availableHpHealed = 0;
        if (previousBoostedHp != -1 && hpDiff > 0) availableHpHealed = hpDiff;
        previousBoostedHp = currentHp;

        String activeSpell = "Generic Magic";
        if (!lockedManualSpell.isEmpty()) {
            activeSpell = lockedManualSpell;
        } else {
            int autoCastVarp = client.getVarpValue(108);
            if (autoCastVarp > 0) activeSpell = getStandardAutocastSpell(autoCastVarp);
        }

        for (InventoryEvent invEvent : events) {
            int itemId = invEvent.itemId;
            int qty = invEvent.qty;
            String name = itemManager.getItemComposition(itemId).getName();

            String category = "Misc";
            String sourceName = "None";
            String eventTypeStr = invEvent.actionType.name();
            String skillName = null;
            Integer hpHealedForThisItem = null;

            switch (invEvent.actionType) {
                case GATHER_GAIN:
                    category = "Skilling";
                    skillName = SKILL_MAP.getOrDefault(lastActiveAnimation, "Unknown");
                    sourceName = (lockedSkillingTarget != null && !lockedSkillingTarget.isEmpty()) ? lockedSkillingTarget : "Unknown Source";
                    break;
                case BANK_DEPOSIT:
                case BANK_WITHDRAWAL:
                    category = "Banking";
                    sourceName = "Bank";
                    break;
                case SHOP_BUY:
                case SHOP_SELL:
                case SHOP_SPEND:
                case SHOP_RECEIVE:
                    category = "Shopping";
                    sourceName = invEvent.targetName;
                    break;
                case SPELL_CAST:
                    category = "Combat";
                    sourceName = activeSpell;
                    break;
                case RANGED_FIRE:
                    category = "Combat";
                    sourceName = invEvent.targetName;
                    break;
                case TAKE:
                    category = "Misc";
                    sourceName = "Pickup";
                    break;
                case SKILLING_CONSUME:
                case COMBAT_CONSUME:
                case CONSUME:
                    category = "Combat";
                    sourceName = invEvent.targetName;
                    if (availableHpHealed > 0) {
                        hpHealedForThisItem = availableHpHealed;
                        availableHpHealed = 0;
                    }
                    break;
                case DROP:
                case DESTROY:
                    category = "Misc";
                    break;
            }

            List<DroppedItem> eventItems = List.of(new DroppedItem(
                    itemId, name, qty,
                    (itemManager.getItemPrice(itemId) * qty),
                    (itemManager.getItemComposition(itemId).getHaPrice() * qty),
                    (itemManager.getItemComposition(itemId).getPrice() * qty)
            ));

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
                    .hpHealed(hpHealedForThisItem)
                    .build();

            executor.execute(() -> lootWriter.queueRecord(record));
        }
        previousInventory = currentInventory.clone();
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