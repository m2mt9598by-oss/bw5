package com.bricklyworld.expedition.command;

import com.bricklyworld.expedition.economy.EconomyService;
import com.bricklyworld.expedition.editor.ZoneEditorController;
import com.bricklyworld.expedition.editor.ZoneEditorGUI;
import com.bricklyworld.expedition.entry.EntryQueueService;
import com.bricklyworld.expedition.pvp.KnockoutService;
import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateMachine;
import com.bricklyworld.expedition.world.MultiverseBridge;
import com.bricklyworld.expedition.zone.CuboidRegion;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneManifestStore;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Admin command surface. /bwexpedition menu is the real entry point now
 * (the Stage B guided GUI in ZoneEditorGUI) - the typed subcommands below
 * still work too, as a scriptable/no-GUI alternative and for testing.
 */
public final class BWExpeditionCommand implements CommandExecutor {

    private final ZoneRegistry zoneRegistry;
    private final ZoneManifestStore store;
    private final RaidStateMachine raidStateMachine;
    private final MultiverseBridge multiverse;
    private final ZoneEditorController controller;
    private final ZoneEditorGUI gui;
    private final EntryQueueService entryQueue;
    private final EconomyService economy;
    private final KnockoutService knockout;

    public BWExpeditionCommand(ZoneRegistry zoneRegistry, ZoneManifestStore store, RaidStateMachine raidStateMachine,
                                MultiverseBridge multiverse, ZoneEditorController controller, ZoneEditorGUI gui,
                                EntryQueueService entryQueue, EconomyService economy, KnockoutService knockout) {
        this.zoneRegistry = zoneRegistry;
        this.store = store;
        this.raidStateMachine = raidStateMachine;
        this.multiverse = multiverse;
        this.controller = controller;
        this.gui = gui;
        this.entryQueue = entryQueue;
        this.economy = economy;
        this.knockout = knockout;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (ADMIN_SUBCOMMANDS.contains(sub) && !sender.hasPermission("bricklyexpedition.admin")) {
            sender.sendMessage("§cУ вас нет доступа к этой команде.");
            return true;
        }

        switch (sub) {
            case "menu" -> handleMenu(sender);
            case "edit" -> handleEdit(sender, args);
            case "list" -> handleList(sender);
            case "create" -> handleCreate(sender, args);
            case "wand" -> handleWand(sender);
            case "setbounds" -> handleSetBounds(sender, args);
            case "addmarker" -> handleAddMarker(sender, args);
            case "selftest" -> handleSelftest(sender, args);
            case "startraid" -> handleStartRaid(sender, args);
            case "forceend" -> handleForceEnd(sender, args);
            case "join" -> handleJoin(sender);
            case "leave" -> handleLeave(sender);
            case "buykey" -> handleBuyKey(sender);
            case "balance" -> handleBalance(sender);
            case "surrender" -> handleSurrender(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private static final java.util.Set<String> ADMIN_SUBCOMMANDS = java.util.Set.of(
            "menu", "edit", "list", "create", "wand", "setbounds", "addmarker", "selftest", "startraid", "forceend");

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("""
                §7/bwexpedition menu §f- guided-редактор зоны (открыть меню)
                §7/bwexpedition edit <id> §f- начать редактировать зону без меню
                §7/bwexpedition list
                §7/bwexpedition create <id> <world>
                §7/bwexpedition wand
                §7/bwexpedition setbounds <id>
                §7/bwexpedition addmarker <id> <type> [meta]
                §7/bwexpedition selftest <id>
                §7/bwexpedition startraid <id> §f- запуск в обход очереди (для админов/теста)
                §7/bwexpedition forceend <id>
                §7/bwexpedition join §f- встать в очередь на вылазку
                §7/bwexpedition leave §f- выйти из очереди
                §7/bwexpedition buykey §f- купить ключ эвакуации (быстрый платный люк)
                §7/bwexpedition balance
                §7/bwexpedition surrender §f- сдаться сразу, если вы сбиты (не ждать минуту)""");
    }

    private void handleMenu(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        gui.openMainMenu(player);
    }

    private void handleEdit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /bwexpedition edit <id>");
            return;
        }
        int id = parseZoneId(sender, args[1]);
        if (id == -1) return;

        controller.setEditingZone(player, id);
        controller.giveWand(player);
        player.sendMessage("§aТеперь редактируете зону #" + id + ". Wand в режиме границ - откройте /bwexpedition menu, чтобы вооружить точку конкретного типа.");
    }

    private void handleList(CommandSender sender) {
        for (ZoneManifest manifest : zoneRegistry.all().values()) {
            RaidState state = raidStateMachine.stateOf(manifest.id());
            String status = zoneRegistry.isActive(manifest.id()) ? "§c" + state : "§7RESTORED";
            sender.sendMessage("§e#" + manifest.id() + " §f" + manifest.displayName() + " " + status
                    + (manifest.isReadyForRaid() ? " §a[готова]" : " §c[не готова]"));
        }
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /bwexpedition create <id> <world>");
            return;
        }
        int id = parseZoneId(sender, args[1]);
        if (id == -1) return;

        String worldName = args[2];
        World world = multiverse.resolveWorld(worldName);
        if (world == null) {
            sender.sendMessage("§cМир \"" + worldName + "\" не найден"
                    + (multiverse.isAvailable() ? " (проверено через Multiverse-Core)." : " (Multiverse-Core не обнаружен, проверено через Bukkit)."));
            return;
        }

        ZoneManifest manifest = zoneRegistry.get(id);
        manifest.setDisplayName("Zone " + id + " (" + worldName + ")");
        store.save(manifest);
        sender.sendMessage("§aЗона #" + id + " привязана к миру \"" + worldName + "\". Теперь задайте границы: /bwexpedition wand, затем /bwexpedition setbounds " + id);
    }

    private void handleWand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        controller.giveWand(player);
        player.sendMessage("§aWand выдан. Левый клик по блоку - точка 1, правый клик - точка 2 куба зоны.");
    }

    private void handleSetBounds(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /bwexpedition setbounds <id>");
            return;
        }
        int id = parseZoneId(sender, args[1]);
        if (id == -1) return;

        Location a = controller.pos1(player);
        Location b = controller.pos2(player);
        if (a == null || b == null) {
            sender.sendMessage("§cСначала отметьте обе точки wand'ом (левый клик = точка 1, правый клик = точка 2).");
            return;
        }

        ZoneManifest manifest = zoneRegistry.get(id);
        manifest.setBounds(CuboidRegion.fromCorners(a, b));
        store.save(manifest);
        sender.sendMessage("§aГраницы зоны #" + id + " сохранены: " + manifest.bounds()
                + " (объём " + manifest.bounds().volume() + " блоков).");
    }

    private void handleAddMarker(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /bwexpedition addmarker <id> <тип> [метка]. Типы: "
                    + String.join(", ", markerTypeNames()));
            return;
        }
        int id = parseZoneId(sender, args[1]);
        if (id == -1) return;

        MarkerType type;
        try {
            type = MarkerType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cНеизвестный тип точки. Доступные: " + String.join(", ", markerTypeNames()));
            return;
        }

        String meta = args.length > 3 ? String.join(" ", List.of(args).subList(3, args.length)) : "";
        Location loc = player.getLocation();
        ZoneManifest manifest = zoneRegistry.get(id);
        manifest.addMarker(new com.bricklyworld.expedition.zone.ZoneMarker(
                type, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), meta));
        store.save(manifest);
        sender.sendMessage("§aТочка " + type + " добавлена в зону #" + id + " на вашей позиции"
                + (meta.isBlank() ? "" : " (метка: " + meta + ")") + ".");
    }

    private String[] markerTypeNames() {
        MarkerType[] values = MarkerType.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) names[i] = values[i].name();
        return names;
    }

    private void handleSelftest(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /bwexpedition selftest <id>");
            return;
        }
        int id = parseZoneId(sender, args[1]);
        if (id == -1) return;

        ZoneManifest manifest = zoneRegistry.get(id);
        List<String> problems = manifest.validate();
        if (problems.isEmpty()) {
            sender.sendMessage("§aЗона #" + id + ": манифест в порядке, готова к рейду.");
        } else {
            sender.sendMessage("§cЗона #" + id + ": найдены проблемы:");
            for (String problem : problems) {
                sender.sendMessage("§c - " + problem);
            }
        }
    }

    private void handleStartRaid(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /bwexpedition startraid <id>");
            return;
        }
        int id = parseZoneId(sender, args[1]);
        if (id == -1) return;

        boolean started = raidStateMachine.startRaid(id);
        sender.sendMessage(started
                ? "§aРейд в зоне #" + id + " запущен вручную в обход очереди (для теста/админа)."
                : "§cНе удалось запустить рейд в зоне #" + id + " - зона уже активна или манифест не прошёл selftest.");
    }

    private void handleJoin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        entryQueue.join(player);
    }

    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        entryQueue.leave(player);
    }

    private void handleBuyKey(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        entryQueue.buyEvacKey(player);
    }

    private void handleBalance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        sender.sendMessage("§7Ваш баланс: §f" + economy.balance(player.getUniqueId()));
    }

    private void handleSurrender(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cТолько для игрока.");
            return;
        }
        if (!knockout.surrender(player)) {
            sender.sendMessage("§7Вы не сбиты - сдаваться не от чего.");
        }
    }

    private void handleForceEnd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /bwexpedition forceend <id>");
            return;
        }
        int id = parseZoneId(sender, args[1]);
        if (id == -1) return;

        raidStateMachine.forceEnd(id);
        sender.sendMessage("§aРейд в зоне #" + id + " принудительно завершён, восстановление мира запущено.");
    }

    private int parseZoneId(CommandSender sender, String raw) {
        try {
            int id = Integer.parseInt(raw);
            if (zoneRegistry.get(id) == null) {
                sender.sendMessage("§cНет зоны с id " + id + ".");
                return -1;
            }
            return id;
        } catch (NumberFormatException e) {
            sender.sendMessage("§cid зоны должен быть числом.");
            return -1;
        }
    }
}
