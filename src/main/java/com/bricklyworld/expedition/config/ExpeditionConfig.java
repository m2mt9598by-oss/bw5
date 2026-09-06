package com.bricklyworld.expedition.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/** Typed view over config.yml - see that file's comments for what each value does. */
public final class ExpeditionConfig {

    public final int zoneCount;
    public final long activeDurationSeconds;
    public final long apocalypseDurationSeconds;
    public final long prepDurationPaidSeconds;
    public final long prepDurationFreeSeconds;
    public final int evacKeyPrice;
    public final int groundFloorYOffset;
    public final long lobbyWaitSeconds;
    public final long prepCountdownSeconds;
    public final String hubWorldName;
    public final double hubX, hubY, hubZ;
    public final float hubYaw, hubPitch;
    public final Map<Material, Material> blockRemap;

    public ExpeditionConfig(FileConfiguration config, Logger logger) {
        this.zoneCount = config.getInt("zones.count", 8);
        this.activeDurationSeconds = config.getLong("raid.active-duration-seconds", 1680);
        this.apocalypseDurationSeconds = config.getLong("raid.apocalypse-duration-seconds", 120);
        this.prepDurationPaidSeconds = config.getLong("raid.prep-duration-paid-seconds", 30);
        this.prepDurationFreeSeconds = config.getLong("raid.prep-duration-free-seconds", 90);
        this.evacKeyPrice = config.getInt("raid.evac-key-price", 1500);
        this.groundFloorYOffset = config.getInt("anti-extraction.ground-floor-y-offset", 0);
        this.lobbyWaitSeconds = config.getLong("raid.lobby-wait-seconds", 45);
        this.prepCountdownSeconds = config.getLong("raid.prep-countdown-seconds", 10);

        this.hubWorldName = config.getString("raid.hub.world", "");
        this.hubX = config.getDouble("raid.hub.x", 0.0);
        this.hubY = config.getDouble("raid.hub.y", 100.0);
        this.hubZ = config.getDouble("raid.hub.z", 0.0);
        this.hubYaw = (float) config.getDouble("raid.hub.yaw", 0.0);
        this.hubPitch = (float) config.getDouble("raid.hub.pitch", 0.0);

        Map<Material, Material> remap = new EnumMap<>(Material.class);
        if (config.isConfigurationSection("anti-extraction.block-remap")) {
            for (String key : config.getConfigurationSection("anti-extraction.block-remap").getKeys(false)) {
                try {
                    Material from = Material.valueOf(key);
                    Material to = Material.valueOf(config.getString("anti-extraction.block-remap." + key));
                    remap.put(from, to);
                } catch (IllegalArgumentException e) {
                    logger.warning("Неизвестный материал в block-remap: " + key);
                }
            }
        }
        this.blockRemap = remap;
    }

    private boolean warnedMissingHub = false;

    /**
     * Where an evacuated (or force-ended) player lands. Resolved lazily
     * rather than cached at load time, since raid.hub.world may name a
     * world that Multiverse hasn't loaded yet when the plugin enables.
     * Falls back to whatever world loaded first and its spawn point,
     * logging the fallback only once so it doesn't spam the console every
     * single evacuation.
     */
    public Location resolveHub(Logger logger) {
        if (!hubWorldName.isBlank()) {
            World world = Bukkit.getWorld(hubWorldName);
            if (world != null) {
                return new Location(world, hubX, hubY, hubZ, hubYaw, hubPitch);
            }
        }
        if (!warnedMissingHub) {
            logger.warning("raid.hub.world не задан или мир не загружен - использую спавн первого загруженного мира. "
                    + "Задайте raid.hub в config.yml, когда определитесь с хабом.");
            warnedMissingHub = true;
        }
        World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        return fallback != null ? fallback.getSpawnLocation() : null;
    }
}
