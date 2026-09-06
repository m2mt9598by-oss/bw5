package com.bricklyworld.expedition.activity;

import com.bricklyworld.expedition.config.LootTierConfig;
import com.bricklyworld.expedition.loot.CorpseService;
import com.bricklyworld.expedition.mob.MobArchetype;
import com.bricklyworld.expedition.mob.MobSpawnService;
import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneMarker;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Walks every ACTIVITY_SCRIPT marker in the currently active zone once a
 * second and fires its template (see ActivityScriptType) the first time a
 * raid player gets within range, then sits on cooldown so the same point
 * can go off again for a later wave of players instead of being a strict
 * one-shot - a 30-minute session cycling many solo raiders through the
 * same map benefits more from reusable points than single-use ones.
 */
public final class ActivityScriptService {

    private static final double TRIGGER_RADIUS = 6.0;
    private static final long COOLDOWN_MILLIS = 45_000;
    private static final int AMBUSH_LOOT_TIER = 7;

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final RaidPlayerRegistry raidPlayers;
    private final MobSpawnService mobSpawnService;
    private final CorpseService corpseService;
    private final LootTierConfig tierConfig;
    private final Map<String, Long> lastTriggered = new HashMap<>();
    private BukkitTask task;

    public ActivityScriptService(Plugin plugin, ZoneRegistry zoneRegistry, RaidPlayerRegistry raidPlayers,
                                  MobSpawnService mobSpawnService, CorpseService corpseService, LootTierConfig tierConfig) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.raidPlayers = raidPlayers;
        this.mobSpawnService = mobSpawnService;
        this.corpseService = corpseService;
        this.tierConfig = tierConfig;
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    private void tick() {
        Integer zoneId = zoneRegistry.activeZoneId();
        if (zoneId == null) return;
        ZoneManifest manifest = zoneRegistry.get(zoneId);
        if (manifest == null || manifest.bounds() == null) return;
        World world = Bukkit.getWorld(manifest.bounds().worldName());
        if (world == null) return;

        for (ZoneMarker marker : manifest.markers(MarkerType.ACTIVITY_SCRIPT)) {
            String key = zoneId + ":" + marker.x() + "," + marker.y() + "," + marker.z();
            Long last = lastTriggered.get(key);
            long now = System.currentTimeMillis();
            if (last != null && now - last < COOLDOWN_MILLIS) continue;

            Location loc = new Location(world, marker.x(), marker.y(), marker.z());
            Player nearby = nearestPlayerWithin(zoneId, loc, TRIGGER_RADIUS);
            if (nearby == null) continue;

            ActivityScriptType type = resolveType(marker.meta());
            lastTriggered.put(key, now);
            fire(type, loc, zoneId);
        }
    }

    private Player nearestPlayerWithin(int zoneId, Location loc, double radius) {
        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getWorld().equals(loc.getWorld()) && player.getLocation().distance(loc) <= radius) {
                return player;
            }
        }
        return null;
    }

    private ActivityScriptType resolveType(String meta) {
        if (meta != null && !meta.isBlank()) {
            try {
                return ActivityScriptType.valueOf(meta.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to random below
            }
        }
        ActivityScriptType[] values = ActivityScriptType.values();
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }

    private void fire(ActivityScriptType type, Location loc, int zoneId) {
        switch (type) {
            case AMBUSH -> fireAmbush(loc, zoneId);
            case ALARM_TRAP -> fireAlarm(loc, zoneId);
            case TREASURE_RUSH -> fireTreasureRush(loc, zoneId);
        }
    }

    private void fireAmbush(Location loc, int zoneId) {
        int count = ThreadLocalRandom.current().nextInt(3, 6);
        MobArchetype[] values = MobArchetype.values();
        for (int i = 0; i < count; i++) {
            MobArchetype archetype = values[ThreadLocalRandom.current().nextInt(values.length)];
            mobSpawnService.spawnAdd(archetype, scatter(loc, 3.0), zoneId);
        }
        // Particle.SMOKE_LARGE confirmed by a real GitHub Actions compile
        // against Paper 1.20.1 (2026-09-06) - LARGE_SMOKE is the later
        // (1.20.5+) rename and doesn't exist on this API version.
        loc.getWorld().spawnParticle(Particle.SMOKE_LARGE, loc, 40, 2, 1, 2, 0.05);
        loc.getWorld().playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.5f, 0.6f);
        messageNear(zoneId, loc, "§c§lЗАСАДА! §7Со всех сторон появляются враги.");
    }

    private void fireAlarm(Location loc, int zoneId) {
        loc.getWorld().playSound(loc, Sound.BLOCK_BELL_USE, 3.0f, 0.5f);
        loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0, 1, 0), 30, 1, 1, 1, 0.1);
        // Note (simplification): the concept doc's "мобы становятся агрессивнее"
        // buff isn't wired to the mob AI yet - this template is currently the
        // signal/sound/particle beat only, a real aggro-radius buff on nearby
        // tagged mobs is a follow-up once MobBehaviorListener exposes a hook.
        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage("§e§lТРЕВОГА! §7Сработала сигнализация - вас услышали.");
            }
        }
    }

    private void fireTreasureRush(Location loc, int zoneId) {
        LootTierConfig.Tier tier = tierConfig.tier(AMBUSH_LOOT_TIER);
        List<org.bukkit.inventory.ItemStack> loot = tier != null ? tier.rollLoot(2, 4) : List.of();
        corpseService.spawnCorpse(loc, zoneId, "§6Тайник-приманка", loot);
        loc.getWorld().spawnParticle(Particle.TOTEM, loc.clone().add(0, 1, 0), 40, 1, 1, 1, 0.1);
        loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.3f);
        messageNear(zoneId, loc, "§6§lРядом обнаружен ценный тайник!");
    }

    private Location scatter(Location center, double radius) {
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double dist = ThreadLocalRandom.current().nextDouble(1.0, radius);
        Vector offset = new Vector(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
        return center.clone().add(offset);
    }

    private void messageNear(int zoneId, Location loc, String message) {
        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getWorld().equals(loc.getWorld()) && player.getLocation().distance(loc) <= 32) {
                player.sendMessage(message);
            }
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
        lastTriggered.clear();
    }
}
