package com.bricklyworld.expedition.mob;

import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * "Ванильные мобы убираются полностью" - cancels the vanilla spontaneous
 * spawn reasons (natural spawning, spawners, patrols/raids, ...) for any
 * Monster inside ANY of the 8 zone cuboids, active raid or not, so the
 * raid map never grows vanilla hostile mobs on its own. Deliberately does
 * NOT block SpawnReason.CUSTOM (how MobSpawnService spawns its own
 * archetypes) or admin-triggered spawns (spawn eggs, /summon), so testing
 * isn't blocked by this rule.
 */
public final class VanillaMobBanListener implements Listener {

    private static final Set<CreatureSpawnEvent.SpawnReason> BANNED_REASONS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.RAID,
            CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION,
            CreatureSpawnEvent.SpawnReason.NETHER_PORTAL,
            CreatureSpawnEvent.SpawnReason.MOUNT
    );

    private final ZoneRegistry zoneRegistry;

    public VanillaMobBanListener(ZoneRegistry zoneRegistry) {
        this.zoneRegistry = zoneRegistry;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;
        if (!BANNED_REASONS.contains(event.getSpawnReason())) return;

        for (ZoneManifest manifest : zoneRegistry.all().values()) {
            if (manifest.bounds() != null && manifest.bounds().contains(event.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
