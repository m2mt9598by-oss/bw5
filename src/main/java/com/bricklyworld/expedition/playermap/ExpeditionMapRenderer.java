package com.bricklyworld.expedition.playermap;

import com.bricklyworld.expedition.zone.CuboidRegion;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneMarker;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "На ней плагин дополнительно рисует зоны игровой области, опасные
 * территории с повышенными лутом и конечно точки эвакуационных выходов,
 * так-же видит свое расположение, но не видит боссов и других подсказок."
 *
 * Deliberately additive: the vanilla renderers stay on the MapView (they're
 * what draws the explored terrain and the player's own position cursor for
 * free), this just draws one extra static overlay on top - the zone
 * boundary, danger-zone markers, and evac points. Nothing about mob/boss
 * locations is ever read here.
 *
 * The overlay is genuinely static for the life of a raid (a zone's bounds
 * and markers don't move once a raid is running), so it draws once and
 * returns immediately on every later call instead of repainting 128x128
 * pixels on every server tick a player holds the map.
 */
public final class ExpeditionMapRenderer extends MapRenderer {

    private static final Color BOUNDARY_COLOR = new Color(90, 90, 90);
    private static final Color DANGER_HIGH_COLOR = new Color(200, 30, 30);
    private static final Color DANGER_LOW_COLOR = new Color(210, 170, 40);
    private static final Color EVAC_MAIN_COLOR = new Color(40, 190, 80);
    private static final Color EVAC_PAID_COLOR = new Color(210, 150, 20);

    private final ZoneManifest manifest;
    private final int blocksPerPixel;
    // contextual=true means Bukkit gives each viewing player their own canvas
    // state, so "already drawn" has to be tracked per player - a single
    // shared boolean would only ever paint the overlay for whichever player
    // happens to render first and leave everyone else's map blank.
    private final Set<UUID> drawnFor = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public ExpeditionMapRenderer(ZoneManifest manifest, int blocksPerPixel) {
        super(true);
        this.manifest = manifest;
        this.blocksPerPixel = Math.max(1, blocksPerPixel);
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (drawnFor.contains(player.getUniqueId())) return;
        CuboidRegion bounds = manifest.bounds();
        if (bounds == null) return; // not ready yet - don't mark drawn, try again next call

        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;

        drawRectOutline(canvas, bounds, centerX, centerZ, BOUNDARY_COLOR);
        drawMarkerDots(canvas, MarkerType.DANGER_HIGH, centerX, centerZ, DANGER_HIGH_COLOR);
        drawMarkerDots(canvas, MarkerType.DANGER_LOW, centerX, centerZ, DANGER_LOW_COLOR);
        drawMarkerDots(canvas, MarkerType.EVAC_MAIN, centerX, centerZ, EVAC_MAIN_COLOR);
        drawMarkerDots(canvas, MarkerType.EVAC_PAID, centerX, centerZ, EVAC_PAID_COLOR);

        drawnFor.add(player.getUniqueId());
    }

    private void drawRectOutline(MapCanvas canvas, CuboidRegion bounds, int centerX, int centerZ, Color color) {
        byte colorByte = MapPalette.matchColor(color);
        int x1 = toPixel(bounds.minX(), centerX);
        int x2 = toPixel(bounds.maxX(), centerX);
        int z1 = toPixel(bounds.minZ(), centerZ);
        int z2 = toPixel(bounds.maxZ(), centerZ);

        for (int x = clamp(x1); x <= clamp(x2); x++) {
            setPixelSafe(canvas, x, clamp(z1), colorByte);
            setPixelSafe(canvas, x, clamp(z2), colorByte);
        }
        for (int z = clamp(z1); z <= clamp(z2); z++) {
            setPixelSafe(canvas, clamp(x1), z, colorByte);
            setPixelSafe(canvas, clamp(x2), z, colorByte);
        }
    }

    private void drawMarkerDots(MapCanvas canvas, MarkerType type, int centerX, int centerZ, Color color) {
        byte colorByte = MapPalette.matchColor(color);
        for (ZoneMarker marker : manifest.markers(type)) {
            int px = toPixel((int) marker.x(), centerX);
            int pz = toPixel((int) marker.z(), centerZ);
            // A small filled 3x3 dot - a single pixel is too easy to miss on a 128x128 map.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    setPixelSafe(canvas, px + dx, pz + dz, colorByte);
                }
            }
        }
    }

    private int toPixel(int worldCoord, int center) {
        return 64 + (worldCoord - center) / blocksPerPixel;
    }

    private int clamp(int pixel) {
        return Math.max(0, Math.min(127, pixel));
    }

    private void setPixelSafe(MapCanvas canvas, int x, int y, byte color) {
        if (x < 0 || x > 127 || y < 0 || y > 127) return;
        canvas.setPixel(x, y, color);
    }
}
