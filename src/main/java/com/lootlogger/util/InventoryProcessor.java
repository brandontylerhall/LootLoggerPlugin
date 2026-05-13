package com.lootlogger.util;

import com.lootlogger.data.ActionType;
import com.lootlogger.data.InventoryEvent;
import com.lootlogger.data.PlayerState;
import net.runelite.api.Item;
import net.runelite.client.game.ItemManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InventoryProcessor {
    private static final java.util.Set<String> CONSUME_OPTIONS = java.util.Set.of(
            "Eat", "Drink", "Bury", "Scatter", "Break", "Read", "Empty"
    );

    private static final java.util.Set<String> DESTROY_OPTIONS = java.util.Set.of("Destroy");
    private static final java.util.Set<Integer> FIREMAKE_OPTIONS = java.util.Set.of(733, 10572);

    // NEW: Options that trigger an equip action
    private static final java.util.Set<String> EQUIP_OPTIONS = java.util.Set.of(
            "Wield", "Wear", "Equip"
    );

    public static List<InventoryEvent> invProcess(
            Item[] previousInventory,
            Item[] currentInventory,
            PlayerState state,
            ItemManager itemManager
    ) {
        List<InventoryEvent> events = new ArrayList<>();

        Map<Integer, Integer> prevCounts = getCounts(previousInventory);
        Map<Integer, Integer> currCounts = getCounts(currentInventory);

        // ==========================================
        //       PROCESS LOSSES (Items that left)
        // ==========================================
        for (Integer itemId : prevCounts.keySet()) {
            int oldQty = prevCounts.get(itemId);
            int newQty = currCounts.getOrDefault(itemId, 0);

            if (newQty < oldQty) {
                int qtyLost = oldQty - newQty;
                String itemNameLower = itemManager.getItemComposition(itemId).getName().toLowerCase();

                boolean isRune = itemNameLower.contains("rune");
                boolean isAmmo = itemNameLower.matches("(?i).*\\b(arrow|arrows|bolt|bolts|dart|darts|javelin|javelins)\\b.*");
                boolean inCombat = !state.getCombatTarget().equals("None");

                if (state.isBanking()) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.BANK_DEPOSIT, "Bank"));
                }
                else if (DESTROY_OPTIONS.contains(state.getMenuOption())) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.DESTROY, "None"));
                }
                // NEW: Catch Equips before they fall to the DROP catch-all
                else if (EQUIP_OPTIONS.contains(state.getMenuOption())) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.EQUIP, "None"));
                }
                else if ("Drop".equals(state.getMenuOption())) {
                    if (itemNameLower.contains("raw ") &&
                            (itemNameLower.contains("lobster") || itemNameLower.contains("shrimp") ||
                                    itemNameLower.contains("trout") || itemNameLower.contains("salmon") ||
                                    itemNameLower.contains("tuna") || itemNameLower.contains("swordfish"))) {

                        events.add(new InventoryEvent(itemId, qtyLost, ActionType.SKILLING_CONSUME, "Cooking"));
                    }
                    else {
                        events.add(new InventoryEvent(itemId, qtyLost, ActionType.DROP, "None"));
                    }
                }
                else if (isRune && state.isJustCastSpell()) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.SPELL_CAST, state.getCombatTarget()));
                }
                else if (isAmmo && state.isJustFiredRanged()) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.RANGED_FIRE, state.getCombatTarget()));
                }
                else if (inCombat && (CONSUME_OPTIONS.contains(state.getMenuOption()) || state.getCurrentAnim() != -1)) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.COMBAT_CONSUME, state.getCombatTarget()));
                }
                else if (FIREMAKE_OPTIONS.contains(state.getLastAnim())) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.SKILLING_CONSUME, "Firemaking"));
                }
                else if (CONSUME_OPTIONS.contains(state.getMenuOption())) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.CONSUME, "None"));
                }
                else {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.DROP, "None"));
                }
            }
        }

        // ==========================================
        //       PROCESS GAINS (Items that entered)
        // ==========================================
        for (Integer itemId : currCounts.keySet()) {
            int newQty = currCounts.get(itemId);
            int oldQty = prevCounts.getOrDefault(itemId, 0);

            if (newQty > oldQty) {
                int qtyGained = newQty - oldQty;

                if (state.isBanking()) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.BANK_WITHDRAWAL, "Bank"));
                }
                else if (state.getLastAnim() == 619 || state.getLastAnim() == 618 || state.getLastAnim() == 621 ||
                        state.getCurrentAnim() == 619 || state.getCurrentAnim() == 618 || state.getCurrentAnim() == 621) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.GATHER_GAIN, "None"));
                }
                else if ("Take".equals(state.getMenuOption())) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.TAKE, "None"));
                }
                // NEW: Catch unequipping (or forced unequip from swapping 2H weapons) before it becomes a GATHER_GAIN
                else if ("Remove".equals(state.getMenuOption()) || EQUIP_OPTIONS.contains(state.getMenuOption())) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.UNEQUIP, "None"));
                }
                else if (state.getMenuOption() == null || (!"Drop".equals(state.getMenuOption()) && !DESTROY_OPTIONS.contains(state.getMenuOption()))) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.GATHER_GAIN, state.getMenuTarget()));
                }
                else {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.GATHER_GAIN, "None"));
                }
            }
        }

        return events;
    }

    private static Map<Integer, Integer> getCounts(Item[] inv) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (inv == null) return counts;
        for (Item item : inv) {
            if (item != null && item.getId() > 0) {
                counts.put(item.getId(), counts.getOrDefault(item.getId(), 0) + item.getQuantity());
            }
        }
        return counts;
    }
}