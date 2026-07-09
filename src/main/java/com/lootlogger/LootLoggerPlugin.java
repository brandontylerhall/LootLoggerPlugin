package com.lootlogger;

import com.google.inject.Provides;
import com.lootlogger.data.RawEvent;
import com.lootlogger.data.RawItem;
import com.lootlogger.io.LootWriter;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
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

/**
 * "Dumb client" version of the Loot Logger plugin.
 * <p>
 * All intent-inference (Net-Diff, context-locking, XP attribution, quest state
 * machine) has been moved to the Next.js backend. This plugin collects raw game
 * signals and ships them as typed RawEvent messages to /api/ingest via LootWriter.
 * <p>
 * Wire format — eight event types:
 * TICK           — per-tick context: HP, widget flags, autocast varp, quest-points,
 * last menu option + inv/equip snapshots (only on dirty ticks).
 * MENU_CLICK     — raw option + target string on every MenuOptionClicked.
 * XP_UPDATE      — absolute XP per skill on every StatChanged.
 * NPC_LOOT       — loot items on NpcLootReceived.
 * SHOP_STOCK     — shop inventory when the shop widget first opens.
 * EXAMINE_TEXT   — raw widget-522 text when monster examine opens.
 * QUEST_STATE    — quest id/name/newState on every QuestState change.
 * STATS_SNAPSHOT — real skill levels + total/combat level on login and every 10 min.
 */
@Slf4j
@PluginDescriptor(name = "Loot to JSON")
public class LootLoggerPlugin extends Plugin {

    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private LootLoggerConfig config;
    @Inject
    private ItemManager itemManager;
    @Inject
    private ConfigManager configManager;
    @Inject
    private java.util.concurrent.ScheduledExecutorService executor;

    @Provides
    LootLoggerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(LootLoggerConfig.class);
    }

    // --- SESSION ---
    private String sessionId;
    private LootWriter lootWriter;

    // --- MENU CLICK SNAPSHOT ---
    // Kept client-side so the TICK payload can include "what was last clicked at
    // the moment this tick fired" — the same timing the old processNetItemDiff used.
    private String lastMenuOptionRaw = "";
    private String lastMenuTargetRaw = "";

    // --- QUEST STATE CHANGE DETECTION ---
    private final Map<Quest, QuestState> questStates = new EnumMap<>(Quest.class);
    private int questSyncTicks = 0; // 3-tick delay after login before change-detection is live

    // --- INVENTORY / EQUIPMENT DIRTY FLAGS ---
    private boolean invDirty = false;
    private boolean equipDirty = false;

    // --- WIDGET EDGE TRACKING ---
    private boolean prevShopOpen = false;
    private boolean prevExamineOpen = false;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void startUp() throws Exception {
        sessionId = java.util.UUID.randomUUID().toString();
        lootWriter = new LootWriter();
        lootWriter.configure(config.backendUrl(), config.apiKey());
        lootWriter.init();

        // Force a full snapshot on the very first post-login tick.
        invDirty = true;
        equipDirty = true;
        prevShopOpen = false;
        prevExamineOpen = false;

        if (client.getGameState() == GameState.LOGGED_IN) {
            seedQuestMap();
            questSyncTicks = 3;
        }
        log.info("[LL] Startup: session={}, gameState={}, endpoint={}/api/ingest, apiKeySet={}",
                sessionId, client.getGameState(), config.backendUrl(),
                config.apiKey() != null && !config.apiKey().isBlank());
    }

    @Override
    public void shutDown() throws Exception {
        if (lootWriter != null) {
            lootWriter.flush();
            lootWriter.close();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (event.getGroup().equals("lootlogger") && lootWriter != null) {
            lootWriter.configure(config.backendUrl(), config.apiKey());
        }
    }

    @Schedule(period = 10, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void submitBatch() {
        if (lootWriter != null) lootWriter.flush();
    }

    /**
     * Periodic STATS_SNAPSHOT. @Schedule runs off the client thread, so the
     * client reads are marshalled back onto it via ClientThread.
     */
    @Schedule(period = 10, unit = ChronoUnit.MINUTES, asynchronous = true)
    public void submitStatsSnapshot() {
        clientThread.invoke(() -> {
            if (client.getGameState() == GameState.LOGGED_IN) {
                emitStatsSnapshot();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Event subscribers
    // -------------------------------------------------------------------------

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            seedQuestMap();
            questSyncTicks = 3;
            // Force snapshots so the server can calibrate its state on login.
            invDirty = true;
            equipDirty = true;
            prevShopOpen = false;
            prevExamineOpen = false;
            emitStatsSnapshot();
        }
    }

    /**
     * Sets dirty flags so the next TICK includes a full inv/equip snapshot.
     * We only ship the heavy container arrays when they actually changed.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        int id = event.getContainerId();
        if (id == InventoryID.INVENTORY.getId()) {
            invDirty = true;
        } else if (id == InventoryID.EQUIPMENT.getId()) {
            equipDirty = true;
        }
    }

    /**
     * Core per-tick event. Emits TICK first (baseline context), then any
     * derived events that fire within the same tick (quest changes, shop open,
     * examine open). All share the same clientTick value.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        // --- Build TICK payload ---
        TickPayload tick = new TickPayload();
        tick.hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        tick.questPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);
        tick.autocastVarp = client.getVarpValue(108);
        tick.lastMenuOption = lastMenuOptionRaw;
        tick.lastMenuTarget = lastMenuTargetRaw;

        // Raw text of the currently-active dialogue NPC widget (231,4).
        // Server uses this to set lastDialogueNpc for NPC DIALOGUE_REWARD attribution.
        Widget npcNameWidget = client.getWidget(231, 4);
        if (npcNameWidget != null && !npcNameWidget.isHidden() && npcNameWidget.getText() != null) {
            String cleanName = Text.removeTags(npcNameWidget.getText()).trim();
            if (!cleanName.isEmpty()) tick.dialogueNpcText = cleanName;
        }

        tick.widgets = new WidgetFlags();
        tick.widgets.bank = isWidgetVisible(12, 0);
        tick.widgets.ge = isWidgetVisible(465, 0);
        tick.widgets.deposit = isWidgetVisible(192, 0);
        tick.widgets.shop = isWidgetVisible(300, 0);
        tick.widgets.dialogue231 = isWidgetVisible(231, 0);
        tick.widgets.dialogue217 = isWidgetVisible(217, 0);
        tick.widgets.dialogue193 = isWidgetVisible(193, 0);
        tick.widgets.dialogue229 = isWidgetVisible(229, 0);
        tick.widgets.dialogue277 = isWidgetVisible(277, 0);

        if (invDirty) {
            tick.inv = snapshotContainer(InventoryID.INVENTORY);
            invDirty = false;
        }
        if (equipDirty) {
            tick.equip = snapshotContainer(InventoryID.EQUIPMENT);
            equipDirty = false;
        }

        emit("TICK", tick);

        // --- Quest state change detection ---
        checkQuestProgress();

        // --- Shop open edge: emit SHOP_STOCK when shop widget just opened ---
        handleShopEdge(tick.widgets.shop);

        // --- Monster examine open edge: emit EXAMINE_TEXT ---
        handleExamineEdge();
    }

    /**
     * Ships raw option + target strings. Server strips tags and runs context-locking.
     */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        lastMenuOptionRaw = event.getMenuOption();
        lastMenuTargetRaw = event.getMenuTarget();

        MenuClickPayload payload = new MenuClickPayload();
        payload.option = lastMenuOptionRaw;
        payload.target = lastMenuTargetRaw;
        emit("MENU_CLICK", payload);
    }

    /**
     * Ships absolute XP value. Server computes delta and resolves source.
     */
    @Subscribe
    public void onStatChanged(StatChanged event) {
        XpUpdatePayload payload = new XpUpdatePayload();
        payload.skill = event.getSkill().getName();
        payload.xp = event.getXp();
        emit("XP_UPDATE", payload);
    }

    /**
     * Ships enriched loot items. Server emits NPC_DROP.
     */
    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        NpcLootPayload payload = new NpcLootPayload();
        payload.npcName = event.getNpc().getName();
        payload.npcLevel = event.getNpc().getCombatLevel();
        payload.items = new ArrayList<>();
        event.getItems().forEach(item -> payload.items.add(enrichItem(item.getId(), item.getQuantity())));
        emit("NPC_LOOT", payload);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void seedQuestMap() {
        questStates.clear();
        for (Quest quest : Quest.values()) {
            questStates.put(quest, quest.getState(client));
        }
    }

    /**
     * Detects quest state transitions and emits QUEST_STATE deltas.
     * The 3-tick questSyncTicks guard prevents false transitions on login
     * (the game updates quest varps for several ticks after login).
     * The server maintains in-progress tick counters on its side.
     */
    private void checkQuestProgress() {
        if (questSyncTicks > 0) {
            questSyncTicks--;
            if (questSyncTicks == 0) seedQuestMap();
            return;
        }

        for (Quest quest : Quest.values()) {
            QuestState currentState = quest.getState(client);
            QuestState previousState = questStates.get(quest);
            if (previousState != null && currentState != previousState) {
                questStates.put(quest, currentState);
                QuestStatePayload payload = new QuestStatePayload();
                payload.questId = quest.getId();
                payload.questName = quest.getName();
                payload.newState = currentState.name();
                emit("QUEST_STATE", payload);
            }
        }
    }

    /**
     * Emits SHOP_STOCK on the tick the shop widget first becomes visible.
     * Client-side edge detection is necessary here because only the client
     * can read the shop's item grid widget.
     */
    private void handleShopEdge(boolean shopOpen) {
        if (shopOpen && !prevShopOpen) {
            ShopStockPayload payload = new ShopStockPayload();
            payload.shopNameCandidates = getShopNameCandidates();
            payload.items = getShopItems();
            emit("SHOP_STOCK", payload);
        }
        prevShopOpen = shopOpen;
    }

    /**
     * Emits EXAMINE_TEXT on the tick the monster-examine widget (522) first opens.
     * Only emits when the text contains actual stat data (aggressive/defensive/hitpoints).
     */
    private void handleExamineEdge() {
        boolean examineOpen = false;
        for (int i = 0; i < 10; i++) {
            if (isWidgetVisible(522, i)) {
                examineOpen = true;
                break;
            }
        }

        if (examineOpen && !prevExamineOpen) {
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < 50; i++) texts.addAll(extractAllText(client.getWidget(522, i)));

            boolean hasStats = texts.stream().map(String::toLowerCase)
                    .anyMatch(t -> t.contains("aggressive") || t.contains("defensive") || t.contains("hitpoints"));

            if (hasStats) {
                ExamineTextPayload payload = new ExamineTextPayload();
                payload.texts = texts;
                emit("EXAMINE_TEXT", payload);
            }
        }
        prevExamineOpen = examineOpen;
    }

    /**
     * Emits a STATS_SNAPSHOT with real (unboosted) levels for every skill.
     * Must run on the client thread.
     */
    private void emitStatsSnapshot() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return;

        Map<String, Integer> skillLevels = new LinkedHashMap<>();
        for (Skill skill : Skill.values()) {
            skillLevels.put(skill.getName(), client.getRealSkillLevel(skill));
        }

        StatsSnapshotPayload payload = new StatsSnapshotPayload();
        payload.setSkillLevels(skillLevels);
        payload.setTotalLevel(client.getTotalLevel());
        payload.setCombatLevel(localPlayer.getCombatLevel());
        emit("STATS_SNAPSHOT", payload);
    }

    /**
     * Stamps world coords, wraps payload in RawEvent, hands off to LootWriter asynchronously.
     */
    private void emit(String type, Object payload) {
        Player localPlayer = client.getLocalPlayer();
        int x = 0, y = 0, plane = 0, regionId = 0;
        if (localPlayer != null) {
            WorldPoint wp = localPlayer.getWorldLocation();
            x = wp.getX();
            y = wp.getY();
            plane = wp.getPlane();
            regionId = wp.getRegionID();
        }

        RawEvent event = RawEvent.builder()
                .sessionId(sessionId)
                .clientTick(client.getTickCount())
                .type(type)
                .x(x).y(y).plane(plane).regionId(regionId)
                .payload(payload)
                .build();

        executor.execute(() -> lootWriter.queueRecord(event));
    }

    /**
     * Returns a full item list for the given container; null-safe.
     */
    private List<RawItem> snapshotContainer(InventoryID containerId) {
        List<RawItem> items = new ArrayList<>();
        ItemContainer container = client.getItemContainer(containerId);
        if (container == null) return items;
        for (Item item : container.getItems()) {
            if (item.getId() != -1) {
                items.add(enrichItem(item.getId(), item.getQuantity()));
            }
        }
        return items;
    }

    /**
     * Looks up ItemComposition metadata and returns a RawItem with unit prices.
     * This is a data lookup, not classification — the server classifies based
     * on the returned invActions (Eat/Drink) and name (rune, ammo suffix).
     */
    private RawItem enrichItem(int id, int qty) {
        ItemComposition comp = itemManager.getItemComposition(id);
        String name = comp != null ? comp.getName() : "Unknown";

        List<String> actions = new ArrayList<>();
        if (comp != null && comp.getInventoryActions() != null) {
            for (String action : comp.getInventoryActions()) {
                if (action != null) actions.add(action);
            }
        }

        int geUnit = itemManager.getItemPrice(id);
        int haUnit = comp != null ? comp.getHaPrice() : 0;
        int baseUnit = comp != null ? comp.getPrice() : 0;

        return new RawItem(id, qty, name, actions, geUnit, haUnit, baseUnit);
    }

    /**
     * Reads the raw text candidates from the shop title widgets (300, {1,2,78}).
     * Server applies the name-resolution logic (fallback to last locked shop target).
     */
    private List<String> getShopNameCandidates() {
        List<String> candidates = new ArrayList<>();
        int[] slots = {1, 2, 78};
        for (int slot : slots) {
            Widget w = client.getWidget(300, slot);
            if (w != null && w.getText() != null && !w.getText().isEmpty()) {
                String clean = Text.removeTags(w.getText()).trim();
                if (!clean.isEmpty()) candidates.add(clean);
            }
        }
        return candidates;
    }

    /**
     * Reads enriched items from the shop item grid (300, 16).
     */
    private List<RawItem> getShopItems() {
        List<RawItem> stockItems = new ArrayList<>();
        Widget itemGrid = client.getWidget(300, 16);
        if (itemGrid != null && itemGrid.getChildren() != null) {
            for (Widget itemWidget : itemGrid.getChildren()) {
                if (itemWidget.getItemId() > 0) {
                    int canonicalId = itemManager.canonicalize(itemWidget.getItemId());
                    stockItems.add(enrichItem(canonicalId, itemWidget.getItemQuantity()));
                }
            }
        }
        return stockItems;
    }

    /**
     * Recursively scrapes all non-empty text from a widget and its children.
     */
    private List<String> extractAllText(Widget w) {
        List<String> texts = new ArrayList<>();
        if (w == null) return texts;
        if (w.getText() != null && !w.getText().trim().isEmpty()) {
            String clean = Text.removeTags(w.getText()).trim();
            if (!clean.isEmpty()) texts.add(clean);
        }
        if (w.getDynamicChildren() != null)
            for (Widget child : w.getDynamicChildren()) texts.addAll(extractAllText(child));
        if (w.getStaticChildren() != null)
            for (Widget child : w.getStaticChildren()) texts.addAll(extractAllText(child));
        if (w.getNestedChildren() != null)
            for (Widget child : w.getNestedChildren()) texts.addAll(extractAllText(child));
        return texts;
    }

    private boolean isWidgetVisible(int groupId, int childId) {
        Widget w = client.getWidget(groupId, childId);
        return w != null && !w.isHidden();
    }

    // =========================================================================
    // Payload DTOs — serialised as JSON by Gson via RawEvent.payload.
    // The server uses the `type` discriminator to deserialise to the correct type.
    // =========================================================================

    /**
     * Emitted every GameTick. Heavy inv/equip arrays only present on dirty ticks.
     */
    public static class TickPayload {
        public int hp;
        public int questPoints;
        public int autocastVarp;         // varp 108 — server decodes autocast spell name
        public String lastMenuOption;    // raw (HTML tags intact) — server strips
        public String lastMenuTarget;    // raw — server strips + removes (level-NN)
        public String dialogueNpcText;   // stripped text of widget(231,4); null when no NPC dialogue open
        public WidgetFlags widgets;
        public List<RawItem> inv;        // null when inventory unchanged since last snapshot
        public List<RawItem> equip;      // null when equipment unchanged since last snapshot
    }

    /**
     * Open/hidden state of the widgets the server needs for Net-Diff classification.
     */
    public static class WidgetFlags {
        public boolean bank;        // widget(12,0)  — suppress net-diff
        public boolean ge;          // widget(465,0) — suppress net-diff
        public boolean deposit;     // widget(192,0) — suppress net-diff
        public boolean shop;        // widget(300,0) — gain → SHOP_TRANSACTION
        public boolean dialogue231; // NPC dialogue  — gain → DIALOGUE_REWARD
        public boolean dialogue217; // Options        — gain → DIALOGUE_REWARD
        public boolean dialogue193; // XP lamp        — non-combat XP source
        public boolean dialogue229; // Level up       — gain → DIALOGUE_REWARD
        public boolean dialogue277; // Quest complete — quest reward items
    }

    /**
     * Emitted on every MenuOptionClicked. Raw strings — server strips and locks context.
     */
    public static class MenuClickPayload {
        public String option;
        public String target;
    }

    /**
     * Emitted on every StatChanged. Absolute XP — server computes delta and resolves source.
     */
    public static class XpUpdatePayload {
        public String skill;
        public int xp;
    }

    /**
     * Emitted on NpcLootReceived. Server emits NPC_DROP classified event.
     */
    public static class NpcLootPayload {
        public String npcName;
        public int npcLevel;
        public List<RawItem> items;
    }

    /**
     * Emitted when the shop widget (300,0) first opens.
     * shopNameCandidates: raw texts from title widgets (300,{1,2,78}) —
     * server applies name-resolution logic + locked-shop-target fallback.
     */
    public static class ShopStockPayload {
        public List<String> shopNameCandidates;
        public List<RawItem> items;
    }

    /**
     * Emitted when the monster examine widget (522) first opens and contains
     * stat data. Server attributes to the current locked combat target.
     */
    public static class ExamineTextPayload {
        public List<String> texts;
    }

    /**
     * Emitted when a quest transitions between NOT_STARTED / IN_PROGRESS / FINISHED.
     * Server maintains in-progress tick counters (replacing the old qticks_* config).
     */
    public static class QuestStatePayload {
        public int questId;
        public String questName;
        public String newState; // "NOT_STARTED" | "IN_PROGRESS" | "FINISHED"
    }

    /**
     * Emitted on login and every 10 minutes. Server passes it through as a
     * classified STATS_SNAPSHOT row; get_stats_at_time() correlates kill
     * sessions with the player's levels at that moment.
     */
    @Data
    public static class StatsSnapshotPayload {
        private Map<String, Integer> skillLevels; // skill name → real (unboosted) level
        private int totalLevel;
        private int combatLevel;
    }
}