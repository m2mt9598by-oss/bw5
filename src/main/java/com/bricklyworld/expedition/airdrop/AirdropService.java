package com.bricklyworld.expedition.airdrop;

import com.bricklyworld.expedition.config.ExpeditionConfig;
import com.bricklyworld.expedition.loot.LootCacheService;
import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import com.bricklyworld.expedition.raid.RaidStateMachine;
import com.bricklyworld.expedition.world.DeltaJournalManager;
import com.bricklyworld.expedition.zone.CuboidRegion;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Аирдроп может прилететь когда угодно но не позже половины игровой
 * сессии" - once a raid goes ACTIVE, a single airdrop is scheduled at a
 * random time within the first half of the session. It's a real physical
 * FallingBlock (a chest) that free-falls onto the terrain from height and
 * places itself on landing - the same trick WorldEdit-style plugins use
 * for "floating then dropped" blocks - then gets filled/tagged exactly
 * like a manifest loot cache (reusing LootCacheService's PDC keys means
 * the existing LootLockListener already knows how to gate opening it,
 * zero extra wiring needed there). The landing block is journaled into
 * the zone's DeltaJournal like any other change, so it cleanly reverts
 * with the rest of the world on raid close.
 */
public final class AirdropService implements Listener {

    private static final int AIRDROP_TIER = 8; // richer than a normal cache - it's meant to be worth the risk of rushing it
    private static final int EDGE_MARGIN = 8;
    private static final int DROP_HEIGHT_ABOVE_GROUND = 40;

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final RaidStateMachine raidStateMachine;
    private final RaidPlayerRegistry raidPlayers;
    private final DeltaJournalManager deltaJournals;
    private final LootCacheService lootCacheService;
    private final ExpeditionConfig config;
    private final Map<UUID, Integer> pendingDrops = new HashMap<>();

    public AirdropService(Plugin plugin, ZoneRegistry zoneRegistry, RaidStateMachine raidStateMachine,
                           RaidPlayerRegistry raidPlayers, DeltaJournalManager deltaJournals,
                           LootCacheService lootCacheService, ExpeditionConfig config) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.raidStateMachine = raidStateMachine;
        this.raidPlayers = raidPlayers;
        this.deltaJournals = deltaJournals;
        this.lootCacheService = lootCacheService;
        this.config = config;
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        if (event.to() != RaidState.ACTIVE) return;
        int zoneId = event.zoneId();
        long halfSessionTicks = (config.activeDurationSeconds * 20L) / 2;
        long delay = halfSessionTicks <= 0 ? 0 : ThreadLocalRandom.current().nextLong(0, halfSessionTicks + 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> attemptDrop(zoneId), delay);
    }

    private void attemptDrop(int zoneId) {
        if (raidStateMachine.stateOf(zoneId) != RaidState.ACTIVE) return; // raid ended early - no drop
        ZoneManifest manifest = zoneRegistry.get(zoneId);
        if (manifest == null || manifest.bounds() == null) return;
        CuboidRegion bounds = manifest.bounds();
        World world = Bukkit.getWorld(bounds.worldName());
        if (world == null) return;

        int width = bounds.maxX() - bounds.minX();
        int depth = bounds.maxZ() - bounds.minZ();
        int x = width > EDGE_MARGIN * 2
                ? ThreadLocalRandom.current().nextInt(bounds.minX() + EDGE_MARGIN, bounds.maxX() - EDGE_MARGIN + 1)
                : (bounds.minX() + bounds.maxX()) / 2;
        int z = depth > EDGE_MARGIN * 2
                ? ThreadLocalRandom.current().nextInt(bounds.minZ() + EDGE_MARGIN, bounds.maxZ() - EDGE_MARGIN + 1)
                : (bounds.minZ() + bounds.maxZ()) / 2;
        int landingY = world.getHighestBlockYAt(x, z) + 1;

        Location spawnLoc = new Location(world, x + 0.5, landingY + DROP_HEIGHT_ABOVE_GROUND, z + 0.5);
        FallingBlock fallingBlock = world.spawnFallingBlock(spawnLoc, Material.CHEST.createBlockData());
        fallingBlock.setDropItem(false);
        fallingBlock.setHurtEntities(false);
        pendingDrops.put(fallingBlock.getUniqueId(), zoneId);

        announceIncoming(zoneId, spawnLoc);
    }

    private void announceIncoming(int zoneId, Location loc) {
        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            player.sendTitle("§b§lАИРДРОП", "§7Груз приближается к зоне!", 10, 60, 20);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.4f);
        }
        loc.getWorld().playSound(loc, Sound.ITEM_ELYTRA_FLYING, 3.0f, 0.5f);
    }

    @EventHandler
    public void onFallingBlockLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock)) return;
        Integer zoneId = pendingDrops.remove(event.getEntity().getUniqueId());
        if (zoneId == null) return;

        var journal = deltaJournals.journalFor(zoneId);
        if (journal != null) {
            journal.recordIfAbsent(event.getBlock());
        }

        var block = event.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (block.getState() instanceof Container container) {
                lootCacheService.fillAdHocContainer(container, zoneId, AIRDROP_TIER);
            }
            block.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, block.getLocation().add(0.5, 0.5, 0.5), 2);
            block.getWorld().playSound(block.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.2f);
            for (UUID id : raidPlayers.playersIn(zoneId)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.sendMessage("§b§lГруз приземлился! §7Ищите его на карте.");
                }
            }
        });
    }

    // Particle.EXPLOSION_LARGE confirmed compiling fine against Paper 1.20.1
    // (real GitHub Actions build, 2026-09-06).
}
