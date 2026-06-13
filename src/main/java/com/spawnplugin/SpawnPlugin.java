package com.spawnplugin;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

public class SpawnPlugin extends JavaPlugin {

    /*
     * Three protection zones around spawn (XZ radius):
     *
     *   0 - 45 blocks  : full protection — no pvp, no mob spawns, no block changes
     *   45 - 80 blocks : no building allowed, pvp is fine
     *   80 - 200 blocks: building is allowed but we track what players placed so
     *                    explosions don't blow up terrain that wasn't theirs to begin with
     */
    static final double SPAWN_RADIUS     = 45.5;   // edge of the pvp-free zone
    static final double PROTECTED_RADIUS = 45.0;   // edge of full protection
    static final int    TERRAIN_RADIUS   = 200;    // outer edge — beyond this we don't care
    public static final int RETURN_TO_SPAWN_RADIUS = 512; // CyberWorldReset: players beyond this go to spawn instead of back

    private String spawnWorldName;
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;

    // nether spawn — stored separately, protected zone is always 100 blocks from 0,0 in any nether world
    private String netherSpawnWorldName;
    private double netherSpawnX;
    private double netherSpawnY;
    private double netherSpawnZ;
    private float netherSpawnYaw;
    private float netherSpawnPitch;

    // players who currently have spawn protection (cleared when they leave the zone or attack)
    private final Set<UUID> protectedPlayers = new HashSet<>();

    // tracks blocks placed by players in the 80-200 zone so explosions don't remove terrain
    private final Set<String> playerPlacedBlocks = new HashSet<>();

    private final Map<UUID, Long> randomCooldowns = new HashMap<>();

    private SpawnWarpManager warpManager;

    private File spawnFile;
    private File netherSpawnFile;
    private File placedFile;
    private File cooldownFile;
    private File protectionFile;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();

        spawnFile       = new File(getDataFolder(), "spawn.yml");
        netherSpawnFile = new File(getDataFolder(), "netherspawn.yml");
        placedFile      = new File(getDataFolder(), "placed_blocks.yml");
        cooldownFile    = new File(getDataFolder(), "random_cooldowns.yml");
        protectionFile  = new File(getDataFolder(), "spawn_protected_players.yml");

        loadSpawn();
        loadNetherSpawn();
        loadProtectedPlayers();
        loadPlacedBlocks();
        loadRandomCooldowns();

        warpManager = new SpawnWarpManager(this);

        getCommand("spawn").setExecutor(new SpawnCommand(this));
        getCommand("netherspawn").setExecutor(new NetherSpawnCommand(this));
        getCommand("random").setExecutor(new RandomCommand(this));
        getCommand("kill").setExecutor(new KillCommand(this));
        getServer().getPluginManager().registerEvents(new SpawnListener(this), this);

        // make sure mooshrooms don't despawn between restarts
        for (org.bukkit.World world : getServer().getWorlds()) {
            for (org.bukkit.entity.MushroomCow mc : world.getEntitiesByClass(org.bukkit.entity.MushroomCow.class)) {
                mc.setPersistent(true);
            }
        }

        // force clear weather everywhere — rain just gets annoying
        for (org.bukkit.World world : getServer().getWorlds()) {
            world.setStorm(false);
            world.setThundering(false);
            world.setWeatherDuration(Integer.MAX_VALUE);
        }

        getLogger().info("SpawnPlugin enabled" + (spawnWorldName != null ? " — spawn loaded." : " — no spawn set yet."));
    }

    @Override
    public void onDisable() {
        saveSpawn();
        saveNetherSpawn();
        saveProtectedPlayers();
        savePlacedBlocks();
        saveRandomCooldowns();
        getLogger().info("SpawnPlugin disabled.");
    }

    // --- spawn location ---

    private void loadSpawn() {
        if (!spawnFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(spawnFile);
        if (!cfg.contains("spawn.world")) return;
        try {
            spawnWorldName = cfg.getString("spawn.world");
            spawnX = cfg.getDouble("spawn.x");
            spawnY = cfg.getDouble("spawn.y");
            spawnZ = cfg.getDouble("spawn.z");
            spawnYaw = (float) cfg.getDouble("spawn.yaw");
            spawnPitch = (float) cfg.getDouble("spawn.pitch");
        } catch (Exception e) {
            getLogger().warning("Failed to load spawn: " + e.getMessage());
        }
    }

    private void saveSpawn() {
        YamlConfiguration cfg = new YamlConfiguration();
        if (spawnWorldName != null) {
            cfg.set("spawn.world", spawnWorldName);
            cfg.set("spawn.x",     spawnX);
            cfg.set("spawn.y",     spawnY);
            cfg.set("spawn.z",     spawnZ);
            cfg.set("spawn.yaw",   (double) spawnYaw);
            cfg.set("spawn.pitch", (double) spawnPitch);
        }
        try {
            cfg.save(spawnFile);
        } catch (Exception e) {
            getLogger().severe("Failed to save spawn.yml: " + e.getMessage());
        }
    }

    // --- public API ---

    public Location getSpawnLocation() {
        if (spawnWorldName == null) return null;
        World world = getServer().getWorld(spawnWorldName);
        if (world == null) return null;
        return new Location(world, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
    }

    public void setSpawnLocation(Location loc) {
        if (loc.getWorld() == null) return;
        spawnWorldName = loc.getWorld().getName();
        spawnX = loc.getX();
        spawnY = loc.getY();
        spawnZ = loc.getZ();
        spawnYaw = loc.getYaw();
        spawnPitch = loc.getPitch();
        saveSpawn();
    }

    public void clearSpawnLocation() {
        spawnWorldName = null;
        saveSpawn();
    }

    // --- nether spawn location ---

    private void loadNetherSpawn() {
        if (!netherSpawnFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(netherSpawnFile);
        if (!cfg.contains("netherspawn.world")) return;
        try {
            netherSpawnWorldName = cfg.getString("netherspawn.world");
            netherSpawnX     = cfg.getDouble("netherspawn.x");
            netherSpawnY     = cfg.getDouble("netherspawn.y");
            netherSpawnZ     = cfg.getDouble("netherspawn.z");
            netherSpawnYaw   = (float) cfg.getDouble("netherspawn.yaw");
            netherSpawnPitch = (float) cfg.getDouble("netherspawn.pitch");
        } catch (Exception e) {
            getLogger().warning("Failed to load netherspawn: " + e.getMessage());
        }
    }

    private void saveNetherSpawn() {
        YamlConfiguration cfg = new YamlConfiguration();
        if (netherSpawnWorldName != null) {
            cfg.set("netherspawn.world", netherSpawnWorldName);
            cfg.set("netherspawn.x",     netherSpawnX);
            cfg.set("netherspawn.y",     netherSpawnY);
            cfg.set("netherspawn.z",     netherSpawnZ);
            cfg.set("netherspawn.yaw",   (double) netherSpawnYaw);
            cfg.set("netherspawn.pitch", (double) netherSpawnPitch);
        }
        try {
            cfg.save(netherSpawnFile);
        } catch (Exception e) {
            getLogger().severe("Failed to save netherspawn.yml: " + e.getMessage());
        }
    }

    public Location getNetherSpawnLocation() {
        if (netherSpawnWorldName == null) return null;
        World world = getServer().getWorld(netherSpawnWorldName);
        if (world == null) return null;
        return new Location(world, netherSpawnX, netherSpawnY, netherSpawnZ, netherSpawnYaw, netherSpawnPitch);
    }

    public void setNetherSpawnLocation(Location loc) {
        if (loc.getWorld() == null) return;
        netherSpawnWorldName = loc.getWorld().getName();
        netherSpawnX     = loc.getX();
        netherSpawnY     = loc.getY();
        netherSpawnZ     = loc.getZ();
        netherSpawnYaw   = loc.getYaw();
        netherSpawnPitch = loc.getPitch();
        saveNetherSpawn();
    }

    public void clearNetherSpawnLocation() {
        netherSpawnWorldName = null;
        saveNetherSpawn();
    }

    /** Inner nether zone — full spawn protection (no PvP, no mobs, no building). */
    public static final double NETHER_CORE_RADIUS = 30.0;
    /** Outer nether zone — no building, but PvP is allowed. */
    public static final double NETHER_PROTECTED_RADIUS = 100.0;

    public boolean hasProtection(UUID uuid)  { return protectedPlayers.contains(uuid); }

    public void giveProtection(UUID uuid) {
        if (protectedPlayers.add(uuid)) {
            saveProtectedPlayers();
        }
    }

    public void removeProtection(UUID uuid) {
        if (protectedPlayers.remove(uuid)) {
            saveProtectedPlayers();
        }
    }

    public SpawnWarpManager getWarpManager() { return warpManager; }

    // Called by CyberWorldReset before a region reset.
    // Returns true if the player is within 512 blocks of spawn (can be teleported back after reset).
    // Players outside this radius get sent to spawn instead.
    public boolean isWithinSpawnReturnArea(String worldName, double x, double z) {
        if (spawnWorldName == null || !spawnWorldName.equals(worldName)) return false;
        return Math.abs(x - spawnX) <= RETURN_TO_SPAWN_RADIUS
            && Math.abs(z - spawnZ) <= RETURN_TO_SPAWN_RADIUS;
    }

    // Called by CyberWorldReset to send a player to spawn (used for players outside RETURN_TO_SPAWN_RADIUS).
    public boolean sendToSpawn(Player player) {
        Location spawn = getSpawnLocation();
        if (spawn == null) return false;
        player.teleport(spawn);
        giveProtection(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "You were teleported to spawn due to a world reset.");
        return true;
    }

    private void loadProtectedPlayers() {
        if (!protectionFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(protectionFile);
        for (String entry : cfg.getStringList("protected")) {
            try {
                protectedPlayers.add(UUID.fromString(entry));
            } catch (Exception ignored) {}
        }
    }

    private void saveProtectedPlayers() {
        YamlConfiguration cfg = new YamlConfiguration();
        ArrayList<String> entries = new ArrayList<>();
        for (UUID uuid : protectedPlayers) {
            entries.add(uuid.toString());
        }
        cfg.set("protected", entries);
        try {
            cfg.save(protectionFile);
        } catch (Exception e) {
            getLogger().warning("Failed to save spawn_protected_players.yml: " + e.getMessage());
        }
    }

    // --- player-placed block tracking ---

    private static String blockKey(org.bukkit.block.Block block) {
        return block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    public boolean isPlayerPlaced(org.bukkit.block.Block block) {
        return playerPlacedBlocks.contains(blockKey(block));
    }

    public void markPlayerPlaced(org.bukkit.block.Block block) {
        playerPlacedBlocks.add(blockKey(block));
        savePlacedBlocks();
    }

    public void unmarkPlayerPlaced(org.bukkit.block.Block block) {
        playerPlacedBlocks.remove(blockKey(block));
        savePlacedBlocks();
    }

    private void loadPlacedBlocks() {
        if (!placedFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(placedFile);
        playerPlacedBlocks.addAll(cfg.getStringList("blocks"));
    }

    private void savePlacedBlocks() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("blocks", new java.util.ArrayList<>(playerPlacedBlocks));
        try {
            cfg.save(placedFile);
        } catch (Exception e) {
            getLogger().warning("Failed to save placed_blocks.yml: " + e.getMessage());
        }
    }

    // --- /random cooldowns ---

    public long getRandomCooldown(UUID uuid) {
        return randomCooldowns.getOrDefault(uuid, 0L);
    }

    public void setRandomCooldown(UUID uuid, long timestamp) {
        randomCooldowns.put(uuid, timestamp);
        saveRandomCooldowns();
    }

    private void saveRandomCooldowns() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : randomCooldowns.entrySet())
            cfg.set("cooldowns." + e.getKey(), e.getValue());
        try {
            cfg.save(cooldownFile);
        } catch (Exception e) {
            getLogger().warning("Failed to save random_cooldowns.yml: " + e.getMessage());
        }
    }

    private void loadRandomCooldowns() {
        if (!cooldownFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(cooldownFile);
        if (!cfg.contains("cooldowns")) return;
        for (String key : cfg.getConfigurationSection("cooldowns").getKeys(false)) {
            try {
                randomCooldowns.put(UUID.fromString(key), cfg.getLong("cooldowns." + key));
            } catch (Exception ignored) {}
        }
    }
}
