package me.gilles.megahoppers;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

/** Triggers chunkloader re-evaluation when hoppers are placed, broken, or their contents change. */
public final class ChunkLoaderListener implements Listener {

    private static final BlockFace[] HORIZONTAL =
            {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};

    private final ChunkLoaderManager mgr;

    public ChunkLoaderListener(ChunkLoaderManager mgr) {
        this.mgr = mgr;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (b.getType() != Material.HOPPER) return;
        mgr.scheduleEvaluate(b);
        for (BlockFace f : HORIZONTAL) {
            Block n = b.getRelative(f);
            if (n.getType() == Material.HOPPER) mgr.scheduleEvaluate(n); // neighbour may now face into us
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() == Material.HOPPER) mgr.deactivateInvolving(e.getBlock().getLocation());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof org.bukkit.block.Hopper hop) mgr.scheduleEvaluate(hop.getBlock());
    }
}
