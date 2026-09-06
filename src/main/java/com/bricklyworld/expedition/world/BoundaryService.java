package com.bricklyworld.expedition.world;

import com.bricklyworld.expedition.pvp.KnockoutService;
import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "В пределах игровой сессии игроки не могут выбраться за пределы игровой
 * зоны. появляется красная надпись на экран вернитесь в игровую зону и
 * пиликать звуковой сигнал а далее если он рвется дальше - убивать его
 * сквозь нок сразу [в аурелию]". Only X/Z matter here (see
 * CuboidRegion#horizontalDistanceOutside) - flying up out of the top of the
 * build isn't a boundary breach the way walking past the edge is.
 */
public final class BoundaryService {

    private static final double WARN_AT = 0.5;      // any distance outside at all
    private static final double KILL_AT = 20.0;     // blocks past the edge before it's fatal
    private static final long BEEP_INTERVAL_MILLIS = 900;

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final RaidPlayerRegistry raidPlayers;
    private final KnockoutService knockout;
    private final Map<UUID, Long> lastBeep = new HashMap<>();
    private BukkitTask task;

    public BoundaryService(Plugin plugin, ZoneRegistry zoneRegistry, RaidPlayerRegistry raidPlayers, KnockoutService knockout) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.raidPlayers = raidPlayers;
        this.knockout = knockout;
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    private void tick() {
        Integer zoneId = zoneRegistry.activeZoneId();
        if (zoneId == null) return;
        ZoneManifest manifest = zoneRegistry.get(zoneId);
        if (manifest == null || manifest.bounds() == null) return;

        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;

            double outside = manifest.bounds().horizontalDistanceOutside(player.getLocation());
            if (outside < WARN_AT) continue;

            if (outside >= KILL_AT) {
                player.sendTitle("§4§lВЫ ПОКИНУЛИ ЗОНУ", "§7Возврата нет.", 0, 20, 10);
                knockout.instantKill(player);
                lastBeep.remove(id);
                continue;
            }

            player.sendActionBar("§c§lВЕРНИТЕСЬ В ИГРОВУЮ ЗОНУ! §7(" + (int) Math.ceil(KILL_AT - outside) + " м до предела)");
            long now = System.currentTimeMillis();
            Long last = lastBeep.get(id);
            if (last == null || now - last >= BEEP_INTERVAL_MILLIS) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.7f);
                lastBeep.put(id, now);
            }
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
        lastBeep.clear();
    }
}
