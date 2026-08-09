package me.gilles.megahoppers;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Redstone-hopper chunkloader.
 *
 * Two hoppers facing into each other with redstone dust inside load a square area of chunks around
 * them ({@code chunks-per-side} ^ 2, default 16). Because Minecraft only ticks chunks near players,
 * a companion {@link ChunkTicker} simulates crop growth and mob spawning in the loaded area so it
 * behaves closer to a player being there.
 */
public final class ChunkLoaderManager {

    private final MegaHoppersPlugin plugin;
    private final Map<String, Loader> loaders = new HashMap<>();
    private final Map<ChunkPos, Integer> chunkRefs = new HashMap<>(); // how many loaders keep each chunk
    private ChunkTicker ticker;

    private boolean enabled;
    private boolean requireRedstone;
    private int validateInterval;
    private int chunksPerSide;
    private BukkitTask validateTask;

    public ChunkLoaderManager(MegaHoppersPlugin plugin) {
        this.plugin = plugin;
        readConfig();
    }

    private void readConfig() {
        var c = plugin.getConfig();
        this.enabled = c.getBoolean("chunkloader.enabled", true);
        this.requireRedstone = c.getBoolean("chunkloader.require-redstone", true);
        this.validateInterval = Math.max(20, c.getInt("chunkloader.validate-interval-ticks", 40));
        this.chunksPerSide = Math.max(1, c.getInt("chunkloader.chunks-per-side", 4)); // 4 -> 16 chunks
    }

    public void start() {
        if (!enabled) return;
        long p = validateInterval;
        validateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::validateAll, p, p);
        plugin.getServer().getScheduler().runTaskLater(plugin, this::restore, 60L);
        ticker = new ChunkTicker(plugin, this);
        ticker.start();
    }

    public void reload() {
        boolean wasEnabled = enabled;
        readConfig();
        if (!enabled && wasEnabled) clearAll();
        if (validateTask != null) {
            validateTask.cancel();
            validateTask = null;
        }
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
        start();
    }

    public void shutdown() {
        if (validateTask != null) {
            validateTask.cancel();
            validateTask = null;
        }
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
        // Leave chunk tickets in place; Paper persists them and restore() re-validates on next enable.
    }

    public int activeCount() {
        return loaders.size();
    }

    public int loadedChunkCount() {
        return chunkRefs.size();
    }

    /** Snapshot of every chunk currently kept loaded, for the ticker. */
    public List<ChunkPos> loadedChunks() {
        return new ArrayList<>(chunkRefs.keySet());
    }

    public List<String> describeActive() {
        List<String> out = new ArrayList<>();
        for (Loader l : loaders.values()) {
            World w = Bukkit.getWorld(l.world);
            out.add((w == null ? "?" : w.getName()) + " center [" + l.cx + ", " + l.cz + "] ("
                    + l.chunks.size() + " chunks)");
        }
        return out;
    }

    /** Evaluate a hopper on the next tick (safe to call from inside events). */
    public void scheduleEvaluate(Block block) {
        if (!enabled) return;
        Location loc = block.getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (loc.getWorld() != null) evaluate(loc.getBlock());
        });
    }

    /** Core rule: is this hopper part of a valid facing pair with redstone? Activate or release. */
    public void evaluate(Block block) {
        if (!enabled) return;
        Location loc = block.getLocation();
        if (block.getType() != Material.HOPPER) {
            deactivateInvolving(loc);
            return;
        }

        BlockFace facing = facingOf(block);
        if (facing == null || facing == BlockFace.DOWN) {
            deactivateInvolving(loc);
            return;
        }

        Block partner = block.getRelative(facing);
        if (partner.getType() != Material.HOPPER) {
            deactivateInvolving(loc);
            return;
        }

        BlockFace pf = facingOf(partner);
        if (pf == null || !partner.getRelative(pf).equals(block)) {
            deactivateInvolving(loc);
            return;
        }

        boolean redstone = !requireRedstone || hasRedstone(block) || hasRedstone(partner);
        String id = pairId(block.getLocation(), partner.getLocation());
        if (redstone) activate(id, block.getLocation(), partner.getLocation());
        else deactivate(id);
    }

    private void activate(String id, Location a, Location b) {
        if (loaders.containsKey(id)) return; // already loaded
        Location primary = min(a, b);
        World w = primary.getWorld();
        if (w == null) return;
        int cx = primary.getBlockX() >> 4;
        int cz = primary.getBlockZ() >> 4;
        List<ChunkPos> area = computeArea(w.getUID(), cx, cz);
        for (ChunkPos cp : area) addRef(w, cp);
        loaders.put(id, new Loader(w.getUID(), cx, cz, a.clone(), b.clone(), area));
        notifyNear(primary, "§aChunkloader on §7— " + area.size() + " chunks around [" + cx + ", " + cz + "] stay loaded & ticking.");
    }

    private void deactivate(String id) {
        Loader l = loaders.remove(id);
        if (l == null) return;
        for (ChunkPos cp : l.chunks) removeRef(cp);
        World w = Bukkit.getWorld(l.world);
        if (w != null) {
            notifyNear(new Location(w, (l.cx << 4) + 8, 70, (l.cz << 4) + 8),
                    "§7Chunkloader off — chunks around [" + l.cx + ", " + l.cz + "] can unload.");
        }
    }

    public void deactivateInvolving(Location loc) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Loader> e : loaders.entrySet()) {
            Loader l = e.getValue();
            if (sameBlock(l.a, loc) || sameBlock(l.b, loc)) ids.add(e.getKey());
        }
        for (String id : ids) deactivate(id);
    }

    private List<ChunkPos> computeArea(UUID world, int cx, int cz) {
        int side = Math.max(1, chunksPerSide);
        int half = (side - 1) / 2;
        List<ChunkPos> area = new ArrayList<>(side * side);
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                area.add(new ChunkPos(world, cx - half + dx, cz - half + dz));
            }
        }
        return area;
    }

    private void addRef(World w, ChunkPos cp) {
        int n = chunkRefs.merge(cp, 1, Integer::sum);
        if (n == 1) w.addPluginChunkTicket(cp.cx, cp.cz, plugin);
    }

    private void removeRef(ChunkPos cp) {
        Integer n = chunkRefs.get(cp);
        if (n == null) return;
        if (n <= 1) {
            chunkRefs.remove(cp);
            World w = Bukkit.getWorld(cp.world);
            if (w != null) w.removePluginChunkTicket(cp.cx, cp.cz, plugin);
        } else {
            chunkRefs.put(cp, n - 1);
        }
    }

    private void validateAll() {
        for (String id : new ArrayList<>(loaders.keySet())) {
            Loader l = loaders.get(id);
            if (l == null) continue;
            World w = Bukkit.getWorld(l.world);
            if (w == null) continue;
            if (!validPair(w.getBlockAt(l.a), w.getBlockAt(l.b))) deactivate(id);
        }
    }

    /** Snapshot of the currently active chunkloaders, for the in-game map view. */
    public List<LoaderInfo> activeLoaders() {
        List<LoaderInfo> out = new ArrayList<>();
        for (Loader l : loaders.values()) {
            out.add(new LoaderInfo(Bukkit.getWorld(l.world), l.cx, l.cz, l.a.clone(), l.b.clone(), l.chunks.size()));
        }
        return out;
    }

    /** Public, immutable view of one chunkloader. */
    public static final class LoaderInfo {
        public final World world;
        public final int cx, cz, chunkCount;
        public final Location a, b;

        public LoaderInfo(World world, int cx, int cz, Location a, Location b, int chunkCount) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.a = a;
            this.b = b;
            this.chunkCount = chunkCount;
        }
    }

    private boolean validPair(Block a, Block b) {
        if (a.getType() != Material.HOPPER || b.getType() != Material.HOPPER) return false;
        BlockFace fa = facingOf(a), fb = facingOf(b);
        if (fa == null || fb == null) return false;
        if (!a.getRelative(fa).equals(b) || !b.getRelative(fb).equals(a)) return false;
        return !requireRedstone || hasRedstone(a) || hasRedstone(b);
    }

    /** After a restart, Paper re-adds our chunk tickets; rebuild records from the loaded chunks. */
    private void restore() {
        for (World w : Bukkit.getWorlds()) {
            Collection<Chunk> ours = w.getPluginChunkTickets().getOrDefault(plugin, Collections.emptyList());
            for (Chunk c : new ArrayList<>(ours)) {
                for (BlockState bs : c.getTileEntities(false)) {
                    if (bs instanceof org.bukkit.block.Hopper) evaluate(bs.getBlock());
                }
            }
            // Drop any restored ticket that no active loader claims.
            for (Chunk c : new ArrayList<>(w.getPluginChunkTickets().getOrDefault(plugin, Collections.emptyList()))) {
                if (!chunkRefs.containsKey(new ChunkPos(w.getUID(), c.getX(), c.getZ()))) {
                    w.removePluginChunkTicket(c.getX(), c.getZ(), plugin);
                }
            }
        }
    }

    private void clearAll() {
        for (Loader l : new ArrayList<>(loaders.values())) {
            for (ChunkPos cp : l.chunks) removeRef(cp);
        }
        loaders.clear();
        chunkRefs.clear();
    }

    // ---- helpers ----

    private BlockFace facingOf(Block b) {
        BlockData bd = b.getBlockData();
        if (bd instanceof org.bukkit.block.data.type.Hopper h) return h.getFacing();
        return null;
    }

    private boolean hasRedstone(Block b) {
        BlockState st = b.getState(false);
        if (st instanceof org.bukkit.block.Hopper hop) return hop.getInventory().contains(Material.REDSTONE);
        return false;
    }

    private void notifyNear(Location center, String msg) {
        World w = center.getWorld();
        if (w == null) return;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= 256) p.sendMessage(msg);
        }
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private static Location min(Location a, Location b) {
        if (a.getBlockX() != b.getBlockX()) return a.getBlockX() < b.getBlockX() ? a : b;
        if (a.getBlockY() != b.getBlockY()) return a.getBlockY() < b.getBlockY() ? a : b;
        if (a.getBlockZ() != b.getBlockZ()) return a.getBlockZ() < b.getBlockZ() ? a : b;
        return a;
    }

    private static String pairId(Location a, Location b) {
        Location lo = min(a, b);
        Location hi = (lo == a) ? b : a;
        UUID world = a.getWorld() != null ? a.getWorld().getUID() : new UUID(0, 0);
        return world + "|" + lo.getBlockX() + "," + lo.getBlockY() + "," + lo.getBlockZ()
                + "|" + hi.getBlockX() + "," + hi.getBlockY() + "," + hi.getBlockZ();
    }

    /** A chunk in a specific world. */
    public static final class ChunkPos {
        public final UUID world;
        public final int cx, cz;

        public ChunkPos(UUID world, int cx, int cz) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkPos p)) return false;
            return cx == p.cx && cz == p.cz && world.equals(p.world);
        }

        @Override
        public int hashCode() {
            return Objects.hash(world, cx, cz);
        }
    }

    private static final class Loader {
        final UUID world;
        final int cx, cz;
        final Location a, b;
        final List<ChunkPos> chunks;

        Loader(UUID world, int cx, int cz, Location a, Location b, List<ChunkPos> chunks) {
            this.world = world;
            this.cx = cx;
            this.cz = cz;
            this.a = a;
            this.b = b;
            this.chunks = chunks;
        }
    }
}
