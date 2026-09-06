package com.bricklyworld.expedition.entry;

import com.bricklyworld.expedition.audio.MusicService;
import com.bricklyworld.expedition.config.ExpeditionConfig;
import com.bricklyworld.expedition.economy.EconomyService;
import com.bricklyworld.expedition.economy.VaultEconomyBridge;
import com.bricklyworld.expedition.identity.AnonymityService;
import com.bricklyworld.expedition.playermap.MapService;
import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.raid.RaidState;
import com.bricklyworld.expedition.raid.RaidStateChangeEvent;
import com.bricklyworld.expedition.raid.RaidStateMachine;
import com.bricklyworld.expedition.zone.MarkerType;
import com.bricklyworld.expedition.zone.ZoneManifest;
import com.bricklyworld.expedition.zone.ZoneMarker;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stage C - "встаешь в очередь, выбираешь снарягу, смотришь докупать ли
 * ключ". The first player to queue reserves a zone (LOBBY) and starts a
 * countdown broadcast to everyone who joins after them; when it hits zero
 * the zone moves to PREP for one more short countdown, then goes ACTIVE and
 * every queued player is spread across that zone's PLAYER_SPAWN markers -
 * "запускает в эти точки также разбрасывая их с дистанцией друг от друга".
 * Loadout selection proper (Stage G) isn't wired yet - this stage covers
 * queueing, the evac-key purchase, and the launch/spawn-spread mechanics.
 */
public final class EntryQueueService implements Listener {

    private final Plugin plugin;
    private final ZoneRegistry zoneRegistry;
    private final RaidStateMachine raidStateMachine;
    private final RaidPlayerRegistry raidPlayers;
    private final EconomyService economy;
    private final VaultEconomyBridge vaultEconomy;
    private final ExpeditionConfig config;
    private final AnonymityService anonymity;
    private final MapService mapService;
    private final MusicService musicService;

    private final LinkedHashSet<UUID> queued = new LinkedHashSet<>();
    private final Set<UUID> boughtEvacKey = new java.util.HashSet<>();
    private Integer currentLobbyZone;
    private BukkitTask countdownTask;

    public EntryQueueService(Plugin plugin, ZoneRegistry zoneRegistry, RaidStateMachine raidStateMachine,
                              RaidPlayerRegistry raidPlayers, EconomyService economy, VaultEconomyBridge vaultEconomy,
                              ExpeditionConfig config, AnonymityService anonymity, MapService mapService,
                              MusicService musicService) {
        this.plugin = plugin;
        this.zoneRegistry = zoneRegistry;
        this.raidStateMachine = raidStateMachine;
        this.raidPlayers = raidPlayers;
        this.economy = economy;
        this.vaultEconomy = vaultEconomy;
        this.config = config;
        this.anonymity = anonymity;
        this.mapService = mapService;
        this.musicService = musicService;
    }

    public void join(Player player) {
        UUID id = player.getUniqueId();
        if (raidPlayers.inRaid(id)) {
            player.sendMessage("§cВы уже в экспедиции.");
            return;
        }
        if (queued.contains(id)) {
            player.sendMessage("§7Вы уже в очереди (" + queued.size() + " игроков ждут старта).");
            return;
        }
        if (currentLobbyZone == null) {
            Integer zoneId = zoneRegistry.nextZone();
            if (zoneId == null) {
                player.sendMessage("§cНет готовой игровой зоны - обратитесь к администратору.");
                return;
            }
            if (!raidStateMachine.openLobby(zoneId)) {
                player.sendMessage("§cНе удалось открыть зону #" + zoneId + " - попробуйте ещё раз.");
                return;
            }
            currentLobbyZone = zoneId;
            anonymity.resetForNewRaid();
            startLobbyCountdown();
        }
        queued.add(id);
        broadcastToQueue("§a" + player.getName() + " присоединился к очереди (" + queued.size() + ").");
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.2f);
    }

    public void leave(Player player) {
        UUID id = player.getUniqueId();
        if (queued.remove(id)) {
            player.sendMessage("§7Вы покинули очередь.");
        }
    }

    /**
     * BUG FIX (2026-09-06, real playtest report - "/bwexpedition buykey Не
     * работает, хотя баланс есть, система Vault + Essentials"): this used to
     * withdraw exclusively from the plugin's own internal EconomyService - a
     * disconnected fake currency starting every player at 0, unrelated to
     * their real Vault/Essentials balance. Now it withdraws from the real
     * Vault economy whenever one is registered (see VaultEconomyBridge),
     * falling back to the internal EconomyService only when Vault genuinely
     * isn't installed on the server.
     */
    public boolean buyEvacKey(Player player) {
        UUID id = player.getUniqueId();
        if (boughtEvacKey.contains(id)) {
            player.sendMessage("§7У вас уже куплен ключ эвакуации на эту вылазку.");
            return true;
        }
        boolean withdrawn = vaultEconomy.isAvailable()
                ? vaultEconomy.withdraw(player, config.evacKeyPrice)
                : economy.withdraw(id, config.evacKeyPrice);
        if (!withdrawn) {
            player.sendMessage("§cНедостаточно средств. Ключ эвакуации стоит " + config.evacKeyPrice + ".");
            return false;
        }
        boughtEvacKey.add(id);
        player.sendMessage("§aКлюч эвакуации куплен! Платный люк откроется быстрее (" + config.prepDurationPaidSeconds + " сек).");
        return true;
    }

    public boolean hasEvacKey(UUID player) {
        return boughtEvacKey.contains(player);
    }

    public int queueSize() {
        return queued.size();
    }

    public Integer currentLobbyZone() {
        return currentLobbyZone;
    }

    private void startLobbyCountdown() {
        final long[] remaining = {config.lobbyWaitSeconds};
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (remaining[0] <= 0) {
                countdownTask.cancel();
                launchPrep();
                return;
            }
            if (remaining[0] <= 10 || remaining[0] % 15 == 0) {
                broadcastToQueue("§6Старт экспедиции через §f" + remaining[0] + " §6сек. (§f" + queued.size() + " §6в очереди)");
            }
            remaining[0]--;
        }, 20L, 20L);
    }

    private void launchPrep() {
        if (currentLobbyZone == null) return;
        if (queued.isEmpty()) {
            // Nobody actually queued (shouldn't normally happen - join() always
            // adds the caller before this runs) - release the zone quietly.
            raidStateMachine.forceEnd(currentLobbyZone);
            currentLobbyZone = null;
            return;
        }
        if (!raidStateMachine.beginPrep(currentLobbyZone, config.prepCountdownSeconds * 20L)) {
            broadcastToQueue("§cНе удалось начать вылазку, попробуйте снова.");
            currentLobbyZone = null;
            return;
        }
        broadcastToQueue("§e§lПриготовьтесь! §fТелепортация через " + config.prepCountdownSeconds + " сек.");
    }

    @EventHandler
    public void onRaidStateChange(RaidStateChangeEvent event) {
        if (currentLobbyZone != null && event.zoneId() == currentLobbyZone && event.to() == RaidState.ACTIVE) {
            teleportBatch(event.zoneId());
        }
        if (event.to() == RaidState.RESTORED) {
            for (UUID id : raidPlayers.playersIn(event.zoneId())) {
                boughtEvacKey.remove(id);
                Player straggler = Bukkit.getPlayer(id);
                if (straggler != null) {
                    anonymity.clearAnonymity(straggler);
                    musicService.stopFor(straggler);
                    var hub = config.resolveHub(plugin.getLogger());
                    if (hub != null) straggler.teleport(hub);
                }
            }
            raidPlayers.clearZone(event.zoneId());
            mapService.clearZoneMap(event.zoneId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        queued.remove(event.getPlayer().getUniqueId());
    }

    private void teleportBatch(int zoneId) {
        ZoneManifest manifest = zoneRegistry.get(zoneId);
        if (manifest == null || manifest.bounds() == null) {
            broadcastToQueue("§cОшибка запуска зоны #" + zoneId + " - обратитесь к администратору.");
            resetLobby();
            return;
        }
        World world = Bukkit.getWorld(manifest.bounds().worldName());
        if (world == null) {
            broadcastToQueue("§cМир зоны #" + zoneId + " не загружен - обратитесь к администратору.");
            resetLobby();
            return;
        }
        List<ZoneMarker> spawns = new ArrayList<>(manifest.markers(MarkerType.PLAYER_SPAWN));
        Collections.shuffle(spawns);
        if (spawns.isEmpty()) {
            broadcastToQueue("§cВ зоне #" + zoneId + " нет точек спавна игроков.");
            resetLobby();
            return;
        }

        mapService.prepareZoneMap(zoneId, manifest);
        int index = 0;
        for (UUID id : queued) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;
            ZoneMarker marker = spawns.get(index % spawns.size());
            index++;
            Location loc = new Location(world, marker.x(), marker.y(), marker.z(), marker.yaw(), marker.pitch());
            player.teleport(loc);
            raidPlayers.assign(id, zoneId);
            anonymity.applyAnonymity(player);
            mapService.giveMap(player, zoneId);
            musicService.startFor(player);
            player.sendTitle("§4БРИКЛИ ЭКСПЕДИЦИЯ", "§7Вы - рейдер. Выживите.", 10, 60, 20);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
        }
        resetLobby();
    }

    private void resetLobby() {
        queued.clear();
        currentLobbyZone = null;
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void broadcastToQueue(String message) {
        for (UUID id : queued) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendMessage(message);
        }
    }
}
