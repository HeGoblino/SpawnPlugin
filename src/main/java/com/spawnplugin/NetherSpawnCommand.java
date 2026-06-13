package com.spawnplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class NetherSpawnCommand implements CommandExecutor {

    private final SpawnPlugin plugin;

    public NetherSpawnCommand(SpawnPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            // only usable from inside the nether
            if (player.getWorld().getEnvironment() != org.bukkit.World.Environment.NETHER) {
                player.sendMessage(ChatColor.RED + "You can only use /netherspawn from the nether.");
                return true;
            }

            if (plugin.getNetherSpawnLocation() == null) {
                player.sendMessage(ChatColor.RED + "Nether spawn hasn't been set yet.");
                return true;
            }

            int combatSecs = combatSecondsLeft(player.getUniqueId());
            if (combatSecs > 0) {
                player.sendMessage(ChatColor.RED + "You can't use /netherspawn while in combat! ("
                        + combatSecs + "s remaining)");
                return true;
            }

            player.teleport(plugin.getNetherSpawnLocation());
            player.sendMessage(ChatColor.GREEN + "Teleported to nether spawn.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set":
                if (!player.isOp()) {
                    player.sendMessage(ChatColor.RED + "Only operators can set nether spawn.");
                    return true;
                }
                plugin.setNetherSpawnLocation(player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Nether spawn set to your current location!");
                return true;

            case "cancel":
                if (!player.isOp()) {
                    player.sendMessage(ChatColor.RED + "Only operators can use this.");
                    return true;
                }
                plugin.clearNetherSpawnLocation();
                player.sendMessage(ChatColor.GREEN + "Nether spawn cleared.");
                return true;

            default:
                player.sendMessage(ChatColor.RED + "Usage: /netherspawn | /netherspawn set | /netherspawn cancel");
                return true;
        }
    }

    private int combatSecondsLeft(UUID uuid) {
        try {
            org.bukkit.plugin.Plugin shop = Bukkit.getPluginManager().getPlugin("ShopPlugin");
            if (shop == null || !shop.isEnabled()) return 0;
            Object cm = shop.getClass().getMethod("getCombatManager").invoke(shop);
            return (int) cm.getClass().getMethod("secondsLeft", UUID.class).invoke(cm, uuid);
        } catch (Exception ignored) { return 0; }
    }
}
