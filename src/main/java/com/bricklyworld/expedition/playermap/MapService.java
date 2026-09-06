package com.bricklyworld.expedition.playermap;

import com.bricklyworld.expedition.zone.CuboidRegion;
import com.bricklyworld.expedition.zone.ZoneManifest;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Hands each raider a filled map centered on their zone with the overlay
 * from ExpeditionMapRenderer added on top of the normal terrain/player-
 * position rendering - "для ориентирования по карте игроку выдается
 * ванильная карта с ландшафтом местности". One MapView is created per
 * zone launch and shared by every player's copy of the item (cheaper than
 * a fresh MapView per player, and keeps everyone looking at the same
 * overlay draw).
 */
public final class MapService {

    private final Plugin plugin;
    private final Map<Integer, MapView> viewsByZone = new HashMap<>();

    public MapService(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Call once per zone launch, before giveMap() for that zone's batch of players. */
    public void prepareZoneMap(int zoneId, ZoneManifest manifest) {
        CuboidRegion bounds = manifest.bounds();
        if (bounds == null) return;
        World world = Bukkit.getWorld(bounds.worldName());
        if (world == null) {
            plugin.getLogger().warning("Не удалось создать карту для зоны #" + zoneId + " - мир не загружен.");
            return;
        }

        int width = bounds.maxX() - bounds.minX();
        int depth = bounds.maxZ() - bounds.minZ();
        int neededBlocksPerPixel = Math.max(1, (int) Math.ceil(Math.max(width, depth) / 128.0));
        MapView.Scale scale = scaleFor(neededBlocksPerPixel);

        MapView view = Bukkit.createMap(world);
        view.setCenterX((bounds.minX() + bounds.maxX()) / 2);
        view.setCenterZ((bounds.minZ() + bounds.maxZ()) / 2);
        view.setScale(scale);
        view.setUnlimitedTracking(true);
        // BUG FIX (2026-09-06, real playtest report - "на карте не отображается
        // игрок, не отображается много всего нужного"): this used to call
        // view.setLocked(true), thinking it would just freeze the static
        // overlay. In practice a locked MapView suppresses the vanilla base
        // renderers too - the explored-terrain layer AND the player position
        // cursor - since a "locked" map is meant to be a frozen snapshot (the
        // cartography-table locked-map feature). On a fresh MapView that has
        // never been explored yet, locking it immediately means it stays
        // essentially blank forever: no terrain, no player dot, nothing.
        // Deliberately NOT locking it fixes both complaints at once - vanilla
        // terrain exploration and the player cursor work normally again, and
        // our own overlay (added below) still only draws once regardless,
        // since ExpeditionMapRenderer tracks its own "already drawn" state
        // independent of the MapView's lock flag.
        view.addRenderer(new ExpeditionMapRenderer(manifest, blocksPerPixelOf(scale)));

        viewsByZone.put(zoneId, view);
    }

    public void giveMap(Player player, int zoneId) {
        MapView view = viewsByZone.get(zoneId);
        if (view == null) return; // prepareZoneMap wasn't called or failed - fail quiet, not fatal to the raid

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName("§eКарта экспедиции");
        mapItem.setItemMeta(meta);
        player.getInventory().addItem(mapItem);
    }

    public void clearZoneMap(int zoneId) {
        viewsByZone.remove(zoneId);
    }

    // MapView.Scale's five constants (CLOSEST/CLOSE/NORMAL/FAR/FARTHEST at
    // roughly 1/2/4/8/16 blocks per pixel) confirmed compiling fine against
    // Paper 1.20.1 (real GitHub Actions build, 2026-09-06).
    private MapView.Scale scaleFor(int blocksPerPixel) {
        if (blocksPerPixel <= 1) return MapView.Scale.CLOSEST;
        if (blocksPerPixel <= 2) return MapView.Scale.CLOSE;
        if (blocksPerPixel <= 4) return MapView.Scale.NORMAL;
        if (blocksPerPixel <= 8) return MapView.Scale.FAR;
        return MapView.Scale.FARTHEST;
    }

    private int blocksPerPixelOf(MapView.Scale scale) {
        return switch (scale) {
            case CLOSEST -> 1;
            case CLOSE -> 2;
            case NORMAL -> 4;
            case FAR -> 8;
            case FARTHEST -> 16;
        };
    }
}
