package com.bricklyworld.expedition.mob;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tags every entity BricklyExpedition spawns (archetype + owning zone + boss flag) and tracks them per zone so a raid close can clean all of them up. */
public final class ActiveMobRegistry {

    private final NamespacedKey archetypeKey;
    private final NamespacedKey zoneKey;
    private final NamespacedKey bossKey;
    private final Map<Integer, Set<UUID>> spawnedByZone = new HashMap<>();

    public ActiveMobRegistry(Plugin plugin) {
        this.archetypeKey = new NamespacedKey(plugin, "mob-archetype");
        this.zoneKey = new NamespacedKey(plugin, "mob-zone");
        this.bossKey = new NamespacedKey(plugin, "mob-is-boss");
    }

    public void tagAndTrack(LivingEntity entity, MobArchetype archetype, int zoneId, boolean isBoss) {
        var pdc = entity.getPersistentDataContainer();
        pdc.set(archetypeKey, PersistentDataType.STRING, archetype.name());
        pdc.set(zoneKey, PersistentDataType.INTEGER, zoneId);
        pdc.set(bossKey, PersistentDataType.BYTE, (byte) (isBoss ? 1 : 0));
        spawnedByZone.computeIfAbsent(zoneId, id -> new HashSet<>()).add(entity.getUniqueId());
    }

    public MobArchetype archetypeOf(LivingEntity entity) {
        String raw = entity.getPersistentDataContainer().get(archetypeKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return MobArchetype.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isBoss(LivingEntity entity) {
        Byte b = entity.getPersistentDataContainer().get(bossKey, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    public Integer zoneOf(LivingEntity entity) {
        return entity.getPersistentDataContainer().get(zoneKey, PersistentDataType.INTEGER);
    }

    public boolean isOurs(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(archetypeKey, PersistentDataType.STRING);
    }

    public Set<UUID> spawnedIn(int zoneId) {
        return spawnedByZone.getOrDefault(zoneId, Set.of());
    }

    public Set<Integer> activeZones() {
        return spawnedByZone.keySet();
    }

    public void forgetZone(int zoneId) {
        spawnedByZone.remove(zoneId);
    }
}
