package com.bricklyworld.expedition.editor;

import com.bricklyworld.expedition.activity.ActivityScriptType;
import com.bricklyworld.expedition.mob.MobArchetype;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneManifestStore;
import com.bricklyworld.expedition.zone.ZoneMarker;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything one admin's wand-in-hand does, driven by whatever the zone
 * editor GUI last armed for them:
 *  - no marker type armed -> left/right click define the zone's cuboid
 *    corners (pos1/pos2), same as Stage A;
 *  - a marker type armed (via the GUI's zone menu) -> right-click drops
 *    that marker on the clicked block, sneak + left-click removes the
 *    nearest marker of that type instead.
 * "Which zone am I editing" is also tracked here per player, set by the
 * GUI (or the /bwexpedition edit &lt;id&gt; shortcut) so a right-click can
 * know which manifest to write into without re-specifying an id every time.
 */
public final class ZoneEditorController implements Listener {

    private static final double REMOVE_RADIUS = 2.0;

    private final NamespacedKey wandKey;
    private final ZoneRegistry zoneRegistry;
    private final ZoneManifestStore store;

    private final Map<UUID, Integer> editingZone = new HashMap<>();
    private final Map<UUID, MarkerType> armedMarker = new HashMap<>();
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Map<UUID, Integer> pendingMetaIndex = new HashMap<>();

    public ZoneEditorController(Plugin plugin, ZoneRegistry zoneRegistry, ZoneManifestStore store) {
        this.wandKey = new NamespacedKey(plugin, "zone-wand");
        this.zoneRegistry = zoneRegistry;
        this.store = store;
    }

    public ItemStack createWand() {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.setDisplayName("§cWand разметки зоны BricklyExpedition");
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        return wand;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    public void giveWand(Player player) {
        player.getInventory().addItem(createWand());
    }

    public void setEditingZone(Player player, int zoneId) {
        editingZone.put(player.getUniqueId(), zoneId);
    }

    public Integer editingZone(Player player) {
        return editingZone.get(player.getUniqueId());
    }

    public void armMarker(Player player, MarkerType type) {
        armedMarker.put(player.getUniqueId(), type);
        pendingMetaIndex.put(player.getUniqueId(), 0);
        List<String> options = metaOptions(type);
        if (options.size() > 1) {
            player.sendMessage("§7Подсказка: присядьте и кликните в воздух (ЛКМ), чтобы выбрать вариант перед установкой точки. Сейчас: "
                    + describeMeta(options.get(0)));
        }
    }

    public void disarm(Player player) {
        armedMarker.remove(player.getUniqueId());
        pendingMetaIndex.remove(player.getUniqueId());
    }

    public MarkerType armed(Player player) {
        return armedMarker.get(player.getUniqueId());
    }

    /**
     * The list of meaningful meta values for a marker type, admin-facing.
     * First entry is always "" (plugin picks a default at spawn/apply time)
     * so "не трогать выбор" stays the simplest, zero-effort default.
     *  - MOB_SPAWN / BOSS_SPAWN -> one of the 8 mob archetype names.
     *  - LOOT_CACHE -> a loot tier id 1-10 (see config.yml loot.tiers).
     *  - DANGER_HIGH / DANGER_LOW -> a preset action radius in blocks (see
     *    "Зоны интереса и опасные зоны, лучше создать параметр - задать
     *    зону на какую дистанцию - я сам указываю область действия зоны",
     *    a real playtest request - DangerZoneService reads this meta as
     *    the marker's effect radius instead of it being purely decorative).
     *  - anything else -> no meaningful choice, just "".
     */
    private static final String[] DANGER_RADIUS_OPTIONS = {"5", "8", "12", "16", "24", "32"};

    private List<String> metaOptions(MarkerType type) {
        List<String> options = new ArrayList<>();
        options.add("");
        switch (type) {
            case MOB_SPAWN, BOSS_SPAWN -> {
                for (MobArchetype archetype : MobArchetype.values()) {
                    options.add(archetype.name());
                }
            }
            case LOOT_CACHE -> {
                for (int i = 1; i <= 10; i++) {
                    options.add(String.valueOf(i));
                }
            }
            case ACTIVITY_SCRIPT -> {
                for (ActivityScriptType scriptType : ActivityScriptType.values()) {
                    options.add(scriptType.name());
                }
            }
            case DANGER_HIGH, DANGER_LOW -> {
                for (String radius : DANGER_RADIUS_OPTIONS) {
                    options.add(radius);
                }
            }
            default -> { /* no meta choice for this marker type */ }
        }
        return options;
    }

    private String describeMeta(String meta) {
        return meta.isBlank() ? "§7(случайно/по умолчанию)" : "§f" + meta;
    }

    private String currentMeta(Player player, MarkerType type) {
        List<String> options = metaOptions(type);
        int index = pendingMetaIndex.getOrDefault(player.getUniqueId(), 0) % options.size();
        return options.get(index);
    }

    private void cycleMeta(Player player, MarkerType type) {
        List<String> options = metaOptions(type);
        if (options.size() <= 1) {
            player.sendActionBar("§7У этого типа точки нет вариантов.");
            return;
        }
        int index = (pendingMetaIndex.getOrDefault(player.getUniqueId(), 0) + 1) % options.size();
        pendingMetaIndex.put(player.getUniqueId(), index);
        player.sendActionBar("§6Вариант точки: " + describeMeta(options.get(index)));
    }

    public Location pos1(Player player) { return pos1.get(player.getUniqueId()); }
    public Location pos2(Player player) { return pos2.get(player.getUniqueId()); }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!isWand(event.getItem())) return;
        Player player = event.getPlayer();
        MarkerType type = armedMarker.get(player.getUniqueId());

        // Sneak + left-click into the air cycles the pending meta value for
        // the armed marker type (mob archetype / loot tier) - doesn't need a
        // block target, so it works anywhere, including looking at the sky.
        if (type != null && player.isSneaking() && event.getAction() == Action.LEFT_CLICK_AIR) {
            event.setCancelled(true);
            cycleMeta(player, type);
            return;
        }

        if (event.getClickedBlock() == null) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        event.setCancelled(true);

        Location clicked = event.getClickedBlock().getLocation();

        if (type == null) {
            // Bounds mode - independent of any zone selected in the GUI.
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                pos1.put(player.getUniqueId(), clicked);
                player.sendMessage("§aТочка 1 куба зоны: " + format(clicked));
            } else {
                pos2.put(player.getUniqueId(), clicked);
                player.sendMessage("§aТочка 2 куба зоны: " + format(clicked));
            }
            return;
        }

        Integer zoneId = editingZone.get(player.getUniqueId());
        if (zoneId == null) {
            player.sendMessage("§cСначала выберите зону: /bwexpedition menu");
            return;
        }
        ZoneManifest manifest = zoneRegistry.get(zoneId);
        if (manifest == null) return;

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Location center = clicked.clone().add(0.5, 1.0, 0.5);
            String meta = currentMeta(player, type);
            manifest.addMarker(new ZoneMarker(type, center.getX(), center.getY(), center.getZ(),
                    player.getLocation().getYaw(), player.getLocation().getPitch(), meta));
            store.save(manifest);
            player.sendMessage("§aТочка " + type + " добавлена в зону #" + zoneId
                    + (meta.isBlank() ? " (случайный вариант)." : " (" + meta + ")."));
        } else if (player.isSneaking()) {
            boolean removed = manifest.markers(type).removeIf(marker -> distanceSquared(marker, clicked) <= REMOVE_RADIUS * REMOVE_RADIUS);
            if (removed) {
                store.save(manifest);
                player.sendMessage("§aБлижайшая точка " + type + " удалена из зоны #" + zoneId + ".");
            } else {
                player.sendMessage("§7Рядом нет точки " + type + " для удаления.");
            }
        } else {
            player.sendMessage("§7Чтобы удалить точку, встаньте рядом и присядьте (shift) перед левым кликом.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        editingZone.remove(id);
        armedMarker.remove(id);
        pos1.remove(id);
        pos2.remove(id);
        pendingMetaIndex.remove(id);
    }

    private double distanceSquared(ZoneMarker marker, Location loc) {
        double dx = marker.x() - (loc.getBlockX() + 0.5);
        double dy = marker.y() - (loc.getBlockY() + 1.0);
        double dz = marker.z() - (loc.getBlockZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private String format(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
    }
}
