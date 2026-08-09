package me.gilles.megahoppers;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * MegaHoppers main class.
 *
 * Two features:
 *   1. Hoppers move {@code multiplier} items per transfer cycle (default 16) instead of 1,
 *      while keeping vanilla behaviour and furnace slot rules.
 *   2. Two hoppers facing into each other with redstone dust inside force-load their chunk.
 */
public final class MegaHoppersPlugin extends JavaPlugin {

    private HopperBoostListener boostListener;
    private ChunkLoaderManager chunkLoaderManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        boostListener = new HopperBoostListener(this);
        chunkLoaderManager = new ChunkLoaderManager(this);

        getServer().getPluginManager().registerEvents(boostListener, this);
        getServer().getPluginManager().registerEvents(new ChunkLoaderListener(chunkLoaderManager), this);
        getServer().getPluginManager().registerEvents(new ChunkloaderMapListener(this), this);

        chunkLoaderManager.start();

        getLogger().info("MegaHoppers enabled - hopper multiplier x" + boostListener.getMultiplier()
                + ", chunkloader " + (getConfig().getBoolean("chunkloader.enabled", true) ? "on" : "off") + ".");
    }

    @Override
    public void onDisable() {
        if (chunkLoaderManager != null) chunkLoaderManager.shutdown();
    }

    public HopperBoostListener boost() {
        return boostListener;
    }

    public ChunkLoaderManager chunkLoaders() {
        return chunkLoaderManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("megahoppers.admin")) {
            sender.sendMessage("§cYou don't have permission to use that.");
            return true;
        }

        String sub = args.length == 0 ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                boostListener.reload();
                chunkLoaderManager.reload();
                sender.sendMessage("§aMegaHoppers configuration reloaded.");
            }
            case "list" -> {
                List<String> active = chunkLoaderManager.describeActive();
                sender.sendMessage("§eActive chunkloaders: §f" + active.size());
                for (String line : active) sender.sendMessage("  §7- " + line);
            }
            default -> {
                sender.sendMessage("§6MegaHoppers §7v" + getPluginMeta().getVersion());
                sender.sendMessage("§7Hopper multiplier: §f" + boostListener.getMultiplier() + "x");
                sender.sendMessage("§7Boost events seen: §f" + boostListener.boostEventsSeen());
                sender.sendMessage("§7Extra items moved: §f" + boostListener.extraItemsMoved());
                sender.sendMessage("§7Active chunkloaders: §f" + chunkLoaderManager.activeCount()
                        + " §7(" + chunkLoaderManager.loadedChunkCount() + " chunks loaded)");
                sender.sendMessage("§7Commands: §f/mh reload§7, §f/mh list");
            }
        }
        return true;
    }
}
