package com.bricklyworld.expedition.raid;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired every time a zone's raid moves to a new state. This is the seam
 * later stages hook into (mob/loot spawning on ACTIVE, apocalypse effects
 * on APOCALYPSE, forced evac on CLOSING, ...) without touching the state
 * machine itself.
 */
public class RaidStateChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final int zoneId;
    private final RaidState from;
    private final RaidState to;

    public RaidStateChangeEvent(int zoneId, RaidState from, RaidState to) {
        this.zoneId = zoneId;
        this.from = from;
        this.to = to;
    }

    public int zoneId() { return zoneId; }
    public RaidState from() { return from; }
    public RaidState to() { return to; }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
