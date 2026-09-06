package com.bricklyworld.expedition.zone;

import java.util.HashMap;
import java.util.Map;

/**
 * One placed point: a type, a position, and an optional free-form tag
 * (loot tier "7", boss type "obsidian-golem", script template name, ...).
 */
public final class ZoneMarker {

    private final MarkerType type;
    private final double x, y, z;
    private final float yaw, pitch;
    private final String meta;

    public ZoneMarker(MarkerType type, double x, double y, double z, float yaw, float pitch, String meta) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.meta = meta;
    }

    public MarkerType type() { return type; }
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public String meta() { return meta; }

    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("type", type.name());
        map.put("x", x);
        map.put("y", y);
        map.put("z", z);
        map.put("yaw", yaw);
        map.put("pitch", pitch);
        map.put("meta", meta == null ? "" : meta);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ZoneMarker deserialize(Object raw) {
        Map<String, Object> map = (Map<String, Object>) raw;
        return new ZoneMarker(
                MarkerType.valueOf(String.valueOf(map.get("type"))),
                ((Number) map.get("x")).doubleValue(),
                ((Number) map.get("y")).doubleValue(),
                ((Number) map.get("z")).doubleValue(),
                ((Number) map.get("yaw")).floatValue(),
                ((Number) map.get("pitch")).floatValue(),
                String.valueOf(map.getOrDefault("meta", ""))
        );
    }
}
