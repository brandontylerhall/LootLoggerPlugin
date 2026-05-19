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

    private String eventType;
    private String category;
    private String source;
    private String target;

    private int x;
    private int y;
    private int plane;
    private int regionId;
    private Integer npcLevel;

    private List<DroppedItem> items;

    // Extra fields for richer analysis
    private Integer xpGained;
    private String skill;
    private String note;

    // NEW: HP Tracking
    private Integer hpHealed;
}