package com.lootlogger.data;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class GameEvent {

    private String sessionId;

    @Builder.Default
    private String timestamp = Instant.now().toString();

    private String eventType;     // SESSION_START, SESSION_END, GATHER_GAIN, XP_GAIN, NPC_DROP, BANK_DEPOSIT, etc.
    private String category;      // Skilling, Combat, Banking, Misc
    private String source;        // Mining, Fishing, Greater demon, Bank, etc.
    private String target;        // For combat/spells/consumes

    private int x;
    private int y;
    private int plane;
    private int regionId;

    private List<DroppedItem> items;

    // Extra fields for richer analysis
    private Integer xpGained;
    private String skill;
    private String note;
}