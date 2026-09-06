package com.bricklyworld.expedition.zone;

/**
 * The point types an admin places while marking up a zone (see the
 * ZoneManifest concept doc's marker table). Stage A only stores these -
 * a proper guided wand/GUI editor for placing them comes in Stage B.
 */
public enum MarkerType {
    PLAYER_SPAWN,
    MOB_SPAWN,
    BOSS_SPAWN,
    LOOT_CACHE,
    ACTIVITY_SCRIPT,
    EVAC_MAIN,
    EVAC_PAID,
    DANGER_HIGH,
    DANGER_LOW
}
