package com.bricklyworld.expedition.loot;

import com.bricklyworld.expedition.config.LootTierConfig;
import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneMarker;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Открываешь меню берешь предмет ставишь и он далее системой определяется
 * как тайник" - Stage E, first cut: the admin still places the actual
 * chest/barrel block by hand at a LOOT_CACHE marker (a stand-in for the
 * concept doc's future custom prop models, which need a resource pack this
 * environment can't build); this service is what turns that plain block
 * into a real tiered cache each time a raid goes ACTIVE, and wipes it again
 * on RESTORED so the map is "living" and resets every expedition.
 *
 * Locking itself (the channelled open delay) lives in LootLockListener -
 * this class only fills/tags/clears the containers and answers lookups.
 */
public final class LootCacheService implements Listener {

    public static final NamespacedKey KEY_TIER;
    public static final NamespacedKey KEY_ZONE;
    public static final NamespacedKey KEY_LOCKED;

    static {
        // Plugin instance not available in a static initializer, so these are
        // built lazily from the constructor the first time and cached here.
        KEY_TIER = new NamespacedKey("bricklyexpedition", "cache-tier");
        KEY_ZONE = new NamespacedKey("bricklyexpedition", "cache-zone");
        KEY_LOCKED = new NamespacedKey("bricklyexpedition", "cache-locked");
    }

    private static final int MIN_ITEMS = 3;
    private static final int MAX_ITEMS = 6;

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final LootTierConfig tierConfig;
    private final Map<Integer, List<Location>> activeCaches = new HashMap<>();

    public LootCacheService(Plugin plugin, ZoneRegistry zoneRegistry, LootTierConfig tierConfig) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.tierConfig = tierConfig;
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        if (event.to() == RaidState.ACTIVE) {
            ZoneManifest manifest = zoneRegistry.get(event.zoneId());
            if (manifest != null) {
                fillZone(event.zoneId(), manifest);
            }
        } else if (event.to() == RaidState.RESTORED) {
            clearZone(event.zoneId());
        }
    }

    public void fillZone(int zoneId, ZoneManifest manifest) {
        if (manifest.bounds() == null) return;
        World world = Bukkit.getWorld(manifest.bounds().worldName());
        if (world == null) {
            plugin.getLogger().warning("Не удалось заполнить тайники зоны #" + zoneId + " - мир не загружен.");
            return;
        }
        List<Location> tracked = new ArrayList<>();
        for (ZoneMarker marker : manifest.markers(MarkerType.LOOT_CACHE)) {
            Location loc = new Location(world, marker.x(), marker.y(), marker.z());
            Block block = loc.getBlock();
            if (!(block.getState() instanceof Container container)) {
                plugin.getLogger().warning("Маркер LOOT_CACHE в зоне #" + zoneId + " (" + marker.x() + "," + marker.y()
                        + "," + marker.z() + ") не указывает на сундук/бочку - пропускаю. Проверьте расстановку.");
                continue;
            }
            int tierId = parseTier(marker.meta());
            fillContainer(container, zoneId, tierId);
            tracked.add(loc);
        }
        activeCaches.put(zoneId, tracked);
    }

    /**
     * Same fill/tag/lock as a manifest LOOT_CACHE marker, exposed for
     * anything that creates a container outside the normal zone-fill pass -
     * currently just AirdropService, whose crate lands as a real placed
     * chest and should open exactly like any other tiered cache (reusing
     * these PDC keys means the existing LootLockListener already knows how
     * to handle it with zero extra code).
     */
    public void fillAdHocContainer(Container container, int zoneId, int tierId) {
        fillContainer(container, zoneId, tierId);
    }

    private void fillContainer(Container container, int zoneId, int tierId) {
        LootTierConfig.Tier tier = tierConfig.tier(tierId);
        Inventory inv = container.getInventory();
        inv.clear();
        if (tier != null) {
            for (var item : tier.rollLoot(MIN_ITEMS, MAX_ITEMS)) {
                inv.addItem(item);
            }
        }
        container.getPersistentDataContainer().set(KEY_TIER, PersistentDataType.INTEGER, tierId);
        container.getPersistentDataContainer().set(KEY_ZONE, PersistentDataType.INTEGER, zoneId);
        container.getPersistentDataContainer().set(KEY_LOCKED, PersistentDataType.BYTE, (byte) 1);
        container.update();
    }

    private int parseTier(String meta) {
        if (meta != null && !meta.isBlank()) {
            try {
                int id = Integer.parseInt(meta.trim());
                if (tierConfig.hasTier(id)) return id;
            } catch (NumberFormatException ignored) {
                // fall through to random below - admin typed something odd
            }
        }
        int count = Math.max(1, tierConfig.tierCount());
        return 1 + ThreadLocalRandom.current().nextInt(count);
    }

    public void clearZone(int zoneId) {
        List<Location> tracked = activeCaches.remove(zoneId);
        if (tracked == null) return;
        for (Location loc : tracked) {
            Block block = loc.getBlock();
            if (block.getState() instanceof Container container) {
                container.getInventory().clear();
                container.getPersistentDataContainer().remove(KEY_TIER);
                container.getPersistentDataContainer().remove(KEY_ZONE);
                container.getPersistentDataContainer().remove(KEY_LOCKED);
                container.update();
            }
        }
    }

    public static boolean isOurCache(Container container) {
        return container.getPersistentDataContainer().has(KEY_ZONE, PersistentDataType.INTEGER);
    }

    public static boolean isLocked(Container container) {
        Byte locked = container.getPersistentDataContainer().get(KEY_LOCKED, PersistentDataType.BYTE);
        return locked != null && locked == (byte) 1;
    }

    public static Integer tierOf(Container container) {
        return container.getPersistentDataContainer().get(KEY_TIER, PersistentDataType.INTEGER);
    }

    public static void unlock(Container container) {
        container.getPersistentDataContainer().set(KEY_LOCKED, PersistentDataType.BYTE, (byte) 0);
        container.update();
    }
}
