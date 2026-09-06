package com.bricklyworld.expedition.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Reflection-only bridge to Vault's economy API (net.milkbowl.vault.economy.Economy),
 * mirroring {@link com.bricklyworld.expedition.world.MultiverseBridge}'s pattern so a
 * missing/absent Vault install can never NoClassDefFoundError this plugin - see that
 * class's doc for why a direct compile-time reference to a soft dependency's type is
 * dangerous even behind a null-check. RegisteredServiceProvider is safe to reference
 * directly here because it's core Bukkit API (org.bukkit.plugin), not Vault's own -
 * only net.milkbowl.vault.* types are kept out of this file's bytecode.
 *
 * BUG FIX (2026-09-06, real playtest report - "/bwexpedition buykey Не работает,
 * хотя баланс есть, система Vault + Essentials"): every real-money operation in this
 * plugin (EntryQueueService#buyEvacKey, RaidResultsGUI's sell button) used to go
 * through the plugin's own internal EconomyService - a completely separate,
 * disconnected fake currency that starts every player at 0 and has nothing to do
 * with their real Vault/Essentials balance. That's why "Недостаточно средств" fired
 * even though the player's actual /balance was fine - it was checking the wrong
 * ledger entirely. This bridge talks to the real Vault-registered economy plugin
 * (Essentials, on Egor's server) when one is present; callers fall back to the
 * internal EconomyService only when Vault genuinely isn't installed.
 */
public final class VaultEconomyBridge {

    private final Logger logger;
    private Object economy; // net.milkbowl.vault.economy.Economy instance, held only as Object
    private Method hasMethod;                 // has(OfflinePlayer, double): boolean
    private Method withdrawMethod;            // withdrawPlayer(OfflinePlayer, double): EconomyResponse
    private Method depositMethod;             // depositPlayer(OfflinePlayer, double): EconomyResponse
    private Method getBalanceMethod;          // getBalance(OfflinePlayer): double
    private Method transactionSuccessMethod;  // EconomyResponse#transactionSuccess(): boolean

    public VaultEconomyBridge(Plugin plugin, Logger logger) {
        this.logger = logger;
        try {
            Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
            if (vault == null || !vault.isEnabled()) {
                logger.info("Vault не найден - покупка ключа эвакуации и продажа добычи "
                        + "будут использовать только внутренний баланс экспедиции.");
                return;
            }
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economyClass);
            if (registration == null) {
                logger.info("Vault обнаружен, но ни один плагин экономики (Essentials и т.п.) не "
                        + "зарегистрировал провайдер - используем только внутренний баланс экспедиции.");
                return;
            }
            Object provider = registration.getProvider();
            this.hasMethod = economyClass.getMethod("has", OfflinePlayer.class, double.class);
            this.withdrawMethod = economyClass.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            this.depositMethod = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            this.getBalanceMethod = economyClass.getMethod("getBalance", OfflinePlayer.class);
            Class<?> responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            this.transactionSuccessMethod = responseClass.getMethod("transactionSuccess");
            this.economy = provider;
            logger.info("Vault (economy provider: " + vault.getDescription().getVersion()
                    + ") подключен - реальные деньги игроков (Essentials и т.п.) используются "
                    + "для ключей эвакуации и продажи добычи.");
        } catch (Throwable t) {
            logger.warning("Не удалось подключиться к Vault economy API (" + t.getClass().getSimpleName()
                    + ") - используем только внутренний баланс экспедиции.");
            this.economy = null;
        }
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        try {
            return (boolean) hasMethod.invoke(economy, player, amount);
        } catch (Throwable t) {
            logger.warning("Ошибка Vault#has: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Returns true only if the withdrawal actually succeeded (real Vault transaction). */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        try {
            Object response = withdrawMethod.invoke(economy, player, amount);
            return (boolean) transactionSuccessMethod.invoke(response);
        } catch (Throwable t) {
            logger.warning("Ошибка Vault#withdrawPlayer: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Returns true only if the deposit actually succeeded (real Vault transaction). */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        try {
            Object response = depositMethod.invoke(economy, player, amount);
            return (boolean) transactionSuccessMethod.invoke(response);
        } catch (Throwable t) {
            logger.warning("Ошибка Vault#depositPlayer: " + t.getClass().getSimpleName());
            return false;
        }
    }

    public double balance(OfflinePlayer player) {
        if (economy == null) return 0;
        try {
            return (double) getBalanceMethod.invoke(economy, player);
        } catch (Throwable t) {
            logger.warning("Ошибка Vault#getBalance: " + t.getClass().getSimpleName());
            return 0;
        }
    }
}
