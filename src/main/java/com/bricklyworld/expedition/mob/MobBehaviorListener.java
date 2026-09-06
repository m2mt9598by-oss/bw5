package com.bricklyworld.expedition.mob;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

/**
 * Half of the concept doc's "mobs react to sound/action, not infinite
 * chase" behaviour (the other half - giving up an existing chase - is
 * MobSpawnService's leash task): refuses a brand-new target outside the
 * archetype's own detection radius in the first place, so a tagged mob
 * never engages a player it couldn't plausibly have heard or seen yet.
 */
public final class MobBehaviorListener implements Listener {

    private final ActiveMobRegistry registry;

    public MobBehaviorListener(ActiveMobRegistry registry) {
        this.registry = registry;
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity self) || !registry.isOurs(self)) return;
        if (event.getTarget() == null) return;

        MobArchetype archetype = registry.archetypeOf(self);
        if (archetype == null) return;

        if (!self.getWorld().equals(event.getTarget().getWorld())
                || self.getLocation().distance(event.getTarget().getLocation()) > archetype.detectionRadius()) {
            event.setCancelled(true);
        }
    }
}
