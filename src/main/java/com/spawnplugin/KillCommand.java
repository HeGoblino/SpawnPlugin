package com.spawnplugin;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class KillCommand implements CommandExecutor {

    private final SpawnPlugin plugin;

    public KillCommand(SpawnPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        // ops outside survival can always kill themselves (e.g. creative testing)
        if (player.isOp() && player.getGameMode() != GameMode.SURVIVAL) {
            player.setHealth(0);
            return true;
        }

        // protected players can't bypass spawn protection with /kill
        if (plugin.hasProtection(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You cannot use /kill while you have spawn protection.");
            return true;
        }

        Location spawn = plugin.getSpawnLocation();
        if (spawn != null && player.getWorld().equals(spawn.getWorld())) {
            Location loc = player.getLocation();
            double dx = Math.abs(loc.getX() - spawn.getX());
            double dz = Math.abs(loc.getZ() - spawn.getZ());
            if (Math.max(dx, dz) < 80) {
                player.sendMessage(ChatColor.RED + "You cannot use /kill near spawn.");
                return true;
            }
        }

        player.setHealth(0);
        return true;
    }
}
