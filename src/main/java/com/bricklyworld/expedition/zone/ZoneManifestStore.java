package com.bricklyworld.expedition.zone;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads/saves each zone's manifest as its own YAML file under
 * plugins/BricklyExpedition/zones/zone-<id>.yml. Deliberately manual
 * (get/set of plain maps and primitives) rather than Bukkit's
 * ConfigurationSerializable annotations - fewer surprises, easier to
 * hand-edit a zone file if something ever needs a manual fix.
 */
public final class ZoneManifestStore {

    private final File zonesFolder;
    private final Logger logger;

    public ZoneManifestStore(File dataFolder, Logger logger) {
        this.zonesFolder = new File(dataFolder, "zones");
        this.logger = logger;
        if (!zonesFolder.exists() && !zonesFolder.mkdirs()) {
            logger.warning("Не удалось создать папку zones/ в " + dataFolder);
        }
    }

    private File fileFor(int zoneId) {
        return new File(zonesFolder, "zone-" + zoneId + ".yml");
    }

    public boolean exists(int zoneId) {
        return fileFor(zoneId).isFile();
    }

    public ZoneManifest load(int zoneId) {
        File file = fileFor(zoneId);
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ZoneManifest manifest = new ZoneManifest(zoneId, yaml.getString("displayName", "Zone " + zoneId));

        if (yaml.isConfigurationSection("bounds")) {
            Map<String, Object> boundsMap = new HashMap<>();
            for (String key : yaml.getConfigurationSection("bounds").getKeys(false)) {
                boundsMap.put(key, yaml.get("bounds." + key));
            }
            manifest.setBounds(CuboidRegion.deserialize(boundsMap));
        }

        List<Map<?, ?>> markerList = yaml.getMapList("markers");
        for (Map<?, ?> raw : markerList) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            try {
                manifest.addMarker(ZoneMarker.deserialize(map));
            } catch (Exception e) {
                logger.log(Level.WARNING, "Пропущена битая точка в zone-" + zoneId + ".yml: " + map, e);
            }
        }
        return manifest;
    }

    public void save(ZoneManifest manifest) {
        File file = fileFor(manifest.id());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("displayName", manifest.displayName());
        if (manifest.bounds() != null) {
            for (Map.Entry<String, Object> e : manifest.bounds().serialize().entrySet()) {
                yaml.set("bounds." + e.getKey(), e.getValue());
            }
        }
        List<Map<String, Object>> markerList = new ArrayList<>();
        for (var type : com.bricklyworld.expedition.zone.MarkerType.values()) {
            for (ZoneMarker marker : manifest.markers(type)) {
                markerList.add(marker.serialize());
            }
        }
        yaml.set("markers", markerList);

        try {
            yaml.save(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Не удалось сохранить манифест зоны " + manifest.id(), e);
        }
    }
}
