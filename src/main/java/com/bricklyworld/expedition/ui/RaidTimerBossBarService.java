package com.bricklyworld.expedition.ui;

import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import com.bricklyworld.expedition.raid.RaidStateMachine;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
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

/**
 * "Было бы неплохо таймер сделать в босс баре, просто цифры сколько
 * осталось" - a live boss bar per active raid zone showing a plain mm:ss
 * countdown instead of players having to guess or ask in chat. Green while
 * ACTIVE, flips to red with a title change the moment the zone tips into
 * APOCALYPSE, and pulses (color flash + a tick sound) in the last 10
 * seconds of whichever phase is currently running for a bit of extra
 * urgency/visual feedback.
 */
public final class RaidTimerBossBarService implements Listener {

    private static final int TICK_PERIOD = 20; // 1s
    private static final int PULSE_THRESHOLD_SECONDS = 10;

    private final RaidStateMachine raidStateMachine;
    private final RaidPlayerRegistry raidPlayers;
    private final Map<Integer, BossBar> barsByZone = new HashMap<>();
    private final BukkitTask task;

    public RaidTimerBossBarService(Plugin plugin, RaidStateMachine raidStateMachine, RaidPlayerRegistry raidPlayers) {
        this.raidStateMachine = raidStateMachine;
        this.raidPlayers = raidPlayers;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_PERIOD, TICK_PERIOD);
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        switch (event.to()) {
            case ACTIVE -> {
                BossBar bar = Bukkit.createBossBar("§a§lЭКСПЕДИЦИЯ", BarColor.GREEN, BarStyle.SOLID);
                bar.setProgress(1.0);
                barsByZone.put(event.zoneId(), bar);
            }
            case APOCALYPSE -> {
                BossBar bar = barsByZone.get(event.zoneId());
                if (bar != null) {
                    bar.setColor(BarColor.RED);
                    bar.setStyle(BarStyle.SEGMENTED_10);
                }
            }
            case CLOSING, RESTORED -> {
                BossBar bar = barsByZone.remove(event.zoneId());
                if (bar != null) {
                    bar.removeAll();
                }
            }
            default -> { }
        }
    }

    private void tick() {
        if (barsByZone.isEmpty()) return;
        for (Map.Entry<Integer, BossBar> entry : barsByZone.entrySet()) {
            int zoneId = entry.getKey();
            BossBar bar = entry.getValue();
            RaidState state = raidStateMachine.stateOf(zoneId);
            if (state != RaidState.ACTIVE && state != RaidState.APOCALYPSE) continue;

            long remaining = raidStateMachine.remainingSeconds(zoneId);
            long duration = raidStateMachine.phaseDurationSeconds(zoneId);
            double progress = duration > 0 ? Math.max(0.0, Math.min(1.0, remaining / (double) duration)) : 0.0;
            bar.setProgress(progress);

            String label = state == RaidState.APOCALYPSE ? "§4§lАПОКАЛИПСИС §7- до закрытия зоны" : "§a§lЭКСПЕДИЦИЯ §7- до апокалипсиса";
            bar.setTitle(label + " §f" + formatTime(remaining));

            syncPlayers(zoneId, bar);

            if (remaining <= PULSE_THRESHOLD_SECONDS && remaining > 0) {
                bar.setColor(remaining % 2 == 0 ? BarColor.WHITE : (state == RaidState.APOCALYPSE ? BarColor.RED : BarColor.YELLOW));
                for (UUID id : raidPlayers.playersIn(zoneId)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.6f);
                }
            } else if (state == RaidState.APOCALYPSE) {
                bar.setColor(BarColor.RED);
            }
        }
    }

    private void syncPlayers(int zoneId, BossBar bar) {
        Set<UUID> shouldSee = raidPlayers.playersIn(zoneId);
        Set<UUID> currentlyShown = new HashSet<>();
        for (Player p : bar.getPlayers()) currentlyShown.add(p.getUniqueId());

        for (UUID id : shouldSee) {
            if (!currentlyShown.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) bar.addPlayer(p);
            }
        }
        for (UUID id : currentlyShown) {
            if (!shouldSee.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) bar.removePlayer(p);
            }
        }
    }

    private String formatTime(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    public void shutdown() {
        task.cancel();
        for (BossBar bar : barsByZone.values()) {
            bar.removeAll();
        }
        barsByZone.clear();
    }
}
