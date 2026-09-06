package com.bricklyworld.expedition.editor;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Marks an open Inventory as one zone's editor submenu, and remembers which zone. */
public final class ZoneMenuHolder implements InventoryHolder {
    private Inventory inventory;
    private final int zoneId;

    public ZoneMenuHolder(int zoneId) {
        this.zoneId = zoneId;
    }

    public int zoneId() {
        return zoneId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
