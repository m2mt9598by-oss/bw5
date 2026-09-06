package com.bricklyworld.expedition.pvp;

import com.bricklyworld.expedition.audio.MusicService;
import com.bricklyworld.expedition.config.ExpeditionConfig;
import com.bricklyworld.expedition.identity.AnonymityService;
import com.bricklyworld.expedition.loot.CorpseService;
import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.stats.RaidResultsGUI;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Игрок получая накаут издает высокий красный феерверк и на протяжении
 * минуты может быть поднят другим игроком либо добит" - a lethal hit on a
 * raid player doesn't kill them, it downs them: health pinned to 1, a tall
 * red firework signal, a 60s window where ANY other player's right-click
 * revives them. While downed, "мобы не могут его добить, только если это не
 * взрыв или что-то массовое" - a regular mob's attack does nothing to a
 * downed player; only another player's hit, an explosion, or a big area
 * hazard (fall, fire, lava, drowning, freezing, void) actually finishes
 * them early. A downed player can also skip the wait entirely and give up
 * via /bwexpedition surrender ("либо сразу сдаться"). A finish (or the 60s
 * timeout) triggers the real death: inventory moves into a lootable corpse
 * (CorpseService) instead of scattering on the ground, and the vanilla
 * respawn is redirected to the hub.
 */
public final class KnockoutService implements Listener {

    private static final long DOWNED_TICKS = 60L * 20L;
    private static final double REVIVE_HEALTH_FRACTION = 0.5;

    private final Plugin plugin;
    private final RaidPlayerRegistry raidPlayers;
    private final CorpseService corpseService;
    private final ExpeditionConfig config;
    private final AnonymityService anonymity;
    private final RaidResultsGUI resultsGui;
    private final MusicService musicService;

    private final Set<UUID> downed = new HashSet<>();
    private final Map<UUID, BukkitTask> reviveTimers = new HashMap<>();
    private final Set<UUID> awaitingHubRespawn = new HashSet<>();

    public KnockoutService(Plugin plugin, RaidPlayerRegistry raidPlayers, CorpseService corpseService,
                            ExpeditionConfig config, AnonymityService anonymity, RaidResultsGUI resultsGui,
                            MusicService musicService) {
        this.plugin = plugin;
        this.raidPlayers = raidPlayers;
        this.corpseService = corpseService;
        this.config = config;
        this.anonymity = anonymity;
        this.resultsGui = resultsGui;
        this.musicService = musicService;
    }

    // HIGHEST (not just HIGH) so this always gets the final say on whether a
    // hit is lethal, running after every other plugin's damage adjustments -
    // extra insurance against anything upstream still landing a real kill.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID id = player.getUniqueId();
        if (!raidPlayers.inRaid(id)) return;

        if (downed.contains(id)) {
            // Already down - vanilla damage never applies while downed either
            // way, but whether it actually FINISHES them depends on the
            // source: "мобы не могут его добить, только если это не взрыв
            // или что-то массовое" - a regular mob's bite/arrow just does
            // nothing to a downed player (harmless), only another player's
            // hit or a real area hazard (explosion, fall, fire, lava, void,
            // drowning, freezing) actually ends the revive window early.
            event.setCancelled(true);
            if (canFinishDowned(event)) {
                finalizeDeath(player);
            }
            return;
        }

        double resultingHealth = player.getHealth() - event.getFinalDamage();
        if (resultingHealth > 0) return;

        event.setCancelled(true);
        goDown(player);
    }

    private boolean canFinishDowned(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (resolveDamager(byEntity.getDamager()) instanceof Player) return true;
        }
        return switch (event.getCause()) {
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION, FALL, FIRE, FIRE_TICK, LAVA, DROWNING, FREEZE, VOID -> true;
            default -> false; // regular mob melee/ranged attacks - harmless while downed
        };
    }

    /** A mob's arrow/trident/etc. reports the shooter, not the projectile, as the real attacker. */
    private Entity resolveDamager(Entity raw) {
        if (raw instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return raw;
    }

    // PotionEffectType.SLOW confirmed compiling fine against Paper 1.20.1
    // (real GitHub Actions build, 2026-09-06).
    private void goDown(Player player) {
        UUID id = player.getUniqueId();
        downed.add(id);
        player.setHealth(1.0);
        // High-amplitude Slowness is the actual immobilization here - a JUMP
        // effect would raise jump height, not remove it, so it's deliberately
        // not used (a true "can't move at all" crawl state needs NMS/goal
        // control this project doesn't have access to - noted limitation).
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, (int) DOWNED_TICKS, 6, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, (int) DOWNED_TICKS, 4, false, false));

        launchSignalFirework(player);
        actionBarZone(player, "§c§l" + anonymity.labelFor(id) + " сбит! §7Поднимите или добейте в течение минуты.");

        BukkitTask timer = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (downed.contains(id)) {
                finalizeDeath(player);
            }
        }, DOWNED_TICKS);
        reviveTimers.put(id, timer);
    }

    @EventHandler
    public void onRevive(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getType() != EntityType.PLAYER) return;
        Player target = (Player) event.getRightClicked();
        if (!downed.contains(target.getUniqueId())) return;
        if (event.getPlayer().getUniqueId().equals(target.getUniqueId())) return;

        event.setCancelled(true);
        revive(target, event.getPlayer());
    }

    private void revive(Player target, Player reviver) {
        UUID id = target.getUniqueId();
        if (!downed.remove(id)) return;
        cancelTimer(id);

        target.removePotionEffect(PotionEffectType.SLOW);
        target.removePotionEffect(PotionEffectType.WEAKNESS);
        target.removePotionEffect(PotionEffectType.JUMP);
        var maxHealthAttr = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
        target.setHealth(Math.max(1.0, maxHealth * REVIVE_HEALTH_FRACTION));

        actionBarZone(target, "§a§l" + anonymity.labelFor(id) + " поднят(а) §7(поднял: "
                + anonymity.labelFor(reviver.getUniqueId()) + ").");
        target.getWorld().playSound(target.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
    }

    /**
     * "либо сразу сдаться" - a downed player doesn't have to wait out the
     * full minute hoping for a revive or a finisher; they can give up on the
     * spot via /bwexpedition surrender.
     */
    public boolean surrender(Player player) {
        UUID id = player.getUniqueId();
        if (!downed.contains(id)) return false;
        actionBarZone(player, "§7" + anonymity.labelFor(id) + " сдался(-ась).");
        finalizeDeath(player);
        return true;
    }

    /**
     * Bypasses the down/revive window entirely - "если он рвется дальше [за
     * границу] - убивать его сквозь нок сразу" (the boundary and apocalypse
     * systems call this; a normal combat kill always goes through goDown()
     * first). If the player happens to already be downed, this correctly
     * still just finalizes it rather than downing them twice.
     */
    public void instantKill(Player player) {
        downed.remove(player.getUniqueId());
        finalizeDeath(player);
    }

    private void finalizeDeath(Player player) {
        UUID id = player.getUniqueId();
        downed.remove(id);
        cancelTimer(id);

        Integer zoneId = raidPlayers.zoneOf(id);
        if (zoneId != null) {
            List<ItemStack> items = new ArrayList<>();
            items.addAll(java.util.Arrays.asList(player.getInventory().getContents()));
            items.addAll(java.util.Arrays.asList(player.getInventory().getArmorContents()));
            ItemStack offhand = player.getInventory().getItemInOffHand();
            if (!offhand.getType().isAir()) items.add(offhand);

            corpseService.spawnCorpse(player.getLocation(), zoneId,
                    "§7[Труп] §f" + player.getName(), items);
            player.getInventory().clear();
        }

        awaitingHubRespawn.add(id);
        raidPlayers.remove(id);
        anonymity.clearAnonymity(player);
        musicService.stopFor(player);
        player.setHealth(0.0);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!awaitingHubRespawn.contains(player.getUniqueId())) return;
        event.setDeathMessage(null); // clean notification happens via actionBarZone/finalizeDeath, not vanilla
        event.getDrops().clear(); // already moved into the corpse - don't also scatter on the ground
        event.setKeepInventory(false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!awaitingHubRespawn.remove(id)) return;
        var hub = config.resolveHub(plugin.getLogger());
        if (hub != null) {
            event.setRespawnLocation(hub);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) resultsGui.open(player, false);
        });
    }

    private void cancelTimer(UUID id) {
        BukkitTask task = reviveTimers.remove(id);
        if (task != null) task.cancel();
    }

    private void launchSignalFirework(Player player) {
        Firework firework = player.getWorld().spawn(player.getLocation(), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.RED)
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .build());
        meta.setPower(2); // higher = flies further up before popping - "высокий красный феерверк"
        firework.setFireworkMeta(meta);
        // Firework#detonate() is a Paper API addition (not in plain Spigot) -
        // pops it immediately instead of waiting on real flight physics.
        // Should be fine since this project depends on paper-api, flagging
        // anyway since it's unverified by an actual compiler here.
        firework.detonate();
    }

    /**
     * "основные уведомления выводи в action bar а не засоряй чат" - knockdown/
     * revive/surrender happen often enough in a raid that broadcasting them
     * to the whole zone's chat would spam it; the action bar reads clearly
     * without lingering in scrollback the way a chat line does.
     */
    private void actionBarZone(Player anchor, String message) {
        Integer zoneId = raidPlayers.zoneOf(anchor.getUniqueId());
        if (zoneId == null) {
            anchor.sendActionBar(message);
            return;
        }
        for (UUID id : raidPlayers.playersIn(zoneId)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendActionBar(message);
        }
    }
}
