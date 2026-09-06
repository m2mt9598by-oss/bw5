package com.bricklyworld.expedition.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Thin optional integration with Multiverse-Core. BricklyExpedition does
 * not create or register worlds itself - Multiverse-Core owns that - this
 * class only resolves the Bukkit World a zone's worldName points at,
 * preferring Multiverse-Core's own world manager when it's present,
 * enabled, and running an API shape this class recognizes, and falling
 * back to a plain Bukkit.getWorld() lookup otherwise.
 *
 * BUG FIX (2026-09-06, found on Egor's real server via a real startup log):
 * this class used to `import com.onarandombox.MultiverseCore.MultiverseCore`
 * and do `found instanceof MultiverseCore mv`. Referencing that type in the
 * bytecode - even inside a null-safe instanceof check - makes the JVM try
 * to resolve/load the class the moment this constructor runs, regardless
 * of whether Multiverse-Core is actually installed. Fixed by talking to
 * Multiverse-Core purely through reflection: no com.onarandombox.* or
 * org.mvplugins.* type appears in this file's compiled bytecode, so the
 * JVM never needs to resolve those classes unless Multiverse-Core is
 * actually present and this code chooses to call into it.
 *
 * KNOWN LIMITATION (2026-09-06, confirmed against Egor's real server -
 * running Multiverse-Core v5.7.3): the reflection below targets the
 * legacy 4.x Multiverse-Core API (getMVWorldManager()/getMVWorld()/
 * getCBWorld()). Multiverse 5.x is a ground-up rewrite under a different
 * package (org.mvplugins.multiverse.core...) with a service-locator-style
 * API (MultiverseCoreApi.get().getWorldManager()...) that returns
 * Optional/custom-Result-wrapped values - genuinely different enough that
 * guessing the exact reflection calls without a compiler to check against
 * risks silently-wrong behaviour, which is worse than just not trying.
 * Deliberately NOT chasing that API here: on 5.x this class will log
 * "Multiverse-Core несовместим" once at startup and always fall back to
 * Bukkit.getWorld(worldName) - which works identically for this plugin's
 * purposes, because a world Multiverse-Core has loaded and registered is
 * still a completely normal Bukkit World once loaded, and every
 * BricklyExpedition zone points at a worldName that's expected to already
 * be loaded (via Multiverse or otherwise) before a raid starts there. The
 * only thing actually lost by not integrating with 5.x's own API is
 * Multiverse-specific metadata (per-world game rules, etc.) this plugin
 * never reads anyway - there is no functional gap for BricklyExpedition.
 */
public final class MultiverseBridge {

    private final Logger logger;
    private Plugin core; // the Multiverse-Core plugin instance itself, held only as a generic Plugin
    private Method getMVWorldManagerMethod;
    private Method getMVWorldMethod;
    private Method getCBWorldMethod;

    public MultiverseBridge(Logger logger) {
        this.logger = logger;
        try {
            Plugin found = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (found != null && found.isEnabled()) {
                // Resolve the two methods we need up front via reflection so
                // resolveWorld() below doesn't have to look them up every call.
                // This only succeeds against the legacy 4.x API shape - see the
                // KNOWN LIMITATION note above for why a 5.x server (like
                // Egor's) falls through to the catch block below instead.
                getMVWorldManagerMethod = found.getClass().getMethod("getMVWorldManager");
                Object worldManager = getMVWorldManagerMethod.invoke(found);
                getMVWorldMethod = worldManager.getClass().getMethod("getMVWorld", String.class);
                this.core = found;
                logger.info("Multiverse-Core обнаружен (v" + found.getDescription().getVersion()
                        + ") - используем его API для разрешения миров.");
            }
        } catch (Throwable t) {
            // Multiverse-Core absent, disabled, or (most likely, per the
            // KNOWN LIMITATION note above) running the 5.x API this
            // reflection doesn't target - either way, fall back cleanly
            // rather than taking the whole plugin down with it.
            Plugin found = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            String version = found != null ? found.getDescription().getVersion() : "?";
            logger.info("Multiverse-Core v" + version + " обнаружен, но использует несовместимый с этим "
                    + "мостом API (" + t.getClass().getSimpleName()
                    + ") - миры будут разрешаться через обычный Bukkit.getWorld() (это не влияет на работу режима).");
            this.core = null;
        }
    }

    public boolean isAvailable() {
        return core != null;
    }

    public World resolveWorld(String worldName) {
        if (core != null) {
            try {
                Object worldManager = getMVWorldManagerMethod.invoke(core);
                Object mvWorld = getMVWorldMethod.invoke(worldManager, worldName);
                if (mvWorld != null) {
                    if (getCBWorldMethod == null) {
                        getCBWorldMethod = mvWorld.getClass().getMethod("getCBWorld");
                    }
                    Object cbWorld = getCBWorldMethod.invoke(mvWorld);
                    if (cbWorld instanceof World world) {
                        return world;
                    }
                }
            } catch (Throwable t) {
                logger.warning("Не удалось получить мир \"" + worldName + "\" через Multiverse-Core API ("
                        + t.getClass().getSimpleName() + ") - fallback на Bukkit.getWorld()");
            }
        }
        return Bukkit.getWorld(worldName);
    }
}
