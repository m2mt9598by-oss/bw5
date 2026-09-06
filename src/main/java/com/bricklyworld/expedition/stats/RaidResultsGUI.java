package com.bricklyworld.expedition.stats;

import com.bricklyworld.expedition.economy.EconomyService;
import com.bricklyworld.expedition.economy.SellPriceTable;
import com.bricklyworld.expedition.economy.VaultEconomyBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Выход из экспедиции открывает не пропускаемое окно статистики матча...
 * Кнопки продать добытое и все такое... чтобы принять нужно нажать ок".
 * True modal windows aren't something plain Bukkit can enforce (a player
 * can always hit Escape) - this approximates it by reopening the screen
 * automatically if it's closed before "Продолжить" is clicked, so walking
 * away from it isn't a way to skip it, only delay it.
 */
public final class RaidResultsGUI implements Listener {

    private static final class ResultsHolder implements InventoryHolder {
        boolean confirmed = false;
        boolean survived;
        long soldValue = 0;
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Plugin plugin;
    private final EconomyService economy;
    private final VaultEconomyBridge vaultEconomy;
    private final SellPriceTable sellPrices;
    private final Set<UUID> suppressReopen = new HashSet<>();

    public RaidResultsGUI(Plugin plugin, EconomyService economy, VaultEconomyBridge vaultEconomy, SellPriceTable sellPrices) {
        this.plugin = plugin;
        this.economy = economy;
        this.vaultEconomy = vaultEconomy;
        this.sellPrices = sellPrices;
    }

    public void open(Player player, boolean survived) {
        ResultsHolder holder = new ResultsHolder();
        holder.survived = survived;
        Inventory inv = Bukkit.createInventory(holder, 27, survived ? "§aЭкспедиция завершена" : "§4Вы погибли в экспедиции");
        holder.inventory = inv;

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler());
        }
        inv.setItem(11, infoItem(player, holder));
        inv.setItem(13, sellButton());
        inv.setItem(15, continueButton());

        player.openInventory(inv);
    }

    private ItemStack filler() {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.setDisplayName(" ");
        glass.setItemMeta(meta);
        return glass;
    }

    private ItemStack infoItem(Player player, ResultsHolder holder) {
        ItemStack item = new ItemStack(holder.survived ? Material.NETHER_STAR : Material.SKELETON_SKULL);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(holder.survived ? "§a§lЭвакуация успешна" : "§4§lВы не выбрались");
        List<String> lore = new ArrayList<>();
        double shownBalance = vaultEconomy.isAvailable() ? vaultEconomy.balance(player) : economy.balance(player.getUniqueId());
        lore.add("§7Баланс: §f" + shownBalance);
        if (holder.soldValue > 0) {
            lore.add("§7Продано за это окно: §a+" + holder.soldValue);
        }
        lore.add("");
        lore.add("§7Нажмите §f«Продать всё» §7или сразу §f«Продолжить»§7.");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack sellButton() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6§lПродать всё добытое");
        meta.setLore(List.of("§7Обменять содержимое инвентаря", "§7на баланс экспедиции."));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack continueButton() {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§a§lПродолжить");
        meta.setLore(List.of("§7Закрыть окно и вернуться", "§7на спавн."));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ResultsHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        if (slot == 13) {
            long value = sellInventory(player);
            holder.soldValue += value;
            event.getInventory().setItem(11, infoItem(player, holder));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.2f);
        } else if (slot == 15) {
            holder.confirmed = true;
            suppressReopen.add(player.getUniqueId());
            player.closeInventory();
        }
    }

    /**
     * BUG FIX (2026-09-06, same playtest report as EntryQueueService#buyEvacKey):
     * this used to deposit sale proceeds into the internal fake EconomyService
     * only, so "sold" loot never actually reached the player's real Vault/
     * Essentials balance. Now it deposits into the real Vault economy whenever
     * one is registered, falling back to the internal EconomyService only when
     * Vault genuinely isn't installed.
     */
    private long sellInventory(Player player) {
        long total = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            total += (long) sellPrices.priceOf(item) * item.getAmount();
            player.getInventory().setItem(i, null);
        }
        if (total > 0) {
            if (vaultEconomy.isAvailable()) {
                vaultEconomy.deposit(player, total);
            } else {
                economy.deposit(player.getUniqueId(), total);
            }
        }
        return total;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ResultsHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID id = player.getUniqueId();

        if (suppressReopen.remove(id)) {
            return; // closed via the "Продолжить" button - let it close for real
        }
        if (holder.confirmed || !player.isOnline()) {
            return;
        }
        // Closed early (Escape, inventory swap, ...) - reopen next tick so it
        // isn't actually skippable, just delayable.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                open(player, holder.survived);
            }
        });
    }
}
