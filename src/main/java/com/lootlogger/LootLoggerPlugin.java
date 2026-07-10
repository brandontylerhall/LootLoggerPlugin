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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Dumb client" version of the Loot Logger plugin.
 * <p>
 * All intent-inference (Net-Diff, context-locking, XP attribution, quest state
 * machine) has been moved to the Next.js backend. This plugin collects raw game
 * signals and ships them as typed RawEvent messages to /api/ingest via LootWriter.
 * <p>
 * Wire format — nine event types:
 * TICK           — per-tick context: HP, widget flags, autocast varp, quest-points,
 * last menu option + inv/equip snapshots (only on dirty ticks).
 * MENU_CLICK     — raw option + target string on every MenuOptionClicked.
 * XP_UPDATE      — absolute XP per skill on every StatChanged.
 * NPC_LOOT       — loot items on NpcLootReceived.
 * SHOP_STOCK     — shop inventory when the shop widget first opens.
 * EXAMINE_TEXT   — raw widget-522 text when monster examine opens.
 * QUEST_STATE    — quest id/name/newState on every QuestState change.
 * STATS_SNAPSHOT — real skill levels + total/combat level (+ membership) on login and every 10 min.
 * BANK_SNAPSHOT  — full bank contents whenever the bank container changes while open.
 * QUEST_SNAPSHOT — full quest-state list + quest points once per login (after quest varps settle).
 * CHEST_LOOT     — interface-delivered rewards (Barrows/CoX/ToB/ToA chests, clue caskets) with
 *                  items; or an items-empty marker (Wintertodt/Tempoross/GOTR) telling the server
 *                  to attribute the next inventory gains to the named content.
 * COLLECTION_LOG — item name whenever a new collection log slot is filled (popup script or chat).
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
    private boolean shopCaptured = false; // one SHOP_STOCK per shop visit
    private boolean prevExamineOpen = false;

    // --- CHEST / MINIGAME LOOT (mirrors the official LootTrackerPlugin mechanics) ---
    // Handled content: Barrows, Chambers of Xeric, Theatre of Blood, Tombs of Amascut,
    // Clue Scroll caskets (all tiers), Wintertodt supply crate/reward cart,
    // Tempoross reward pool, Guardians of the Rift, and collection log slots.
    //
    // TODO (deferred): the following official-loot-tracker content types are intentionally
    // NOT handled yet and can be added incrementally using the same CHEST_LOOT event:
    // HAM chest, Larran's chests, Rogues' chest, shade chests, herbiboar, birdhouses,
    // pickpockets, seed pack, Kingdom of Miscellania, Fishing Trawler, drift net, LMS,
    // Soul Wars spoils, Barbarian Assault high gamble, Unsired, Lunar Chest,
    // Fortis Colosseum, ore/gem/salvage packs, hallowed sack, Doom of Mokhaiotl,
    // and item-triggered caskets/bird nests.
    private static final Pattern CLUE_SCROLL_PATTERN =
            Pattern.compile("You have completed [0-9]+ ([a-z]+) Treasure Trails?\\.");
    private static final Pattern COLLECTION_LOG_CHAT_PATTERN =
            Pattern.compile("New item added to your collection log: (.*)");
    private static final String MINIGAME_LOOT_STRING = "You found some loot: ";
    private static final String COLLECTION_POPUP_PREFIX = "New item:";
    private static final int THEATRE_OF_BLOOD_REGION = 12867;
    private static final int WINTERTODT_REGION = 6461;
    private static final int TEMPOROSS_REGION = 12588;
    private static final int GUARDIANS_OF_THE_RIFT_REGION = 14484;
    private static final int CLUE_PENDING_TIMEOUT_TICKS = 10; // mirrors official INVCHANGE_TIMEOUT

    private boolean chestLooted = false;              // one reward chest per raid instance
    private boolean lastLoadingIntoInstance = false;  // chestLooted resets when this flips on LOADING
    private String pendingClueTier = null;            // set by clue chat msg; consumed by TRAIL_REWARDINV change
    private int pendingClueTick = -1;
    private boolean collectionPopupStarted = false;   // NOTIFICATION_START seen, awaiting NOTIFICATION_DELAY

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
        shopCaptured = false;
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
        // Raid chest latch resets when loading into/out of an instance
        // (same mechanic as the official loot tracker).
        if (event.getGameState() == GameState.LOADING) {
            boolean inInstance = client.isInInstancedRegion();
            if (inInstance != lastLoadingIntoInstance) {
                lastLoadingIntoInstance = inInstance;
                chestLooted = false;
            }
        }

        if (event.getGameState() == GameState.LOGGED_IN) {
            seedQuestMap();
            questSyncTicks = 3;
            // Force snapshots so the server can calibrate its state on login.
            invDirty = true;
            equipDirty = true;
            shopCaptured = false;
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
        } else if (id == InventoryID.BANK.getId()) {
            // The BANK container populates/changes only while the bank is open —
            // the reliable point to capture a full snapshot.
            emitBankSnapshot();
        } else if (id == net.runelite.api.gameval.InventoryID.TRAIL_REWARDINV) {
            // Clue casket rewards land in the shared trail-reward container after
            // the "You have completed N <tier> Treasure Trails." chat message.
            // (Barrows uses this container too, but via onWidgetLoaded — it never
            // sets pendingClueTier, so there is no double emission.)
            if (pendingClueTier != null
                    && client.getTickCount() - pendingClueTick <= CLUE_PENDING_TIMEOUT_TICKS) {
                emitChestLoot(pendingClueTier, event.getItemContainer());
            }
            pendingClueTier = null;
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

    /**
     * Interface-delivered rewards: read the reward ItemContainer when the chest
     * interface loads (same interface/container pairs as the official loot tracker).
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        final String source;
        final ItemContainer container;

        // gameval classes are fully qualified: the wildcard import of
        // net.runelite.api.* brings in the legacy InventoryID enum (name clash).
        switch (event.getGroupId()) {
            case net.runelite.api.gameval.InterfaceID.BARROWS_REWARD:
                source = "Barrows";
                container = client.getItemContainer(net.runelite.api.gameval.InventoryID.TRAIL_REWARDINV);
                break;
            case net.runelite.api.gameval.InterfaceID.RAIDS_REWARDS:
                if (chestLooted) return;
                source = "Chambers of Xeric";
                container = client.getItemContainer(net.runelite.api.gameval.InventoryID.RAIDS_REWARDS);
                chestLooted = true;
                break;
            case net.runelite.api.gameval.InterfaceID.TOB_CHESTS:
                if (chestLooted || !inTobChestRegion()) return;
                source = "Theatre of Blood";
                container = client.getItemContainer(net.runelite.api.gameval.InventoryID.TOB_CHESTS);
                chestLooted = true;
                break;
            case net.runelite.api.gameval.InterfaceID.TOA_CHESTS:
                if (chestLooted) return;
                // TODO (deferred): raid level / team size / damage varbit metadata
                source = "Tombs of Amascut";
                container = client.getItemContainer(net.runelite.api.gameval.InventoryID.TOA_CHESTS);
                chestLooted = true;
                break;
            default:
                return;
        }

        emitChestLoot(source, container);
    }

    private boolean inTobChestRegion() {
        // Instanced content: map the local position back to the template region.
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return false;
        int region = WorldPoint.fromLocalInstance(client, localPlayer.getLocalLocation()).getRegionID();
        return region == THEATRE_OF_BLOOD_REGION;
    }

    /**
     * Chat-triggered loot: clue casket tier locking, region-gated minigame loot
     * markers, and the collection-log chat fallback.
     */
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        ChatMessageType type = event.getType();
        if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM
                && type != ChatMessageType.MESBOX) {
            return;
        }

        final String message = Text.removeTags(event.getMessage());

        // Clue casket: the completion message names the tier; the loot arrives via
        // the TRAIL_REWARDINV container change (handled in onItemContainerChanged).
        final Matcher clue = CLUE_SCROLL_PATTERN.matcher(message);
        if (clue.find()) {
            String tier = clue.group(1);
            pendingClueTier = "Clue Scroll (" + Character.toUpperCase(tier.charAt(0)) + tier.substring(1) + ")";
            pendingClueTick = client.getTickCount();
            return;
        }

        // Collection log chat fallback — only when the game setting routes the
        // notification to chat (varbit == 1); popup mode is handled by
        // onScriptPreFired, so the two paths never double-emit (mirrors Dink).
        final Matcher clog = COLLECTION_LOG_CHAT_PATTERN.matcher(message);
        if (clog.find()
                && client.getVarbitValue(net.runelite.api.gameval.VarbitID.OPTION_COLLECTION_NEW_ITEM) == 1) {
            emitCollectionLog(clog.group(1).trim());
            return;
        }

        // Region-gated minigame loot ("You found some loot: …" has no reward
        // container — the items appear directly in the inventory).
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return;
        final int regionId = localPlayer.getWorldLocation().getRegionID();

        if (regionId == TEMPOROSS_REGION && message.startsWith(MINIGAME_LOOT_STRING)) {
            emitChestLootMarker("Reward pool (Tempoross)");
        } else if (regionId == GUARDIANS_OF_THE_RIFT_REGION && message.startsWith(MINIGAME_LOOT_STRING)) {
            emitChestLootMarker("Guardians of the Rift");
        } else if (regionId == WINTERTODT_REGION && message.contains(MINIGAME_LOOT_STRING)) {
            // Region-gated, so crates opened outside Wintertodt are missed, and the
            // crate cannot be told apart from the reward cart — both limitations
            // shared with the official tracker.
            emitChestLootMarker("Supply crate (Wintertodt)");
        }
    }

    /**
     * Collection log popup detection (Dink-verified mechanism): NOTIFICATION_START
     * flags an incoming popup, NOTIFICATION_DELAY exposes its title/body VarcStrings.
     * Immune to chat filters, unlike the chat-message fallback above.
     */
    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        int scriptId = event.getScriptId();
        if (scriptId == ScriptID.NOTIFICATION_START) {
            collectionPopupStarted = true;
        } else if (scriptId == ScriptID.NOTIFICATION_DELAY) {
            String title = client.getVarcStrValue(net.runelite.api.gameval.VarClientID.NOTIFICATION_TITLE);
            if (collectionPopupStarted && "Collection log".equalsIgnoreCase(title)) {
                String body = client.getVarcStrValue(net.runelite.api.gameval.VarClientID.NOTIFICATION_MAIN);
                if (body != null) {
                    String clean = Text.removeTags(body).trim();
                    if (clean.startsWith(COLLECTION_POPUP_PREFIX)) {
                        emitCollectionLog(clean.substring(COLLECTION_POPUP_PREFIX.length()).trim());
                    }
                }
            }
            collectionPopupStarted = false;
        }
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
            if (questSyncTicks == 0) {
                seedQuestMap();
                // Quest varps have settled — capture the authoritative baseline.
                // This is what surfaces pre-plugin completions (and progress made
                // on mobile) without waiting for a state *transition*.
                emitQuestSnapshot();
            }
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
     * Emits SHOP_STOCK once per shop visit. Unlike a widget-open edge, this
     * retries every tick until the item grid has populated (the interface
     * loads over several ticks), then resolves the shop title client-side.
     * If the title genuinely cannot be found, no SHOP_STOCK is emitted —
     * falling back to the NPC menu target would record "Shop keeper" /
     * "Shop assistant" instead of the shop's real name.
     */
    private void handleShopEdge(boolean shopOpen) {
        if (!shopOpen) {
            shopCaptured = false;
            return;
        }
        if (shopCaptured) return;

        List<RawItem> items = getShopItems();
        if (items.isEmpty()) return; // interface still loading — retry next tick

        String shopName = getActiveShopName();
        if (shopName == null) {
            log.info("[LL] Shop name not found. Widget 300 children dump: {}", dumpWidget300());
            shopCaptured = true; // skip this visit; dump once, no NPC-name fallback
            return;
        }

        ShopStockPayload payload = new ShopStockPayload();
        payload.shopNameCandidates = new ArrayList<>();
        payload.shopNameCandidates.add(shopName);
        payload.items = items;
        emit("SHOP_STOCK", payload);
        shopCaptured = true;
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
     * Emits a full BANK_SNAPSHOT of the bank container (placeholders with qty 0
     * excluded). The server stores it and the frontend's get_bank_snapshot RPC
     * reads the most recent one, so emitting on each bank change is harmless.
     */
    private void emitBankSnapshot() {
        List<RawItem> items = new ArrayList<>();
        for (RawItem item : snapshotContainer(InventoryID.BANK)) {
            if (item.getQty() > 0) items.add(item);
        }
        if (items.isEmpty()) return;

        BankSnapshotPayload payload = new BankSnapshotPayload();
        payload.items = items;
        emit("BANK_SNAPSHOT", payload);
    }

    /**
     * Emits a CHEST_LOOT with the full contents of a reward container
     * (Barrows/raid chests, clue caskets). Skips silently when the container
     * is missing or empty.
     */
    private void emitChestLoot(String source, ItemContainer container) {
        if (container == null) return;

        List<RawItem> items = new ArrayList<>();
        for (Item item : container.getItems()) {
            if (item.getId() != -1 && item.getQuantity() > 0) {
                items.add(enrichItem(item.getId(), item.getQuantity()));
            }
        }
        if (items.isEmpty()) return;

        ChestLootPayload payload = new ChestLootPayload();
        payload.source = source;
        payload.items = items;
        emit("CHEST_LOOT", payload);
    }

    /**
     * Emits an items-empty CHEST_LOOT marker: the server attributes the next
     * few ticks of inventory gains (its existing net-diff) to {@code source}.
     * Used for loot that goes straight to the inventory with no reward container
     * (Wintertodt / Tempoross / GOTR "You found some loot:" messages).
     */
    private void emitChestLootMarker(String source) {
        // KNOWN LIMITATION: any inventory gain that occurs between this loot
        // message and the actual crate-open tick — for reasons unrelated to the
        // crate — will be misattributed to this source. This is the same
        // limitation the official loot tracker accepts; do not try to "fix" it.
        ChestLootPayload payload = new ChestLootPayload();
        payload.source = source;
        payload.items = new ArrayList<>();
        emit("CHEST_LOOT", payload);
    }

    /**
     * Emits a COLLECTION_LOG event with the newly filled slot's item name.
     */
    private void emitCollectionLog(String itemName) {
        if (itemName == null || itemName.isEmpty()) return;
        CollectionLogPayload payload = new CollectionLogPayload();
        payload.itemName = itemName;
        emit("COLLECTION_LOG", payload);
    }

    /**
     * Emits a STATS_SNAPSHOT with real (unboosted) levels for every skill,
     * plus membership signals (days remaining varp + members-world flag).
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
        payload.setMembershipDays(client.getVarpValue(VarPlayer.MEMBERSHIP_DAYS));
        payload.setMemberWorld(client.getWorldType().contains(WorldType.MEMBERS));
        emit("STATS_SNAPSHOT", payload);
    }

    /**
     * Emits a QUEST_SNAPSHOT: the full quest→state map (as seeded from the
     * client) plus current quest points. Fired once per login after the
     * 3-tick quest-varp settle, so the server always has an authoritative
     * baseline — QUEST_STATE events then record deltas on top of it.
     */
    private void emitQuestSnapshot() {
        QuestSnapshotPayload payload = new QuestSnapshotPayload();
        payload.quests = new ArrayList<>();
        for (Map.Entry<Quest, QuestState> e : questStates.entrySet()) {
            if (e.getValue() == null) continue;
            QuestEntry entry = new QuestEntry();
            entry.id = e.getKey().getId();
            entry.name = e.getKey().getName();
            entry.state = e.getValue().name();
            payload.quests.add(entry);
        }
        payload.questPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);
        emit("QUEST_SNAPSHOT", payload);
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
     * Resolves the shop's display title from widget 300 children {1, 2, 78}:
     * the widget's own text first, then each of its children's texts. Returns
     * null when no valid title is present (caller logs a diagnostic dump and
     * skips the snapshot rather than corrupting data with an NPC name).
     */
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
        return null;
    }

    /**
     * Diagnostic: every non-null child of interface 300 that carries any text,
     * as "[id, text='…']" pairs (child texts as "[id/childIdx, text='…']"),
     * so the correct title widget can be identified from the log.
     */
    private String dumpWidget300() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            Widget w = client.getWidget(300, i);
            if (w == null) continue;
            if (w.getText() != null && !w.getText().trim().isEmpty()) {
                sb.append("[").append(i).append(", text='")
                        .append(Text.removeTags(w.getText()).trim()).append("'] ");
            }
            Widget[] children = w.getChildren();
            if (children != null) {
                for (int c = 0; c < children.length; c++) {
                    Widget child = children[c];
                    if (child != null && child.getText() != null && !child.getText().trim().isEmpty()) {
                        sb.append("[").append(i).append("/").append(c).append(", text='")
                                .append(Text.removeTags(child.getText()).trim()).append("'] ");
                    }
                }
            }
        }
        return sb.length() == 0 ? "(no text found in any child)" : sb.toString().trim();
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
        private int membershipDays;   // VarPlayer.MEMBERSHIP_DAYS — >0 means currently a member
        private boolean memberWorld;  // logged into a members world — also implies current membership
    }

    /**
     * Emitted once per login after the quest varps settle. The authoritative
     * baseline of every quest's state + total quest points; QUEST_STATE
     * events record transitions on top of this.
     */
    public static class QuestSnapshotPayload {
        public List<QuestEntry> quests;
        public int questPoints;
    }

    public static class QuestEntry {
        public int id;
        public String name;
        public String state; // "NOT_STARTED" | "IN_PROGRESS" | "FINISHED"
    }

    /**
     * Interface-delivered loot. items non-empty = the reward container contents;
     * items EMPTY = a marker telling the server to attribute the next inventory
     * gains (its net-diff) to {@code source}.
     */
    public static class ChestLootPayload {
        public String source; // e.g. "Barrows", "Clue Scroll (Elite)", "Reward pool (Tempoross)"
        public List<RawItem> items;
    }

    /**
     * A new collection log slot was filled. Capture-only — no frontend consumes
     * this yet.
     */
    public static class CollectionLogPayload {
        public String itemName;
    }

    /**
     * Emitted whenever the bank container changes while open. Full contents
     * snapshot (qty-0 placeholders excluded). Server stores it; the frontend
     * reads the most recent one via get_bank_snapshot.
     */
    public static class BankSnapshotPayload {
        public List<RawItem> items;
    }
}