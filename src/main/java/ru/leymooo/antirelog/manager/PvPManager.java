package ru.leymooo.antirelog.manager;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.codemc.worldguardwrapper.WorldGuardWrapper;
import org.codemc.worldguardwrapper.region.IWrappedRegion;
import ru.leymooo.antirelog.Antirelog;
import ru.leymooo.antirelog.config.Settings;
import ru.leymooo.antirelog.event.PvpPreStartEvent;
import ru.leymooo.antirelog.event.PvpPreStartEvent.PvPStatus;
import ru.leymooo.antirelog.event.PvpStartedEvent;
import ru.leymooo.antirelog.event.PvpStoppedEvent;
import ru.leymooo.antirelog.event.PvpTimeUpdateEvent;
import ru.leymooo.antirelog.util.*;

import java.util.*;

public class PvPManager {

    private final Settings settings;
    private final Antirelog plugin;
    private final Map<Player, Integer> pvpMap = new HashMap<>();
    private final Map<Player, Integer> silentPvpMap = new HashMap<>();
    private final PowerUpsManager powerUpsManager;
    private final BossbarManager bossbarManager;
    private final Set<String> whiteListedCommands = new HashSet<>();
    private final Map<Player, Set<Player>> hitOpponents = new HashMap<>();

    public PvPManager(Settings settings, Antirelog plugin) {
        this.settings = settings;
        this.plugin = plugin;
        this.powerUpsManager = new PowerUpsManager(settings);
        this.bossbarManager = new BossbarManager(settings);
        onPluginEnable();
    }

    public void onPluginDisable() {
        pvpMap.clear();
        silentPvpMap.clear();
        hitOpponents.clear();
        this.bossbarManager.clearBossbars();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PvPScoreboard.removeScoreboard(player);
        }
    }

    public void onPlayerQuit(Player player) {
        if (isPlayerInPvP(player)) {
            stopPvP(player);
        }
        removePlayerFromPvP(player);
    }

    public void onPlayerDeath(Player player) {
        if (isPlayerInPvP(player)) {
            forceStopPvP(player);
        }
        removePlayerFromPvP(player);

        // Немедленно удаляем PvP скорборд
        PvPScoreboard.forceRemoveScoreboard(player);

        // Устанавливаем флаг, что игрок только что умер
        player.setMetadata("pvp_just_died", new FixedMetadataValue(plugin, true));

        // Запускаем задачу для удаления метаданных через короткое время
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.removeMetadata("pvp_just_died", plugin);
        }, 5L);
    }


    private void forceStopPvP(Player player) {
        pvpMap.remove(player);
        silentPvpMap.remove(player);
        hitOpponents.remove(player);
        for (Set<Player> opponents : hitOpponents.values()) {
            opponents.remove(player);
        }
        bossbarManager.clearBossbar(player);
        Bukkit.getPluginManager().callEvent(new PvpStoppedEvent(player));
    }

    private void removePlayerFromPvP(Player player) {
        hitOpponents.remove(player);
        for (Set<Player> opponents : hitOpponents.values()) {
            opponents.remove(player);
        }
        // Обновляем скорборды для всех оппонентов
        for (Player opponent : getOpponents(player)) {
            updatePlayerScoreboard(opponent);
        }
    }

    public void onPlayerJoin(Player player) {
        if (isPlayerInPvP(player)) {
            updatePlayerScoreboard(player);
        }
    }

    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Player) {
            Player attacker = (Player) event.getDamager();
            Player defender = (Player) event.getEntity();
            playerDamagedByPlayer(attacker, defender);
        } else if (event.getDamager() instanceof Player && !(event.getEntity() instanceof Player)) {
            Player player = (Player) event.getDamager();
            if (isPlayerInPvP(player)) {
                // Не обновляем PvP статус, так как это атака на моба
                return;
            }
        }
    }

    public void onPluginEnable() {
        PvPScoreboard.init(settings);
        whiteListedCommands.clear();
        if (settings.isDisableCommandsInPvp() && !settings.getWhiteListedCommands().isEmpty()) {
            settings.getWhiteListedCommands().forEach(wcommand -> {
                Command command = CommandMapUtils.getCommand(wcommand);
                whiteListedCommands.add(wcommand.toLowerCase());
                if (command != null) {
                    whiteListedCommands.add(command.getName().toLowerCase());
                    command.getAliases().forEach(alias -> whiteListedCommands.add(alias.toLowerCase()));
                }
            });
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (pvpMap.isEmpty() && silentPvpMap.isEmpty()) {
                return;
            }
            iterateMap(pvpMap, false);
            iterateMap(silentPvpMap, true);
        }, 20, 20);
        this.bossbarManager.createBossBars();
    }

    private void iterateMap(Map<Player, Integer> map, boolean bypassed) {
        if (!map.isEmpty()) {
            List<Player> playersInPvp = new ArrayList<>(map.keySet());
            for (Player player : playersInPvp) {
                int currentTime = bypassed ? getTimeRemainingInPvPSilent(player) : getTimeRemainingInPvP(player);
                int timeRemaining = currentTime - 1;

                // Проверяем, находится ли игрок в игнорируемом регионе (в любом мире)
                boolean inIgnoredRegion = settings.isDisablePvpInIgnoredRegion() && isInIgnoredRegion(player);

                if (timeRemaining <= 0 || inIgnoredRegion) {
                    // Если время истекло или игрок в игнорируемом регионе - останавливаем PvP
                    if (bypassed) {
                        stopPvPSilent(player);
                    } else {
                        stopPvP(player);
                    }

                    // Если останавливаем из-за региона, показываем сообщение
                    if (inIgnoredRegion && timeRemaining > 0) {
                        player.sendMessage(Utils.color(settings.getMessages().getPvpStoppedInIgnoredRegion()));
                    }
                } else {
                    updatePvpMode(player, bypassed, timeRemaining);
                    callUpdateEvent(player, currentTime, timeRemaining);
                }
            }
        }
    }

    public boolean isInPvP(Player player) {
        return pvpMap.containsKey(player);
    }

    public boolean isInSilentPvP(Player player) {
        return silentPvpMap.containsKey(player);
    }

    public int getTimeRemainingInPvP(Player player) {
        return pvpMap.getOrDefault(player, 0);
    }

    public int getTimeRemainingInPvPSilent(Player player) {
        return silentPvpMap.getOrDefault(player, 0);
    }

    public void playerDamagedByPlayer(Player attacker, Player defender) {
        if (defender != attacker && attacker != null && defender != null && (attacker.getWorld() == defender.getWorld())) {
            if (defender.getGameMode() == GameMode.CREATIVE) {
                return;
            }

            if (attacker.hasMetadata("NPC") || defender.hasMetadata("NPC")) {
                return;
            }

            if (defender.isDead() || attacker.isDead()) {
                return;
            }

            hitOpponents.computeIfAbsent(attacker, k -> new HashSet<>()).add(defender);
            hitOpponents.computeIfAbsent(defender, k -> new HashSet<>()).add(attacker);

            tryStartPvP(attacker, defender);

            // Обновляем скорборды для обоих игроков
            updatePlayerScoreboard(attacker);
            updatePlayerScoreboard(defender);
        }
    }

    private void updatePlayerScoreboard(Player player) {
        if (isPlayerInPvP(player)) {
            List<Player> opponents = new ArrayList<>(hitOpponents.getOrDefault(player, new HashSet<>()));
            int remainingTime = getTimeRemainingInPvP(player);
            PvPScoreboard.updateScoreboard(player, opponents, remainingTime);
        } else {
            PvPScoreboard.removeScoreboard(player);
        }
    }

    public boolean isPlayerInPvP(Player player) {
        return isInPvP(player) || isInSilentPvP(player);
    }

    private void tryStartPvP(Player attacker, Player defender) {
        // Сначала проверяем миры
        if (isInIgnoredWorld(attacker) || isInIgnoredWorld(defender)) {
            return;
        }

        // Затем проверяем регионы (в любом мире)
        if (isInIgnoredRegion(attacker) || isInIgnoredRegion(defender)) {
            return;
        }

        if (!isPvPModeEnabled()) {
            if (settings.isDisablePowerups()) {
                if (!isHasBypassPermission(attacker)) {
                    powerUpsManager.disablePowerUpsWithRunCommands(attacker);
                }
                if (!isHasBypassPermission(defender)) {
                    powerUpsManager.disablePowerUps(defender);
                }
            }
            return;
        }

        boolean attackerBypassed = isHasBypassPermission(attacker);
        boolean defenderBypassed = isHasBypassPermission(defender);

        if (attackerBypassed && defenderBypassed) {
            return;
        }

        boolean attackerInPvp = isInPvP(attacker) || isInSilentPvP(attacker);
        boolean defenderInPvp = isInPvP(defender) || isInSilentPvP(defender);
        PvPStatus pvpStatus = PvPStatus.ALL_NOT_IN_PVP;
        if (attackerInPvp && defenderInPvp) {
            updateAttackerAndCallEvent(attacker, defender, attackerBypassed);
            updateDefenderAndCallEvent(defender, attacker, defenderBypassed);
            return;
        } else if (attackerInPvp) {
            pvpStatus = PvPStatus.ATTACKER_IN_PVP;
        } else if (defenderInPvp) {
            pvpStatus = PvPStatus.DEFENDER_IN_PVP;
        }
        if (pvpStatus == PvPStatus.ATTACKER_IN_PVP || pvpStatus == PvPStatus.DEFENDER_IN_PVP) {
            if (callPvpPreStartEvent(defender, attacker, pvpStatus)) {
                if (attackerInPvp) {
                    updateAttackerAndCallEvent(attacker, defender, attackerBypassed);
                    startPvp(defender, defenderBypassed, false);
                } else {
                    updateDefenderAndCallEvent(defender, attacker, defenderBypassed);
                    startPvp(attacker, attackerBypassed, true);
                }
                Bukkit.getPluginManager().callEvent(new PvpStartedEvent(defender, attacker, settings.getPvpTime(), pvpStatus));
            }
            return;
        }

        if (callPvpPreStartEvent(defender, attacker, pvpStatus)) {
            startPvp(attacker, attackerBypassed, true);
            startPvp(defender, defenderBypassed, false);
            Bukkit.getPluginManager().callEvent(new PvpStartedEvent(defender, attacker, settings.getPvpTime(), pvpStatus));
        }
    }

    private void startPvp(Player player, boolean bypassed, boolean attacker) {
        if (!bypassed) {
            String message = Utils.color(settings.getMessages().getPvpStarted());
            if (!message.isEmpty()) {
                player.sendMessage(message);
            }
            if (attacker && settings.isDisablePowerups()) {
                powerUpsManager.disablePowerUpsWithRunCommands(player);
            }
            sendTitles(player, true);
        }
        updatePvpMode(player, bypassed, settings.getPvpTime());
        player.setNoDamageTicks(0);
    }

    public void updatePvpMode(Player player, boolean bypassed, int newTime) {
        if (bypassed) {
            silentPvpMap.put(player, newTime);
        } else {
            pvpMap.put(player, newTime);
            bossbarManager.setBossBar(player, newTime);
            String actionBar = settings.getMessages().getInPvpActionbar();
            if (!actionBar.isEmpty()) {
                sendActionBar(player, Utils.color(Utils.replaceTime(actionBar, newTime)));
            }
            if (settings.isDisablePowerups()) {
                powerUpsManager.disablePowerUps(player);
            }
            updatePlayerScoreboard(player);
        }
    }

    public void stopPvP(Player player) {
        List<Player> formerOpponents = new ArrayList<>(hitOpponents.getOrDefault(player, new HashSet<>()));

        stopPvPSilent(player);
        sendTitles(player, false);
        String message = Utils.color(settings.getMessages().getPvpStopped());
        if (!isEmpty(message)) {
            player.sendMessage(message);
        }
        String actionBar = settings.getMessages().getPvpStoppedActionbar();
        if (!isEmpty(actionBar)) {
            sendActionBar(player, Utils.color(actionBar));
        }

        PvPScoreboard.removeScoreboard(player);

        for (Player opponent : formerOpponents) {
            hitOpponents.getOrDefault(opponent, new HashSet<>()).remove(player);
            updatePlayerScoreboard(opponent);
        }

        hitOpponents.remove(player);
    }

    public void stopPvPSilent(Player player) {
        pvpMap.remove(player);
        bossbarManager.clearBossbar(player);
        silentPvpMap.remove(player);

        for (Set<Player> opponents : hitOpponents.values()) {
            opponents.remove(player);
        }
        hitOpponents.remove(player);

        Bukkit.getPluginManager().callEvent(new PvpStoppedEvent(player));
    }

    public List<Player> getOpponents(Player player) {
        return new ArrayList<>(hitOpponents.getOrDefault(player, new HashSet<>()));
    }

    private boolean callPvpPreStartEvent(Player defender, Player attacker, PvPStatus pvpStatus) {
        PvpPreStartEvent pvpPreStartEvent = new PvpPreStartEvent(defender, attacker, settings.getPvpTime(), pvpStatus);
        Bukkit.getPluginManager().callEvent(pvpPreStartEvent);
        return !pvpPreStartEvent.isCancelled();
    }

    private void updateAttackerAndCallEvent(Player attacker, Player defender, boolean bypassed) {
        int oldTime = bypassed ? getTimeRemainingInPvPSilent(attacker) : getTimeRemainingInPvP(attacker);
        updatePvpMode(attacker, bypassed, settings.getPvpTime());
        PvpTimeUpdateEvent pvpTimeUpdateEvent = new PvpTimeUpdateEvent(attacker, oldTime, settings.getPvpTime());
        pvpTimeUpdateEvent.setDamagedPlayer(defender);
        Bukkit.getPluginManager().callEvent(pvpTimeUpdateEvent);
    }

    private void updateDefenderAndCallEvent(Player defender, Player attackedBy, boolean bypassed) {
        int oldTime = bypassed ? getTimeRemainingInPvPSilent(defender) : getTimeRemainingInPvP(defender);
        updatePvpMode(defender, bypassed, settings.getPvpTime());
        PvpTimeUpdateEvent pvpTimeUpdateEvent = new PvpTimeUpdateEvent(defender, oldTime, settings.getPvpTime());
        pvpTimeUpdateEvent.setDamagedBy(attackedBy);
        Bukkit.getPluginManager().callEvent(pvpTimeUpdateEvent);
    }

    private void callUpdateEvent(Player player, int oldTime, int newTime) {
        PvpTimeUpdateEvent pvpTimeUpdateEvent = new PvpTimeUpdateEvent(player, oldTime, newTime);
        Bukkit.getPluginManager().callEvent(pvpTimeUpdateEvent);
    }

    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isPlayerInPvP(player)) {
            ItemStack item = event.getItem();
            if (item != null && item.getType() == Material.FIREWORK_ROCKET) {
                event.setCancelled(true);
                String message = Utils.color(settings.getMessages().getFireworkDisabled());
                if (!message.isEmpty()) {
                    player.sendMessage(message);
                }
            }
        }
    }

    public boolean isCommandWhiteListed(String command) {
        if (whiteListedCommands.isEmpty()) {
            return false;
        }
        return whiteListedCommands.contains(command.toLowerCase());
    }

    public PowerUpsManager getPowerUpsManager() {
        return powerUpsManager;
    }

    public BossbarManager getBossbarManager() {
        return bossbarManager;
    }

    private void sendTitles(Player player, boolean isPvpStarted) {
        String title = isPvpStarted ? settings.getMessages().getPvpStartedTitle() : settings.getMessages().getPvpStoppedTitle();
        String subtitle = isPvpStarted ? settings.getMessages().getPvpStartedSubtitle() : settings.getMessages().getPvpStoppedSubtitle();
        title = title.isEmpty() ? null : Utils.color(title);
        subtitle = subtitle.isEmpty() ? null : Utils.color(subtitle);
        if (title == null && subtitle == null) {
            return;
        }
        if (VersionUtils.isVersion(11)) {
            player.sendTitle(title, subtitle, 10, 30, 10);
        } else {
            player.sendTitle(title, subtitle);
        }
    }

    private void sendActionBar(Player player, String message) {
        ActionBar.sendAction(player, message);
    }

    public boolean isPvPModeEnabled() {
        return settings.getPvpTime() > 0;
    }

    public boolean isBypassed(Player player) {
        return isHasBypassPermission(player) || isInIgnoredWorld(player);
    }

    public boolean isHasBypassPermission(Player player) {
        return player.hasPermission("antirelog.bypass");
    }

    public boolean isInIgnoredWorld(Player player) {
        return settings.getDisabledWorlds().contains(player.getWorld().getName().toLowerCase());
    }

    public boolean isInIgnoredRegion(Player player) {
        if (!plugin.isWorldguardEnabled() || settings.getIgnoredWgRegions().isEmpty()) {
            return false;
        }

        Set<String> ignoredRegions = settings.getIgnoredWgRegions();

        try {
            // Получаем регионы в текущей локации игрока
            Set<IWrappedRegion> wrappedRegions = WorldGuardWrapper.getInstance().getRegions(player.getLocation());

            if (!wrappedRegions.isEmpty()) {
                for (IWrappedRegion region : wrappedRegions) {
                    if (ignoredRegions.contains(region.getId().toLowerCase())) {
                        return true;
                    }
                }
            }

            // Дополнительно проверяем все регионы во всех мирах
            for (String regionId : ignoredRegions) {
                // Проверяем, находится ли игрок в этом регионе, независимо от мира
                if (isPlayerInRegion(player, regionId)) {
                    return true;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при проверке регионов WorldGuard: " + e.getMessage());
        }

        return false;
    }
    /**
     * Проверяет, находится ли игрок в указанном регионе
     * @param player Игрок для проверки
     * @param regionId ID региона
     * @return true, если игрок находится в регионе
     */
    private boolean isPlayerInRegion(Player player, String regionId) {
        try {
            WorldGuardWrapper wrapper = WorldGuardWrapper.getInstance();

            // Получаем регион по ID
            Optional<IWrappedRegion> region = wrapper.getRegion(player.getWorld(), regionId);

            if (region.isPresent()) {
                // Проверяем, содержит ли регион локацию игрока
                return region.get().contains(player.getLocation());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при проверке региона " + regionId + ": " + e.getMessage());
        }

        return false;
    }

    private boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }
}
