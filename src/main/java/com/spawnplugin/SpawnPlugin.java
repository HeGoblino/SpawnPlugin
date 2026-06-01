package com.spawnplugin;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    private Location spawnLocation;

    // players who currently have spawn protection (cleared when they leave the zone or attack)
    private final Set<UUID> protectedPlayers = new HashSet<>();

    // tracks blocks placed by players in the 80-200 zone so explosions don't remove terrain
    private final Set<String> playerPlacedBlocks = new HashSet<>();

    private final Map<UUID, Long> randomCooldowns = new HashMap<>();

    private SpawnWarpManager warpManager;

    private File spawnFile;
    private File placedFile;
    private File cooldownFile;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();

        spawnFile    = new File(getDataFolder(), "spawn.yml");
        placedFile   = new File(getDataFolder(), "placed_blocks.yml");
        cooldownFile = new File(getDataFolder(), "random_cooldowns.yml");

        loadSpawn();
        loadPlacedBlocks();
        loadRandomCooldowns();

        warpManager = new SpawnWarpManager(this);

        getCommand("spawn").setExecutor(new SpawnCommand(this));
        getCommand("random").setExecutor(new RandomCommand(this));
        getServer().getPluginManager().registerEvents(new SpawnListener(this), this);

        // players clipping into each other near spawn was getting exploited for griefing
        // turning collision off globally — SpawnListener also adds new players to the NoCollide scoreboard team
        for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
            p.setCollidable(false);
        }

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

        getLogger().info("SpawnPlugin enabled" + (spawnLocation != null ? " — spawn loaded." : " — no spawn set yet."));
    }

    @Override
    public void onDisable() {
        saveSpawn();
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
            String worldName = cfg.getString("spawn.world");
            org.bukkit.World world = getServer().getWorld(worldName);
            if (world == null) {
                getLogger().warning("Spawn world '" + worldName + "' not found — did it get renamed?");
                return;
            }
            spawnLocation = new Location(world,
                    cfg.getDouble("spawn.x"),
                    cfg.getDouble("spawn.y"),
                    cfg.getDouble("spawn.z"),
                    (float) cfg.getDouble("spawn.yaw"),
                    (float) cfg.getDouble("spawn.pitch"));
        } catch (Exception e) {
            getLogger().warning("Failed to load spawn: " + e.getMessage());
        }
    }

    private void saveSpawn() {
        YamlConfiguration cfg = new YamlConfiguration();
        if (spawnLocation != null) {
            cfg.set("spawn.world", spawnLocation.getWorld().getName());
            cfg.set("spawn.x",     spawnLocation.getX());
            cfg.set("spawn.y",     spawnLocation.getY());
            cfg.set("spawn.z",     spawnLocation.getZ());
            cfg.set("spawn.yaw",   (double) spawnLocation.getYaw());
            cfg.set("spawn.pitch", (double) spawnLocation.getPitch());
        }
        try {
            cfg.save(spawnFile);
        } catch (Exception e) {
            getLogger().severe("Failed to save spawn.yml: " + e.getMessage());
        }
    }

    // --- public API ---

    public Location getSpawnLocation() { return spawnLocation; }

    public void setSpawnLocation(Location loc) {
        spawnLocation = loc.clone();
        saveSpawn();
    }

    public void clearSpawnLocation() {
        spawnLocation = null;
        saveSpawn();
    }

    public boolean hasProtection(UUID uuid)  { return protectedPlayers.contains(uuid); }
    public void    giveProtection(UUID uuid) { protectedPlayers.add(uuid); }
    public void    removeProtection(UUID uuid) { protectedPlayers.remove(uuid); }

    public SpawnWarpManager getWarpManager() { return warpManager; }

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
