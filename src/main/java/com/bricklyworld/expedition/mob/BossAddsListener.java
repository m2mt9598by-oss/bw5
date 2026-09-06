package com.bricklyworld.expedition.mob;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Может призывать помощников, когда по боссу бьют" - on a tagged boss
 * taking damage, with a per-boss cooldown so one flurry of hits doesn't
 * spawn a crowd, summon 2-3 regular mobs of a random archetype near it.
 */
public final class BossAddsListener implements Listener {

    private static final long COOLDOWN_MILLIS = 15_000;
    private static final int MIN_ADDS = 2;
    private static final int MAX_ADDS = 3;

    private final ActiveMobRegistry registry;
    private final MobSpawnService spawnService;
    private final Map<UUID, Long> lastSummon = new HashMap<>();

    public BossAddsListener(ActiveMobRegistry registry, MobSpawnService spawnService) {
        this.registry = registry;
        this.spawnService = spawnService;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity boss)) return;
        if (!registry.isOurs(boss) || !registry.isBoss(boss)) return;

        long now = System.currentTimeMillis();
        Long last = lastSummon.get(boss.getUniqueId());
        if (last != null && now - last < COOLDOWN_MILLIS) return;
        lastSummon.put(boss.getUniqueId(), now);

        Integer zoneId = registry.zoneOf(boss);
        if (zoneId == null) return;

        int count = ThreadLocalRandom.current().nextInt(MIN_ADDS, MAX_ADDS + 1);
        MobArchetype archetype = MobArchetype.values()[ThreadLocalRandom.current().nextInt(MobArchetype.values().length)];
        for (int i = 0; i < count; i++) {
            Location spawnLoc = randomNearby(boss.getLocation(), 3.0);
            spawnService.spawnAdd(archetype, spawnLoc, zoneId);
        }
    }

    private Location randomNearby(Location center, double radius) {
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        Vector offset = new Vector(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        return center.clone().add(offset);
    }
}
