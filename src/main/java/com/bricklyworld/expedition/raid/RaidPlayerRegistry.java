package com.bricklyworld.expedition.raid;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Which player is in which raid zone right now" - shared lookup used by
 * every later system that needs to know (anonymity/no-nicknames, the
 * boundary/apocalypse enforcement, evac, the post-raid stats screen,
 * knockout/downed handling). Deliberately just bookkeeping, no game logic
 * of its own - EntryQueueService populates it when a raid launches,
 * RaidStateMachine's CLOSING/RESTORED transition clears a zone out.
 */
public final class RaidPlayerRegistry {

    private final Map<UUID, Integer> zoneOfPlayer = new HashMap<>();
    private final Map<Integer, Set<UUID>> playersInZone = new HashMap<>();

    public void assign(UUID player, int zoneId) {
        Integer previous = zoneOfPlayer.put(player, zoneId);
        if (previous != null && previous != zoneId) {
            var prevSet = playersInZone.get(previous);
            if (prevSet != null) prevSet.remove(player);
        }
        playersInZone.computeIfAbsent(zoneId, k -> new HashSet<>()).add(player);
    }

    public void remove(UUID player) {
        Integer zoneId = zoneOfPlayer.remove(player);
        if (zoneId != null) {
            var set = playersInZone.get(zoneId);
            if (set != null) set.remove(player);
        }
    }

    public Integer zoneOf(UUID player) {
        return zoneOfPlayer.get(player);
    }

    public boolean inRaid(UUID player) {
        return zoneOfPlayer.containsKey(player);
    }

    public Set<UUID> playersIn(int zoneId) {
        return Collections.unmodifiableSet(playersInZone.getOrDefault(zoneId, Collections.emptySet()));
    }

    public void clearZone(int zoneId) {
        Set<UUID> players = playersInZone.remove(zoneId);
        if (players != null) {
            for (UUID player : players) {
                zoneOfPlayer.remove(player);
            }
        }
    }
}
