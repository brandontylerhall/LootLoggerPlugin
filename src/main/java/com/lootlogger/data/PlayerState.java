package com.lootlogger.data;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlayerState {
    private boolean isBanking;
    private String menuOption;
    private String menuTarget;
    private int currentAnim;
    private int lastAnim;
    private boolean justCastSpell;
    private boolean justFiredRanged;
    private String combatTarget;
    private String shopName;
}