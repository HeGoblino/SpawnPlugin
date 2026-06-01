package com.spawnplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public class SpawnCommand implements CommandExecutor {

    private final SpawnPlugin plugin;

    public SpawnCommand(SpawnPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        Player player = (Player) sender;

        // no args = just warp to spawn
        if (args.length == 0) {
            if (plugin.getSpawnLocation() == null) {
                player.sendMessage(ChatColor.RED + "Spawn hasn't been set yet.");
                return true;
            }

            // don't let people /spawn out of fights
            int combatSecs = combatSecondsLeft(player.getUniqueId());
            if (combatSecs > 0) {
                player.sendMessage(ChatColor.RED + "You can't use /spawn while in combat! ("
                        + combatSecs + "s remaining)");
                return true;
            }

            // restart the countdown if they already had one going
            if (plugin.getWarpManager().isPending(player.getUniqueId())) {
                plugin.getWarpManager().cancel(player.getUniqueId());
            }

            plugin.getWarpManager().requestSpawnWarp(player);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "set":
                if (!player.isOp()) {
                    player.sendMessage(ChatColor.RED + "Only operators can set spawn.");
                    return true;
                }
                plugin.setSpawnLocation(player.getLocation());
                player.sendMessage(ChatColor.GREEN + "Spawn set to your current location!");
                player.sendMessage(ChatColor.GRAY + "Use " + ChatColor.WHITE + "/spawn cancel"
                        + ChatColor.GRAY + " to clear it if you need to move it.");
                return true;

            case "cancel":
                if (!player.isOp()) {
                    player.sendMessage(ChatColor.RED + "Only operators can use this.");
                    return true;
                }
                if (plugin.getSpawnLocation() == null) {
                    player.sendMessage(ChatColor.YELLOW + "No spawn is currently set.");
                    return true;
                }
                plugin.clearSpawnLocation();
                player.sendMessage(ChatColor.GREEN + "Spawn cleared. Use "
                        + ChatColor.WHITE + "/spawn set" + ChatColor.GREEN + " to set a new one.");
                return true;

            default:
                player.sendMessage(ChatColor.RED + "Usage: /spawn | /spawn set | /spawn cancel");
                return true;
        }
    }

    // checks ShopPlugin's combat manager via reflection — returns 0 if ShopPlugin isn't loaded
    private int combatSecondsLeft(UUID uuid) {
        try {
            org.bukkit.plugin.Plugin shop = Bukkit.getPluginManager().getPlugin("ShopPlugin");
            if (shop == null || !shop.isEnabled()) return 0;
            Object cm = shop.getClass().getMethod("getCombatManager").invoke(shop);
            return (int) cm.getClass().getMethod("secondsLeft", UUID.class).invoke(cm, uuid);
        } catch (Exception ignored) {
            return 0;
        }
    }
}
