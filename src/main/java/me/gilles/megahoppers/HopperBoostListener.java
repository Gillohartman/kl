package me.gilles.megahoppers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Makes hoppers move {@code multiplier} items per transfer instead of 1 - dupe-proof.
 *
 * How it stays safe: the plugin CANCELS Minecraft's own hopper item-transfer entirely and becomes
 * the sole mover. The actual move is done one tick later, on the settled inventories, completely
 * outside Minecraft's transfer logic. Because vanilla never moves the item itself, there is no
 * shared state for the server to overwrite - so items can never be duplicated or lost (this is what
 * fixes the "two hoppers facing each other multiply items forever" dupe).
 *
 * Timing: each hopper moves once every {@code ticks-per-transfer} ticks (default 8, same rhythm as
 * a vanilla hopper), moving up to {@code multiplier} items each time = 16 items per hopper tick.
 *
 * Furnace rules are honoured:
 *   - hopper below a furnace/smoker/blast furnace only pulls the finished product (result slot);
 *   - hopper on TOP inserts into the input slot, never the output;
 *   - hopper on the SIDE inserts into the fuel slot (fuel items only).
 */
public final class HopperBoostListener implements Listener {

    private static final int INPUT = 0, FUEL = 1, RESULT = 2;
    private static final int[] ALL = new int[0]; // sentinel: "every slot"

    private final MegaHoppersPlugin plugin;
    private final Map<Location, long[]> cooldowns = new HashMap<>(); // {nextPushTick, nextPullTick}
    private final Set<String> disabledWorlds = new HashSet<>();

    private boolean enabled;
    private int multiplier;
    private int ticksPerTransfer;
    private boolean enforceFurnace;
    private boolean debug;
    private boolean warned;

    private long boostEventsSeen;
    private long extraItemsMoved;

    public HopperBoostListener(MegaHoppersPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var c = plugin.getConfig();
        this.enabled = c.getBoolean("hoppers.enabled", true);
        this.multiplier = Math.max(1, c.getInt("hoppers.multiplier", 16));
        this.ticksPerTransfer = Math.max(1, c.getInt("hoppers.ticks-per-transfer", 8));
        this.enforceFurnace = c.getBoolean("hoppers.enforce-furnace-slots", true);
        this.debug = c.getBoolean("hoppers.debug", false);
        disabledWorlds.clear();
        for (String w : c.getStringList("hoppers.disabled-worlds")) disabledWorlds.add(w.toLowerCase());
        cooldowns.clear();
    }

    public int getMultiplier() {
        return multiplier;
    }

    public long boostEventsSeen() {
        return boostEventsSeen;
    }

    public long extraItemsMoved() {
        return extraItemsMoved;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (!enabled) return;
        if (event.getInitiator().getType() != InventoryType.HOPPER) return;

        final Inventory src = event.getSource();
        final Inventory dst = event.getDestination();
        boolean push = event.getInitiator() == src; // hopper is the source when pushing
        Location loc = (push ? src : dst).getLocation();
        if (loc == null || loc.getWorld() == null) return;
        // Only take over real hopper BLOCKS; hopper minecarts and the like stay vanilla.
        Block hopperBlock = loc.getBlock();
        if (hopperBlock.getType() != Material.HOPPER) return;
        // Leave two hoppers that face into each other completely vanilla. They are used for the
        // chunkloader, not transport, and keeping our hands off them makes any dupe impossible.
        if (isFacingHopperPair(hopperBlock)) return;
        if (!disabledWorlds.isEmpty() && disabledWorlds.contains(loc.getWorld().getName().toLowerCase())) return;

        boostEventsSeen++;

        // We own all hopper item movement now, so vanilla must not move anything itself.
        event.setCancelled(true);

        long now = Bukkit.getServer().getCurrentTick();
        int idx = push ? 0 : 1;
        long[] cd = cooldowns.get(loc);
        if (cd != null && now < cd[idx]) return; // not this hopper's turn yet
        if (cd == null) {
            cd = new long[]{0L, 0L};
            cooldowns.put(loc.clone(), cd);
        }
        cd[idx] = now + ticksPerTransfer;

        if (boostEventsSeen % 2000L == 0L) pruneCooldowns(now);

        // Do the actual move next tick, on settled inventories, outside vanilla's transfer logic.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                int moved = bulkMove(src, dst, multiplier);
                if (moved > 0) {
                    extraItemsMoved += moved;
                    if (debug) {
                        plugin.getLogger().info("[boost] moved " + moved
                                + " (" + src.getType() + " -> " + dst.getType() + ")");
                    }
                }
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    plugin.getLogger().warning("Hopper move error: " + t);
                }
            }
        });
    }

    /** Move up to {@code max} items of the first movable type from src to dst, slot-rules aware. */
    private int bulkMove(Inventory src, Inventory dst, int max) {
        int[] ss = sourceSlots(src);
        for (int i : scope(src, ss)) {
            if (i >= src.getSize()) continue;
            ItemStack cur = src.getItem(i);
            if (cur == null || cur.getType().isAir()) continue;

            ItemStack template = cur.clone();
            int[] ds = destSlots(src, dst, template);
            if (ds == null) continue; // this item can't be placed here (e.g. non-fuel into fuel slot)
            int space = spaceIn(dst, template, ds);
            if (space <= 0) continue;
            int available = countIn(src, template, ss);
            int n = Math.min(max, Math.min(available, space));
            if (n <= 0) continue;

            int removed = removeIn(src, template, n, ss);
            int added = addIn(dst, template, removed, ds);
            if (added < removed) addIn(src, template, removed - added, ss); // never lose items
            return added;
        }
        return 0;
    }

    /** Which source slots may be pulled from. Covers furnaces, smokers and blast furnaces alike. */
    private int[] sourceSlots(Inventory src) {
        if (enforceFurnace && src instanceof FurnaceInventory) return new int[]{RESULT}; // output only
        if (src instanceof BrewerInventory) return new int[]{0, 1, 2};                    // finished potions
        return ALL;
    }

    /** Which destination slots may be pushed into, or null if the item cannot be placed at all. */
    private int[] destSlots(Inventory src, Inventory dst, ItemStack template) {
        if (enforceFurnace && dst instanceof FurnaceInventory furnace) {
            BlockFace facing = BlockFace.DOWN;
            if (src.getHolder() instanceof org.bukkit.block.Hopper hop) facing = hopperFacing(hop);
            if (facing == BlockFace.DOWN) return new int[]{INPUT};   // hopper on top -> input, never output
            if (furnace.isFuel(template)) return new int[]{FUEL};    // hopper on side -> fuel slot (fuel only)
            return null;                                             // side hopper, non-fuel item -> nothing
        }
        if (dst instanceof BrewerInventory) {
            Material m = template.getType();
            if (m == Material.BLAZE_POWDER) return new int[]{4};
            if (isPotion(m)) return new int[]{0, 1, 2};
            return new int[]{3};
        }
        return ALL;
    }

    private static boolean isPotion(Material m) {
        return m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION;
    }

    private BlockFace hopperFacing(org.bukkit.block.Hopper hopperState) {
        BlockData bd = hopperState.getBlock().getBlockData();
        if (bd instanceof org.bukkit.block.data.type.Hopper h) return h.getFacing();
        return BlockFace.DOWN;
    }

    /** True if this hopper faces another hopper that faces back into it (a facing pair). */
    private boolean isFacingHopperPair(Block hopper) {
        BlockFace f = facingOf(hopper);
        if (f == null || f == BlockFace.DOWN) return false;
        Block front = hopper.getRelative(f);
        if (front.getType() != Material.HOPPER) return false;
        BlockFace f2 = facingOf(front);
        return f2 != null && front.getRelative(f2).equals(hopper);
    }

    private BlockFace facingOf(Block b) {
        BlockData bd = b.getBlockData();
        if (bd instanceof org.bukkit.block.data.type.Hopper h) return h.getFacing();
        return null;
    }

    private void pruneCooldowns(long now) {
        cooldowns.entrySet().removeIf(e -> Math.max(e.getValue()[0], e.getValue()[1]) < now - 200);
    }

    // ---- slot-scoped inventory helpers (scope: ALL sentinel = whole inventory) ----

    private int[] scope(Inventory inv, int[] slots) {
        if (slots.length > 0) return slots;
        int size = inv.getSize();
        int[] all = new int[size];
        for (int i = 0; i < size; i++) all[i] = i;
        return all;
    }

    private int countIn(Inventory inv, ItemStack template, int[] slots) {
        int count = 0, size = inv.getSize();
        for (int i : scope(inv, slots)) {
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it != null && it.isSimilar(template)) count += it.getAmount();
        }
        return count;
    }

    private int spaceIn(Inventory inv, ItemStack template, int[] slots) {
        int max = template.getMaxStackSize(), space = 0, size = inv.getSize();
        for (int i : scope(inv, slots)) {
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) space += max;
            else if (it.isSimilar(template)) space += Math.max(0, max - it.getAmount());
        }
        return space;
    }

    private int removeIn(Inventory inv, ItemStack template, int n, int[] slots) {
        int remaining = n, size = inv.getSize();
        for (int i : scope(inv, slots)) {
            if (remaining <= 0) break;
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it != null && it.isSimilar(template)) {
                int take = Math.min(remaining, it.getAmount());
                int left = it.getAmount() - take;
                if (left <= 0) inv.setItem(i, null);
                else {
                    it.setAmount(left);
                    inv.setItem(i, it);
                }
                remaining -= take;
            }
        }
        return n - remaining;
    }

    private int addIn(Inventory inv, ItemStack template, int n, int[] slots) {
        int remaining = n, max = template.getMaxStackSize(), size = inv.getSize();
        int[] sc = scope(inv, slots);
        for (int i : sc) { // stack onto existing matching items first
            if (remaining <= 0) break;
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it != null && it.isSimilar(template)) {
                int add = Math.min(remaining, max - it.getAmount());
                if (add > 0) {
                    it.setAmount(it.getAmount() + add);
                    inv.setItem(i, it);
                    remaining -= add;
                }
            }
        }
        for (int i : sc) { // then fill empty slots
            if (remaining <= 0) break;
            if (i >= size) continue;
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) {
                int add = Math.min(remaining, max);
                ItemStack put = template.clone();
                put.setAmount(add);
                inv.setItem(i, put);
                remaining -= add;
            }
        }
        return n - remaining;
    }
}
