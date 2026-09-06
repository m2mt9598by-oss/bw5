package com.bricklyworld.expedition.loot;

import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "После убийства мобов бигбоссов и игроков у всех остаются силуэты которые
 * можно лутать (лутаться так-же сначала ждешь 6 секунд. потом открывается
 * инвентарь)". A corpse is an invisible marker ArmorStand (so it doesn't
 * need any real block placed/restored - no DeltaJournal involvement) that
 * owns a normal Bukkit inventory; interacting with it before its 6-second
 * timer is up just reminds the player to wait, after that it opens like
 * any container. Boss kills call spawnCorpses with count=5 ("силуэтов
 * должно быть больше чем один... штук 5") scattered a couple of blocks
 * apart; everything else (players, regular mobs) is count=1.
 *
 * BUG FIX (2026-09-06, real playtest report - "спавнят тайники их никак не
 * открыть, просто пустая земля с надписью"): this used to call
 * stand.setMarker(true) on the invisible ArmorStand. A "marker" armor stand
 * has NO hitbox at all in vanilla Minecraft - it's a pure decoration mode
 * with nothing for a player's right-click raycast to ever hit, so the
 * corpse's floating name was visible but the entity itself was completely
 * unclickable. Fixed by leaving marker mode off (an invisible, small,
 * non-gravity armor stand is still perfectly clickable - this is the
 * standard "invisible interactable stand" pattern).
 *
 * VISUAL UPGRADE (2026-09-06, per Egor's reference plugin in his own old
 * build - ru.bricklyworld.expedition.downed.CorpseService - which spawns a
 * floating ItemDisplay showing a skull above every lootable point): added
 * the same idea here - a small floating, slowly spinning ItemDisplay with a
 * skull item hovers just above the corpse, so a lootable spot reads clearly
 * from a distance instead of just an easy-to-miss floating name tag.
 */
public final class CorpseService implements Listener {

    private static final long OPEN_DELAY_MILLIS = 6_000;
    private static final double SCATTER_RADIUS = 2.5;
    private static final double HEAD_HOVER_HEIGHT = 1.1;

    private final Plugin plugin;
    private final NamespacedKey corpseKey;
    private final Map<UUID, Inventory> inventories = new HashMap<>();
    private final Map<UUID, Long> unlockAtMillis = new HashMap<>();
    private final Map<Integer, Set<UUID>> corpsesByZone = new HashMap<>();
    private final Map<UUID, UUID> headDisplayOf = new HashMap<>();

    public CorpseService(Plugin plugin) {
        this.plugin = plugin;
        this.corpseKey = new NamespacedKey(plugin, "corpse-marker");
    }

    /** One corpse at the exact location, holding a copy of the given items. */
    public void spawnCorpse(Location location, int zoneId, String label, List<ItemStack> items) {
        ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class);
        stand.setInvisible(true);
        stand.setSmall(true);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setCustomName(label);
        stand.setCustomNameVisible(true);
        stand.getPersistentDataContainer().set(corpseKey, PersistentDataType.BYTE, (byte) 1);

        Inventory inv = plugin.getServer().createInventory(null, 54, trim(label));
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            var leftover = inv.addItem(item);
            if (!leftover.isEmpty()) {
                plugin.getLogger().info("Труп \"" + label + "\" переполнен - часть предметов потеряна (инвентарь 54 слота).");
            }
        }

        UUID id = stand.getUniqueId();
        inventories.put(id, inv);
        unlockAtMillis.put(id, System.currentTimeMillis() + OPEN_DELAY_MILLIS);
        corpsesByZone.computeIfAbsent(zoneId, k -> new HashSet<>()).add(id);

        UUID headId = spawnHeadMarker(location);
        if (headId != null) {
            headDisplayOf.put(id, headId);
        }
    }

    /**
     * Purely visual: a small skull hovering just above a lootable point,
     * spinning slowly so it reads clearly against any background. Uses the
     * ItemDisplay entity (Paper/Spigot 1.19.4+) the same way Egor's own
     * reference plugin does it - see the class doc above.
     */
    private UUID spawnHeadMarker(Location location) {
        Location headLoc = location.clone().add(0, HEAD_HOVER_HEIGHT, 0);
        ItemDisplay display = location.getWorld().spawn(headLoc, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.PLAYER_HEAD));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 1, 0),
                    new Vector3f(0.6f, 0.6f, 0.6f),
                    new AxisAngle4f(0, 0, 1, 0)));
            d.setInvulnerable(true);
            d.setPersistent(true);
        });
        // Slow constant spin around the vertical axis, purely cosmetic -
        // rebuilds a fresh Transformation each tick rather than mutating the
        // one returned by getTransformation(), since it's unclear from
        // training knowledge alone whether that getter returns a live,
        // mutable reference or a defensive copy - constructing a new one
        // explicitly every tick sidesteps the question either way.
        int period = 2;
        display.setInterpolationDuration(period);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!display.isValid()) {
                task.cancel();
                return;
            }
            float angle = (float) ((System.currentTimeMillis() / 1000.0) % (Math.PI * 2));
            display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(angle, 0, 1, 0),
                    new Vector3f(0.6f, 0.6f, 0.6f),
                    new AxisAngle4f(0, 0, 1, 0)));
        }, 1L, period);
        return display.getUniqueId();
    }

    /** Several corpses scattered near one location - the big-boss case. */
    public void spawnCorpses(Location origin, int count, int zoneId, String label, List<List<ItemStack>> lootPerCorpse) {
        for (int i = 0; i < count; i++) {
            Location loc = i == 0 ? origin.clone() : scatter(origin);
            List<ItemStack> items = i < lootPerCorpse.size() ? lootPerCorpse.get(i) : new ArrayList<>();
            spawnCorpse(loc, zoneId, label + " §7(" + (i + 1) + "/" + count + ")", items);
        }
    }

    private Location scatter(Location center) {
        double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
        double dist = ThreadLocalRandom.current().nextDouble(1.0, SCATTER_RADIUS);
        Vector offset = new Vector(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
        return center.clone().add(offset);
    }

    private String trim(String label) {
        // Inventory titles have a length cap in vanilla - keep it short and safe.
        return label.length() > 32 ? label.substring(0, 32) : label;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getType() != EntityType.ARMOR_STAND) return;
        ArmorStand stand = (ArmorStand) event.getRightClicked();
        if (!stand.getPersistentDataContainer().has(corpseKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        UUID id = stand.getUniqueId();
        Long unlockAt = unlockAtMillis.get(id);
        Player player = event.getPlayer();
        if (unlockAt != null && System.currentTimeMillis() < unlockAt) {
            player.sendActionBar("§7Тело ещё не остыло...");
            return;
        }
        Inventory inv = inventories.get(id);
        if (inv != null) {
            player.openInventory(inv);
        }
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        if (event.to() != RaidState.RESTORED) return;
        Set<UUID> ids = corpsesByZone.remove(event.zoneId());
        if (ids == null) return;
        for (UUID id : ids) {
            inventories.remove(id);
            unlockAtMillis.remove(id);
            var entity = plugin.getServer().getEntity(id);
            if (entity != null) entity.remove();

            UUID headId = headDisplayOf.remove(id);
            if (headId != null) {
                var head = plugin.getServer().getEntity(headId);
                if (head != null) head.remove();
            }
        }
    }
}
