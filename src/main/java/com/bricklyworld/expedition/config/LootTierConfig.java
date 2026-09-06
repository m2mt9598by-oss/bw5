package com.bricklyworld.expedition.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * "10 команд каждая определяет от хуйни до супер топ лут" - ten loot tiers
 * loaded from config.yml's loot.tiers section. Each tier has how long its
 * cache takes to unlock (higher tier = longer, tenser channel) and a
 * weighted pool of possible item rolls. Deliberately data-driven so the
 * admin can rebalance loot without touching code.
 */
public final class LootTierConfig {

    /**
     * One entry in a tier's pool: a material, a random amount range, a
     * relative weight, and optionally a display name + flavor lore so a
     * reskinned vanilla item reads as a genuine found artifact/treasure
     * ("огромный пак кастомного лута... от хуйни до легендарного") without
     * needing real custom item ids or a resource pack yet.
     */
    public record ItemRoll(Material material, int min, int max, int weight, String displayName, List<String> lore) {
        public ItemStack roll() {
            int amount = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            ItemStack stack = new ItemStack(material, Math.max(1, amount));
            boolean hasName = displayName != null && !displayName.isBlank();
            boolean hasLore = lore != null && !lore.isEmpty();
            if (hasName || hasLore) {
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    if (hasName) meta.setDisplayName(displayName);
                    if (hasLore) meta.setLore(lore);
                    stack.setItemMeta(meta);
                }
            }
            return stack;
        }
    }

    public record Tier(int id, int openSeconds, List<ItemRoll> pool) {
        public List<ItemStack> rollLoot(int minItems, int maxItems) {
            List<ItemStack> result = new ArrayList<>();
            if (pool.isEmpty()) return result;
            int totalWeight = pool.stream().mapToInt(ItemRoll::weight).sum();
            if (totalWeight <= 0) return result;

            int count = minItems == maxItems ? minItems : ThreadLocalRandom.current().nextInt(minItems, maxItems + 1);
            for (int i = 0; i < count; i++) {
                int roll = ThreadLocalRandom.current().nextInt(totalWeight);
                int acc = 0;
                for (ItemRoll item : pool) {
                    acc += item.weight();
                    if (roll < acc) {
                        result.add(item.roll());
                        break;
                    }
                }
            }
            return result;
        }
    }

    private final Map<Integer, Tier> tiers = new HashMap<>();
    private final Logger logger;

    public LootTierConfig(ConfigurationSection lootSection, Logger logger) {
        this.logger = logger;
        if (lootSection == null) {
            logger.warning("Секция loot.tiers отсутствует в config.yml - тайники будут пустыми. Проверьте config.yml.");
            return;
        }
        ConfigurationSection tiersSection = lootSection.getConfigurationSection("tiers");
        if (tiersSection == null) {
            logger.warning("Секция loot.tiers отсутствует в config.yml - тайники будут пустыми.");
            return;
        }
        for (String key : tiersSection.getKeys(false)) {
            int id;
            try {
                id = Integer.parseInt(key);
            } catch (NumberFormatException ex) {
                logger.warning("Пропускаю loot.tiers." + key + " - ключ должен быть числом 1-10.");
                continue;
            }
            ConfigurationSection tierSection = tiersSection.getConfigurationSection(key);
            if (tierSection == null) continue;

            int openSeconds = tierSection.getInt("open-seconds", 6);
            List<ItemRoll> pool = new ArrayList<>();
            List<Map<?, ?>> itemMaps = tierSection.getMapList("items");
            for (Map<?, ?> raw : itemMaps) {
                ItemRoll roll = parseItemRoll(raw, id);
                if (roll != null) pool.add(roll);
            }
            tiers.put(id, new Tier(id, openSeconds, pool));
        }
        logger.info("Загружено уровней лута: " + tiers.size());
    }

    private ItemRoll parseItemRoll(Map<?, ?> raw, int tierId) {
        Object matRaw = raw.get("material");
        if (matRaw == null) {
            logger.warning("Пропускаю предмет без \"material\" в loot.tiers." + tierId);
            return null;
        }
        Material material;
        try {
            material = Material.valueOf(matRaw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("Неизвестный материал \"" + matRaw + "\" в loot.tiers." + tierId + " - пропускаю.");
            return null;
        }
        int min = raw.get("min") instanceof Number n ? n.intValue() : 1;
        int max = raw.get("max") instanceof Number n ? n.intValue() : min;
        int weight = raw.get("weight") instanceof Number n ? n.intValue() : 1;
        String name = raw.get("name") != null ? raw.get("name").toString() : null;
        List<String> lore = parseLore(raw.get("lore"));
        return new ItemRoll(material, Math.min(min, max), Math.max(min, max), Math.max(1, weight), name, lore);
    }

    private List<String> parseLore(Object raw) {
        if (raw == null) return null;
        if (raw instanceof List<?> list) {
            List<String> lines = new ArrayList<>();
            for (Object line : list) {
                lines.add(String.valueOf(line));
            }
            return lines;
        }
        // A single string is fine too - "lore: '§7Одна строка'" instead of a list.
        return List.of(String.valueOf(raw));
    }

    public Tier tier(int id) {
        return tiers.get(id);
    }

    public boolean hasTier(int id) {
        return tiers.containsKey(id);
    }

    public int tierCount() {
        return tiers.size();
    }
}
