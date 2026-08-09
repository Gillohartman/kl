package me.gilles.megahoppers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hold redstone dust and either right-click the air, or sneak + right-click a block, to open a GUI
 * listing every active chunkloader (nearest first). With the {@code megahoppers.map.teleport}
 * permission, clicking an entry teleports you to that chunkloader.
 */
public final class ChunkloaderMapListener implements Listener {

    private final MegaHoppersPlugin plugin;

    public ChunkloaderMapListener(MegaHoppersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        boolean air = a == Action.RIGHT_CLICK_AIR;
        boolean sneakBlock = a == Action.RIGHT_CLICK_BLOCK && e.getPlayer().isSneaking();
        if (!air && !sneakBlock) return;

        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.REDSTONE) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("megahoppers.map")) return;

        e.setCancelled(true); // don't place the redstone / trigger the block
        openMap(p);
    }

    private void openMap(Player p) {
        List<ChunkLoaderManager.LoaderInfo> loaders = plugin.chunkLoaders().activeLoaders();
        loaders.sort((x, y) -> Double.compare(distSq(p, x), distSq(p, y)));

        int count = loaders.size();
        int rows = Math.max(1, Math.min(6, (count + 8) / 9));
        MapHolder holder = new MapHolder();
        Inventory inv = Bukkit.createInventory(holder, rows * 9,
                Component.text("Chunkloaders (" + count + ")", NamedTextColor.DARK_RED));
        holder.inv = inv;

        boolean canTp = p.hasPermission("megahoppers.map.teleport");

        if (count == 0) {
            inv.setItem(4, named(Material.BARRIER, "No active chunkloaders", NamedTextColor.RED,
                    List.of("Build two hoppers facing each other",
                            "with redstone dust inside to make one.")));
        } else {
            int slots = rows * 9;
            for (int i = 0; i < count && i < slots; i++) {
                ChunkLoaderManager.LoaderInfo l = loaders.get(i);
                List<String> lore = new ArrayList<>();
                lore.add("World: " + (l.world == null ? "?" : l.world.getName()));
                lore.add("Chunk: [" + l.cx + ", " + l.cz + "]");
                lore.add("Hoppers at: " + l.a.getBlockX() + ", " + l.a.getBlockY() + ", " + l.a.getBlockZ());
                if (l.world != null && l.world.equals(p.getWorld())) {
                    lore.add("Distance: " + Math.round(Math.sqrt(distSq(p, l))) + " blocks");
                }
                lore.add(canTp ? "Click to teleport" : "");
                inv.setItem(i, named(Material.FILLED_MAP, "Chunkloader #" + (i + 1), NamedTextColor.GOLD, lore));
                holder.targets.put(i, l.a.clone());
            }
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MapHolder holder)) return;
        e.setCancelled(true); // the map is read-only
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.hasPermission("megahoppers.map.teleport")) return;

        Location target = holder.targets.get(e.getRawSlot());
        if (target == null || target.getWorld() == null) return;
        p.closeInventory();
        p.teleport(target.clone().add(0.5, 1.0, 0.5));
        p.sendMessage(Component.text("Teleported to chunkloader.", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MapHolder) e.setCancelled(true);
    }

    private double distSq(Player p, ChunkLoaderManager.LoaderInfo l) {
        if (l.world == null || !l.world.equals(p.getWorld())) return Double.MAX_VALUE;
        return p.getLocation().distanceSquared(l.a);
    }

    private ItemStack named(Material m, String name, NamedTextColor color, List<String> loreLines) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String s : loreLines) {
            if (s.isEmpty()) continue;
            lore.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** Owns the map inventory and remembers which slot points at which chunkloader. */
    static final class MapHolder implements InventoryHolder {
        Inventory inv;
        final Map<Integer, Location> targets = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }
}
