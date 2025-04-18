package ru.leymooo.antirelog.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import ru.leymooo.antirelog.Antirelog;
import ru.leymooo.antirelog.config.Settings;

import java.util.List;

public class PvPScoreboard {

    private static Settings settings;
    private static final int MAX_SCOREBOARD_LINES = 15;

    public static void init(Settings settings) {
        PvPScoreboard.settings = settings;
    }

    public static void updateScoreboard(Player player, List<Player> hitOpponents, int remainingTime) {
        if (player == null || !player.isOnline() || player.isDead() || player.hasPermission("antirelog.bypass") || remainingTime <= 0 || player.hasMetadata("pvp_just_died")) {
            forceRemoveScoreboard(player);
            return;
        }

        Scoreboard board = player.getScoreboard();
        if (board == Bukkit.getScoreboardManager().getMainScoreboard()) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
        }

        Objective objective = board.getObjective("pvp");
        if (objective == null) {
            objective = board.registerNewObjective("pvp", "dummy", colorize(settings.getScoreboardSettings().getTitle()));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        updateScoreboardContent(board, objective, player, hitOpponents, remainingTime);

        player.setScoreboard(board);
    }

    private static void updateScoreboardContent(Scoreboard board, Objective objective, Player player, List<Player> hitOpponents, int remainingTime) {
        List<String> lines = settings.getScoreboardSettings().getLines();
        int availableLines = MAX_SCOREBOARD_LINES - (lines.size() - 1);
        int opponentsToShow = Math.min(hitOpponents.size(), availableLines);

        int score = MAX_SCOREBOARD_LINES;

        for (String line : lines) {
            String formattedLine = formatLine(line, player, hitOpponents, remainingTime);
            if (formattedLine.contains("%opponent_name%")) {
                for (int i = 0; i < opponentsToShow; i++) {
                    Player opponent = hitOpponents.get(i);
                    if (opponent != null && opponent.isOnline() && !opponent.isDead()) {
                        String opponentLine = formattedLine
                                .replace("%opponent_name%", opponent.getName())
                                .replace("%opponent_health%", String.format("%.0f", opponent.getHealth()) + "/" + String.format("%.0f", opponent.getMaxHealth()));
                        setScoreboardLine(board, objective, opponentLine, score);
                        score--;
                    }
                }
            } else {
                setScoreboardLine(board, objective, formattedLine, score);
                score--;
            }
        }

        // Удаляем лишние строки, если они есть
        for (int i = score; i > 0; i--) {
            String entry = getEntry(i);
            board.resetScores(entry);
        }
    }

    private static void setScoreboardLine(Scoreboard board, Objective objective, String line, int score) {
        Team team = board.getTeam("line_" + score);
        if (team == null) {
            team = board.registerNewTeam("line_" + score);
        }
        String entry = getEntry(score);
        team.addEntry(entry);
        team.setPrefix(colorize(line));
        objective.getScore(entry).setScore(score);
    }

    private static String getEntry(int score) {
        return ChatColor.values()[score % ChatColor.values().length] + "" + ChatColor.values()[(score / ChatColor.values().length) % ChatColor.values().length];
    }

    private static String formatLine(String line, Player player, List<Player> opponents, int remainingTime) {
        return line
                .replace("%player_name%", player.getName())
                .replace("%time%", String.valueOf(remainingTime))
                .replace("%player_health%", String.format("%.0f", player.getHealth()));
    }

    public static void removeScoreboard(Player player) {
        if (player != null && player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    public static void forceRemoveScoreboard(Player player) {
        if (player != null && player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        }
    }


    private static String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
