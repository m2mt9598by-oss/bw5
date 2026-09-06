package com.bricklyworld.expedition.zone;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The packaged description of one of the 8 game locations: where it is
 * (world + cuboid bounds) and every point an admin has marked inside it.
 * This is the file the raid state machine "plays back" at the start of
 * every session (Stage A only stores/validates it - actually spawning
 * loot/mobs/scripts from it is later stages).
 */
public final class ZoneManifest {

    private final int id;
    private String displayName;
    private CuboidRegion bounds;
    private final Map<MarkerType, List<ZoneMarker>> markers = new EnumMap<>(MarkerType.class);

    public ZoneManifest(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
        for (MarkerType type : MarkerType.values()) {
            markers.put(type, new ArrayList<>());
        }
    }

    public int id() { return id; }

    public String displayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public CuboidRegion bounds() { return bounds; }
    public void setBounds(CuboidRegion bounds) { this.bounds = bounds; }

    public void addMarker(ZoneMarker marker) {
        markers.get(marker.type()).add(marker);
    }

    public List<ZoneMarker> markers(MarkerType type) {
        return markers.get(type);
    }

    public int markerCount(MarkerType type) {
        return markers.get(type).size();
    }

    /**
     * Stage A validation - just enough for /bwexpedition selftest to catch
     * an obviously broken manifest before a raid tries to use it. Later
     * stages add real checks (extraction points reachable, mob types exist,
     * loot tables resolve, ...).
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (bounds == null) {
            problems.add("не задан куб локации (bounds)");
        }
        if (markerCount(MarkerType.PLAYER_SPAWN) == 0) {
            problems.add("нет ни одной точки спавна игроков");
        }
        if (markerCount(MarkerType.EVAC_MAIN) == 0) {
            problems.add("нет ни одного основного эвакуационного выхода");
        }
        return problems;
    }

    public boolean isReadyForRaid() {
        return validate().isEmpty();
    }
}
