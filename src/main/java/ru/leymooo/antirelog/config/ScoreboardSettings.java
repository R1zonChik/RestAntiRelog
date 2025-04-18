package ru.leymooo.antirelog.config;

import ru.leymooo.annotatedyaml.Annotations.*;
import ru.leymooo.annotatedyaml.ConfigurationSection;

import java.util.Arrays;
import java.util.List;

@Comment("Настройки Scoreboard")
public class ScoreboardSettings implements ConfigurationSection {

    @Comment("Заголовок Scoreboard")
    @Key("title")
    private String title = "§x§F§B§9§8§0§8&lР§x§F§C§A§0§0§7&lе§x§F§C§A§8§0§6&lж§x§F§D§B§0§0§5&lи§x§F§D§B§7§0§3&lм §x§F§E§B§F§0§2&lP§x§F§E§C§7§0§1&lV§x§F§F§C§F§0§0&lP";

    @Comment("Строки Scoreboard")
    @Key("lines")
    private List<String> lines = Arrays.asList(
            "&f",
            "&7⚔ &cНе выходите",
            "&cиз игры &e%time% &cсекунд",
            "&f",
            "&e⚡ Список противников:",
            "&e▸ &f%opponent_name% &c%opponent_health%❤",
            "&f"
    );

    public String getTitle() {
        return title;
    }

    public List<String> getLines() {
        return lines;
    }
}