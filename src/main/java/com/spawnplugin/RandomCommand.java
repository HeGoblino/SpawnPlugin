package com.spawnplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Random;
import java.util.UUID;

/**
 * /random — teleports the player to a random location outside the spawn area.
 *
 * Requirements:
 *   - must be at spawn with protection active (so people can't abuse this to escape combat)
 *   - 24 hour cooldown per player
 *   - destination must be outside spawn area, on solid ground, with 2 blocks of headroom
 */
public class RandomCommand implements CommandExecutor {

    // max distance from spawn the random destination can land
    private static final int RANGE = 512;

    // 24h in milliseconds
    private static final long COOLDOWN_MS = 24L * 60 * 60 * 1000;

    // how many times to try finding a safe spot before giving up
    private static final int MAX_TRIES = 25;

    private final SpawnPlugin plugin;
    private final Random rng = new Random();

    public RandomCommand(SpawnPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        Player player = (Player) sender;

        // require spawn protection — this ensures the player is actually at spawn
        // and hasn't recently been in combat
        if (!plugin.hasProtection(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED
                    + "You need to be at spawn with spawn protection to use /random.");
            return true;
        }

        int combatSecs = combatSecondsLeft(player.getUniqueId());
        if (combatSecs > 0) {
            player.sendMessage(ChatColor.RED + "You can't use /random while in combat! ("
                    + combatSecs + "s remaining)");
            return true;
        }

        long now      = System.currentTimeMillis();
        long lastUsed = plugin.getRandomCooldown(player.getUniqueId());
        long remaining = (lastUsed + COOLDOWN_MS) - now;
        if (remaining > 0) {
            player.sendMessage(ChatColor.RED + "You can use /random again in "
                    + formatTime(remaining) + ".");
            return true;
        }

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) {
            player.sendMessage(ChatColor.RED + "Spawn hasn't been set.");
            return true;
        }

        Location dest = findSafe(spawn.getWorld(), spawn);
        if (dest == null) {
            // this is pretty rare — usually means we got unlucky with ocean/lava tiles
            player.sendMessage(ChatColor.RED + "Couldn't find a safe spot. Try again.");
            return true;
        }

        plugin.setRandomCooldown(player.getUniqueId(), now);
        plugin.removeProtection(player.getUniqueId());
        player.teleport(dest);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        player.sendMessage(ChatColor.GREEN + "Sent to a random location. Good luck!");
        player.sendMessage(ChatColor.GRAY + "Spawn protection removed. /random resets in 24 hours.");
        return true;
    }

    private Location findSafe(World world, Location spawn) {
        for (int i = 0; i < MAX_TRIES; i++) {
            int x = spawn.getBlockX() + rng.nextInt(RANGE * 2 + 1) - RANGE;
            int z = spawn.getBlockZ() + rng.nextInt(RANGE * 2 + 1) - RANGE;

            Block ground = world.getHighestBlockAt(x, z);
            if (isSafeGround(ground)) {
                return new Location(world, x + 0.5, ground.getY() + 1, z + 0.5);
            }
        }
        return null;
    }

    private boolean isSafeGround(Block ground) {
        Material t = ground.getType();
        if (!t.isSolid()) return false;
        if (t == Material.LAVA || t == Material.WATER) return false;

        // need 2 clear blocks above for the player to stand in
        Block head  = ground.getRelative(0, 1, 0);
        Block upper = ground.getRelative(0, 2, 0);
        return head.getType().isAir() && upper.getType().isAir();
    }

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

    private String formatTime(long ms) {
        long h = ms / 3_600_000;
        long m = (ms % 3_600_000) / 60_000;
        long s = (ms % 60_000) / 1_000;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
