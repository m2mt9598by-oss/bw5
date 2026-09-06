package com.bricklyworld.expedition.mob;

import org.bukkit.entity.EntityType;

/**
 * The 8 hostile archetypes from the concept doc (5 skin/tier variants of
 * each is a content/resource-pack job, not something this enum does by
 * itself - see the class comment below for what's real here vs. what's
 * still a placeholder).
 *
 * IMPORTANT HONESTY NOTE: plain Bukkit/Paper has no public API for fully
 * custom entity models or brand-new AI goal trees without either NMS
 * reflection or a dedicated model/AI plugin (e.g. ModelEngine, MythicMobs)
 * - those are real follow-up decisions, not something to fake here. So
 * each archetype below is a vanilla EntityType picked for the closest
 * *behavioural* fit (a creeper-family mob for the wall-breaching
 * detonator, a spider for the climber, ...), reskinned only via custom
 * name + attribute scaling + equipment, tagged so the plugin knows which
 * archetype it is. Swapping in real custom models later (via a resource
 * pack + item-model-on-a-mob-head/armor-stand trick, or a modeling
 * plugin) does not require touching this enum's callers - only baseType
 * and the spawn/equip code in MobSpawnService.
 */
public enum MobArchetype {

    DETONATOR(EntityType.CREEPER, "Детонатор", 24.0, 1.0, 16.0, 6, 4),
    CLIMBER(EntityType.SPIDER, "Скалолаз", 20.0, 1.4, 20.0, 5, 3),
    BRUTE(EntityType.ZOMBIE, "Штурмовик", 40.0, 0.9, 14.0, 8, 6),
    MARKSMAN(EntityType.SKELETON, "Стрелок", 24.0, 1.0, 24.0, 7, 5),
    STALKER(EntityType.ENDERMAN, "Соглядатай", 30.0, 1.0, 18.0, 5, 5),
    SWARM(EntityType.CAVE_SPIDER, "Рой", 8.0, 1.3, 16.0, 4, 1),
    SPOTTER(EntityType.PHANTOM, "Наводчик", 12.0, 1.0, 32.0, 10, 2),
    TRAPPER(EntityType.PILLAGER, "Ловец", 26.0, 0.9, 14.0, 8, 4);

    private final EntityType baseType;
    private final String displayName;
    private final double maxHealth;
    private final double speedMultiplier;
    private final double detectionRadius;
    private final int giveUpAfterSeconds;
    private final int lootTier;

    MobArchetype(EntityType baseType, String displayName, double maxHealth, double speedMultiplier,
                 double detectionRadius, int giveUpAfterSeconds, int lootTier) {
        this.baseType = baseType;
        this.displayName = displayName;
        this.maxHealth = maxHealth;
        this.speedMultiplier = speedMultiplier;
        this.detectionRadius = detectionRadius;
        this.giveUpAfterSeconds = giveUpAfterSeconds;
        this.lootTier = lootTier;
    }

    public EntityType baseType() { return baseType; }
    public String displayName() { return displayName; }
    public double maxHealth() { return maxHealth; }
    public double speedMultiplier() { return speedMultiplier; }
    public double detectionRadius() { return detectionRadius; }
    public int giveUpAfterSeconds() { return giveUpAfterSeconds; }

    /**
     * A regular kill's loot-tier baseline (1-10, see config.yml loot.tiers) -
     * "лут... в зависимости от... ценности [мoба] для режима" - a Рой dying
     * is worth much less than a Штурмовик. A boss version of this archetype
     * scales up from here (see MobDeathListener) rather than using a flat
     * boss tier for every archetype.
     */
    public int lootTier() { return lootTier; }
}
