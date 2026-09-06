package com.bricklyworld.expedition.mob;

import com.bricklyworld.expedition.config.LootTierConfig;
import com.bricklyworld.expedition.loot.CorpseService;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * "Из наших мобов больше частиц и партиклов" / "убийство боссов - визуальное
 * и аудио событие. Партиклы разрыва плоти" - every tagged mob death gets a
 * loot corpse (via CorpseService) plus a small VFX/SFX beat; bosses get the
 * full 5-corpse treatment with richer loot and a much bigger show.
 * Loot tier scales from the archetype's own MobArchetype#lootTier() -
 * a Рой corpse is worth much less than a Штурмовик - and a boss version of
 * an archetype scales further up from there (archetype tier + BOSS_TIER_BOOST,
 * clamped to the top tier), instead of every boss sharing one flat tier.
 */
public final class MobDeathListener implements Listener {

    private static final int BOSS_TIER_BOOST = 4;
    private static final int MAX_TIER = 10;
    private static final int BOSS_CORPSE_COUNT = 5;

    private final ActiveMobRegistry registry;
    private final CorpseService corpseService;
    private final LootTierConfig tierConfig;

    public MobDeathListener(ActiveMobRegistry registry, CorpseService corpseService, LootTierConfig tierConfig) {
        this.registry = registry;
        this.corpseService = corpseService;
        this.tierConfig = tierConfig;
    }

    // Particle.EXPLOSION_HUGE and Particle.DAMAGE_INDICATOR both confirmed
    // compiling fine against Paper 1.20.1 (real GitHub Actions build,
    // 2026-09-06). The one real compile error this project has hit so far
    // was Particle.LARGE_SMOKE (see ApocalypseService/ActivityScriptService).
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!registry.isOurs(entity)) return;

        Integer zoneId = registry.zoneOf(entity);
        if (zoneId == null) return;
        boolean boss = registry.isBoss(entity);
        MobArchetype archetype = registry.archetypeOf(entity);
        String name = archetype != null ? archetype.displayName() : "Существо";
        int baseTier = archetype != null ? archetype.lootTier() : 3;

        // The tagged mob keeps its normal vanilla drops too (arrows, rotten
        // flesh, etc. from the base entity type) - the corpse is on top of
        // that, matching "лут оставляем так-же и артефакты... крутое оружие"
        // as an addition, not a replacement.
        if (boss) {
            entity.getWorld().spawnParticle(Particle.EXPLOSION_HUGE, entity.getLocation(), 4, 0.6, 0.6, 0.6, 0);
            entity.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, entity.getLocation(), 60, 1, 1, 1, 0.2);
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ENDER_DRAGON_DEATH, 1.5f, 0.8f);
            entity.getWorld().playSound(entity.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

            List<List<org.bukkit.inventory.ItemStack>> loot = new ArrayList<>();
            int bossTier = Math.min(MAX_TIER, baseTier + BOSS_TIER_BOOST);
            LootTierConfig.Tier tier = tierConfig.tier(bossTier);
            for (int i = 0; i < BOSS_CORPSE_COUNT; i++) {
                loot.add(tier != null ? tier.rollLoot(2, 4) : new ArrayList<>());
            }
            corpseService.spawnCorpses(entity.getLocation(), BOSS_CORPSE_COUNT, zoneId,
                    "§4[БОСС] §c" + name, loot);
        } else {
            entity.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, entity.getLocation(), 20, 0.4, 0.6, 0.4, 0.15);
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_WITHER_HURT, 0.6f, 1.6f);

            LootTierConfig.Tier tier = tierConfig.tier(baseTier);
            List<org.bukkit.inventory.ItemStack> loot = tier != null ? tier.rollLoot(1, 3) : new ArrayList<>();
            corpseService.spawnCorpse(entity.getLocation(), zoneId, "§c" + name, loot);
        }
    }
}
