package com.bricklyworld.expedition.hazard;

import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneMarker;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gives DANGER_HIGH/DANGER_LOW markers a real, radius-configurable gameplay
 * effect instead of being purely decorative (real playtest request: "Зоны
 * интереса и опасные зоны, лучше создать параметр - задать зону на какую
 * дистанцию - я сам указываю область действия зоны"). The radius is just the
 * marker's meta value in blocks - ZoneEditorController's DANGER_RADIUS_OPTIONS
 * preset list lets an admin pick it with the same sneak+click cycling UI
 * already used for mob archetypes and loot tiers, no free-form chat input
 * needed; a blank meta falls back to DEFAULT_RADIUS.
 *
 * DANGER_HIGH ("опасная зона"): a real hazard - while a raid player stands
 * within the radius they carry a refreshed Weakness debuff, a red particle
 * marker underfoot, and get a one-time entry warning + low bass note so it
 * reads clearly as dangerous ground, not just a name on the map.
 *
 * DANGER_LOW ("зона интереса"): the opposite signal, and a real incentive
 * rather than just a map dot - while a raid player stands within the radius
 * they carry a refreshed Luck buff (better odds on anything that rolls loot
 * nearby), a gold particle marker underfoot, and a one-time entry chime.
 */
public final class DangerZoneService implements Listener {

    public static final double DEFAULT_RADIUS = 10.0;
    private static final int TICK_PERIOD = 20; // 1s
    private static final int EFFECT_DURATION_TICKS = 60; // > TICK_PERIOD so it never visibly lapses between ticks

    private record Hazard(MarkerType type, Location center, double radiusSquared) {
    }

    private final ZoneRegistry zoneRegistry;
    private final RaidPlayerRegistry raidPlayers;
    private final Map<Integer, List<Hazard>> hazardsByZone = new HashMap<>();
    private final Map<UUID, Boolean> lastInDanger = new HashMap<>();
    private final Map<UUID, Boolean> lastInInterest = new HashMap<>();
    private final BukkitTask task;

    public DangerZoneService(Plugin plugin, ZoneRegistry zoneRegistry, RaidPlayerRegistry raidPlayers) {
        this.zoneRegistry = zoneRegistry;
        this.raidPlayers = raidPlayers;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        if (event.to() == RaidState.ACTIVE) {
            ZoneManifest manifest = zoneRegistry.get(event.zoneId());
            if (manifest != null) {
                buildHazards(event.zoneId(), manifest);
            }
        } else if (event.to() == RaidState.RESTORED) {
            hazardsByZone.remove(event.zoneId());
        }
    }

    private void buildHazards(int zoneId, ZoneManifest manifest) {
        if (manifest.bounds() == null) return;
        World world = Bukkit.getWorld(manifest.bounds().worldName());
        if (world == null) return;
        List<Hazard> hazards = new ArrayList<>();
        for (MarkerType type : new MarkerType[]{MarkerType.DANGER_HIGH, MarkerType.DANGER_LOW}) {
            for (ZoneMarker marker : manifest.markers(type)) {
                double radius = parseRadius(marker.meta());
                Location center = new Location(world, marker.x(), marker.y(), marker.z());
                hazards.add(new Hazard(type, center, radius * radius));
            }
        }
        hazardsByZone.put(zoneId, hazards);
    }

    private double parseRadius(String meta) {
        if (meta == null || meta.isBlank()) return DEFAULT_RADIUS;
        try {
            return Double.parseDouble(meta.trim());
        } catch (NumberFormatException ignored) {
            return DEFAULT_RADIUS;
        }
    }

    private void tick() {
        if (hazardsByZone.isEmpty()) return;
        for (Map.Entry<Integer, List<Hazard>> entry : hazardsByZone.entrySet()) {
            List<Hazard> hazards = entry.getValue();
            if (hazards.isEmpty()) continue;
            for (UUID id : raidPlayers.playersIn(entry.getKey())) {
                Player player = Bukkit.getPlayer(id);
                if (player == null || !player.isOnline() || player.isDead()) continue;
                applyHazards(player, hazards);
            }
        }
    }

    private void applyHazards(Player player, List<Hazard> hazards) {
        boolean inDanger = false;
        boolean inInterest = false;
        for (Hazard hazard : hazards) {
            World hazardWorld = hazard.center().getWorld();
            if (hazardWorld == null || !player.getWorld().equals(hazardWorld)) continue;
            if (player.getLocation().distanceSquared(hazard.center()) > hazard.radiusSquared()) continue;
            if (hazard.type() == MarkerType.DANGER_HIGH) {
                inDanger = true;
            } else {
                inInterest = true;
            }
        }

        UUID id = player.getUniqueId();
        if (inDanger) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, EFFECT_DURATION_TICKS, 0, true, false, false));
            player.getWorld().spawnParticle(Particle.REDSTONE, player.getLocation().add(0, 0.1, 0), 3,
                    0.6, 0.05, 0.6, 0, new Particle.DustOptions(Color.RED, 1.2f));
            if (!Boolean.TRUE.equals(lastInDanger.get(id))) {
                player.sendActionBar("§4⚠ Опасная зона! §cЗдесь сильнее противники и слабее вы.");
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.6f);
            }
        }
        lastInDanger.put(id, inDanger);

        if (inInterest) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.LUCK, EFFECT_DURATION_TICKS, 0, true, false, false));
            player.getWorld().spawnParticle(Particle.REDSTONE, player.getLocation().add(0, 0.1, 0), 3,
                    0.6, 0.05, 0.6, 0, new Particle.DustOptions(Color.YELLOW, 1.2f));
            if (!Boolean.TRUE.equals(lastInInterest.get(id))) {
                player.sendActionBar("§6★ Зона интереса! §eЗдесь может найтись что-то ценное.");
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f);
            }
        }
        lastInInterest.put(id, inInterest);
    }

    public void shutdown() {
        task.cancel();
        hazardsByZone.clear();
        lastInDanger.clear();
        lastInInterest.clear();
    }

    // Particle.REDSTONE confirmed by a real GitHub Actions compile against
    // Paper 1.20.1 (2026-09-06) - DUST is the later (1.20.5+) rename and
    // doesn't exist on this API version, same pattern as the earlier
    // LARGE_SMOKE/SMOKE_LARGE fix. The colored-dust data type itself is
    // still Particle.DustOptions regardless of which enum name is used.
}
