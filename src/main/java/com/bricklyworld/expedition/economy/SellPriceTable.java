package com.bricklyworld.expedition.economy;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Backs the Stage G "sell what you looted" screen - see config.yml's
 * economy section.
 *
 * CONTENT UPGRADE (2026-09-06, real request - "огромный пак кастомного лута,
 * но не убивать экономику"): a unique named artifact (e.g. a renamed
 * NETHER_STAR flavor) used to always sell for exactly its base material's
 * price, same as any plain copy of that material - fine for most loot, but
 * it means every "legendary" reskin of a shared material was forced to the
 * same price as every other reskin of it. `sell-prices-by-name` lets a
 * specific display name (exact match, including its color codes) get its
 * own bespoke price that overrides the material price for just that named
 * item, so a genuine grail-tier unique can be worth a bit more than a
 * common pull of the same underlying material without inflating the price
 * of that material everywhere else it's used.
 */
public final class SellPriceTable {

    private final int defaultPrice;
    private final Map<Material, Integer> prices = new EnumMap<>(Material.class);
    private final Map<String, Integer> pricesByName = new HashMap<>();

    public SellPriceTable(ConfigurationSection economySection, Logger logger) {
        if (economySection == null) {
            this.defaultPrice = 2;
            return;
        }
        this.defaultPrice = economySection.getInt("default-sell-price", 2);
        ConfigurationSection pricesSection = economySection.getConfigurationSection("sell-prices");
        if (pricesSection != null) {
            for (String key : pricesSection.getKeys(false)) {
                try {
                    prices.put(Material.valueOf(key.toUpperCase(Locale.ROOT)), pricesSection.getInt(key));
                } catch (IllegalArgumentException ex) {
                    logger.warning("Неизвестный материал в economy.sell-prices: " + key);
                }
            }
        }
        ConfigurationSection byNameSection = economySection.getConfigurationSection("sell-prices-by-name");
        if (byNameSection != null) {
            for (String key : byNameSection.getKeys(false)) {
                pricesByName.put(key, byNameSection.getInt(key));
            }
        }
    }

    public int priceOf(Material material) {
        return prices.getOrDefault(material, defaultPrice);
    }

    /** Prefers a name-specific override (an item's exact display name) over its material's price. */
    public int priceOf(ItemStack item) {
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        if (meta != null && meta.hasDisplayName()) {
            Integer named = pricesByName.get(meta.getDisplayName());
            if (named != null) return named;
        }
        return priceOf(item.getType());
    }
}
