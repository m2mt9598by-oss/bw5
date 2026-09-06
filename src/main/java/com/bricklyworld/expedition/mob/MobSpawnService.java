package com.bricklyworld.expedition.mob;

import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneMarker;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns every zone's MOB_SPAWN/BOSS_SPAWN markers when its raid goes
 * ACTIVE, and removes everything it spawned once the zone is RESTORED.
 * Also runs the "give up the chase" half of the concept doc's mob
 * behaviour: a lightweight repeating task that clears a tracked mob's
 * target once it has wandered further than its archetype's detection
 * radius, instead of letting vanilla AI chase forever.
 */
public final class MobSpawnService implements Listener {

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final ActiveMobRegistry registry;

    private static final double BOSS_HEALTH_MULTIPLIER = 12.0;
    private static final double BOSS_DAMAGE_MULTIPLIER = 4.0;
    private static final double LEASH_SLACK = 1.5; // give up once this far past the archetype's own detection radius

    public MobSpawnService(Plugin plugin, ZoneRegistry zoneRegistry, ActiveMobRegistry registry) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.registry = registry;
        Bukkit.getScheduler().runTaskTimer(plugin, this::leashTick, 40L, 40L);
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        if (event.to() == RaidState.ACTIVE) {
            ZoneManifest manifest = zoneRegistry.get(event.zoneId());
            if (manifest != null) {
                spawnForZone(event.zoneId(), manifest);
            }
        } else if (event.to() == RaidState.RESTORED) {
            despawnZone(event.zoneId());
        }
    }

    public void spawnForZone(int zoneId, ZoneManifest manifest) {
        if (manifest.bounds() == null) return;
        World world = Bukkit.getWorld(manifest.bounds().worldName());
        if (world == null) {
            plugin.getLogger().warning("Не удалось заспавнить мобов зоны #" + zoneId + " - мир \"" + manifest.bounds().worldName() + "\" не загружен.");
            return;
        }
        for (ZoneMarker marker : manifest.markers(MarkerType.MOB_SPAWN)) {
            spawnOne(world, marker, zoneId, false);
        }
        for (ZoneMarker marker : manifest.markers(MarkerType.BOSS_SPAWN)) {
            spawnOne(world, marker, zoneId, true);
        }
    }

    /** Used by the boss-adds behaviour too - spawns one regular (non-boss) mob of a given archetype near a location. */
    public LivingEntity spawnAdd(MobArchetype archetype, Location location, int zoneId) {
        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, archetype.baseType());
        applyAttributes(entity, archetype, 1.0, 1.0);
        nameEntity(entity, archetype, false);
        registry.tagAndTrack(entity, archetype, zoneId, false);
        return entity;
    }

    private void spawnOne(World world, ZoneMarker marker, int zoneId, boolean boss) {
        MobArchetype archetype = resolveArchetype(marker.meta());
        Location loc = safeSpawnLocation(world, marker);
        Entity spawned = world.spawnEntity(loc, archetype.baseType());
        if (!(spawned instanceof LivingEntity entity)) {
            spawned.remove();
            return;
        }
        applyAttributes(entity, archetype, boss ? BOSS_HEALTH_MULTIPLIER : 1.0, boss ? BOSS_DAMAGE_MULTIPLIER : 1.0);
        nameEntity(entity, archetype, boss);
        registry.tagAndTrack(entity, archetype, zoneId, boss);
    }

    /**
     * BUG FIX (2026-09-06, real playtest report - "мобы часто спавнятся в
     * стенах и умирают"): spawnOne used to spawn exactly at the marker's
     * raw x/y/z with no check that the space is actually clear, so an
     * admin-placed marker even one block off from the surface (easy to do
     * when eyeballing a spot in the world) suffocates the mob in a wall or
     * ceiling the instant it spawns. This searches a small vertical range
     * around the marker's Y for the first spot with two clear (non-solid)
     * blocks - feet and head - falling back to the marker's exact
     * coordinates unchanged if nothing better is found nearby (so a
     * genuinely bad marker placement still spawns something rather than
     * silently spawning nothing).
     */
    private static final int SAFE_SPAWN_SEARCH_RANGE = 4;

    private Location safeSpawnLocation(World world, ZoneMarker marker) {
        int x = (int) Math.floor(marker.x());
        int z = (int) Math.floor(marker.z());
        int baseY = (int) Math.round(marker.y());
        for (int dy = 0; dy <= SAFE_SPAWN_SEARCH_RANGE; dy++) {
            if (dy == 0) {
                if (isClear(world, x, baseY, z)) {
                    return new Location(world, marker.x(), marker.y(), marker.z(), marker.yaw(), marker.pitch());
                }
                continue;
            }
            if (isClear(world, x, baseY + dy, z)) {
                return new Location(world, marker.x(), baseY + dy, marker.z(), marker.yaw(), marker.pitch());
            }
            if (isClear(world, x, baseY - dy, z)) {
                return new Location(world, marker.x(), baseY - dy, marker.z(), marker.yaw(), marker.pitch());
            }
        }
        // Nothing clear found nearby - spawn as configured rather than
        // silently skipping the marker; at least logs discoverably via the
        // mob dying if it really is stuck.
        return new Location(world, marker.x(), marker.y(), marker.z(), marker.yaw(), marker.pitch());
    }

    private boolean isClear(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        return !feet.getType().isSolid() && !head.getType().isSolid();
    }

    private MobArchetype resolveArchetype(String meta) {
        if (meta != null && !meta.isBlank()) {
            try {
                return MobArchetype.valueOf(meta.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to random - an admin typed something that isn't a real archetype name
            }
        }
        MobArchetype[] values = MobArchetype.values();
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }

    /**
     * PARTIAL MITIGATION (2026-09-06, real playtest report - "Ники боссов и
     * мобов видно сквозь стены, такого не должно быть"): vanilla custom
     * nameplates aren't occluded by terrain at all - the client renders them
     * at any distance within render range regardless of line of sight, and
     * there's no safe public Bukkit API to change that (real occlusion would
     * mean per-viewer, per-tick line-of-sight raycasting and toggling name
     * visibility with packets - a ProtocolLib-class feature, not something
     * to bolt on here without one). What IS a reasonable, safe fix: regular
     * mobs no longer show a permanent nameplate at all (their archetype name
     * added little value floating over every zombie-reskin anyway), while
     * bosses keep theirs since knowing where "the boss" is matters and
     * there's usually only one or two of them nearby at once - so this cuts
     * the volume of the complaint drastically without an unsupported hack.
     */
    private void nameEntity(LivingEntity entity, MobArchetype archetype, boolean boss) {
        entity.setCustomName((boss ? "§4[БОСС] §c" : "§c") + archetype.displayName());
        entity.setCustomNameVisible(boss);
        entity.setRemoveWhenFarAway(false);
    }

    private void applyAttributes(LivingEntity entity, MobArchetype archetype, double healthMultiplier, double damageMultiplier) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            double newMax = archetype.maxHealth() * healthMultiplier;
            maxHealth.setBaseValue(newMax);
            entity.setHealth(newMax);
        }
        AttributeInstance speed = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * archetype.speedMultiplier());
        }
        AttributeInstance damage = entity.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        if (damage != null && damageMultiplier != 1.0) {
            damage.setBaseValue(damage.getBaseValue() * damageMultiplier);
        }
        AttributeInstance followRange = entity.getAttribute(Attribute.GENERIC_FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(archetype.detectionRadius());
        }
    }

    public void despawnZone(int zoneId) {
        for (var id : new HashSet<>(registry.spawnedIn(zoneId))) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        registry.forgetZone(zoneId);
    }

    private void leashTick() {
        Set<Integer> zones = new HashSet<>(registry.activeZones());
        for (int zoneId : zones) {
            for (var id : registry.spawnedIn(zoneId)) {
                Entity entity = Bukkit.getEntity(id);
                if (!(entity instanceof Mob mob)) continue;
                LivingEntity target = mob.getTarget();
                if (target == null) continue;

                MobArchetype archetype = registry.archetypeOf(mob);
                double leashRange = (archetype != null ? archetype.detectionRadius() : 16.0) * LEASH_SLACK;
                if (!mob.getWorld().equals(target.getWorld()) || mob.getLocation().distance(target.getLocation()) > leashRange) {
                    mob.setTarget(null);
                }
            }
        }
    }
}
