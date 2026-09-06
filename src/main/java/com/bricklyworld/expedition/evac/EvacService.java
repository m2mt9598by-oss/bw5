package com.bricklyworld.expedition.evac;

import com.bricklyworld.expedition.audio.MusicService;
import com.bricklyworld.expedition.config.ExpeditionConfig;
import com.bricklyworld.expedition.entry.EntryQueueService;
import com.bricklyworld.expedition.identity.AnonymityService;
import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.stats.RaidResultsGUI;
import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import com.bricklyworld.expedition.world.DeltaJournal;
import com.bricklyworld.expedition.world.DeltaJournalManager;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneMarker;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stage F, first cut - "Эвакуационных выходов будет 2 на всю игровую
 * локацию а так-же 2 дополнительных" (EVAC_MAIN x2, EVAC_PAID x2 markers).
 * Walking up to one and interacting starts a channelled open ("платный
 * люк открывается 30 секунд а обычный 1:30") that teleports the player
 * back to the hub on success - the actual per-run win condition. The paid
 * hatch also requires having bought a key via EntryQueueService first.
 *
 * "нажатый выход - громкий визуальный сигнал и звук на всю карту" is done
 * here as a signal broadcast to every player currently in that raid zone
 * the moment someone starts channelling, not just on success, so the rest
 * of the map can react to (or race for) an evac in progress.
 */
public final class EvacService implements Listener {

    private static final double TRIGGER_RADIUS = 4.0;
    private static final double STAY_RADIUS = 5.0;
    private static final int MAIN_STATION_RADIUS = 6;   // "крупная до 11 блоков в радиусе" - comfortably under the cap
    private static final int PAID_STATION_RADIUS = 3;   // "платные люки меньше в размерах"
    private static final int PYLON_HEIGHT = 4;
    private static final double ACTIVITY_RADIUS = 15.0; // station "notices" a raider and gets louder/busier within this range

    private record Channel(UUID player, int zoneId, Location hatchLoc, boolean paid, int requiredTicks, int elapsedTicks) {
        Channel tick() {
            return new Channel(player, zoneId, hatchLoc, paid, requiredTicks, elapsedTicks + 1);
        }
    }

    private record Station(Location console, boolean paid) {
    }

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final RaidPlayerRegistry raidPlayers;
    private final EntryQueueService entryQueue;
    private final DeltaJournalManager deltaJournals;
    private final ExpeditionConfig config;
    private final AnonymityService anonymity;
    private final RaidResultsGUI resultsGui;
    private final MusicService musicService;
    private final Map<UUID, Channel> channels = new HashMap<>();
    private final Map<Integer, List<Station>> stationsByZone = new HashMap<>();
    private BukkitTask task;
    private BukkitTask ambientTask;
    private long ambientTicks = 0;

    public EvacService(Plugin plugin, ZoneRegistry zoneRegistry, RaidPlayerRegistry raidPlayers,
                        EntryQueueService entryQueue, DeltaJournalManager deltaJournals, ExpeditionConfig config,
                        AnonymityService anonymity, RaidResultsGUI resultsGui, MusicService musicService) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.raidPlayers = raidPlayers;
        this.entryQueue = entryQueue;
        this.deltaJournals = deltaJournals;
        this.config = config;
        this.anonymity = anonymity;
        this.resultsGui = resultsGui;
        this.musicService = musicService;
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        this.ambientTask = Bukkit.getScheduler().runTaskTimer(plugin, this::ambientTick, 20L, 20L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        Integer zoneId = raidPlayers.zoneOf(player.getUniqueId());
        if (zoneId == null) return; // not in a raid - not our business

        ZoneManifest manifest = zoneRegistry.get(zoneId);
        if (manifest == null) return;
        Location clicked = event.getClickedBlock().getLocation();

        ZoneMarker hatch = nearestHatch(manifest, clicked);
        if (hatch == null) return;

        event.setCancelled(true);
        if (channels.containsKey(player.getUniqueId())) {
            return; // already channelling
        }

        boolean paid = hatch.type() == MarkerType.EVAC_PAID;
        if (paid && !entryQueue.hasEvacKey(player.getUniqueId())) {
            player.sendMessage("§cЭтот люк платный - нужен ключ эвакуации. §f/bwexpedition buykey");
            return;
        }

        int seconds = (int) (paid ? config.prepDurationPaidSeconds : config.prepDurationFreeSeconds);
        Location hatchLoc = new Location(clicked.getWorld(), hatch.x(), hatch.y(), hatch.z());
        channels.put(player.getUniqueId(), new Channel(player.getUniqueId(), zoneId, hatchLoc, paid, seconds * 20, 0));
        player.sendActionBar("§6Станция вызвана (" + (paid ? "платная" : "обычная") + ")... §7не отходите, ждите прибытия (" + seconds + " сек).");
        player.playSound(hatchLoc, Sound.BLOCK_DISPENSER_DISPENSE, 1.0f, 1.6f);
        signalZone(manifest, zoneId, hatchLoc, player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        channels.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        if (event.to() == RaidState.ACTIVE) {
            ZoneManifest manifest = zoneRegistry.get(event.zoneId());
            if (manifest != null && manifest.bounds() != null) {
                buildStations(event.zoneId(), manifest);
            }
        }
        if (event.to() == RaidState.CLOSING || event.to() == RaidState.RESTORED) {
            channels.entrySet().removeIf(e -> e.getValue().zoneId() == event.zoneId());
        }
        if (event.to() == RaidState.RESTORED) {
            stationsByZone.remove(event.zoneId());
        }
    }

    /**
     * VISUAL/STRUCTURAL UPGRADE (2026-09-06, real playtest feedback - evac
     * points need to read as "real technological stations, not just floating
     * coordinates", ARC Raiders-style but no magic-looking blocks): every
     * EVAC_MAIN/EVAC_PAID marker gets a real, physical station built out of
     * ordinary blocks the moment its zone goes ACTIVE - a circular platform,
     * a perimeter railing, glowing pylons, and a raised console with a real,
     * clickable call button on top. Every placed block is journaled into the
     * zone's own DeltaJournal (already open by the time this fires - see
     * RaidStateMachine#advance) exactly like any other change, so the whole
     * station cleanly reverts along with the rest of the zone on RESTORED -
     * no separate cleanup code needed here for the structure itself.
     */
    private void buildStations(int zoneId, ZoneManifest manifest) {
        World world = Bukkit.getWorld(manifest.bounds().worldName());
        if (world == null) return;
        DeltaJournal journal = deltaJournals.journalFor(zoneId);

        List<Station> stations = new ArrayList<>();
        for (MarkerType type : new MarkerType[]{MarkerType.EVAC_MAIN, MarkerType.EVAC_PAID}) {
            boolean paid = type == MarkerType.EVAC_PAID;
            for (ZoneMarker marker : manifest.markers(type)) {
                Location markerLoc = new Location(world, marker.x(), marker.y(), marker.z());
                Location console = buildStation(journal, world, markerLoc, paid);
                stations.add(new Station(console, paid));
            }
        }
        if (!stations.isEmpty()) {
            stationsByZone.put(zoneId, stations);
        }
    }

    /** Builds one station and returns the location of its call-button console (used for ambient FX/sound). */
    private Location buildStation(DeltaJournal journal, World world, Location marker, boolean paid) {
        int radius = paid ? PAID_STATION_RADIUS : MAIN_STATION_RADIUS;
        int baseY = marker.getBlockY() - 1;
        int cx = marker.getBlockX();
        int cz = marker.getBlockZ();

        Material floorMain = paid ? Material.GOLD_BLOCK : Material.LIGHT_GRAY_CONCRETE;
        Material pylonMain = paid ? Material.GOLD_BLOCK : Material.IRON_BLOCK;

        // Circular platform - solid core, a darker ring right at the edge.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > radius) continue;
                Material mat = dist > radius - 1 ? Material.POLISHED_BLACKSTONE : floorMain;
                placeBlock(journal, world.getBlockAt(cx + dx, baseY, cz + dz), mat.createBlockData());
            }
        }

        // Perimeter railing.
        int railSteps = paid ? 8 : 14;
        for (int i = 0; i < railSteps; i++) {
            double angle = (Math.PI * 2 * i) / railSteps;
            int x = cx + (int) Math.round(Math.cos(angle) * radius);
            int z = cz + (int) Math.round(Math.sin(angle) * radius);
            placeBlock(journal, world.getBlockAt(x, baseY + 1, z), Material.IRON_BARS.createBlockData());
        }

        // Pylons - glowing tips, so the station reads from a distance.
        int pylonCount = paid ? 3 : 6;
        int height = paid ? PYLON_HEIGHT - 1 : PYLON_HEIGHT;
        for (int i = 0; i < pylonCount; i++) {
            double angle = (Math.PI * 2 * i) / pylonCount;
            int x = cx + (int) Math.round(Math.cos(angle) * (radius - 1));
            int z = cz + (int) Math.round(Math.sin(angle) * (radius - 1));
            for (int h = 1; h <= height; h++) {
                placeBlock(journal, world.getBlockAt(x, baseY + h, z), pylonMain.createBlockData());
            }
            placeBlock(journal, world.getBlockAt(x, baseY + height + 1, z), Material.SEA_LANTERN.createBlockData());
        }

        // Central console: short pillar, a real clickable call button on top, and a status light.
        placeBlock(journal, world.getBlockAt(cx, baseY + 1, cz), Material.POLISHED_BLACKSTONE.createBlockData());
        BlockData buttonData = Material.STONE_BUTTON.createBlockData();
        if (buttonData instanceof Switch sw) {
            sw.setFace(Switch.Face.FLOOR);
            sw.setFacing(BlockFace.NORTH);
            buttonData = sw;
        }
        Block buttonBlock = world.getBlockAt(cx, baseY + 2, cz);
        placeBlock(journal, buttonBlock, buttonData);
        placeBlock(journal, world.getBlockAt(cx + 1, baseY + 1, cz), Material.REDSTONE_LAMP.createBlockData("[lit=true]"));

        return buttonBlock.getLocation().add(0.5, 0.5, 0.5);
    }

    private void placeBlock(DeltaJournal journal, Block block, BlockData data) {
        if (journal != null) {
            journal.recordIfAbsent(block);
        }
        block.setBlockData(data, false);
    }

    /**
     * "Должны шуметь, издавать технический радио шум, проявлять активность
     * когда игрок рядом" - every station hums quietly at all times and gets
     * noticeably busier (more frequent blips + particles) once a raider is
     * within ACTIVITY_RADIUS, entirely via world-level sound (naturally
     * distance-attenuated per listener, no per-player looping needed).
     */
    private void ambientTick() {
        if (stationsByZone.isEmpty()) return;
        ambientTicks++;
        for (Map.Entry<Integer, List<Station>> entry : stationsByZone.entrySet()) {
            int zoneId = entry.getKey();
            for (Station station : entry.getValue()) {
                World w = station.console().getWorld();
                if (w == null) continue;
                boolean active = isAnyPlayerWithin(zoneId, station.console(), ACTIVITY_RADIUS);
                if (active) {
                    w.spawnParticle(Particle.END_ROD, station.console().clone().add(0, 0.4, 0), 2, 0.2, 0.15, 0.2, 0.01);
                    if (ambientTicks % 4 == 0) {
                        w.playSound(station.console(), Sound.BLOCK_BEACON_AMBIENT, 1.4f, 1.6f);
                        w.playSound(station.console(), Sound.BLOCK_DISPENSER_DISPENSE, 0.5f, 1.8f);
                    }
                } else if (ambientTicks % 10 == 0) {
                    w.playSound(station.console(), Sound.BLOCK_BEACON_AMBIENT, 0.4f, 0.8f);
                }
            }
        }
    }

    private boolean isAnyPlayerWithin(int zoneId, Location point, double radius) {
        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.getWorld().equals(point.getWorld())) continue;
            if (player.getLocation().distance(point) <= radius) return true;
        }
        return false;
    }

    private ZoneMarker nearestHatch(ZoneManifest manifest, Location clicked) {
        ZoneMarker best = null;
        double bestDist = TRIGGER_RADIUS * TRIGGER_RADIUS;
        for (MarkerType type : new MarkerType[]{MarkerType.EVAC_MAIN, MarkerType.EVAC_PAID}) {
            for (ZoneMarker marker : manifest.markers(type)) {
                double dx = marker.x() - (clicked.getBlockX() + 0.5);
                double dy = marker.y() - (clicked.getBlockY() + 0.5);
                double dz = marker.z() - (clicked.getBlockZ() + 0.5);
                double dist = dx * dx + dy * dy + dz * dz;
                if (dist <= bestDist) {
                    bestDist = dist;
                    best = marker;
                }
            }
        }
        return best;
    }

    private void signalZone(ZoneManifest manifest, int zoneId, Location hatchLoc, Player opener) {
        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.sendActionBar("§e§lСигнал эвакуации! §7Кто-то открывает люк где-то на карте.");
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1.0f, 0.7f);
        }
        opener.getWorld().spawnParticle(Particle.END_ROD, hatchLoc.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.05);
        opener.getWorld().playSound(hatchLoc, Sound.BLOCK_BEACON_ACTIVATE, 2.0f, 0.6f);
    }

    private void tick() {
        if (channels.isEmpty()) return;
        Iterator<Map.Entry<UUID, Channel>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Channel channel = entry.getValue();
            Player player = Bukkit.getPlayer(channel.player());
            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            if (!player.getWorld().equals(channel.hatchLoc().getWorld())
                    || player.getLocation().distance(channel.hatchLoc()) > STAY_RADIUS) {
                player.sendActionBar("§cВы отошли от люка - эвакуация прервана.");
                it.remove();
                continue;
            }

            Channel advanced = channel.tick();
            if (advanced.elapsedTicks() >= advanced.requiredTicks()) {
                it.remove();
                finishEvac(player, channel.zoneId(), channel.hatchLoc());
            } else {
                entry.setValue(advanced);
                int percent = (int) (100.0 * advanced.elapsedTicks() / advanced.requiredTicks());
                player.sendActionBar("§6Эвакуация: §f" + percent + "%");
            }
        }
    }

    private void finishEvac(Player player, int zoneId, Location hatchLoc) {
        playStationDeparture(hatchLoc);
        Location hub = config.resolveHub(plugin.getLogger());
        if (hub != null) {
            player.teleport(hub);
        }
        raidPlayers.remove(player.getUniqueId());
        anonymity.clearAnonymity(player);
        musicService.stopFor(player);
        player.sendMessage("§a§lЭвакуация успешна! §7Вы выбрались с добычей.");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        launchCelebrationFirework(player);
        Bukkit.broadcastMessage("§7» §f" + player.getName() + " §7выбрался(-ась) из экспедиции живым.");
        Bukkit.getScheduler().runTask(plugin, () -> resultsGui.open(player, true));
    }

    /**
     * "Потом вылетает флаер или маяк появляется, смотря что красивее" -
     * chose the beacon: a rising column of light plus a bright flash and a
     * heavy activation/departure sound right at the station the instant the
     * channel completes, immediately before the player is actually
     * teleported to the hub - reads as "the station just beamed them out".
     */
    private void playStationDeparture(Location hatchLoc) {
        World world = hatchLoc.getWorld();
        if (world == null) return;
        for (int i = 0; i <= 6; i++) {
            world.spawnParticle(Particle.END_ROD, hatchLoc.clone().add(0, 1 + i * 0.6, 0), 6, 0.25, 0.1, 0.25, 0.01);
        }
        world.spawnParticle(Particle.FLASH, hatchLoc.clone().add(0, 1, 0), 1);
        world.playSound(hatchLoc, Sound.BLOCK_BEACON_ACTIVATE, 2.0f, 1.3f);
        world.playSound(hatchLoc, Sound.ITEM_ELYTRA_FLYING, 1.5f, 0.9f);
    }

    private void launchCelebrationFirework(Player player) {
        Firework firework = player.getWorld().spawn(player.getLocation(), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.RED, Color.ORANGE)
                .with(FireworkEffect.Type.BURST)
                .trail(true)
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        if (ambientTask != null) ambientTask.cancel();
        channels.clear();
        stationsByZone.clear();
    }
}
