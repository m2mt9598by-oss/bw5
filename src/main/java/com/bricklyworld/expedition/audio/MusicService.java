package com.bricklyworld.expedition.audio;

import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Нужно полностью убрать ванильную музыку из режима, сделать все в один
 * наш общий стиль" - while a player is inside an active raid, this
 * repeatedly stops the vanilla MUSIC and AMBIENT sound categories for them
 * (disc jukeboxes, biome ambience beds, the vanilla menu/end music, etc.
 * all live in those categories) so nothing vanilla can sneak back in
 * between our own cues, then plays one safe placeholder loop on the MUSIC
 * category in its place.
 *
 * HONEST SCOPE NOTE: a real "our own house style" soundtrack needs actual
 * custom audio assets shipped via a resource pack (Sound.play with a
 * resource-pack-defined sound key) - this project has no such assets and
 * this sandbox can't fabricate binary audio out of nothing. So the
 * placeholder loop deliberately reuses Sound.AMBIENT_CAVE (already used
 * safely elsewhere in this codebase, e.g. ApocalypseService) rather than
 * guessing at riskier, harder-to-verify Sound enum constants (specific
 * biome music tracks, music discs) - this project already carries enough
 * unverified-API risk without adding more here. Swapping in real custom
 * tracks later is a one-line change in loop() once BricklyWorld has actual
 * audio assets and a resource pack: replace the Sound.AMBIENT_CAVE call
 * with a custom resource-pack sound key, everything else (the per-player
 * loop lifecycle, the vanilla-suppression tick) stays the same.
 */
public final class MusicService {

    // Every 25s: long enough not to spam playSound, short enough that any
    // vanilla music/ambience that manages to start (e.g. a disc a player
    // triggers themselves) gets cut again quickly.
    private static final long LOOP_PERIOD_TICKS = 500L;

    private final Plugin plugin;
    private final Map<UUID, BukkitTask> loops = new HashMap<>();

    public MusicService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void startFor(Player player) {
        stopFor(player); // cancel any existing loop first - avoid stacking tasks on rejoin/re-teleport
        UUID id = player.getUniqueId();
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Player p = plugin.getServer().getPlayer(id);
            if (p == null || !p.isOnline()) {
                stopFor(player);
                return;
            }
            p.stopSound(SoundCategory.MUSIC);
            p.stopSound(SoundCategory.AMBIENT);
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.MUSIC, 0.5f, 1.0f);
        }, 0L, LOOP_PERIOD_TICKS);
        loops.put(id, task);
    }

    public void stopFor(Player player) {
        UUID id = player.getUniqueId();
        BukkitTask task = loops.remove(id);
        if (task != null) task.cancel();
        if (player.isOnline()) {
            player.stopSound(SoundCategory.MUSIC);
        }
    }

    public void shutdown() {
        for (BukkitTask task : loops.values()) {
            task.cancel();
        }
        loops.clear();
    }
}
