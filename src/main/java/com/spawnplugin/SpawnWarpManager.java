package com.spawnplugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles /spawn teleport requests with a proximity-based countdown.
 *
 * If enemies are close by, the player has to stand still for a few seconds
 * before they can teleport. Moving or getting hit cancels it. The closer
 * the enemy, the longer the wait.
 */
public class SpawnWarpManager {

    private final SpawnPlugin plugin;

    // players who have a pending /spawn countdown
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public SpawnWarpManager(SpawnPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Start a warp request for this player. If they already have protection or
     * there's nobody nearby, it teleports them instantly. Otherwise it starts
     * the countdown timer.
     */
    public void requestSpawnWarp(Player player) {
        if (plugin.hasProtection(player.getUniqueId())) {
            execute(player);
            return;
        }

        int seconds = getWarpDelay(player);
        if (seconds == 0) {
            execute(player);
            return;
        }

        cancel(player.getUniqueId());

        final UUID uuid = player.getUniqueId();
        final int[] remaining = {seconds};
        // need a reference to the task so we can cancel it from inside the lambda
        final BukkitTask[] taskRef = {null};

        player.sendMessage(ChatColor.YELLOW + "A player is nearby! Stand still for "
                + seconds + "s to teleport to spawn.");
        player.sendMessage(ChatColor.GRAY + "Moving or being hit will cancel the teleport.");

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) {
                cancel(uuid);
                return;
            }

            remaining[0]--;

            if (remaining[0] <= 0) {
                pending.remove(uuid);
                if (taskRef[0] != null) taskRef[0].cancel();
                execute(p);
                return;
            }

            // only spam them at the last few seconds or every 5s so chat doesn't get flooded
            if (remaining[0] <= 3 || remaining[0] % 5 == 0) {
                p.sendMessage(ChatColor.YELLOW + "Teleporting to spawn in " + remaining[0] + "s...");
            }
        }, 20L, 20L);

        taskRef[0] = task;
        pending.put(uuid, task);
    }

    public void cancel(UUID uuid) {
        BukkitTask t = pending.remove(uuid);
        if (t != null) t.cancel();
    }

    public boolean isPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    // actually do the teleport and give protection
    private void execute(Player player) {
        player.teleport(plugin.getSpawnLocation());
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        plugin.giveProtection(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Teleported to spawn. You have spawn protection!");
    }

    /**
     * Returns delay in seconds based on nearest enemy player:
     *   > 128 blocks away  →  0s (instant)
     *   64 - 128 blocks    →  5s
     *   32 - 64 blocks     →  10s
     *   under 32 blocks    →  15s
     *
     * Creative/spectator players and teammates are ignored.
     */
    private int getWarpDelay(Player player) {
        double closestSq = Double.MAX_VALUE;

        for (Player other : player.getWorld().getPlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) continue;
            if (other.getGameMode() == GameMode.CREATIVE
                    || other.getGameMode() == GameMode.SPECTATOR) continue;
            if (isGhost(other)) continue;
            if (isTeammate(player, other)) continue;

            double dSq = player.getLocation().distanceSquared(other.getLocation());
            if (dSq < closestSq) closestSq = dSq;
        }

        if (closestSq > 128 * 128) return 0;
        if (closestSq > 64  * 64)  return 5;
        if (closestSq > 32  * 32)  return 10;
        return 15;
    }

    // checks if a player is in AdminPlugin's ghost/vanish mode
    private boolean isGhost(Player player) {
        try {
            org.bukkit.plugin.Plugin ap = Bukkit.getPluginManager().getPlugin("AdminPlugin");
            if (ap == null) return false;
            return (boolean) ap.getClass()
                    .getMethod("isGhost", UUID.class)
                    .invoke(ap, player.getUniqueId());
        } catch (Exception e) {
            return false;
        }
    }

    // checks RankTeamPlugin to see if two players are on the same team
    private boolean isTeammate(Player a, Player b) {
        try {
            org.bukkit.plugin.Plugin rtp = Bukkit.getPluginManager().getPlugin("RankTeamPlugin");
            if (rtp == null) return false;
            Object tm = rtp.getClass().getMethod("getTeamManager").invoke(rtp);
            String teamA = (String) tm.getClass().getMethod("getPlayerTeamName", UUID.class).invoke(tm, a.getUniqueId());
            String teamB = (String) tm.getClass().getMethod("getPlayerTeamName", UUID.class).invoke(tm, b.getUniqueId());
            return teamA != null && teamA.equals(teamB);
        } catch (Exception e) {
            return false;
        }
    }
}
