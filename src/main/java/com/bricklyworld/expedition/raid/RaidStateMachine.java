package com.bricklyworld.expedition.raid;

import com.bricklyworld.expedition.world.DeltaJournalManager;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Drives every zone's LOBBY -> PREP -> ACTIVE -> APOCALYPSE -> CLOSING ->
 * RESTORED lifecycle. Stage C's EntryQueueService is the normal caller of
 * openLobby()/beginPrep() (real queueing in front of a raid); startRaid()
 * remains a manual override an admin can use from any pre-ACTIVE state
 * (the GUI's "запустить рейд" button, or testing) that skips straight to
 * ACTIVE without waiting for a queue.
 */
public final class RaidStateMachine {

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final DeltaJournalManager deltaJournals;
    private final Logger logger;

    private final long activeDurationTicks;
    private final long apocalypseDurationTicks;

    private final Map<Integer, RaidState> states = new HashMap<>();
    private final Map<Integer, BukkitTask> timers = new HashMap<>();
    // Wall-clock (not tick-counter) bookkeeping for the current phase's
    // countdown, purely so RaidTimerBossBarService can show a live "сколько
    // осталось" number without duplicating the state machine's own timing.
    private final Map<Integer, Long> phaseEndMillis = new HashMap<>();
    private final Map<Integer, Long> phaseDurationSeconds = new HashMap<>();

    public RaidStateMachine(Plugin plugin, ZoneRegistry zoneRegistry, DeltaJournalManager deltaJournals,
                             long activeDurationSeconds, long apocalypseDurationSeconds) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.deltaJournals = deltaJournals;
        this.logger = plugin.getLogger();
        this.activeDurationTicks = activeDurationSeconds * 20L;
        this.apocalypseDurationTicks = apocalypseDurationSeconds * 20L;
    }

    public RaidState stateOf(int zoneId) {
        return states.getOrDefault(zoneId, RaidState.RESTORED);
    }

    public boolean isActiveOrApocalypse(int zoneId) {
        RaidState s = stateOf(zoneId);
        return s == RaidState.ACTIVE || s == RaidState.APOCALYPSE;
    }

    /**
     * Reserves a RESTORED zone for an incoming queue: marks it as the
     * server's active zone (so nextZone() skips it for anyone else) and
     * moves it to LOBBY, but starts no timer - EntryQueueService drives the
     * lobby countdown itself so it can keep broadcasting to waiting players.
     */
    public boolean openLobby(int zoneId) {
        if (stateOf(zoneId) != RaidState.RESTORED) return false;
        var manifest = zoneRegistry.get(zoneId);
        if (manifest == null || !manifest.isReadyForRaid()) return false;
        zoneRegistry.setActive(zoneId);
        transition(zoneId, RaidState.LOBBY);
        return true;
    }

    /** LOBBY -> PREP, with its own short countdown before the raid actually goes ACTIVE. */
    public boolean beginPrep(int zoneId, long prepDurationTicks) {
        if (stateOf(zoneId) != RaidState.LOBBY) return false;
        transition(zoneId, RaidState.PREP);
        scheduleTransition(zoneId, RaidState.ACTIVE, prepDurationTicks);
        return true;
    }

    /**
     * Manual "launch now" override - works from RESTORED (skip the queue
     * entirely, e.g. an admin self-test) as well as from LOBBY/PREP (cut a
     * queue's countdown short). Not the normal path once Stage C's queue is
     * live, but always available as an escape hatch.
     */
    public boolean startRaid(int zoneId) {
        RaidState current = stateOf(zoneId);
        if (current != RaidState.RESTORED && current != RaidState.LOBBY && current != RaidState.PREP) {
            return false; // already active or mid-shutdown
        }
        var manifest = zoneRegistry.get(zoneId);
        if (manifest == null || !manifest.isReadyForRaid()) {
            return false;
        }
        cancelTimer(zoneId);
        zoneRegistry.setActive(zoneId);
        deltaJournals.begin(zoneId);
        transition(zoneId, RaidState.ACTIVE);
        scheduleTransition(zoneId, RaidState.APOCALYPSE, activeDurationTicks);
        return true;
    }

    /** Skips straight to CLOSING regardless of current state - the admin "kill it now" button. */
    public void forceEnd(int zoneId) {
        cancelTimer(zoneId);
        transition(zoneId, RaidState.CLOSING);
        finishClosing(zoneId);
    }

    private void scheduleTransition(int zoneId, RaidState next, long delayTicks) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> advance(zoneId, next), delayTicks);
        timers.put(zoneId, task);
        phaseEndMillis.put(zoneId, System.currentTimeMillis() + delayTicks * 50L);
        phaseDurationSeconds.put(zoneId, delayTicks / 20L);
    }

    /** Seconds left in whatever phase is currently running, for the boss-bar timer. 0 if none is scheduled. */
    public long remainingSeconds(int zoneId) {
        Long end = phaseEndMillis.get(zoneId);
        if (end == null) return 0;
        return Math.max(0, (end - System.currentTimeMillis() + 999) / 1000);
    }

    /** Total length in seconds of whatever phase is currently running - the boss-bar's progress denominator. */
    public long phaseDurationSeconds(int zoneId) {
        return phaseDurationSeconds.getOrDefault(zoneId, 0L);
    }

    private void advance(int zoneId, RaidState next) {
        if (next == RaidState.ACTIVE) {
            // Coming from PREP's own countdown, not startRaid() - still needs
            // the delta journal opened here, same as the manual path does.
            deltaJournals.begin(zoneId);
        }
        transition(zoneId, next);
        switch (next) {
            case ACTIVE -> scheduleTransition(zoneId, RaidState.APOCALYPSE, activeDurationTicks);
            case APOCALYPSE -> scheduleTransition(zoneId, RaidState.CLOSING, apocalypseDurationTicks);
            case CLOSING -> finishClosing(zoneId);
            default -> { }
        }
    }

    private void finishClosing(int zoneId) {
        deltaJournals.restoreAndClear(zoneId, () -> {
            transition(zoneId, RaidState.RESTORED);
            zoneRegistry.clearActive();
        });
        cancelTimer(zoneId);
    }

    private void cancelTimer(int zoneId) {
        BukkitTask task = timers.remove(zoneId);
        if (task != null) task.cancel();
        phaseEndMillis.remove(zoneId);
        phaseDurationSeconds.remove(zoneId);
    }

    private void transition(int zoneId, RaidState next) {
        RaidState previous = stateOf(zoneId);
        states.put(zoneId, next);
        logger.info("[BricklyExpedition] Zone " + zoneId + ": " + previous + " -> " + next);
        Bukkit.getPluginManager().callEvent(new RaidStateChangeEvent(zoneId, previous, next));
    }
}
