package com.bricklyworld.expedition.editor;

import com.bricklyworld.expedition.raid.RaidStateMachine;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The zone editor's guided GUI: pick a zone from the main menu, then in
 * that zone's menu either arm a marker type (right-click in the world to
 * drop it), switch to bounds mode, toggle a particle preview of existing
 * points, run selftest, or start/force-end a test raid - all without
 * typing a command or remembering a marker type's name.
 */
public final class ZoneEditorGUI implements Listener {

    private static final MarkerType[] MARKER_SLOTS = MarkerType.values(); // 9 types -> slots 0-8

    private final ZoneRegistry zoneRegistry;
    private final RaidStateMachine raidStateMachine;
    private final ZoneEditorController controller;
    private final MarkerPreviewService preview;

    public ZoneEditorGUI(ZoneRegistry zoneRegistry, RaidStateMachine raidStateMachine,
                          ZoneEditorController controller, MarkerPreviewService preview) {
        this.zoneRegistry = zoneRegistry;
        this.raidStateMachine = raidStateMachine;
        this.controller = controller;
        this.preview = preview;
    }

    public void openMainMenu(Player player) {
        MainMenuHolder holder = new MainMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, "§8BricklyExpedition - зоны");
        holder.setInventory(inv);

        for (Map.Entry<Integer, ZoneManifest> e : zoneRegistry.all().entrySet()) {
            int id = e.getKey();
            ZoneManifest manifest = e.getValue();
            boolean ready = manifest.isReadyForRaid();
            boolean active = zoneRegistry.isActive(id);
            Material icon = active ? Material.REDSTONE_LAMP : (ready ? Material.EMERALD : Material.REDSTONE_BLOCK);
            List<String> lore = new ArrayList<>();
            lore.add(manifest.bounds() == null ? "§7Границы: §cне заданы" : "§7Границы: §aзаданы");
            lore.add("§7Спавнов игроков: §f" + manifest.markerCount(MarkerType.PLAYER_SPAWN));
            lore.add("§7Эвакуация (осн.): §f" + manifest.markerCount(MarkerType.EVAC_MAIN));
            lore.add(active ? "§cАктивна: " + raidStateMachine.stateOf(id) : (ready ? "§aГотова к рейду" : "§cНе готова"));
            if (id >= 1 && id <= 8) {
                inv.setItem(id - 1, namedItem(icon, "§e#" + id + " " + manifest.displayName(), lore));
            }
        }
        fillEmpty(inv);
        player.openInventory(inv);
    }

    public void openZoneMenu(Player player, int zoneId) {
        controller.setEditingZone(player, zoneId);
        ZoneManifest manifest = zoneRegistry.get(zoneId);
        if (manifest == null) return;

        ZoneMenuHolder holder = new ZoneMenuHolder(zoneId);
        Inventory inv = Bukkit.createInventory(holder, 27, "§8Зона #" + zoneId);
        holder.setInventory(inv);

        for (int i = 0; i < MARKER_SLOTS.length; i++) {
            MarkerType type = MARKER_SLOTS[i];
            List<String> lore = List.of(
                    "§7ПКМ по блоку в мире - поставить точку",
                    "§7Shift+ЛКМ по блоку рядом - удалить ближайшую"
            );
            inv.setItem(i, namedItem(iconFor(type), "§b" + type + " §7(" + manifest.markerCount(type) + ")", lore));
        }

        inv.setItem(9, namedItem(Material.BLAZE_ROD, "§fГраницы зоны (wand)",
                List.of("§7Левый клик по блоку - точка 1", "§7Правый клик по блоку - точка 2")));
        inv.setItem(10, namedItem(Material.ENDER_EYE, "§fПоказать точки: " + (preview.isOn(player) ? "§aвкл" : "§7выкл"),
                List.of("§7Клик - переключить подсветку точек частицами")));
        inv.setItem(11, namedItem(Material.ANVIL, "§fSelftest", List.of("§7Проверить манифест зоны")));
        inv.setItem(12, namedItem(Material.LIME_WOOL, "§aЗапустить рейд (тест)", List.of("§7Без матчмейкинга - для проверки")));
        inv.setItem(13, namedItem(Material.RED_WOOL, "§cПринудительно завершить", List.of("§7Форс-стоп текущего рейда зоны")));
        inv.setItem(17, namedItem(Material.ARROW, "§7Назад", List.of()));

        fillEmpty(inv);
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object rawHolder = event.getInventory().getHolder();
        if (!(rawHolder instanceof MainMenuHolder) && !(rawHolder instanceof ZoneMenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) {
            return; // click landed in the player's own inventory, not the menu
        }

        if (rawHolder instanceof MainMenuHolder) {
            int zoneId = slot + 1;
            if (slot >= 0 && slot < 8 && zoneRegistry.get(zoneId) != null) {
                openZoneMenu(player, zoneId);
            }
            return;
        }

        ZoneMenuHolder holder = (ZoneMenuHolder) rawHolder;
        int zoneId = holder.zoneId();

        if (slot < MARKER_SLOTS.length) {
            MarkerType type = MARKER_SLOTS[slot];
            controller.armMarker(player, type);
            controller.giveWand(player);
            player.closeInventory();
            player.sendMessage("§aВ руке wand: правый клик по блоку ставит точку " + type
                    + " (shift+левый клик рядом с точкой - удалить).");
            return;
        }

        switch (slot) {
            case 9 -> {
                controller.disarm(player);
                controller.giveWand(player);
                player.closeInventory();
                player.sendMessage("§aВ руке wand: режим границ зоны (левый клик - точка 1, правый - точка 2).");
            }
            case 10 -> {
                boolean nowOn = preview.toggle(player);
                player.sendMessage(nowOn ? "§aПодсветка точек включена." : "§7Подсветка точек выключена.");
                openZoneMenu(player, zoneId);
            }
            case 11 -> {
                player.closeInventory();
                runSelftest(player, zoneId);
            }
            case 12 -> {
                player.closeInventory();
                boolean started = raidStateMachine.startRaid(zoneId);
                player.sendMessage(started
                        ? "§aРейд в зоне #" + zoneId + " запущен."
                        : "§cНе удалось запустить - зона уже активна или не прошла selftest.");
            }
            case 13 -> {
                player.closeInventory();
                raidStateMachine.forceEnd(zoneId);
                player.sendMessage("§aРейд в зоне #" + zoneId + " принудительно завершён, мир восстанавливается.");
            }
            case 17 -> openMainMenu(player);
            default -> { }
        }
    }

    private void runSelftest(Player player, int zoneId) {
        ZoneManifest manifest = zoneRegistry.get(zoneId);
        if (manifest == null) return;
        List<String> problems = manifest.validate();
        if (problems.isEmpty()) {
            player.sendMessage("§aЗона #" + zoneId + ": манифест в порядке, готова к рейду.");
        } else {
            player.sendMessage("§cЗона #" + zoneId + ": найдены проблемы:");
            for (String problem : problems) {
                player.sendMessage("§c - " + problem);
            }
        }
    }

    private Material iconFor(MarkerType type) {
        return switch (type) {
            case PLAYER_SPAWN -> Material.COMPASS;
            case MOB_SPAWN -> Material.SPAWNER;
            case BOSS_SPAWN -> Material.WITHER_SKELETON_SKULL;
            case LOOT_CACHE -> Material.CHEST;
            case ACTIVITY_SCRIPT -> Material.WRITTEN_BOOK;
            case EVAC_MAIN -> Material.ENDER_PEARL;
            case EVAC_PAID -> Material.GOLD_NUGGET;
            case DANGER_HIGH -> Material.TNT;
            case DANGER_LOW -> Material.YELLOW_CONCRETE;
        };
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private void fillEmpty(Inventory inv) {
        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
    }
}
