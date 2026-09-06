package com.bricklyworld.expedition.activity;

/**
 * "Мы убираем систему эвентов из прошлой системы полностью, заменяем их
 * заготовленными скриптами на карте" - an admin drops an ACTIVITY_SCRIPT
 * marker and picks one of these template names as its meta (same
 * sneak+left-click-in-air cycle the zone editor already uses for mob
 * archetypes / loot tiers). Each template is a small, self-contained
 * "something happens when a player gets close" behaviour - no per-marker
 * scripting language, just a curated library, matching "чтобы я не ебался
 * с сотней команд". More templates are meant to be added here over time.
 */
public enum ActivityScriptType {
    AMBUSH("Засада", "Спавнит группу мобов вокруг точки, когда рейдер подходит близко."),
    ALARM_TRAP("Ловушка-сигнализация", "Громкая сирена на всю зону + мобы поблизости становятся агрессивнее на время."),
    TREASURE_RUSH("Тайник-приманка", "Разово создаёт богатый лутовый труп рядом - привлекает внимание, стоит рискнуть.");

    private final String displayName;
    private final String description;

    ActivityScriptType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
