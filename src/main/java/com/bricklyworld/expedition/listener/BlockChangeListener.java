package com.bricklyworld.expedition.listener;

import com.bricklyworld.expedition.config.ExpeditionConfig;
import com.bricklyworld.expedition.raid.RaidStateMachine;
import com.bricklyworld.expedition.world.DeltaJournalManager;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import com.bricklyworld.expedition.world.DeltaJournal;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;

/**
 * Implements the concept doc's anti-resource-extraction rules inside
 * whichever zone is currently ACTIVE/APOCALYPSE:
 *  - ground/underground blocks (at or below the zone's floor) can't be
 *    broken at all - one Y comparison, cancelled before anything else
 *    runs, so it costs the server almost nothing;
 *  - blocks in the configured remap table drop a harmless custom item
 *    instead of their real vanilla drop.
 * Every change that's allowed to happen is also journaled so
 * RaidStateMachine can restore the zone to its manifest baseline once the
 * raid closes.
 *
 * BUG FIX (2026-09-06, real question from Egor - "что у нас с поломкой
 * зданий стен и земли под игроками"): this listener only ever covered
 * BlockBreakEvent/BlockPlaceEvent - normal pickaxe-style digging. It had NO
 * handler at all for EntityExplodeEvent, so the DETONATOR archetype (a
 * reskinned vanilla Creeper, intentionally "a long-range wall-breaching
 * detonator" per the concept doc) could blow real holes in an admin's
 * hand-built structure that were never recorded in the zone's DeltaJournal -
 * meaning that damage was PERMANENT, never restored when the raid closed,
 * unlike every other change in the zone. Wall-breaching itself is the
 * intended feature for this archetype, not a bug - what was missing is
 * exactly the same treatment every other block change already gets: record
 * it so it reverts, and never let it punch through the zone's own floor
 * into the void below (same rule normal digging already respects).
 */
public final class BlockChangeListener implements Listener {

    private final ZoneRegistry zoneRegistry;
    private final RaidStateMachine raidStateMachine;
    private final DeltaJournalManager deltaJournals;
    private final ExpeditionConfig config;

    public BlockChangeListener(ZoneRegistry zoneRegistry, RaidStateMachine raidStateMachine,
                                DeltaJournalManager deltaJournals, ExpeditionConfig config) {
        this.zoneRegistry = zoneRegistry;
        this.raidStateMachine = raidStateMachine;
        this.deltaJournals = deltaJournals;
        this.config = config;
    }

    private ZoneManifest activeZoneContaining(Block block) {
        Integer activeId = zoneRegistry.activeZoneId();
        if (activeId == null || !raidStateMachine.isActiveOrApocalypse(activeId)) {
            return null;
        }
        ZoneManifest manifest = zoneRegistry.get(activeId);
        if (manifest == null || manifest.bounds() == null) {
            return null;
        }
        return manifest.bounds().contains(block.getLocation()) ? manifest : null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        ZoneManifest zone = activeZoneContaining(block);
        if (zone == null) return;

        int floorY = zone.bounds().minY() + config.groundFloorYOffset;
        if (block.getY() <= floorY) {
            event.setCancelled(true);
            return;
        }

        deltaJournals.journalFor(zoneRegistry.activeZoneId()).recordIfAbsent(block);

        Material remapTo = config.blockRemap.get(block.getType());
        if (remapTo != null) {
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), new ItemStack(remapTo));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        ZoneManifest zone = activeZoneContaining(block);
        if (zone == null) return;

        int floorY = zone.bounds().minY() + config.groundFloorYOffset;
        if (block.getY() <= floorY) {
            event.setCancelled(true);
            return;
        }

        // The "original" state for a placed block is whatever it replaced (usually air),
        // not the block's own new data - so restore turns it back into that on raid close.
        deltaJournals.journalFor(zoneRegistry.activeZoneId())
                .recordIfAbsent(block, event.getBlockReplacedState().getBlockData());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Integer activeId = zoneRegistry.activeZoneId();
        if (activeId == null || !raidStateMachine.isActiveOrApocalypse(activeId)) {
            // No active raid using its own destructible-zone rules right now -
            // never let an explosion touch terrain unrecorded, just in case
            // some other plugin's TNT/creeper wanders near a RESTORED zone.
            event.blockList().clear();
            return;
        }
        ZoneManifest zone = zoneRegistry.get(activeId);
        if (zone == null || zone.bounds() == null) {
            event.blockList().clear();
            return;
        }

        int floorY = zone.bounds().minY() + config.groundFloorYOffset;
        DeltaJournal journal = deltaJournals.journalFor(activeId);
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (!zone.bounds().contains(block.getLocation())) {
                it.remove(); // outside this zone entirely - never our business to destroy it
                continue;
            }
            if (block.getY() <= floorY) {
                it.remove(); // same floor rule as manual digging - never blast through into the void
                continue;
            }
            if (journal != null) {
                journal.recordIfAbsent(block);
            }
        }
    }
}
