package com.bricklyworld.expedition.zone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds all 8 zone manifests and which one (if any) is currently the
 * active raid. Only one zone is ever active at a time by design - the
 * other seven sit in the pool so an admin can edit them without downtime.
 */
public final class ZoneRegistry {

    private final int zoneCount;
    private final Map<Integer, ZoneManifest> manifests = new LinkedHashMap<>();
    private final ZoneManifestStore store;
    private Integer activeZoneId = null;
    private Integer lastZoneId = null; // for round-robin rotation

    public ZoneRegistry(ZoneManifestStore store, int zoneCount) {
        this.store = store;
        this.zoneCount = zoneCount;
        for (int id = 1; id <= zoneCount; id++) {
            ZoneManifest loaded = store.load(id);
            manifests.put(id, loaded != null ? loaded : new ZoneManifest(id, "Zone " + id));
        }
    }

    public ZoneManifest get(int id) {
        return manifests.get(id);
    }

    public Map<Integer, ZoneManifest> all() {
        return manifests;
    }

    public void save(int id) {
        ZoneManifest manifest = manifests.get(id);
        if (manifest != null) {
            store.save(manifest);
        }
    }

    public boolean isActive(int id) {
        return activeZoneId != null && activeZoneId == id;
    }

    public Integer activeZoneId() {
        return activeZoneId;
    }

    public void setActive(int id) {
        this.activeZoneId = id;
    }

    public void clearActive() {
        this.lastZoneId = this.activeZoneId;
        this.activeZoneId = null;
    }

    /** Zones not currently hosting a raid - the pool an admin can safely edit. */
    public List<ZoneManifest> pool() {
        List<ZoneManifest> pool = new ArrayList<>();
        for (Map.Entry<Integer, ZoneManifest> e : manifests.entrySet()) {
            if (!isActive(e.getKey())) {
                pool.add(e.getValue());
            }
        }
        return pool;
    }

    /**
     * Simple round-robin over ready (validate() == empty) zones, skipping
     * whichever zone ran last. An admin can always override by starting a
     * specific zone id directly via /bwexpedition startraid.
     */
    public Integer nextZone() {
        int start = (lastZoneId == null ? 0 : lastZoneId) % zoneCount;
        for (int offset = 1; offset <= zoneCount; offset++) {
            int candidate = ((start + offset - 1) % zoneCount) + 1;
            ZoneManifest manifest = manifests.get(candidate);
            if (manifest != null && manifest.isReadyForRaid() && !isActive(candidate)) {
                return candidate;
            }
        }
        return null; // no zone is ready - admin needs to fix a manifest
    }
}
