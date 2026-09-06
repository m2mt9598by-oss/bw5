package com.bricklyworld.expedition.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A minimal, self-contained currency for BricklyExpedition's own economy
 * (evac keys, post-raid loot sales) so the mode doesn't have to assume any
 * particular economy plugin (Vault, etc.) is installed on BricklyWorld.
 * Persisted to economy.yml as a flat UUID -> balance map. Writes are
 * batched (dirty-flag + a periodic async-ish save every 30s, plus a final
 * flush on plugin disable) instead of hitting disk on every single
 * transaction - "продумай как сделать чтобы меньше нагружало сервер"
 * applies to the economy file just as much as to world restores.
 * If BricklyWorld ever adopts a real economy plugin, every caller goes
 * through this one class, so swapping the backing store later is a
 * one-file change.
 */
public final class EconomyService {

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, Long> balances = new HashMap<>();
    private YamlConfiguration yaml;
    private volatile boolean dirty = false;

    public EconomyService(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "economy.yml");
        load();
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::flushIfDirty, 600L, 600L);
    }

    private void load() {
        if (!file.exists()) {
            yaml = new YamlConfiguration();
            return;
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), yaml.getLong(key));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Пропускаю некорректную запись в economy.yml: " + key);
            }
        }
    }

    public synchronized long balance(UUID player) {
        return balances.getOrDefault(player, 0L);
    }

    public synchronized boolean has(UUID player, long amount) {
        return balance(player) >= amount;
    }

    public synchronized void deposit(UUID player, long amount) {
        if (amount <= 0) return;
        balances.merge(player, amount, Long::sum);
        dirty = true;
    }

    /** Returns false (no change made) if the player doesn't have enough. */
    public synchronized boolean withdraw(UUID player, long amount) {
        if (amount <= 0) return true;
        long current = balance(player);
        if (current < amount) return false;
        balances.put(player, current - amount);
        dirty = true;
        return true;
    }

    public synchronized void setBalance(UUID player, long amount) {
        balances.put(player, Math.max(0, amount));
        dirty = true;
    }

    private synchronized void flushIfDirty() {
        if (!dirty) return;
        for (var entry : balances.entrySet()) {
            yaml.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить economy.yml: " + ex.getMessage());
        }
    }

    /** Called from onDisable so a shutdown never silently drops the last few transactions. */
    public void flush() {
        flushIfDirty();
    }
}
