package com.bricklyworld.expedition.world;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

/**
 * One DeltaJournal per currently-running raid zone, plus batched
 * restore-on-close scheduling so restoring a large zone never blocks the
 * server in one long synchronous pass.
 */
public final class DeltaJournalManager {

    private static final int BLOCKS_PER_TICK = 500;

    private final Plugin plugin;
    private final Map<Integer, DeltaJournal> journals = new HashMap<>();

    public DeltaJournalManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void begin(int zoneId) {
        journals.put(zoneId, new DeltaJournal());
    }

    /** Null once the zone isn't in an active raid (or its restore has already finished). */
    public DeltaJournal journalFor(int zoneId) {
        return journals.get(zoneId);
    }

    /** Restores the zone's recorded changes across several ticks, then runs onFinished. */
    public void restoreAndClear(int zoneId, Runnable onFinished) {
        DeltaJournal journal = journals.get(zoneId);
        if (journal == null) {
            onFinished.run();
            return;
        }
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean finished = journal.restoreStep(BLOCKS_PER_TICK);
            if (finished) {
                holder[0].cancel();
                journals.remove(zoneId);
                onFinished.run();
            }
        }, 1L, 1L);
    }
}
