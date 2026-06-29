package com.lootlogger.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * An enriched item snapshot shipped by the client.
 * All prices are UNIT prices (per 1 item). The server multiplies by qty.
 * invActions lets the server classify Consumable / Rune / Ammo without an item DB.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RawItem {
    private int id;
    private int qty;                  // absolute qty in container snapshots; loot qty for NPC_LOOT
    private String name;              // ItemComposition.getName()
    private List<String> invActions;  // ItemComposition.getInventoryActions() — server uses for Eat/Drink detection
    private int geUnit;               // itemManager.getItemPrice(id)  (unit, not × qty)
    private int haUnit;               // comp.getHaPrice()             (unit)
    private int baseUnit;             // comp.getPrice()               (unit)
}
