package com.bricklyworld.expedition.apocalypse;

import com.bricklyworld.expedition.pvp.KnockoutService;
import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "если игрок не успевает, то за 2 минуты видит мир разрушается и он
 * умирает, поэффектнее можно изобразить апокалипсис в мире экспедиции" -
 * the last 2 minutes of a raid (RaidState.APOCALYPSE) get an escalating
 * per-player effects show (rumbling sounds, ash/smoke particles, distant
 * "explosions", intensity ramping up as CLOSING approaches); whoever is
 * still inside the zone when it actually closes doesn't get a graceful
 * evac - they're force-killed, the same instant/unrevivable path the
 * boundary system uses.
 */
public final class ApocalypseService implements Listener {

    private static final long TICK_PERIOD = 20L; // once a second - a slow escalating drumbeat, not a flicker-fest

    private final Plugin plugin;
    private final RaidPlayerRegistry raidPlayers;
    private final KnockoutService knockout;
    private final Map<Integer, BukkitTask> tasks = new HashMap<>();
    private final Map<Integer, Long> startedAt = new HashMap<>();

    public ApocalypseService(Plugin plugin, RaidPlayerRegistry raidPlayers, KnockoutService knockout) {
        this.plugin = plugin;
        this.raidPlayers = raidPlayers;
        this.knockout = knockout;
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        if (event.to() == RaidState.APOCALYPSE) {
            begin(event.zoneId());
        } else if (event.to() == RaidState.CLOSING) {
            end(event.zoneId());
            forceKillRemaining(event.zoneId());
        } else if (event.to() == RaidState.RESTORED) {
            end(event.zoneId()); // safety net in case CLOSING was skipped by forceEnd
        }
    }

    private void begin(int zoneId) {
        startedAt.put(zoneId, System.currentTimeMillis());
        broadcastZone(zoneId, "§4§lМИР НАЧИНАЕТ РУШИТЬСЯ", "§7У вас 2 минуты, чтобы эвакуироваться.");
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(zoneId), TICK_PERIOD, TICK_PERIOD);
        tasks.put(zoneId, task);
    }

    private void end(int zoneId) {
        BukkitTask task = tasks.remove(zoneId);
        if (task != null) task.cancel();
        startedAt.remove(zoneId);
    }

    // Particle.LAVA confirmed compiling fine against Paper 1.20.1 (real
    // GitHub Actions build, 2026-09-06) - see spawnParticle below for the
    // SMOKE_LARGE fix (LARGE_SMOKE, the name originally guessed here, was
    // the one real compile error this project has hit so far).
    private void tick(int zoneId) {
        long elapsedSeconds = (System.currentTimeMillis() - startedAt.getOrDefault(zoneId, System.currentTimeMillis())) / 1000;
        // Intensity ramps from ~1 to ~5 over the apocalypse window - purely
        // cosmetic scaling, doesn't need to match the exact configured
        // apocalypse-duration-seconds to read as "getting worse".
        int intensity = 1 + (int) Math.min(4, elapsedSeconds / 24);

        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            Location loc = player.getLocation();

            player.playSound(loc, Sound.AMBIENT_CAVE, 1.5f, 0.5f);
            if (ThreadLocalRandom.current().nextInt(4) == 0) {
                player.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.8f + intensity * 0.1f, 0.6f);
            }
            // Particle.SMOKE_LARGE confirmed by a real GitHub Actions compile
            // against Paper 1.20.1 (2026-09-06) - LARGE_SMOKE is the later
            // (1.20.5+) rename and doesn't exist on this API version.
            player.spawnParticle(Particle.SMOKE_LARGE, loc.clone().add(0, 2, 0), 6 + intensity * 3, 2, 1, 2, 0.02);
            if (intensity >= 3 && ThreadLocalRandom.current().nextInt(3) == 0) {
                player.spawnParticle(Particle.LAVA, loc.clone().add(
                        ThreadLocalRandom.current().nextDouble(-6, 6), 3,
                        ThreadLocalRandom.current().nextDouble(-6, 6)), 1);
            }
        }
    }

    private void forceKillRemaining(int zoneId) {
        Set<UUID> stragglers = new HashSet<>(raidPlayers.playersIn(zoneId));
        for (UUID id : stragglers) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            player.sendTitle("§4§lВЫ НЕ УСПЕЛИ", "§7Мир поглотил вас.", 0, 40, 10);
            knockout.instantKill(player);
        }
    }

    private void broadcastZone(int zoneId, String title, String subtitle) {
        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendTitle(title, subtitle, 10, 70, 20);
                player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.6f);
            }
        }
    }

    public void shutdown() {
        for (BukkitTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        startedAt.clear();
    }
}
