package me.gilles.megahoppers;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates the ticking that Minecraft skips for chunks with no nearby player: it grows crops and
 * plants and does a best-effort mob spawn in the chunkloader's loaded area. This is an approximation
 * (the vanilla engine ties real ticking to player proximity), but it makes farms run without anyone
 * standing there.
 */
public final class ChunkTicker {

    private static final EntityType[] HOSTILE = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER
    };
    private static final EntityType[] PASSIVE = {
            EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN
    };

    private final MegaHoppersPlugin plugin;
    private final ChunkLoaderManager manager;

    private boolean tickPlants;
    private boolean simulateMobs;
    private int interval;
    private int plantAttempts;
    private int monsterCap;
    private BukkitTask task;
    private boolean warned;

    public ChunkTicker(MegaHoppersPlugin plugin, ChunkLoaderManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        var c = plugin.getConfig();
        this.tickPlants = c.getBoolean("chunkloader.tick-plants", true);
        this.simulateMobs = c.getBoolean("chunkloader.simulate-mobs", true);
        this.interval = Math.max(5, c.getInt("chunkloader.simulate-interval-ticks", 20));
        this.plantAttempts = Math.max(0, c.getInt("chunkloader.plant-attempts-per-chunk", 24));
        this.monsterCap = Math.max(1, c.getInt("chunkloader.mob-cap-per-chunk", 8));
    }

    public void start() {
        if (!tickPlants && !simulateMobs) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::run, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void run() {
        for (ChunkLoaderManager.ChunkPos cp : manager.loadedChunks()) {
            World w = Bukkit.getWorld(cp.world);
            if (w == null || !w.isChunkLoaded(cp.cx, cp.cz)) continue;
            try {
                if (tickPlants) growPlants(w, cp);
                if (simulateMobs) spawnMobs(w, cp);
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    plugin.getLogger().warning("Chunk ticking error (continuing): " + t);
                }
            }
        }
    }

    // ---- plant growth ----

    private void growPlants(World w, ChunkLoaderManager.ChunkPos cp) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int baseX = cp.cx << 4, baseZ = cp.cz << 4;
        for (int i = 0; i < plantAttempts; i++) {
            int x = baseX + rnd.nextInt(16);
            int z = baseZ + rnd.nextInt(16);
            int topY = w.getHighestBlockYAt(x, z);
            for (int dy = 1; dy >= -2; dy--) {
                if (growOne(w.getBlockAt(x, topY + dy, z))) break;
            }
        }
    }

    private boolean growOne(Block b) {
        Material t = b.getType();
        if (t == Material.SUGAR_CANE || t == Material.CACTUS) return growStalk(b, t, 3);
        if (t == Material.BAMBOO) return growStalk(b, t, 12);
        if (b.getBlockData() instanceof Ageable age) {
            if (age.getAge() < age.getMaximumAge()) {
                age.setAge(age.getAge() + 1);
                b.setBlockData(age);
                return true;
            }
        }
        return false;
    }

    private boolean growStalk(Block b, Material t, int maxHeight) {
        Block above = b.getRelative(BlockFace.UP);
        if (above.getType() != Material.AIR) return false;
        int height = 1;
        Block below = b.getRelative(BlockFace.DOWN);
        while (below.getType() == t) {
            height++;
            below = below.getRelative(BlockFace.DOWN);
        }
        if (height >= maxHeight) return false;
        above.setType(t);
        return true;
    }

    // ---- mob spawning ----

    private void spawnMobs(World w, ChunkLoaderManager.ChunkPos cp) {
        Chunk chunk = w.getChunkAt(cp.cx, cp.cz);
        int monsters = 0, animals = 0;
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Monster) monsters++;
            else if (e instanceof Animals) animals++;
        }
        int animalCap = Math.max(1, monsterCap / 2);
        boolean peaceful = w.getDifficulty() == Difficulty.PEACEFUL;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int baseX = cp.cx << 4, baseZ = cp.cz << 4;
        for (int i = 0; i < 3; i++) {
            int x = baseX + rnd.nextInt(16);
            int z = baseZ + rnd.nextInt(16);
            int groundY = w.getHighestBlockYAt(x, z);
            Block ground = w.getBlockAt(x, groundY, z);
            Block feet = w.getBlockAt(x, groundY + 1, z);
            Block head = w.getBlockAt(x, groundY + 2, z);
            if (!ground.getType().isSolid() || !feet.isPassable() || !head.isPassable()) continue;

            if (!peaceful && monsters < monsterCap && feet.getLightLevel() <= 7) {
                spawn(w, feet, HOSTILE[rnd.nextInt(HOSTILE.length)]);
                monsters++;
            } else if (animals < animalCap && ground.getType() == Material.GRASS_BLOCK
                    && feet.getLightFromSky() >= 9 && rnd.nextInt(4) == 0) {
                spawn(w, feet, PASSIVE[rnd.nextInt(PASSIVE.length)]);
                animals++;
            }
        }
    }

    private void spawn(World w, Block feet, EntityType type) {
        try {
            w.spawnEntity(feet.getLocation().add(0.5, 0.0, 0.5), type);
        } catch (Throwable ignored) {
            // some spots/entity types may refuse to spawn; skip quietly
        }
    }
}
