package com.bricklyworld.expedition.identity;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * "На время матча игроки не видят ников друг друга и в табе тоже, они все
 * друг для друга рейдеры" - while a player is inside an active raid, their
 * nametag is hidden (a scoreboard team with NAME_TAG_VISIBILITY.NEVER) and
 * their tab-list entry is replaced with an anonymous "Рейдер #N" label.
 * Every other system that broadcasts something IN a raid (knockout,
 * evac-in-progress signals, ...) should call labelFor() instead of
 * Player#getName() so the anonymity is actually honored end to end, not
 * just cosmetic on the tab list.
 *
 * Known limitation (disclosed, not silently ignored): this hides the
 * nametag and tab name, but does nothing about a player directly reading
 * another's name from, say, a death/kill message from vanilla systems this
 * project doesn't otherwise touch, or from external means (Discord, voice
 * chat). Full anonymity would also want to suppress vanilla combat/death
 * messages entirely, which this project already does for its own raid
 * deaths (KnockoutService suppresses the vanilla death message).
 *
 * DEFENSIVE FIX (2026-09-06, real playtest report - "Ники игроков видно"):
 * applyAnonymity() used to run only once, at teleport-in time. If any other
 * plugin on the server touches scoreboard teams or tab-list names afterwards
 * (a TAB/nametag plugin re-asserting its own team assignment, for example -
 * suspected but not confirmed on Egor's server) it can silently undo this
 * team membership or player-list name with nothing here to notice or
 * re-apply it. A periodic re-apply task now re-asserts both for every
 * currently-anonymous player every few seconds, so a conflicting plugin gets
 * fought back within a handful of seconds instead of winning silently for
 * the rest of the raid.
 */
public final class AnonymityService {

    private static final String TEAM_NAME = "bwexp_anon";
    private static final long REAPPLY_PERIOD_TICKS = 100L; // 5s

    private final Plugin plugin;
    private final Map<UUID, String> labels = new HashMap<>();
    private final Set<UUID> activeAnonymous = new HashSet<>();
    private final AtomicInteger nextNumber = new AtomicInteger(1);
    private final BukkitTask reapplyTask;

    public AnonymityService(Plugin plugin) {
        this.plugin = plugin;
        this.reapplyTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reapplyAll, REAPPLY_PERIOD_TICKS, REAPPLY_PERIOD_TICKS);
    }

    private void reapplyAll() {
        if (activeAnonymous.isEmpty()) return;
        Team team = team();
        for (UUID id : activeAnonymous) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;
            if (!team.hasEntry(player.getName())) {
                team.addEntry(player.getName());
            }
            String label = labels.get(id);
            if (label == null) continue;
            String expected = "§7" + label;
            if (!expected.equals(player.getPlayerListName())) {
                player.setPlayerListName(expected);
            }
        }
    }

    public void shutdown() {
        reapplyTask.cancel();
        activeAnonymous.clear();
    }

    private Team team() {
        Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(TEAM_NAME);
        if (team == null) {
            team = board.registerNewTeam(TEAM_NAME);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        return team;
    }

    /** Call once when a new raid batch launches so labels start again from #1. */
    public void resetForNewRaid() {
        nextNumber.set(1);
    }

    public void applyAnonymity(Player player) {
        String label = "Рейдер #" + nextNumber.getAndIncrement();
        labels.put(player.getUniqueId(), label);
        activeAnonymous.add(player.getUniqueId());
        team().addEntry(player.getName());
        player.setPlayerListName("§7" + label);
    }

    public void clearAnonymity(Player player) {
        labels.remove(player.getUniqueId());
        activeAnonymous.remove(player.getUniqueId());
        Team team = team();
        if (team.hasEntry(player.getName())) {
            team.removeEntry(player.getName());
        }
        player.setPlayerListName(player.getName());
    }

    /** Anonymous display label for a player currently in a raid, or a generic fallback. */
    public String labelFor(UUID player) {
        return labels.getOrDefault(player, "Рейдер");
    }
}
