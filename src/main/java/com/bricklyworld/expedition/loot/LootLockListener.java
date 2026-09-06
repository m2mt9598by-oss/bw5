package com.bricklyworld.expedition.loot;

import com.bricklyworld.expedition.config.LootTierConfig;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * "лутаться так-же сначала ждешь 6 секунд. потом открывается инвентарь" -
 * generalised here to the loot cache system: a tagged, locked container
 * can't be opened instantly, it must be channelled for its tier's
 * open-seconds while the player stays near it. Walking away, dying, or
 * logging off resets the progress - nothing is saved half-open.
 */
public final class LootLockListener implements Listener {

    private static final double STAY_RADIUS = 3.0;

    private record Channel(UUID player, Location blockLoc, int requiredTicks, int elapsedTicks) {
        Channel tick() {
            return new Channel(player, blockLoc, requiredTicks, elapsedTicks + 1);
        }
    }

    private final Plugin plugin;
    private final LootTierConfig tierConfig;
    private final Map<UUID, Channel> channels = new HashMap<>();
    private BukkitTask task;

    public LootLockListener(Plugin plugin, LootTierConfig tierConfig) {
        this.plugin = plugin;
        this.tierConfig = tierConfig;
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (!(block.getState() instanceof Container container)) return;
        if (!LootCacheService.isOurCache(container)) return;

        if (!LootCacheService.isLocked(container)) {
            return; // already unlocked - let the vanilla container GUI open normally
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (channels.containsKey(player.getUniqueId())) {
            return; // already channelling this or another cache - the tick loop drives progress
        }

        Integer tierId = LootCacheService.tierOf(container);
        int openSeconds = 6;
        if (tierId != null) {
            LootTierConfig.Tier tier = tierConfig.tier(tierId);
            if (tier != null) openSeconds = tier.openSeconds();
        }

        channels.put(player.getUniqueId(), new Channel(player.getUniqueId(), block.getLocation(), openSeconds * 20, 0));
        player.sendActionBar("§6Вскрываете тайник... §7не отходите.");
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.6f, 1.4f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        channels.remove(event.getPlayer().getUniqueId());
    }

    private void tick() {
        if (channels.isEmpty()) return;
        Iterator<Map.Entry<UUID, Channel>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Channel> entry = it.next();
            Channel channel = entry.getValue();
            Player player = plugin.getServer().getPlayer(channel.player());
            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            Location blockLoc = channel.blockLoc();
            if (blockLoc.getWorld() == null || !player.getWorld().equals(blockLoc.getWorld())
                    || player.getLocation().distance(blockLoc) > STAY_RADIUS) {
                player.sendActionBar("§cВы отошли от тайника - вскрытие прервано.");
                it.remove();
                continue;
            }

            Channel advanced = channel.tick();
            if (advanced.elapsedTicks() >= advanced.requiredTicks()) {
                it.remove();
                finishUnlock(player, blockLoc);
            } else {
                entry.setValue(advanced);
                int percent = (int) (100.0 * advanced.elapsedTicks() / advanced.requiredTicks());
                player.sendActionBar("§6Вскрытие тайника: §f" + percent + "%");
                if (advanced.elapsedTicks() % 10 == 0) {
                    player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.4f, 1.0f + (percent / 100f));
                }
            }
        }
    }

    private void finishUnlock(Player player, Location blockLoc) {
        Block block = blockLoc.getBlock();
        if (!(block.getState() instanceof Container container)) return;
        LootCacheService.unlock(container);
        player.sendActionBar("§a§lТайник вскрыт!");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
        player.spawnParticle(org.bukkit.Particle.TOTEM, blockLoc.clone().add(0.5, 0.7, 0.5), 30, 0.3, 0.3, 0.3, 0.05);
        player.openInventory(container.getInventory());
    }

    public void shutdown() {
        if (task != null) task.cancel();
        channels.clear();
    }
}
