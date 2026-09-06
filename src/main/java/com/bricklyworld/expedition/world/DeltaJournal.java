package com.bricklyworld.expedition.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the ORIGINAL BlockData of every unique block coordinate touched
 * during one raid (first write wins) and can restore all of them back to
 * that original state afterwards. Deliberately not a full chronological
 * undo log: the goal is "return the zone to its manifest baseline", not
 * step-by-step undo, so first-write-wins gets there in O(unique blocks
 * changed) rather than O(total block events).
 */
public final class DeltaJournal {

    private record Pos(String world, int x, int y, int z) { }

    private final Map<Pos, BlockData> originalStates = new LinkedHashMap<>();
    private Iterator<Map.Entry<Pos, BlockData>> restoreCursor;

    /** Records the block's CURRENT data as its "original" state (use before the block changes, e.g. in BlockBreakEvent). */
    public void recordIfAbsent(Block block) {
        recordIfAbsent(block, block.getBlockData());
    }

    /** Records an explicit original state for the block's coordinate (use when the "before" state isn't the live block, e.g. BlockPlaceEvent's replaced-state). */
    public void recordIfAbsent(Block block, BlockData original) {
        Pos pos = new Pos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        originalStates.putIfAbsent(pos, original.clone());
    }

    public int size() {
        return originalStates.size();
    }

    /**
     * Restores up to maxBlocks recorded blocks per call. Call repeatedly
     * (e.g. once a tick from a repeating task) until it returns true, so a
     * large zone's restore is spread across many ticks instead of one
     * blocking pass that would stall the server.
     *
     * @return true once every recorded block has been restored (and the journal cleared).
     */
    public boolean restoreStep(int maxBlocks) {
        if (restoreCursor == null) {
            restoreCursor = originalStates.entrySet().iterator();
        }
        int done = 0;
        while (done < maxBlocks && restoreCursor.hasNext()) {
            Map.Entry<Pos, BlockData> entry = restoreCursor.next();
            Pos pos = entry.getKey();
            World world = Bukkit.getWorld(pos.world());
            if (world != null) {
                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                block.setBlockData(entry.getValue(), false);
            }
            done++;
        }
        boolean finished = !restoreCursor.hasNext();
        if (finished) {
            originalStates.clear();
        }
        return finished;
    }
}
