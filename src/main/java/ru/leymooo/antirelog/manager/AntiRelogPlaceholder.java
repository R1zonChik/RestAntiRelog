package ru.leymooo.antirelog.manager;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import ru.leymooo.antirelog.Antirelog;

public class AntiRelogPlaceholder extends PlaceholderExpansion {

    private final Antirelog plugin;
    private final PvPManager pvpManager;

    public AntiRelogPlaceholder(Antirelog plugin) {
        this.plugin = plugin;
        this.pvpManager = plugin.getPvPManager(); // Предполагается, что у Antirelog есть метод getPvPManager()
    }

    @Override
    public String getIdentifier() {
        return "antirelog";
    }

    @Override
    public String getAuthor() {
        return "YourName";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) {
            return "";
        }

        if (identifier.equals("in_pvp")) {
            return String.valueOf(pvpManager.isPlayerInPvP(player));
        }

        return null;
    }
}