package com.spawnplugin;

import com.adminplugin.AdminPlugin;
import com.adminplugin.StaffRank;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.entity.MushroomCow;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SpawnListener implements Listener {

    private final SpawnPlugin plugin;
    private final NamespacedKey NETHERITE_KB_KEY;

    /*
     * When CyberWorldReset kicks players to the lobby before a region reset, we save
     * where they were standing so we can handle the return correctly:
     *
     *   - inside the reset area (512 blocks of spawn) → they come back to spawn + get protection
     *   - outside the reset area → CWR would normally dump them at world spawn too, so we
     *     override that and send them back to where they actually were
     */
    private final Map<UUID, Location> savedWorldLocation = new HashMap<>();
    private final Map<UUID, Boolean>  wasInResetArea     = new HashMap<>();

    // matches the 4 region files in the spawn_area reset group (r.-1.-1 / r.-1.0 / r.0.-1 / r.0.0)
    // which together cover 512 blocks in each direction from 0,0
    private static final double RESET_RADIUS = 512.0;

    public SpawnListener(SpawnPlugin plugin) {
        this.plugin = plugin;
        NETHERITE_KB_KEY = new NamespacedKey(plugin, "netherite_kb_cancel");
    }

    // --- join / quit ---

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        Player player = event.getPlayer();
        player.setCollidable(false);
        addToNoCollideTeam(player);
        Bukkit.getScheduler().runTask(plugin, () -> applyNetheriteKBCancel(player));
        Location spawn = plugin.getSpawnLocation();

        if (!player.hasPlayedBefore()) {
            // First join — send to spawn with protection
            if (spawn != null) {
                player.teleport(spawn);
                plugin.giveProtection(player.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Welcome! You have spawn protection.");
            }
        } else {
            // Returning player — stay at logout position
            // Give protection only if they logged out inside the spawn square
            if (spawn != null) {
                Location loc = player.getLocation();
                boolean inSpawn = loc.getWorld().equals(spawn.getWorld())
                        && inSpawnSquare(loc, spawn, SpawnPlugin.SPAWN_RADIUS);
                if (inSpawn) {
                    plugin.giveProtection(player.getUniqueId());
                }
            }
        }
    }

    // --- netherite knockback resistance removal ---
    // netherite armor gives 10% knockback resistance per piece by default — we strip that
    // out and re-apply a negative modifier so it cancels itself. a bit hacky but it works.

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> applyNetheriteKBCancel(event.getPlayer()));
    }

    private void addToNoCollideTeam(Player player) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = sb.getTeam("NoCollide");
        if (team == null) {
            team = sb.registerNewTeam("NoCollide");
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        team.addEntry(player.getName());
    }

    private void applyNetheriteKBCancel(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (attr == null) return;
        attr.getModifiers().stream()
                .filter(m -> NETHERITE_KB_KEY.equals(m.getKey()))
                .forEach(attr::removeModifier);
        int count = 0;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece != null) {
                String name = piece.getType().name();
                if (name.startsWith("NETHERITE_") && (name.endsWith("_HELMET")
                        || name.endsWith("_CHESTPLATE")
                        || name.endsWith("_LEGGINGS")
                        || name.endsWith("_BOOTS"))) {
                    count++;
                }
            }
        }
        if (count > 0) {
            attr.addModifier(new AttributeModifier(NETHERITE_KB_KEY,
                    -(count * 0.1), AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getWarpManager().cancel(uuid);
        savedWorldLocation.remove(uuid);
        wasInResetArea.remove(uuid);
        Bukkit.getScheduler().runTask(plugin, () -> plugin.removeProtection(uuid));
    }

    // --- region reset return logic ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeaveMainWorld(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;
        if (from.getWorld().equals(to.getWorld())) return; // same-world teleport, skip

        Location spawnLoc = plugin.getSpawnLocation();
        if (spawnLoc == null) return;
        if (!from.getWorld().equals(spawnLoc.getWorld())) return; // not leaving main world

        UUID uuid = event.getPlayer().getUniqueId();
        savedWorldLocation.put(uuid, from.clone());
        boolean inReset = Math.abs(from.getX() - spawnLoc.getX()) <= RESET_RADIUS
                       && Math.abs(from.getZ() - spawnLoc.getZ()) <= RESET_RADIUS;
        wasInResetArea.put(uuid, inReset);
    }

    @EventHandler
    public void onReturnToMainWorld(PlayerChangedWorldEvent event) {
        Player player   = event.getPlayer();
        Location spawnLoc = plugin.getSpawnLocation();
        if (spawnLoc == null) return;
        if (!player.getWorld().equals(spawnLoc.getWorld())) return; // didn't arrive in main world

        UUID uuid = player.getUniqueId();
        Boolean inReset  = wasInResetArea.remove(uuid);
        Location savedLoc = savedWorldLocation.remove(uuid);
        if (inReset == null || savedLoc == null) return;

        if (inReset) {
            // Was inside reset area — CyberWorldReset already sent them to spawn.
            // Grant spawn protection so they land safely.
            plugin.giveProtection(uuid);
        } else {
            // Was outside reset area — teleport back to their original location.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                Location currentWorldLoc = savedLoc.clone();
                currentWorldLoc.setWorld(player.getWorld());
                player.teleport(currentWorldLoc);
            }, 5L);
        }
    }

    // --- movement ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();

        // Skip pure camera rotation (no x/y/z change)
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Cancel /spawn countdown if player moved
        if (plugin.getWarpManager().isPending(uuid)) {
            plugin.getWarpManager().cancel(uuid);
            player.sendMessage(ChatColor.RED + "Teleport cancelled — you moved.");
            return;
        }

        // Remove protection when player walks outside the spawn square
        if (plugin.hasProtection(uuid)) {
            Location spawn = plugin.getSpawnLocation();
            if (spawn == null) return;
            boolean leftWorld  = !to.getWorld().equals(spawn.getWorld());
            boolean leftSquare = !inSpawnSquare(to, spawn, SpawnPlugin.SPAWN_RADIUS);
            if (leftWorld || leftSquare) {
                plugin.removeProtection(uuid);
                player.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "You have left spawn protection.");
            }
        }
    }

    // spawn eggs are blocked within 80 blocks of spawn — people were using them to
    // fill spawn with animals and tank the server TPS

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnEggUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack item = event.getItem();
        if (item == null || !item.getType().name().endsWith("_SPAWN_EGG")) return;
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) return;

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        Location loc = event.getPlayer().getLocation();
        if (!loc.getWorld().equals(spawn.getWorld())) return;

        if (inSpawnSquare(loc, spawn, 80.0)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot use spawn eggs within 80 blocks of spawn.");
        }
    }

    // --- velocity / push protection ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (!plugin.hasProtection(event.getPlayer().getUniqueId())) return;
        // Allow sponge launches through even with spawn protection
        Player vp = event.getPlayer();
        Block underFeet = vp.getLocation().getBlock().getRelative(0, -1, 0);
        if (underFeet.getType() == Material.SPONGE) return;
        event.setCancelled(true);
    }

    // all damage is blocked while protection is active — mobs, pvp, fall, fire, drowning, etc.

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.KILL) return; // /kill bypasses protection
        Player victim = (Player) event.getEntity();
        if (plugin.hasProtection(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ── PvP: protected attacker hits an unprotected player → forfeit protection ──

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player defender = (Player) event.getEntity();
        Player attacker = resolveAttacker(event);

        // Tell attacker when the defender is protected
        if (plugin.hasProtection(defender.getUniqueId()) && attacker != null) {
            attacker.sendMessage(ChatColor.YELLOW + "This player has spawn protection.");
        }

        // Cancel /spawn countdown if defender is struck
        if (plugin.getWarpManager().isPending(defender.getUniqueId())) {
            plugin.getWarpManager().cancel(defender.getUniqueId());
            defender.sendMessage(ChatColor.RED + "Teleport cancelled — you were hit.");
        }
    }

    // Runs after all other handlers (friendly fire, spawn protection) have had their say.
    // Only forfeits the attacker's spawn protection if damage actually went through.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttackerForfeitsProtection(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player defender = (Player) event.getEntity();
        Player attacker = resolveAttacker(event);
        if (attacker == null) return;
        if (plugin.hasProtection(attacker.getUniqueId()) && !plugin.hasProtection(defender.getUniqueId())) {
            plugin.removeProtection(attacker.getUniqueId());
            attacker.sendMessage(ChatColor.RED + "" + ChatColor.BOLD
                    + "You attacked a player — spawn protection removed!");
        }
    }

    // --- block protection ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;

        Block block = event.getBlock();
        if (!block.getWorld().equals(spawn.getWorld())) return;

        if (inSpawnCube(block.getLocation(), spawn, 80.0)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot break blocks within 80 blocks of spawn.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;

        Block block = event.getBlock();
        if (!block.getWorld().equals(spawn.getWorld())) return;

        if (inSpawnCube(block.getLocation(), spawn, 80.0)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot place blocks within 80 blocks of spawn.");
        } else if (inSpawnCube(block.getLocation(), spawn, SpawnPlugin.TERRAIN_RADIUS)) {
            // 101–200: placing allowed — track so the player can break it later
            plugin.markPlayerPlaced(block);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;

        // The liquid lands on the face of the clicked block
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (!target.getWorld().equals(spawn.getWorld())) return;

        if (inSpawnCube(target.getLocation(), spawn, SpawnPlugin.TERRAIN_RADIUS)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot place liquids within "
                    + SpawnPlugin.TERRAIN_RADIUS + " blocks of spawn.");
        }
    }

    // --- mob spawn prevention ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();

        // Allow non-natural reasons (eggs, breeding, taming, etc.)
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL
                && reason != CreatureSpawnEvent.SpawnReason.CHUNK_GEN
                && reason != CreatureSpawnEvent.SpawnReason.PATROL) return;

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;

        Location loc = event.getLocation();
        if (!loc.getWorld().equals(spawn.getWorld())) return;

        // bump this to 50 instead of PROTECTED_RADIUS (45) — passive mobs were spawning
        // in the 45-50 block gap and counting against the spawner's area cap in SpawnerPlugin
        if (inSpawnSquare(loc, spawn, 50.0)) {
            event.setCancelled(true);
            return;
        }

        // Block hostile mobs from naturally spawning within 100 blocks of spawn
        if (inSpawnSquare(loc, spawn, 100.0) && event.getEntity() instanceof Monster) {
            event.setCancelled(true);
        }
    }

    // mooshrooms despawn without this — mark them persistent on spawn and on chunk load

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMooshroomSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof MushroomCow) {
            event.getEntity().setPersistent(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (org.bukkit.entity.Entity e : event.getChunk().getEntities()) {
            if (e instanceof MushroomCow) {
                e.setPersistent(true);
            }
        }
    }

    // max 1 creeper per chunk — they were stacking up near spawn and chain-exploding
    // spawn eggs bypass this intentionally

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreeperSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Creeper)) return;
        CreatureSpawnEvent.SpawnReason r = event.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || r == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG) return;

        long count = java.util.Arrays.stream(event.getLocation().getChunk().getEntities())
                .filter(e -> e instanceof Creeper)
                .count();
        if (count >= 1) event.setCancelled(true);
    }

    // death messages are only shown to players within 150 blocks — no reason for
    // someone on the other side of the map to see it

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        net.kyori.adventure.text.Component msg = event.deathMessage();
        if (msg == null) return;

        event.deathMessage(null);

        Location loc = event.getPlayer().getLocation();
        double radiusSq = 150.0 * 150.0;
        for (Player p : event.getPlayer().getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= radiusSq) {
                p.sendMessage(msg);
            }
        }
    }

    // --- respawn ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn()) return;
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        event.setRespawnLocation(spawn);
        plugin.giveProtection(event.getPlayer().getUniqueId());
        event.getPlayer().setCollidable(false);
        Bukkit.getScheduler().runTask(plugin, () -> applyNetheriteKBCancel(event.getPlayer()));
    }

    // --- weather --- keep it clear permanently

    @EventHandler(ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    // --- block growth / spread prevention ---
    // stop grass/vines/mushrooms from slowly eating the spawn build

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        Block block = event.getBlock();
        if (!block.getWorld().equals(spawn.getWorld())) return;
        if (inSpawnCube(block.getLocation(), spawn, SpawnPlugin.PROTECTED_RADIUS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        Block block = event.getBlock();
        if (!block.getWorld().equals(spawn.getWorld())) return;
        if (inSpawnCube(block.getLocation(), spawn, SpawnPlugin.PROTECTED_RADIUS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        if (!event.getLocation().getWorld().equals(spawn.getWorld())) return;
        if (inSpawnCube(event.getLocation(), spawn, SpawnPlugin.PROTECTED_RADIUS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockFertilize(BlockFertilizeEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        Block block = event.getBlock();
        if (!block.getWorld().equals(spawn.getWorld())) return;
        if (!inSpawnCube(block.getLocation(), spawn, SpawnPlugin.PROTECTED_RADIUS)) return;

        // ADMIN and OWNER may use bone meal in spawn
        Player player = event.getPlayer();
        if (player != null && isAdminOrOwner(player)) return;

        event.setCancelled(true);
    }

    // endermen kept stealing blocks from the spawn build, so block them from touching anything in the terrain zone

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Enderman)) return;

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;

        Block block = event.getBlock();
        if (!block.getWorld().equals(spawn.getWorld())) return;

        if (inSpawnCube(block.getLocation(), spawn, SpawnPlugin.TERRAIN_RADIUS)) {
            event.setCancelled(true);
        }
    }

    // --- forbidden potions ---
    // oozing, infested, weaving, wind charged are all disabled — they either spawn mobs
    // near spawn or have effects that are too annoying to deal with competitively

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrinkForbiddenPotion(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (item.getType() != Material.POTION) return;

        String message = null;
        if (isOozingPotion(item) || isInfestedPotion(item)
                || isWindChargedPotion(item) || isWeavingPotion(item)) {
            message = ChatColor.RED + "You can't drink this potion.";
        }
        if (message == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isSimilar(item)) {
            if (held.getAmount() > 1) {
                held.setAmount(held.getAmount() - 1);
                player.getInventory().addItem(new ItemStack(Material.GLASS_BOTTLE));
            } else {
                player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE));
            }
        } else {
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (offhand.getAmount() > 1) {
                offhand.setAmount(offhand.getAmount() - 1);
                player.getInventory().addItem(new ItemStack(Material.GLASS_BOTTLE));
            } else {
                player.getInventory().setItemInOffHand(new ItemStack(Material.GLASS_BOTTLE));
            }
        }
        player.sendMessage(message);
    }

    // lingering potions are fully disabled

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLingeringPotionThrow(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.LINGERING_POTION) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "Lingering potions are disabled.");
    }

    // block the effect itself too in case someone gets it another way (e.g. beacon, command)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        EntityPotionEffectEvent.Action action = event.getAction();
        if (action != EntityPotionEffectEvent.Action.ADDED
                && action != EntityPotionEffectEvent.Action.CHANGED) return;
        if (event.getNewEffect() == null) return;
        PotionEffectType t = event.getNewEffect().getType();
        if (t.equals(PotionEffectType.WIND_CHARGED)
                || t.equals(PotionEffectType.WEAVING)
                || t.equals(PotionEffectType.OOZING)
                || t.equals(PotionEffectType.INFESTED)) {
            event.setCancelled(true);
            return;
        }
    }

    // block them at the brewing stage too so they can't be made in the first place
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        BrewerInventory inv = event.getContents();
        boolean blocked = false;
        for (int i = 0; i < 3; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == Material.LINGERING_POTION) {
                inv.setItem(i, new ItemStack(Material.GLASS_BOTTLE));
                blocked = true;
            } else if (isOozingPotion(item) || isInfestedPotion(item)
                    || isWeavingPotion(item) || isWindChargedPotion(item)) {
                inv.setItem(i, new ItemStack(Material.GLASS_BOTTLE));
                blocked = true;
            }
        }
        if (blocked) {
            for (HumanEntity viewer : inv.getViewers()) {
                viewer.sendMessage(ChatColor.RED + "You can't brew this item.");
            }
        }
    }

    // also catch when they drag/click the ingredient into the stand before it's brewed
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewingClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        BrewerInventory inv = (BrewerInventory) event.getInventory();
        Bukkit.getScheduler().runTask(plugin, () -> removeForbiddenIngredient(inv, player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewingDrag(InventoryDragEvent event) {
        if (!(event.getInventory() instanceof BrewerInventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        BrewerInventory inv = (BrewerInventory) event.getInventory();
        Bukkit.getScheduler().runTask(plugin, () -> removeForbiddenIngredient(inv, player));
    }

    private void removeForbiddenIngredient(BrewerInventory inv, Player player) {
        ItemStack ingredient = inv.getIngredient();
        if (ingredient == null) return;
        Material mat = ingredient.getType();

        boolean forbidden = false;
        // Dragon's Breath → Lingering (always forbidden)
        if (mat == Material.DRAGON_BREATH) {
            forbidden = true;
        }
        // Slime Block + Awkward → Oozing
        if (!forbidden && mat == Material.SLIME_BLOCK) {
            for (int i = 0; i < 3; i++) {
                if (isAwkwardPotion(inv.getItem(i))) { forbidden = true; break; }
            }
        }
        // Stone + Awkward → Infested
        if (!forbidden && mat == Material.STONE) {
            for (int i = 0; i < 3; i++) {
                if (isAwkwardPotion(inv.getItem(i))) { forbidden = true; break; }
            }
        }
        // Cobweb + Awkward → Weaving
        if (!forbidden && mat == Material.COBWEB) {
            for (int i = 0; i < 3; i++) {
                if (isAwkwardPotion(inv.getItem(i))) { forbidden = true; break; }
            }
        }
        // Breeze Rod + Awkward → Wind Charged
        if (!forbidden && mat == Material.BREEZE_ROD) {
            for (int i = 0; i < 3; i++) {
                if (isAwkwardPotion(inv.getItem(i))) { forbidden = true; break; }
            }
        }

        if (forbidden) {
            ItemStack toReturn = ingredient.clone();
            inv.setIngredient(null);
            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(toReturn);
            leftover.values().forEach(drop ->
                    player.getWorld().dropItemNaturally(player.getLocation(), drop));
            player.sendMessage(ChatColor.RED + "You can't brew this item.");
        }
    }

    private boolean isAwkwardPotion(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta)) return false;
        PotionType type = meta.getBasePotionType();
        return type == PotionType.AWKWARD;
    }

    private boolean isOozingPotion(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta)) return false;
        PotionType type = meta.getBasePotionType();
        if (type != null && type.name().contains("OOZING")) return true;
        for (PotionEffect eff : meta.getCustomEffects())
            if (eff.getType().equals(PotionEffectType.OOZING)) return true;
        return false;
    }

    private boolean isInfestedPotion(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta)) return false;
        PotionType type = meta.getBasePotionType();
        if (type != null && type.name().contains("INFESTED")) return true;
        for (PotionEffect eff : meta.getCustomEffects())
            if (eff.getType().equals(PotionEffectType.INFESTED)) return true;
        return false;
    }

    private boolean isWeavingPotion(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta)) return false;
        PotionType type = meta.getBasePotionType();
        if (type != null && type.name().contains("WEAVING")) return true;
        for (PotionEffect eff : meta.getCustomEffects())
            if (eff.getType().equals(PotionEffectType.WEAVING)) return true;
        return false;
    }

    private boolean isWindChargedPotion(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof PotionMeta meta)) return false;
        PotionType type = meta.getBasePotionType();
        if (type != null && type.name().contains("WIND_CHARGED")) return true;
        for (PotionEffect eff : meta.getCustomEffects())
            if (eff.getType().equals(PotionEffectType.WIND_CHARGED)) return true;
        return false;
    }

    // --- loot filtering ---
    // strip Curse of Binding from all loot — it's just annoying with no real gameplay upside
    // also remove overpowered gear from End loot tables

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        boolean isEnd = event.getWorld().getEnvironment() == org.bukkit.World.Environment.THE_END;
        List<ItemStack> filtered = new ArrayList<>();
        for (ItemStack item : event.getLoot()) {
            if (item == null) continue;
            stripBindingCurse(item);
            if (isEnd && isEndBannedItem(item)) continue;
            filtered.add(item);
        }
        event.setLoot(filtered);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFishCatch(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(event.getCaught() instanceof org.bukkit.entity.Item caught)) return;
        ItemStack item = caught.getItemStack();
        if (stripBindingCurse(item)) caught.setItemStack(item);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null) return;
        if (stripBindingCurse(result)) event.setResult(result);
    }

    private boolean stripBindingCurse(ItemStack item) {
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasEnchant(Enchantment.BINDING_CURSE)) return false;
        meta.removeEnchant(Enchantment.BINDING_CURSE);
        item.setItemMeta(meta);
        return true;
    }

    private boolean isEndBannedItem(ItemStack item) {
        String name = item.getType().name();
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) return true;
        if (name.equals("DIAMOND_SWORD") || name.equals("DIAMOND")) return true;
        // Strip any item with Mending from End loot
        if (item.hasItemMeta() && item.getItemMeta().hasEnchant(Enchantment.MENDING)) return true;
        return false;
    }

    // --- explosion protection ---
    // creepers/tnt can't destroy blocks inside the protected zone
    // in the outer zone (80-200) we only protect terrain — player-placed blocks can still be blown up

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        if (!event.getLocation().getWorld().equals(spawn.getWorld())) return;

        // Cancel wind charge burst explosions near spawn entirely (prevents knockback to protected players)
        if (event.getEntity() instanceof WindCharge
                && inSpawnSquare(event.getLocation(), spawn, SpawnPlugin.SPAWN_RADIUS + 20)) {
            event.setCancelled(true);
            return;
        }

        filterExplosionBlocks(event.blockList(), spawn);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        if (!event.getBlock().getWorld().equals(spawn.getWorld())) return;

        filterExplosionBlocks(event.blockList(), spawn);
    }

    private void filterExplosionBlocks(java.util.List<Block> blocks, Location spawn) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (inSpawnCube(block.getLocation(), spawn, SpawnPlugin.PROTECTED_RADIUS)) {
                it.remove();
            } else if (inSpawnCube(block.getLocation(), spawn, SpawnPlugin.TERRAIN_RADIUS)) {
                if (!plugin.isPlayerPlaced(block)) {
                    it.remove();
                } else {
                    plugin.unmarkPlayerPlaced(block);
                }
            }
        }
    }

    /** Square check (X and Z only) — used for movement/PvP protection boundary. */
    private boolean inSpawnSquare(Location loc, Location spawn, double halfSide) {
        return Math.abs(loc.getX() - spawn.getX()) <= halfSide
            && Math.abs(loc.getZ() - spawn.getZ()) <= halfSide;
    }

    /** Cube check (X, Y, and Z) — used for block break/place protection. */
    private boolean inSpawnCube(Location loc, Location spawn, double halfSide) {
        return Math.abs(loc.getX() - spawn.getX()) <= halfSide
            && Math.abs(loc.getY() - spawn.getY()) <= halfSide
            && Math.abs(loc.getZ() - spawn.getZ()) <= halfSide;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().equalsIgnoreCase("/kill")) return;
        Player player = event.getPlayer();
        if (player.isOp() && player.getGameMode() != GameMode.SURVIVAL) return;
        if (plugin.hasProtection(player.getUniqueId())) return;

        // Different world than spawn — no restriction
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null || !player.getWorld().equals(spawn.getWorld())) return;

        // 512+ blocks from spawn on X or Z — allow
        Location loc = player.getLocation();
        double dx = Math.abs(loc.getX() - spawn.getX());
        double dz = Math.abs(loc.getZ() - spawn.getZ());
        if (Math.max(dx, dz) >= 80) return;

        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "You cannot use /kill near spawn without spawn protection.");
    }

    private boolean isAdminOrOwner(Player player) {
        org.bukkit.plugin.Plugin ap = org.bukkit.Bukkit.getPluginManager().getPlugin("AdminPlugin");
        if (!(ap instanceof AdminPlugin)) return false;
        return ((AdminPlugin) ap).getStaffManager().isAtLeast(player.getUniqueId(), StaffRank.ADMIN);
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            return (Player) event.getDamager();
        }
        if (event.getDamager() instanceof Projectile) {
            Projectile proj = (Projectile) event.getDamager();
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player) return (Player) src;
        }
        return null;
    }
}
