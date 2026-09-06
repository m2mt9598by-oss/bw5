package com.bricklyworld.expedition.zone;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;

/**
 * An axis-aligned bounding box tied to a world name (not a live World
 * reference - the world may not be loaded yet when a manifest is read from
 * disk, especially before Multiverse-Core has registered it).
 */
public final class CuboidRegion {

    private final String worldName;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    public CuboidRegion(String worldName, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.worldName = worldName;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public static CuboidRegion fromCorners(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) {
            throw new IllegalArgumentException("Обе точки куба должны быть в загруженном мире");
        }
        if (!a.getWorld().equals(b.getWorld())) {
            throw new IllegalArgumentException("Обе точки куба должны быть в одном мире");
        }
        return new CuboidRegion(a.getWorld().getName(),
                a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }

    public boolean contains(Location loc) {
        World world = loc.getWorld();
        if (world == null || !world.getName().equals(worldName)) {
            return false;
        }
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * Horizontal (X/Z only - Y is deliberately ignored, a player flying up
     * out of the top isn't "leaving the map" the way walking past the edge
     * is) distance a location sits outside these bounds; 0 if it's inside
     * or the world doesn't match. Used by the boundary-warning/kill system.
     */
    public double horizontalDistanceOutside(Location loc) {
        World world = loc.getWorld();
        if (world == null || !world.getName().equals(worldName)) {
            return 0.0;
        }
        double x = loc.getX();
        double z = loc.getZ();
        double dx = x < minX ? (minX - x) : (x > maxX + 1 ? x - (maxX + 1) : 0);
        double dz = z < minZ ? (minZ - z) : (z > maxZ + 1 ? z - (maxZ + 1) : 0);
        return Math.sqrt(dx * dx + dz * dz);
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public String worldName() { return worldName; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("world", worldName);
        map.put("minX", minX);
        map.put("minY", minY);
        map.put("minZ", minZ);
        map.put("maxX", maxX);
        map.put("maxY", maxY);
        map.put("maxZ", maxZ);
        return map;
    }

    public static CuboidRegion deserialize(Map<String, Object> map) {
        if (map == null) return null;
        String world = String.valueOf(map.get("world"));
        return new CuboidRegion(
                world,
                ((Number) map.get("minX")).intValue(),
                ((Number) map.get("minY")).intValue(),
                ((Number) map.get("minZ")).intValue(),
                ((Number) map.get("maxX")).intValue(),
                ((Number) map.get("maxY")).intValue(),
                ((Number) map.get("maxZ")).intValue()
        );
    }

    @Override
    public String toString() {
        return worldName + " [" + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
