package com.lootlogger.util;

import com.lootlogger.data.ActionType;
import com.lootlogger.data.InventoryEvent;
import com.lootlogger.data.PlayerState;
import net.runelite.api.Item;
import net.runelite.client.game.ItemManager;
import java.util.*;

public class InventoryProcessor {
    private static final Set<String> CONSUME_OPTIONS = Set.of("Eat", "Drink", "Bury", "Scatter", "Break", "Read", "Empty");
    private static final Set<String> DESTROY_OPTIONS = Set.of("Destroy");
    private static final Set<Integer> FIREMAKE_OPTIONS = Set.of(733, 10572);
    private static final Set<String> EQUIP_OPTIONS = Set.of("Wield", "Wear", "Equip", "Remove");

    public static List<InventoryEvent> invProcess(Item[] prev, Item[] curr, PlayerState state, ItemManager itemManager) {
        List<InventoryEvent> events = new ArrayList<>();
        String opt = state.getMenuOption();

        // 1. THE EQUIP BOUNCER: Ignore everything if equipping/removing gear
        if (opt != null && EQUIP_OPTIONS.contains(opt)) return events;

        Map<Integer, Integer> prevCounts = getCounts(prev);
        Map<Integer, Integer> currCounts = getCounts(curr);

        // LOSSES
        for (Integer itemId : prevCounts.keySet()) {
            int oldQty = prevCounts.get(itemId);
            int newQty = currCounts.getOrDefault(itemId, 0);

            if (newQty < oldQty) {
                int qtyLost = oldQty - newQty;
                String nameLower = itemManager.getItemComposition(itemId).getName().toLowerCase();
                String target = (state.getMenuTarget() != null && !state.getMenuTarget().isEmpty()) ? state.getMenuTarget() : "None";

                if (state.isBanking()) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.BANK_DEPOSIT, "Bank"));
                } else if (opt != null && (opt.equals("Attack") || opt.equals("Cast") || state.isJustCastSpell())) {
                    if (isRuneOrAmmo(nameLower)) {
                        ActionType cType = (opt.equals("Cast") || nameLower.contains("rune")) ? ActionType.SPELL_CAST : ActionType.RANGED_FIRE;
                        events.add(new InventoryEvent(itemId, qtyLost, cType, target));
                    } else {
                        events.add(new InventoryEvent(itemId, qtyLost, ActionType.COMBAT_CONSUME, target));
                    }
                } else if ("Drop".equals(opt)) {
                    if (nameLower.contains("raw ") && (target.toLowerCase().contains("fire") || target.toLowerCase().contains("range"))) {
                        events.add(new InventoryEvent(itemId, qtyLost, ActionType.SKILLING_CONSUME, target));
                    } else {
                        events.add(new InventoryEvent(itemId, qtyLost, ActionType.DROP, "None"));
                    }
                } else {
                    // Fallback for missing clicks
                    if (isRuneOrAmmo(nameLower) && state.getLastAnim() != -1) {
                        events.add(new InventoryEvent(itemId, qtyLost, nameLower.contains("rune") ? ActionType.SPELL_CAST : ActionType.RANGED_FIRE, target));
                    } else {
                        events.add(new InventoryEvent(itemId, qtyLost, ActionType.DROP, "None"));
                    }
                }
            }
        }

        // GAINS
        for (Integer itemId : currCounts.keySet()) {
            int newQty = currCounts.get(itemId);
            int oldQty = prevCounts.getOrDefault(itemId, 0);
            String target = (state.getMenuTarget() != null && !state.getMenuTarget().isEmpty()) ? state.getMenuTarget() : "None";
            if (newQty > oldQty) {
                if (state.isBanking()) {
                    events.add(new InventoryEvent(itemId, (newQty - oldQty), ActionType.BANK_WITHDRAWAL, "Bank"));
                } else if ("Take".equals(opt)) {
                    events.add(new InventoryEvent(itemId, (newQty - oldQty), ActionType.TAKE, "None"));
                } else {
                    events.add(new InventoryEvent(itemId, (newQty - oldQty), ActionType.GATHER_GAIN, target != null ? target : "None"));
                }
            }
        }
        return events;
    }

    private static boolean isRuneOrAmmo(String n) {
        return n.contains("rune") || n.contains("bolt") || n.contains("arrow") || n.contains("dart") || n.contains("knife");
    }

    private static Map<Integer, Integer> getCounts(Item[] inv) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (inv == null) return counts;
        for (Item item : inv) {
            if (item != null && item.getId() > 0) counts.put(item.getId(), counts.getOrDefault(item.getId(), 0) + item.getQuantity());
        }
        return counts;
    }
}