package com.lootlogger.data;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * The new "dumb client" wire envelope.
 *
 * type discriminator → payload shape:
 *   TICK          → LootLoggerPlugin.TickPayload
 *   MENU_CLICK    → LootLoggerPlugin.MenuClickPayload
 *   XP_UPDATE     → LootLoggerPlugin.XpUpdatePayload
 *   NPC_LOOT      → LootLoggerPlugin.NpcLootPayload
 *   SHOP_STOCK    → LootLoggerPlugin.ShopStockPayload
 *   EXAMINE_TEXT  → LootLoggerPlugin.ExamineTextPayload
 *   QUEST_STATE   → LootLoggerPlugin.QuestStatePayload
 *
 * The server uses sessionId + clientTick to reconstruct tick-ordered
 * streams and replay the Net-Diff / context-locking classifiers.
 */
@Data
@Builder
public class RawEvent {
    @Builder.Default
    private String schemaVersion = "2";

    private String sessionId;

    @Builder.Default
    private String timestamp = Instant.now().toString();

    /** client.getTickCount() — primary ordering key for the server's state machine. */
    private long clientTick;

    /** One of: TICK | MENU_CLICK | XP_UPDATE | NPC_LOOT | SHOP_STOCK | EXAMINE_TEXT | QUEST_STATE */
    private String type;

    // Player world location, stamped on every event.
    private Integer x;
    private Integer y;
    private Integer plane;
    private Integer regionId;

    /**
     * Typed payload — exactly one of the *Payload inner classes in LootLoggerPlugin,
     * serialised as a JSON object by Gson's reflection. The server deserialises using
     * the `type` field as the discriminator.
     */
    private Object payload;
}
