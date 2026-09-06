package com.bricklyworld.expedition.editor;

import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneMarker;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Показать точки" toggle from the zone editor menu: while on for a
 * player, loops colour-coded particles over every marker in whichever
 * zone they're currently editing, so the admin sees the layout in the
 * world instead of only reading a config file.
 *
 * NOTE: uses Particle.REDSTONE + Particle.DustOptions, the colored-dust
 * particle name for the 1.20.1 Bukkit API this project targets - later
 * Paper versions renamed this to Particle.DUST, so if the server is ever
 * bumped past 1.20.1 this is the first thing to check on a compile error.
 */
public final class MarkerPreviewService implements Listener {

    private static final Map<MarkerType, Color> COLORS = new EnumMap<>(MarkerType.class);
    static {
        COLORS.put(MarkerType.PLAYER_SPAWN, Color.AQUA);
        COLORS.put(MarkerType.MOB_SPAWN, Color.LIME);
        COLORS.put(MarkerType.BOSS_SPAWN, Color.RED);
        COLORS.put(MarkerType.LOOT_CACHE, Color.YELLOW);
        COLORS.put(MarkerType.ACTIVITY_SCRIPT, Color.PURPLE);
        COLORS.put(MarkerType.EVAC_MAIN, Color.WHITE);
        COLORS.put(MarkerType.EVAC_PAID, Color.ORANGE);
        COLORS.put(MarkerType.DANGER_HIGH, Color.MAROON);
        COLORS.put(MarkerType.DANGER_LOW, Color.SILVER);
    }

    private static final double RENDER_RADIUS = 96.0;

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final ZoneEditorController controller;
    private final Set<UUID> enabled = new HashSet<>();
    private BukkitTask task;

    public MarkerPreviewService(Plugin plugin, ZoneRegistry zoneRegistry, ZoneEditorController controller) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.controller = controller;
    }

    /** @return the new on/off state. */
    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        boolean nowOn = !enabled.contains(id);
        if (nowOn) {
            enabled.add(id);
            ensureRunning();
        } else {
            enabled.remove(id);
        }
        return nowOn;
    }

    public boolean isOn(Player player) {
        return enabled.contains(player.getUniqueId());
    }

    private void ensureRunning() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 10L);
    }

    private void tick() {
        if (enabled.isEmpty()) {
            task.cancel();
            task = null;
            return;
        }
        for (UUID id : enabled) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            Integer zoneId = controller.editingZone(player);
            if (zoneId == null) continue;
            ZoneManifest manifest = zoneRegistry.get(zoneId);
            if (manifest == null || manifest.bounds() == null) continue;
            World world = Bukkit.getWorld(manifest.bounds().worldName());
            if (world == null || !world.equals(player.getWorld())) continue;

            for (MarkerType type : MarkerType.values()) {
                Particle.DustOptions dust = new Particle.DustOptions(COLORS.getOrDefault(type, Color.WHITE), 1.2f);
                for (ZoneMarker marker : manifest.markers(type)) {
                    Location loc = new Location(world, marker.x(), marker.y(), marker.z());
                    if (loc.distanceSquared(player.getLocation()) > RENDER_RADIUS * RENDER_RADIUS) continue;
                    world.spawnParticle(Particle.REDSTONE, loc, 3, 0.1, 0.2, 0.1, 0, dust);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        enabled.remove(event.getPlayer().getUniqueId());
    }
}
