package com.bricklyworld.expedition;

import com.bricklyworld.expedition.activity.ActivityScriptService;
import com.bricklyworld.expedition.airdrop.AirdropService;
import com.bricklyworld.expedition.apocalypse.ApocalypseService;
import com.bricklyworld.expedition.audio.MusicService;
import com.bricklyworld.expedition.command.BWExpeditionCommand;
import com.bricklyworld.expedition.config.ExpeditionConfig;
import com.bricklyworld.expedition.economy.EconomyService;
import com.bricklyworld.expedition.economy.SellPriceTable;
import com.bricklyworld.expedition.economy.VaultEconomyBridge;
import com.bricklyworld.expedition.editor.MarkerPreviewService;
import com.bricklyworld.expedition.editor.ZoneEditorController;
import com.bricklyworld.expedition.editor.ZoneEditorGUI;
import com.bricklyworld.expedition.entry.EntryQueueService;
import com.bricklyworld.expedition.evac.EvacService;
import com.bricklyworld.expedition.hazard.DangerZoneService;
import com.bricklyworld.expedition.identity.AnonymityService;
import com.bricklyworld.expedition.listener.BlockChangeListener;
import com.bricklyworld.expedition.loot.CorpseService;
import com.bricklyworld.expedition.loot.LootCacheService;
import com.bricklyworld.expedition.loot.LootLockListener;
import com.bricklyworld.expedition.mob.ActiveMobRegistry;
import com.bricklyworld.expedition.mob.BossAddsListener;
import com.bricklyworld.expedition.mob.MobBehaviorListener;
import com.bricklyworld.expedition.mob.MobDeathListener;
import com.bricklyworld.expedition.mob.MobSpawnService;
import com.bricklyworld.expedition.mob.VanillaMobBanListener;
import com.bricklyworld.expedition.config.LootTierConfig;
import com.bricklyworld.expedition.playermap.MapService;
import com.bricklyworld.expedition.pvp.KnockoutService;
import com.bricklyworld.expedition.raid.RaidPlayerRegistry;
import com.bricklyworld.expedition.raid.RaidStateMachine;
import com.bricklyworld.expedition.stats.RaidResultsGUI;
import com.bricklyworld.expedition.ui.RaidTimerBossBarService;
import com.bricklyworld.expedition.world.BoundaryService;
import com.bricklyworld.expedition.world.DeltaJournalManager;
import com.bricklyworld.expedition.world.MultiverseBridge;
import com.bricklyworld.expedition.zone.ZoneManifestStore;
import com.bricklyworld.expedition.zone.ZoneRegistry;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Stage A (foundation: zone manifests, cuboid bounds, delta-journal world
 * restore, raid state machine, zone pool rotation, anti-extraction rules)
 * + Stage B (the guided in-world zone editor GUI) + a first cut of Stage D
 * (custom mob archetypes spawned from a zone's manifest, vanilla mobs
 * banned from every zone, sound/action-based aggro instead of infinite
 * chase, bosses that summon adds when hit) + a first cut of Stage E (tiered
 * loot caches: admin-placed containers get filled and locked behind a
 * channelled open delay each time a raid goes ACTIVE, wiped on RESTORED) +
 * a first cut of Stage C (real queueing: LOBBY/PREP countdowns, the evac-key
 * purchase, spreading queued players across a zone's spawn markers when it
 * goes ACTIVE) + a first cut of Stage F (evac hatches: channelled paid/free
 * exit, zone-wide signal broadcast when one opens, teleport to hub on
 * success) + downed/knockout + lootable corpses (a lethal hit downs instead
 * of killing, red firework signal, 60s revive-or-finish window, real death
 * moves the inventory into a corpse and redirects respawn to the hub; every
 * tagged mob/boss kill also drops a corpse, bosses drop 5) + anonymity
 * (nametag hidden via a scoreboard team, tab list shows "Рейдер #N" instead
 * of the real name for as long as a player is inside an active raid; every
 * in-raid broadcast this project sends uses that label, not the real name)
 * + the boundary/apocalypse systems (walking past a zone's edge warns then
 * force-kills; the last 2 minutes of a raid escalate into a per-player
 * effects show and force-kill anyone still inside when the zone actually
 * closes) + a first cut of the post-raid results screen (a GUI shown on
 * evac or death with a "sell everything" button against a configurable
 * price table and a "continue" button that reopens the screen if closed
 * early, approximating "non-skippable") + a first cut of activity scripts
 * (ACTIVITY_SCRIPT markers fire one of a small curated template library -
 * ambush/alarm/treasure-rush - when a raid player gets close, on a
 * per-marker cooldown so a point can go off again for later players) + the
 * personal player map (a filled map centered on the zone, vanilla terrain
 * rendering left intact so the player's own position still shows, with a
 * static overlay drawn on top for the zone boundary, danger-zone markers,
 * and evac points - deliberately nothing about mob/boss locations) + the
 * airdrop ("может прилететь когда угодно но не позже половины игровой
 * сессии" - a real FallingBlock chest free-falls onto the zone at a random
 * time in the first half of an ACTIVE session, fills/locks exactly like a
 * manifest loot cache) + per-archetype loot value (each MobArchetype now
 * carries its own lootTier so a Рой corpse is worth much less than a
 * Штурмовик, with boss kills of that archetype scaling further up from
 * there instead of every boss sharing one flat tier) + a first cut of the
 * music system ("убираем ванильную музыку из режима" - vanilla MUSIC/
 * AMBIENT sound categories are actively suppressed for every player for as
 * long as they're inside an active raid, replaced by one safe placeholder
 * loop standing in for BricklyWorld's own future custom soundtrack).
 * Still missing: the full custom sound/UI/resource-pack polish pass from
 * the concept doc - everything above uses vanilla Sound/Particle enum
 * values and vanilla materials as placeholders for BricklyWorld's own
 * house style.
 */
public final class BricklyExpeditionPlugin extends JavaPlugin {

    private ExpeditionConfig config;
    private ZoneRegistry zoneRegistry;
    private ZoneManifestStore zoneManifestStore;
    private MultiverseBridge multiverseBridge;
    private DeltaJournalManager deltaJournalManager;
    private RaidStateMachine raidStateMachine;
    private ZoneEditorController zoneEditorController;
    private MarkerPreviewService markerPreviewService;
    private ZoneEditorGUI zoneEditorGUI;
    private ActiveMobRegistry activeMobRegistry;
    private MobSpawnService mobSpawnService;
    private LootTierConfig lootTierConfig;
    private LootCacheService lootCacheService;
    private LootLockListener lootLockListener;
    private EconomyService economyService;
    private VaultEconomyBridge vaultEconomyBridge;
    private RaidPlayerRegistry raidPlayerRegistry;
    private EntryQueueService entryQueueService;
    private EvacService evacService;
    private CorpseService corpseService;
    private KnockoutService knockoutService;
    private AnonymityService anonymityService;
    private BoundaryService boundaryService;
    private ApocalypseService apocalypseService;
    private SellPriceTable sellPriceTable;
    private RaidResultsGUI raidResultsGui;
    private ActivityScriptService activityScriptService;
    private MapService mapService;
    private AirdropService airdropService;
    private MusicService musicService;
    private DangerZoneService dangerZoneService;
    private RaidTimerBossBarService raidTimerBossBarService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new ExpeditionConfig(getConfig(), getLogger());

        this.zoneManifestStore = new ZoneManifestStore(getDataFolder(), getLogger());
        this.zoneRegistry = new ZoneRegistry(zoneManifestStore, config.zoneCount);
        this.multiverseBridge = new MultiverseBridge(getLogger());
        this.deltaJournalManager = new DeltaJournalManager(this);
        this.raidStateMachine = new RaidStateMachine(this, zoneRegistry, deltaJournalManager,
                config.activeDurationSeconds, config.apocalypseDurationSeconds);

        this.zoneEditorController = new ZoneEditorController(this, zoneRegistry, zoneManifestStore);
        this.markerPreviewService = new MarkerPreviewService(this, zoneRegistry, zoneEditorController);
        this.zoneEditorGUI = new ZoneEditorGUI(zoneRegistry, raidStateMachine, zoneEditorController, markerPreviewService);

        this.activeMobRegistry = new ActiveMobRegistry(this);
        this.mobSpawnService = new MobSpawnService(this, zoneRegistry, activeMobRegistry);

        this.lootTierConfig = new LootTierConfig(getConfig().getConfigurationSection("loot"), getLogger());
        this.lootCacheService = new LootCacheService(this, zoneRegistry, lootTierConfig);
        this.lootLockListener = new LootLockListener(this, lootTierConfig);

        this.economyService = new EconomyService(this);
        this.vaultEconomyBridge = new VaultEconomyBridge(this, getLogger());
        this.sellPriceTable = new SellPriceTable(getConfig().getConfigurationSection("economy"), getLogger());
        this.raidResultsGui = new RaidResultsGUI(this, economyService, vaultEconomyBridge, sellPriceTable);
        this.raidPlayerRegistry = new RaidPlayerRegistry();
        this.anonymityService = new AnonymityService(this);
        this.mapService = new MapService(this);
        this.musicService = new MusicService(this);
        this.entryQueueService = new EntryQueueService(this, zoneRegistry, raidStateMachine,
                raidPlayerRegistry, economyService, vaultEconomyBridge, config, anonymityService, mapService, musicService);
        this.evacService = new EvacService(this, zoneRegistry, raidPlayerRegistry, entryQueueService,
                deltaJournalManager, config, anonymityService, raidResultsGui, musicService);

        this.corpseService = new CorpseService(this);
        this.knockoutService = new KnockoutService(this, raidPlayerRegistry, corpseService, config,
                anonymityService, raidResultsGui, musicService);
        this.boundaryService = new BoundaryService(this, zoneRegistry, raidPlayerRegistry, knockoutService);
        this.apocalypseService = new ApocalypseService(this, raidPlayerRegistry, knockoutService);
        this.activityScriptService = new ActivityScriptService(this, zoneRegistry, raidPlayerRegistry,
                mobSpawnService, corpseService, lootTierConfig);
        this.airdropService = new AirdropService(this, zoneRegistry, raidStateMachine, raidPlayerRegistry,
                deltaJournalManager, lootCacheService, config);
        this.dangerZoneService = new DangerZoneService(this, zoneRegistry, raidPlayerRegistry);
        this.raidTimerBossBarService = new RaidTimerBossBarService(this, raidStateMachine, raidPlayerRegistry);

        getServer().getPluginManager().registerEvents(zoneEditorController, this);
        getServer().getPluginManager().registerEvents(markerPreviewService, this);
        getServer().getPluginManager().registerEvents(zoneEditorGUI, this);
        getServer().getPluginManager().registerEvents(
                new BlockChangeListener(zoneRegistry, raidStateMachine, deltaJournalManager, config), this);
        getServer().getPluginManager().registerEvents(mobSpawnService, this);
        getServer().getPluginManager().registerEvents(new MobBehaviorListener(activeMobRegistry), this);
        getServer().getPluginManager().registerEvents(new BossAddsListener(activeMobRegistry, mobSpawnService), this);
        getServer().getPluginManager().registerEvents(new VanillaMobBanListener(zoneRegistry), this);
        getServer().getPluginManager().registerEvents(lootCacheService, this);
        getServer().getPluginManager().registerEvents(lootLockListener, this);
        getServer().getPluginManager().registerEvents(entryQueueService, this);
        getServer().getPluginManager().registerEvents(evacService, this);
        getServer().getPluginManager().registerEvents(corpseService, this);
        getServer().getPluginManager().registerEvents(new MobDeathListener(activeMobRegistry, corpseService, lootTierConfig), this);
        getServer().getPluginManager().registerEvents(knockoutService, this);
        getServer().getPluginManager().registerEvents(apocalypseService, this);
        getServer().getPluginManager().registerEvents(raidResultsGui, this);
        getServer().getPluginManager().registerEvents(airdropService, this);
        getServer().getPluginManager().registerEvents(dangerZoneService, this);
        getServer().getPluginManager().registerEvents(raidTimerBossBarService, this);

        var commandExecutor = new BWExpeditionCommand(zoneRegistry, zoneManifestStore, raidStateMachine,
                multiverseBridge, zoneEditorController, zoneEditorGUI, entryQueueService, economyService, knockoutService);
        getCommand("bwexpedition").setExecutor(commandExecutor);

        getLogger().info("BricklyExpedition Stage A загружен. Зон: " + config.zoneCount
                + ". Multiverse-Core: " + (multiverseBridge.isAvailable() ? "обнаружен" : "не обнаружен, работаем без него") + ".");
    }

    @Override
    public void onDisable() {
        if (zoneRegistry != null) {
            for (int id : zoneRegistry.all().keySet()) {
                zoneRegistry.save(id);
            }
        }
        if (lootLockListener != null) {
            lootLockListener.shutdown();
        }
        if (economyService != null) {
            economyService.flush();
        }
        if (evacService != null) {
            evacService.shutdown();
        }
        if (boundaryService != null) {
            boundaryService.shutdown();
        }
        if (apocalypseService != null) {
            apocalypseService.shutdown();
        }
        if (activityScriptService != null) {
            activityScriptService.shutdown();
        }
        if (musicService != null) {
            musicService.shutdown();
        }
        if (dangerZoneService != null) {
            dangerZoneService.shutdown();
        }
        if (raidTimerBossBarService != null) {
            raidTimerBossBarService.shutdown();
        }
        if (anonymityService != null) {
            anonymityService.shutdown();
        }
    }

    public ZoneRegistry zoneRegistry() { return zoneRegistry; }
    public RaidStateMachine raidStateMachine() { return raidStateMachine; }
    public ExpeditionConfig expeditionConfig() { return config; }
}
