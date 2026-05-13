package com.lootlogger.data;

public class InventoryEvent {
    public final int itemId;
    public final int qty;
    public final ActionType actionType;
    public final String targetName;

    public InventoryEvent(int itemId, int qty, ActionType actionType, String targetName) {
        this.itemId = itemId;
        this.qty = qty;
        this.actionType = actionType;
        this.targetName = targetName;
    }
}