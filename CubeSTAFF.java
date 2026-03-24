package omr.cubeSTAFF;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.event.node.NodeRemoveEvent;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.milkbowl.vault.economy.Economy;
import okhttp3.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.SkullType;

import java.awt.Color;
import java.io.*;
import java.lang.reflect.Method;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

@SuppressWarnings({"deprecation", "unchecked"})
public class CubeSTAFF extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ════════════════════════════════════════════════════════════════
    //                        FIELDS
    // ════════════════════════════════════════════════════════════════

    private LuckPerms luckPerms;
    private JDA jda;
    private Object discordLinkPlugin;
    private Method isLinkedMethod, getDiscordIdMethod;

    // ─── Data files ───
    private File dataFile, statsFile, warningsFile;
    private FileConfiguration dataCfg, statsCfg, warnCfg;

    // ─── Runtime maps ───
    /** UUID → seconds of ACTIVE (non-AFK) time this week */
    private final Map<UUID, Long> weeklySeconds   = new ConcurrentHashMap<>();
    /** UUID → total seconds ever */
    private final Map<UUID, Long> totalSeconds    = new ConcurrentHashMap<>();
    /** UUID → seconds active today */
    private final Map<UUID, Long> todaySeconds    = new ConcurrentHashMap<>();
    /** UUID → epoch-ms of last movement */
    private final Map<UUID, Long> lastMove        = new ConcurrentHashMap<>();
    /** UUID → epoch-ms of last server join */
    private final Map<UUID, Long> lastSeen        = new ConcurrentHashMap<>();
    /** UUID → session start epoch-ms */
    private final Map<UUID, Long> sessionStart    = new ConcurrentHashMap<>();
    /** UUID → vacation end epoch-ms (0 = not on vacation) */
    private final Map<UUID, Long> vacationEnd     = new ConcurrentHashMap<>();
    /** UUID → who set their vacation */
    private final Map<UUID, String> vacationSetBy = new ConcurrentHashMap<>();
    /** UUIDs that already got the no-admin DM this cooldown window */
    private final Set<UUID> noAdminNotified       = ConcurrentHashMap.newKeySet();

    /** Last time the no-admin alert was sent (epoch-ms) */
    private long lastNoAdminAlert = 0L;
    /** Last time daily-top webhook was sent (epoch day) */
    private long lastDailyTopDay = -1L;
    /** Last week-start epoch-ms (for weekly reset) */
    private long lastWeekReset;

    private final OkHttpClient http = new OkHttpClient();

    // ════════════════════════════════════════════════════════════════
    //                    LIFECYCLE
    // ════════════════════════════════════════════════════════════════

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initFiles();

        if (!setupLuckPerms()) {
            getLogger().severe("LuckPerms غير موجود! تعطيل البلوجن.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        setupDiscordLink();
        initJDA();
        loadAllData();
        registerAll();

        // ─── Periodic tasks ───
        new ActivityTask().runTaskTimer(this, 20L, 20L);          // every second
        new NoAdminTask().runTaskTimer(this, 600L, 600L);         // every 30s
        new DailyTask().runTaskTimer(this, 1200L, 1200L);         // every minute
        new WeeklyTask().runTaskTimer(this, 6000L, 6000L);        // every 5 min
        new WarnExpiryTask().runTaskTimer(this, 72000L, 72000L);  // every hour
        new AbsenceTask().runTaskTimer(this, 72000L, 72000L);     // every hour

        getLogger().info("§d§lCubeSTAFF §av4.0 §7— تم التشغيل بنجاح!");
    }

    @Override
    public void onDisable() {
        saveAllData();
        if (jda != null) jda.shutdown();
        getLogger().info("§d§lCubeSTAFF §7— تم الإيقاف.");
    }

    // ════════════════════════════════════════════════════════════════
    //                    SETUP HELPERS
    // ════════════════════════════════════════════════════════════════

    private boolean setupLuckPerms() {
        RegisteredServiceProvider<LuckPerms> rsp =
            Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (rsp == null) return false;
        luckPerms = rsp.getProvider();
        luckPerms.getEventBus().subscribe(this, NodeAddEvent.class,    this::onNodeAdd);
        luckPerms.getEventBus().subscribe(this, NodeRemoveEvent.class, this::onNodeRemove);
        return true;
    }

    private void setupDiscordLink() {
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager()
                .getPlugin(getConfig().getString("discord.discord-link-plugin", "CubeDiscordLink"));
            if (p == null) return;
            discordLinkPlugin  = p;
            isLinkedMethod     = p.getClass().getMethod("isLinked",    UUID.class);
            getDiscordIdMethod = p.getClass().getMethod("getDiscordId", UUID.class);
            getLogger().info("§a✓ CubeDiscordLink متصل");
        } catch (Exception e) {
            getLogger().warning("§eCubeDiscordLink غير موجود — سيتم استخدام JDA فقط");
        }
    }

    private void initJDA() {
        if (!getConfig().getBoolean("discord.enabled", true)) return;
        String token = getConfig().getString("discord.bot-token", "");
        if (token.isEmpty() || token.equals("YOUR_BOT_TOKEN_HERE")) return;
        try {
            jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .build().awaitReady();
            getLogger().info("§a✓ JDA Bot متصل");
        } catch (Exception e) {
            getLogger().severe("فشل JDA: " + e.getMessage());
        }
    }

    private void registerAll() {
        Bukkit.getPluginManager().registerEvents(this, this);
        String[] cmds = {"staffstats","stafftop","staffgui","staffchat","sc",
                         "staffwarn","staffunwarn","vacation","staffreload",
                         "staffscan","dailytop","testnotif"};
        for (String c : cmds) {
            PluginCommand pc = getCommand(c);
            if (pc != null) { pc.setExecutor(this); pc.setTabCompleter(this); }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //                    DATA FILES
    // ════════════════════════════════════════════════════════════════

    private void initFiles() {
        getDataFolder().mkdirs();
        dataFile     = new File(getDataFolder(), "data.yml");
        statsFile    = new File(getDataFolder(), "stats.yml");
        warningsFile = new File(getDataFolder(), "warnings.yml");
        for (File f : new File[]{dataFile, statsFile, warningsFile})
            if (!f.exists()) try { f.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        dataCfg  = YamlConfiguration.loadConfiguration(dataFile);
        statsCfg = YamlConfiguration.loadConfiguration(statsFile);
        warnCfg  = YamlConfiguration.loadConfiguration(warningsFile);
    }

    private void loadAllData() {
        // stats
        load(statsCfg, "weekly",    weeklySeconds);
        load(statsCfg, "total",     totalSeconds);
        load(statsCfg, "today",     todaySeconds);
        load(statsCfg, "last-seen", lastSeen);

        // vacations
        if (dataCfg.contains("vacations")) {
            for (String k : dataCfg.getConfigurationSection("vacations").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(k);
                    vacationEnd.put(uuid,   dataCfg.getLong("vacations." + k + ".end"));
                    vacationSetBy.put(uuid, dataCfg.getString("vacations." + k + ".by", "?"));
                } catch (Exception ignored) {}
            }
        }
        lastWeekReset = dataCfg.getLong("last-week-reset", System.currentTimeMillis());
        lastDailyTopDay = dataCfg.getLong("last-daily-top-day", -1L);
    }

    private void load(FileConfiguration cfg, String section, Map<UUID, Long> map) {
        if (!cfg.contains(section)) return;
        for (String k : cfg.getConfigurationSection(section).getKeys(false)) {
            try { map.put(UUID.fromString(k), cfg.getLong(section + "." + k)); }
            catch (Exception ignored) {}
        }
    }

    private void saveAllData() {
        save(statsCfg, "weekly",    weeklySeconds);
        save(statsCfg, "total",     totalSeconds);
        save(statsCfg, "today",     todaySeconds);
        save(statsCfg, "last-seen", lastSeen);
        try { statsCfg.save(statsFile); } catch (IOException e) { e.printStackTrace(); }

        // vacations
        dataCfg.set("vacations", null);
        vacationEnd.forEach((uuid, end) -> {
            dataCfg.set("vacations." + uuid + ".end", end);
            dataCfg.set("vacations." + uuid + ".by",  vacationSetBy.getOrDefault(uuid, "?"));
        });
        dataCfg.set("last-week-reset",    lastWeekReset);
        dataCfg.set("last-daily-top-day", lastDailyTopDay);
        try { dataCfg.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private void save(FileConfiguration cfg, String section, Map<UUID, Long> map) {
        cfg.set(section, null);
        map.forEach((k, v) -> cfg.set(section + "." + k, v));
    }

    // ════════════════════════════════════════════════════════════════
    //                    LUCKPERMS EVENTS  (rank notifications)
    // ════════════════════════════════════════════════════════════════

    private void onNodeAdd(NodeAddEvent event) {
        if (!(event.getTarget() instanceof net.luckperms.api.model.user.User)) return;
        net.luckperms.api.model.user.User user = (net.luckperms.api.model.user.User) event.getTarget();
        Node node = event.getNode();
        if (!node.getKey().startsWith("group.")) return;

        UUID uuid      = user.getUniqueId();
        String group   = node.getKey().replace("group.", "");
        boolean isTemp = node.hasExpiry();
        String dur     = isTemp ? node.getExpiry()
            .map(i -> fmtDuration(i.getEpochSecond() - Instant.now().getEpochSecond()))
            .orElse("مؤقت") : "دائم ♾️";

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (!cfg("notifications.rank-granted", true)) return;
            String display = rankDisplay(group);
            String rankColor = rankHex(group);
            sendDM(uuid,
                new EmbedBuilder()
                    .setTitle("🎉 تم منحك رتبة!")
                    .setDescription("**الرتبة:** " + display + "\n**المدة:** " + dur)
                    .setColor(hexColor(rankColor))
                    .setFooter(footer())
                    .setTimestamp(Instant.now())
                    .build()
            );

            // Log to webhook
            webhookEmbed("rank-changes",
                "✅ منح رتبة",
                "**اللاعب:** " + playerName(uuid) + "\n**الرتبة:** " + display + "\n**المدة:** " + dur,
                hexInt(rankColor));
        });
    }

    private void onNodeRemove(NodeRemoveEvent event) {
        if (!(event.getTarget() instanceof net.luckperms.api.model.user.User)) return;
        net.luckperms.api.model.user.User user = (net.luckperms.api.model.user.User) event.getTarget();
        Node node = event.getNode();
        if (!node.getKey().startsWith("group.")) return;

        UUID uuid    = user.getUniqueId();
        String group = node.getKey().replace("group.", "");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (!cfg("notifications.rank-removed", true)) return;
            String display = rankDisplay(group);
            sendDM(uuid,
                new EmbedBuilder()
                    .setTitle("🔴 تم إزالة رتبة")
                    .setDescription("**الرتبة المُزالة:** " + display + "\n*تواصل مع الإدارة إن كان هذا خطأً.*")
                    .setColor(hexColor(cfgStr("embed-design.colors.danger", "E74C3C")))
                    .setFooter(footer())
                    .setTimestamp(Instant.now())
                    .build()
            );
            webhookEmbed("rank-changes",
                "🔴 إزالة رتبة",
                "**اللاعب:** " + playerName(uuid) + "\n**الرتبة المُزالة:** " + display,
                hexInt(cfgStr("embed-design.colors.danger", "E74C3C")));
        });
    }

    // ════════════════════════════════════════════════════════════════
    //                    PLAYER EVENTS
    // ════════════════════════════════════════════════════════════════

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p    = e.getPlayer();
        UUID uuid   = p.getUniqueId();
        long now    = System.currentTimeMillis();

        sessionStart.put(uuid, now);
        lastMove.put(uuid, now);
        lastSeen.put(uuid, now);

        if (!isStaff(uuid)) return;

        // Show stats after 2 minutes
        if (getConfig().getBoolean("login-stats.enabled", true)) {
            int delay = getConfig().getInt("login-stats.delay-seconds", 120) * 20;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) sendStatsMessage(p, uuid);
            }, delay);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        sessionStart.remove(uuid);
        lastMove.remove(uuid);
        lastSeen.put(uuid, System.currentTimeMillis());
        saveAllData();
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        // Only count positional movement (not head rotation)
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
         && e.getFrom().getBlockY() == e.getTo().getBlockY()
         && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        lastMove.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    // ════════════════════════════════════════════════════════════════
    //                    PERIODIC TASKS
    // ════════════════════════════════════════════════════════════════

    /** Runs every second — tracks active (non-AFK) time */
    private class ActivityTask extends BukkitRunnable {
        @Override public void run() {
            long now     = System.currentTimeMillis();
            int  afkMs   = getConfig().getInt("afk.timeout-seconds", 300) * 1000;
            boolean afkEnabled = getConfig().getBoolean("afk.enabled", true);

            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID uuid = p.getUniqueId();
                if (!isStaff(uuid)) continue;
                if (onVacation(uuid)) continue;

                if (afkEnabled) {
                    Long lm = lastMove.get(uuid);
                    if (lm == null || (now - lm) > afkMs) continue; // AFK
                }

                weeklySeconds.merge(uuid, 1L, Long::sum);
                totalSeconds.merge(uuid, 1L, Long::sum);
                todaySeconds.merge(uuid, 1L, Long::sum);
            }
        }
    }

    /** Runs every 30 seconds — no-admin DM alert */
    private class NoAdminTask extends BukkitRunnable {
        @Override public void run() {
            if (!getConfig().getBoolean("no-admin-alert.enabled", true)) return;
            if (!cfg("notifications.no-admin-dm", true)) return;

            int threshold = getConfig().getInt("no-admin-alert.player-threshold", 20);
            int players   = Bukkit.getOnlinePlayers().size();
            if (players < threshold) { noAdminNotified.clear(); return; }

            boolean staffOnline = Bukkit.getOnlinePlayers().stream()
                .anyMatch(p -> isStaff(p.getUniqueId()));
            if (staffOnline) { noAdminNotified.clear(); return; }

            long cooldownMs = getConfig().getLong("no-admin-alert.cooldown-minutes", 30) * 60000L;
            long now = System.currentTimeMillis();
            if (now - lastNoAdminAlert < cooldownMs) return;
            lastNoAdminAlert = now;
            noAdminNotified.clear();

            String msg = c(getConfig().getString("no-admin-alert.message",
                "&c⚠ &fلا يوجد أي إداري متصل حالياً!"));

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (noAdminNotified.contains(p.getUniqueId())) continue;
                p.sendMessage(msg);
                noAdminNotified.add(p.getUniqueId());
            }

            webhookEmbed("general-log",
                "🚨 لا يوجد أدمن!",
                "**عدد اللاعبين:** " + players + "\nلا يوجد أي إداري متصل!",
                hexInt(cfgStr("embed-design.colors.danger", "E74C3C")));
        }
    }

    /** Runs every minute — daily top webhook + today reset at midnight */
    private class DailyTask extends BukkitRunnable {
        @Override public void run() {
            LocalDateTime now = LocalDateTime.now();
            long today = LocalDate.now().toEpochDay();

            // Reset today seconds at midnight
            if (now.getHour() == 0 && now.getMinute() == 0) {
                todaySeconds.clear();
            }

            // Daily top webhook
            if (!getConfig().getBoolean("daily-top.enabled", true)) return;
            int targetHour   = getConfig().getInt("daily-top.hour",   20);
            int targetMinute = getConfig().getInt("daily-top.minute",  0);
            if (now.getHour() == targetHour && now.getMinute() == targetMinute
                && lastDailyTopDay != today) {
                lastDailyTopDay = today;
                sendDailyTopWebhook();
            }
        }
    }

    /** Runs every 5 minutes — weekly reset on Monday 00:00 */
    private class WeeklyTask extends BukkitRunnable {
        @Override public void run() {
            LocalDateTime now = LocalDateTime.now();
            if (now.getDayOfWeek() != DayOfWeek.MONDAY) return;
            if (now.getHour() != 0) return;

            long weekMs = 7L * 24 * 60 * 60 * 1000;
            if (System.currentTimeMillis() - lastWeekReset < weekMs - 300_000) return;

            runWeeklyReset();
        }
    }

    /** Runs every hour — expire old warnings */
    private class WarnExpiryTask extends BukkitRunnable {
        @Override public void run() {
            long now    = System.currentTimeMillis();
            int  days   = getConfig().getInt("warnings.warn-duration-days", 15);
            long expiry = (long) days * 86400_000L;

            if (!warnCfg.contains("warnings")) return;
            for (String key : warnCfg.getConfigurationSection("warnings").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    List<Map<?,?>> list = warnCfg.getMapList("warnings." + key);
                    boolean changed = false;
                    Iterator<Map<?,?>> it = list.iterator();
                    while (it.hasNext()) {
                        Map<?,?> w = it.next();
                        long ts = ((Number) w.get("timestamp")).longValue();
                        if (now - ts > expiry) { it.remove(); changed = true; }
                    }
                    if (changed) {
                        warnCfg.set("warnings." + key, list);
                        try { warnCfg.save(warningsFile); } catch (IOException e) { e.printStackTrace(); }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    /** Runs every hour — 24h absence notification */
    private class AbsenceTask extends BukkitRunnable {
        @Override public void run() {
            if (!cfg("notifications.absence-24h", true)) return;
            long now   = System.currentTimeMillis();
            long day   = 86_400_000L;

            lastSeen.forEach((uuid, seen) -> {
                if (Bukkit.getPlayer(uuid) != null) return; // online
                if (!isStaff(uuid)) return;
                if (onVacation(uuid)) return;
                if (now - seen < day) return;
                if (now - seen > day + 3_600_000L) return; // only notify once (1h window)

                sendDM(uuid, new EmbedBuilder()
                    .setTitle("👋 اشتقنا إليك!")
                    .setDescription(getConfig().getString("absence-alert.message",
                        "لم تتصل بالخادم منذ أكثر من 24 ساعة. حضورك مهم! 💙"))
                    .setColor(hexColor(cfgStr("embed-design.colors.info", "3498DB")))
                    .setFooter(footer())
                    .setTimestamp(Instant.now())
                    .build());
            });
        }
    }

    // ════════════════════════════════════════════════════════════════
    //                    WEEKLY RESET LOGIC
    // ════════════════════════════════════════════════════════════════

    private void runWeeklyReset() {
        lastWeekReset = System.currentTimeMillis();
        List<String> ranks = getConfig().getStringList("staff-ranks");

        for (Map.Entry<UUID, Long> entry : weeklySeconds.entrySet()) {
            UUID uuid   = entry.getKey();
            long secs   = entry.getValue();
            double hrs  = secs / 3600.0;
            String rank = getStaffRank(uuid);
            if (rank == null) continue;
            if (onVacation(uuid)) {
                // On vacation — skip accountability, notify
                if (cfg("notifications.vacation-status", true)) {
                    sendDM(uuid, new EmbedBuilder()
                        .setTitle("🏖️ أنت في إجازة")
                        .setDescription("لن يتم محاسبتك على ساعات هذا الأسبوع بسبب إجازتك.")
                        .setColor(hexColor(cfgStr("embed-design.colors.info", "3498DB")))
                        .setFooter(footer()).setTimestamp(Instant.now()).build());
                }
                continue;
            }

            double required = getConfig().getDouble("required-weekly-hours." + rank, 0);
            boolean passed  = hrs >= required || required == 0;

            if (!cfg("notifications.weekly-summary", true)) { weeklySeconds.put(uuid, 0L); continue; }

            String summary = "**ساعاتك هذا الأسبوع:** `" + String.format("%.1f", hrs) + "ه`\n"
                + "**المطلوب:** `" + (int) required + "ه`\n"
                + "**الحالة:** " + (passed ? "✅ ممتاز! استمر." : "❌ لم تستوفِ الحد المطلوب.");

            if (!passed && required > 0) {
                // Determine consequence
                int rankIndex = ranks.indexOf(rank);
                if (rankIndex == ranks.size() - 1) {
                    // Lowest rank → permanent remove
                    summary += "\n\n🚫 **تم طردك من الإدارة بسبب عدم استيفاء الساعات.**";
                    removeFromStaff(uuid, "عدم استيفاء الساعات الأسبوعية");
                } else {
                    // Demote one rank
                    String newRank = ranks.get(rankIndex + 1);
                    summary += "\n\n⬇️ **تم تنزيل رتبتك إلى " + rankDisplay(newRank) + ".**";
                    demotePlayer(uuid, rank, newRank);
                }
            }

            int color = passed
                ? hexInt(cfgStr("embed-design.colors.success", "2ECC71"))
                : hexInt(cfgStr("embed-design.colors.danger",  "E74C3C"));

            sendDM(uuid, new EmbedBuilder()
                .setTitle("📊 ملخص الأسبوع")
                .setDescription(summary)
                .setColor(color)
                .setFooter(footer())
                .setTimestamp(Instant.now())
                .build());

            weeklySeconds.put(uuid, 0L);
        }
        todaySeconds.replaceAll((k, v) -> 0L);
        saveAllData();
    }

    // ════════════════════════════════════════════════════════════════
    //                    WARNING SYSTEM
    // ════════════════════════════════════════════════════════════════

    private void addWarning(UUID target, String targetName, String reason, String byName) {
        int max = getConfig().getInt("warnings.max-warnings", 4);
        List<Map<?,?>> warns = getWarnings(target);

        Map<String, Object> warn = new LinkedHashMap<>();
        warn.put("reason",    reason);
        warn.put("by",        byName);
        warn.put("timestamp", System.currentTimeMillis());
        warns.add(warn);
        warnCfg.set("warnings." + target, warns);
        try { warnCfg.save(warningsFile); } catch (IOException e) { e.printStackTrace(); }

        int count = warns.size();
        int color = hexInt(cfgStr("embed-design.colors.warning", "E67E22"));

        // ─── DM to warned player ───
        if (cfg("notifications.warning-received", true)) {
            String desc = "**السبب:** " + reason + "\n"
                + "**بواسطة:** " + byName + "\n"
                + "**تحذيراتك:** `" + count + "/" + max + "`\n\n"
                + (count >= max
                    ? "🚫 **وصلت للحد الأقصى! تم إيقافك من الإدارة.**"
                    : "⚠️ التحذيرات تُشطب تلقائياً بعد " + getConfig().getInt("warnings.warn-duration-days", 15) + " يوم.");
            sendDM(target, new EmbedBuilder()
                .setTitle("⚠️ تحذير #" + count)
                .setDescription(desc)
                .setColor(count >= max
                    ? hexInt(cfgStr("embed-design.colors.danger", "E74C3C"))
                    : color)
                .setFooter(footer()).setTimestamp(Instant.now()).build());
        }

        // ─── Webhook ───
        webhookEmbed("warnings",
            "⚠️ تحذير جديد",
            "**الإداري:** " + targetName
            + "\n**السبب:** " + reason
            + "\n**بواسطة:** " + byName
            + "\n**التحذيرات:** `" + count + "/" + max + "`",
            color);

        // ─── Broadcast to online admins ───
        broadcastAdmins(c("&8[&c⚠ &eتحذير&8] &b" + targetName + " &7(" + count + "/" + max + ") &8| &f" + reason));

        // ─── Max warnings → remove from staff ───
        if (count >= max) {
            removeFromStaff(target, "الوصول للحد الأقصى من التحذيرات");
        }
    }

    private void removeWarning(UUID target, String targetName, int index, String byName) {
        List<Map<?,?>> warns = getWarnings(target);
        if (index < 1 || index > warns.size()) return;
        warns.remove(index - 1);

        List<Map<String, Object>> writable = warns.stream()
            .map(m -> new LinkedHashMap<String, Object>(m)).collect(Collectors.toList());
        warnCfg.set("warnings." + target, writable);
        try { warnCfg.save(warningsFile); } catch (IOException e) { e.printStackTrace(); }

        webhookEmbed("warnings",
            "🗑️ تم حذف تحذير",
            "**الإداري:** " + targetName + "\n**بواسطة:** " + byName + "\n**رقم التحذير:** #" + index,
            hexInt(cfgStr("embed-design.colors.success", "2ECC71")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<?,?>> getWarnings(UUID uuid) {
        return new ArrayList<>(warnCfg.getMapList("warnings." + uuid));
    }

    // ════════════════════════════════════════════════════════════════
    //                    STAFF MANAGEMENT
    // ════════════════════════════════════════════════════════════════

    private void removeFromStaff(UUID uuid, String reason) {
        String rank = getStaffRank(uuid);
        Bukkit.getScheduler().runTask(this, () -> {
            luckPerms.getUserManager().modifyUser(uuid, user -> {
                user.data().remove(Node.builder("group." + (rank != null ? rank : "")).build());
            });
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.kickPlayer(c(getConfig().getString("messages.demote-kick-message",
                    "&c&lتم إيقافك من الإدارة.\n&7السبب: &f") + reason));
            }
        });

        sendDM(uuid, new EmbedBuilder()
            .setTitle("🚫 تم إيقافك من الإدارة")
            .setDescription("**السبب:** " + reason + "\n\n*تواصل مع الإدارة العليا للاستفسار.*")
            .setColor(hexInt(cfgStr("embed-design.colors.danger", "E74C3C")))
            .setFooter(footer()).setTimestamp(Instant.now()).build());

        webhookEmbed("demotions",
            "🚫 طرد من الإدارة",
            "**الإداري:** " + playerName(uuid)
            + "\n**الرتبة السابقة:** " + (rank != null ? rankDisplay(rank) : "غير معروف")
            + "\n**السبب:** " + reason,
            hexInt(cfgStr("embed-design.colors.danger", "E74C3C")));
    }

    private void demotePlayer(UUID uuid, String oldRank, String newRank) {
        Bukkit.getScheduler().runTask(this, () ->
            luckPerms.getUserManager().modifyUser(uuid, user -> {
                user.data().remove(Node.builder("group." + oldRank).build());
                user.data().add(Node.builder("group." + newRank).build());
            })
        );
        webhookEmbed("demotions",
            "⬇️ تنزيل رتبة",
            "**الإداري:** " + playerName(uuid)
            + "\n**من:** " + rankDisplay(oldRank)
            + "\n**إلى:** " + rankDisplay(newRank)
            + "\n**السبب:** عدم استيفاء الساعات",
            hexInt(cfgStr("embed-design.colors.warning", "E67E22")));
    }

    // ════════════════════════════════════════════════════════════════
    //                    COMMANDS
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();

        switch (name) {

            // ─── /staffstats [player] ───
            case "staffstats": {
                if (!(sender instanceof Player) && args.length == 0) {
                    sender.sendMessage(c("&c✗ يجب تحديد اسم اللاعب")); return true;
                }
                UUID target;
                String targetName;
                if (args.length == 0) {
                    target = ((Player) sender).getUniqueId();
                    targetName = sender.getName();
                } else {
                    if (!sender.hasPermission("cubestaff.admin")) {
                        sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                    }
                    OfflinePlayer op = Bukkit.getOfflinePlayer(args[0]);
                    target = op.getUniqueId();
                    targetName = op.getName() != null ? op.getName() : args[0];
                }
                showStats(sender, target, targetName);
                return true;
            }

            // ─── /stafftop ───
            case "stafftop": {
                if (!sender.hasPermission("cubestaff.use")) {
                    sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                }
                showTop(sender);
                return true;
            }

            // ─── /staffgui ───
            case "staffgui": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(c(cfgStr("messages.player-only", "&c✗ هذا الأمر للاعبين فقط!"))); return true;
                }
                if (!sender.hasPermission("cubestaff.use")) {
                    sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                }
                openStaffGUI((Player) sender);
                return true;
            }

            // ─── /staffchat | /sc ───
            case "staffchat":
            case "sc": {
                if (!(sender instanceof Player)) return true;
                Player p = (Player) sender;
                if (!isStaff(p.getUniqueId())) {
                    p.sendMessage(c(cfgStr("messages.not-staff", "&c✗ هذا الأمر للإدارة فقط!"))); return true;
                }
                if (args.length == 0) {
                    p.sendMessage(c("&e⚠ الاستخدام: &6/" + label + " <الرسالة>")); return true;
                }
                String message = String.join(" ", args);
                String rank    = getStaffRank(p.getUniqueId());
                String format  = getConfig().getString("staff-chat.format",
                    "&8[&5⚡&8] &d{rank} &b{player} &8➜ &f{message}")
                    .replace("{rank}",    rank != null ? rankDisplay(rank) : "إداري")
                    .replace("{player}",  p.getName())
                    .replace("{message}", message);

                Bukkit.getOnlinePlayers().stream()
                    .filter(pl -> isStaff(pl.getUniqueId()) || pl.hasPermission("cubestaff.admin"))
                    .forEach(pl -> pl.sendMessage(c(format)));
                Bukkit.getConsoleSender().sendMessage(c(format));

                webhookEmbed("staff-chat",
                    "💬 شات الإدارة",
                    "**" + p.getName() + "** [" + (rank != null ? rankDisplay(rank) : "إداري") + "]\n" + message,
                    hexInt(cfgStr("embed-design.colors.staff-chat", "5865F2")));
                return true;
            }

            // ─── /staffwarn <player> <reason> ───
            case "staffwarn": {
                if (!sender.hasPermission(getConfig().getString("warnings.warn-permission", "cubestaff.warn"))) {
                    sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(c("&e⚠ الاستخدام: &6/staffwarn <لاعب> <السبب>")); return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                if (!isStaff(target.getUniqueId())) {
                    sender.sendMessage(c(cfgStr("messages.not-staff", "&c✗ هذا اللاعب ليس من الإدارة!"))); return true;
                }
                String reason = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                addWarning(target.getUniqueId(), target.getName() != null ? target.getName() : args[0],
                           reason, sender.getName());
                sender.sendMessage(c("&a✓ تم تحذير &e" + target.getName()));
                return true;
            }

            // ─── /staffunwarn <player> <index> ───
            case "staffunwarn": {
                if (!sender.hasPermission(getConfig().getString("warnings.warn-permission", "cubestaff.warn"))) {
                    sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(c("&e⚠ الاستخدام: &6/staffunwarn <لاعب> <رقم>")); return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                try {
                    int idx = Integer.parseInt(args[1]);
                    removeWarning(target.getUniqueId(), target.getName() != null ? target.getName() : args[0],
                                  idx, sender.getName());
                    sender.sendMessage(c("&a✓ تم حذف التحذير #" + idx + " من &e" + target.getName()));
                } catch (NumberFormatException e) {
                    sender.sendMessage(c("&c✗ رقم التحذير غير صالح!"));
                }
                return true;
            }

            // ─── /vacation <player> <on/off> [days] ───
            case "vacation": {
                if (!sender.hasPermission("cubestaff.vacation")) {
                    sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(c("&e⚠ الاستخدام: &6/vacation <لاعب> <on/off> [أيام]")); return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                UUID tUUID = target.getUniqueId();
                String tName = target.getName() != null ? target.getName() : args[0];

                if (!isStaff(tUUID)) {
                    sender.sendMessage(c(cfgStr("messages.not-staff", "&c✗ هذا اللاعب ليس من الإدارة!"))); return true;
                }

                boolean on = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                if (on) {
                    int days = args.length >= 3 ? parseInt(args[2], 7) : 7;
                    long endMs = System.currentTimeMillis() + (long) days * 86_400_000L;
                    vacationEnd.put(tUUID, endMs);
                    vacationSetBy.put(tUUID, sender.getName());
                    saveAllData();

                    String endDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")
                        .format(LocalDateTime.now().plusDays(days));

                    sender.sendMessage(c("&a✓ تم تفعيل إجازة &e" + tName + " &7لمدة &e" + days + " &7يوم"));

                    if (cfg("notifications.vacation-status", true)) {
                        sendDM(tUUID, new EmbedBuilder()
                            .setTitle("🏖️ تم تفعيل إجازتك!")
                            .setDescription("**المدة:** " + days + " يوم (حتى " + endDate + ")\n"
                                + "**بواسطة:** " + sender.getName() + "\n\n"
                                + "لن يتم محاسبتك على الساعات حتى انتهاء الإجازة. استمتع! 🌴")
                            .setColor(hexColor(cfgStr("embed-design.colors.info", "3498DB")))
                            .setFooter(footer()).setTimestamp(Instant.now()).build());
                    }

                    webhookEmbed("vacations",
                        "🏖️ إجازة جديدة",
                        "**الإداري:** " + tName
                        + "\n**المدة:** " + days + " يوم (حتى " + endDate + ")"
                        + "\n**بواسطة:** " + sender.getName(),
                        hexInt(cfgStr("embed-design.colors.info", "3498DB")));

                } else {
                    vacationEnd.remove(tUUID);
                    vacationSetBy.remove(tUUID);
                    saveAllData();
                    sender.sendMessage(c("&a✓ تم إنهاء إجازة &e" + tName));

                    if (cfg("notifications.vacation-status", true)) {
                        sendDM(tUUID, new EmbedBuilder()
                            .setTitle("🔙 انتهت إجازتك")
                            .setDescription("مرحباً بعودتك! سيتم احتساب ساعاتك من الآن. 💪")
                            .setColor(hexColor(cfgStr("embed-design.colors.success", "2ECC71")))
                            .setFooter(footer()).setTimestamp(Instant.now()).build());
                    }
                }
                return true;
            }

            // ─── /staffreload ───
            case "staffreload": {
                if (!sender.hasPermission("cubestaff.admin")) {
                    sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                }
                reloadConfig();
                dataCfg = YamlConfiguration.loadConfiguration(dataFile);
                sender.sendMessage(c(cfgStr("messages.reload-success", "&a✓ تم إعادة تحميل الإعدادات!")));
                return true;
            }

            // ─── /staffscan ───
            case "staffscan": {
                if (!sender.hasPermission("cubestaff.admin")) {
                    sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                }
                showScan(sender);
                return true;
            }

            // ─── /dailytop ───
            case "dailytop": {
                if (!sender.hasPermission("cubestaff.admin")) {
                    sender.sendMessage(c(cfgStr("messages.no-permission", "&c✗ ليس لديك صلاحية!"))); return true;
                }
                sendDailyTopWebhook();
                sender.sendMessage(c("&a✓ تم إرسال Top Staff إلى Discord!"));
                return true;
            }

            // ─── /testnotif ───
            case "testnotif": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(c(cfgStr("messages.player-only", "&c✗ هذا الأمر للاعبين فقط!"))); return true;
                }
                Player p = (Player) sender;
                boolean linked = isLinked(p.getUniqueId());
                p.sendMessage(c("&d─── اختبار الإشعارات ───"));
                p.sendMessage(c("&7CubeDiscordLink: " + (discordLinkPlugin != null ? "&aمتصل ✓" : "&cغير متصل ✗")));
                p.sendMessage(c("&7JDA Bot: "         + (jda              != null ? "&aمتصل ✓" : "&cغير متصل ✗")));
                p.sendMessage(c("&7حالة الربط: "      + (linked           ? "&aمربوط ✓"         : "&cغير مربوط ✗")));
                if (linked) {
                    sendDM(p.getUniqueId(), new EmbedBuilder()
                        .setTitle("🧪 اختبار الإشعارات")
                        .setDescription("مرحباً **" + p.getName() + "**!\n✅ CubeSTAFF v4 — الإشعارات تعمل بنجاح!")
                        .setColor(hexColor(cfgStr("embed-design.colors.success", "2ECC71")))
                        .setFooter(footer()).setTimestamp(Instant.now()).build());
                    p.sendMessage(c("&a✓ تم إرسال إشعار تجريبي — تحقق من Discord!"));
                }
                return true;
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //                    GUI
    // ════════════════════════════════════════════════════════════════

    private void openStaffGUI(Player viewer) {
        int rows      = Math.max(1, Math.min(6, getConfig().getInt("gui.rows", 6)));
        String title  = c(getConfig().getString("gui.title", "&5&l✦ &d&lإدارة الخادم &5&l✦"));
        Inventory inv = Bukkit.createInventory(null, rows * 9, title);

        // Fill with glass panes
        if (getConfig().getBoolean("gui.fill-empty", true)) {
            ItemStack glass = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ", new ArrayList<>());
            for (int i = 0; i < rows * 9; i++) inv.setItem(i, glass);
        }

        // Add staff heads
        List<String> staffRanks = getConfig().getStringList("staff-ranks");
        List<OfflinePlayer> staffList = new ArrayList<>();

        // Collect all players who hold a staff rank
        luckPerms.getUserManager().getLoadedUsers().forEach(lpUser -> {
            UUID uuid = lpUser.getUniqueId();
            if (getStaffRank(uuid) != null) {
                staffList.add(Bukkit.getOfflinePlayer(uuid));
            }
        });
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (getStaffRank(p.getUniqueId()) != null
                && staffList.stream().noneMatch(op -> op.getUniqueId().equals(p.getUniqueId()))) {
                staffList.add(p);
            }
        });

        int slot = 0;
        for (OfflinePlayer op : staffList) {
            if (slot >= rows * 9) break;
            UUID uuid  = op.getUniqueId();
            String rank = getStaffRank(uuid);
            if (rank == null) continue;

            double weekH = weeklySeconds.getOrDefault(uuid, 0L) / 3600.0;
            double todayH = todaySeconds.getOrDefault(uuid, 0L) / 3600.0;
            double totalH = totalSeconds.getOrDefault(uuid, 0L) / 3600.0;
            double reqH   = getConfig().getDouble("required-weekly-hours." + rank, 0);
            List<Map<?,?>> warns = getWarnings(uuid);
            int maxW = getConfig().getInt("warnings.max-warnings", 4);

            boolean online   = Bukkit.getPlayer(uuid) != null;
            boolean vacation = onVacation(uuid);

            List<String> lore = new ArrayList<>();
            lore.add(c("&8═══════════════"));
            lore.add(c("&7الرتبة: &d" + rankDisplay(rank)));
            lore.add(c("&7الحالة: " + (vacation ? "&b🏖️ في إجازة" : online ? "&aمتصل ✓" : "&7غير متصل")));
            lore.add(c("&8───────────────"));
            lore.add(c("&7ساعات اليوم:    &e" + String.format("%.1f", todayH) + "ه"));
            lore.add(c("&7ساعات الأسبوع: &e" + String.format("%.1f", weekH) + "/" + (reqH > 0 ? (int)reqH : "∞") + "ه"));
            lore.add(c("&7الإجمالي:      &e" + String.format("%.1f", totalH) + "ه"));
            lore.add(c("&8───────────────"));
            lore.add(c("&7التحذيرات: " + warnColor(warns.size(), maxW) + warns.size() + "/" + maxW));
            lore.add(c("&8═══════════════"));

            ItemStack head = makePlayerHead(op, op.getName() != null ? op.getName() : "Unknown", lore);

            // Enchant glow if online
            if (online) head.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.DURABILITY, 1);

            inv.setItem(slot++, head);
        }

        viewer.openInventory(inv);
    }

    @EventHandler
    public void onGUIClick(InventoryClickEvent e) {
        String title = c(getConfig().getString("gui.title", "&5&l✦ &d&lإدارة الخادم &5&l✦"));
        if (!e.getView().getTitle().equals(title)) return;
        e.setCancelled(true); // read-only
    }

    // ════════════════════════════════════════════════════════════════
    //                    DISPLAY METHODS
    // ════════════════════════════════════════════════════════════════

    private void showStats(CommandSender sender, UUID uuid, String name) {
        String rank   = getStaffRank(uuid);
        double weekH  = weeklySeconds.getOrDefault(uuid, 0L) / 3600.0;
        double todayH = todaySeconds.getOrDefault(uuid, 0L) / 3600.0;
        double totalH = totalSeconds.getOrDefault(uuid, 0L) / 3600.0;
        double reqH   = rank != null ? getConfig().getDouble("required-weekly-hours." + rank, 0) : 0;
        List<Map<?,?>> warns = getWarnings(uuid);
        int maxW = getConfig().getInt("warnings.max-warnings", 4);
        boolean vacation = onVacation(uuid);

        sender.sendMessage(c(""));
        sender.sendMessage(c("&d&l    ╔══════════════════════════╗"));
        sender.sendMessage(c("&d&l    ║  &b&l" + centerText(name, 24) + "&d&l  ║"));
        sender.sendMessage(c("&d&l    ╚══════════════════════════╝"));
        sender.sendMessage(c(""));
        sender.sendMessage(c("  &8• &7الرتبة:       &d" + (rank != null ? rankDisplay(rank) : "&cلا يوجد")));
        sender.sendMessage(c("  &8• &7الحالة:       " + (vacation ? "&b🏖️ في إجازة" : Bukkit.getPlayer(uuid) != null ? "&a● متصل" : "&8○ غير متصل")));
        sender.sendMessage(c(""));
        sender.sendMessage(c("  &8◈ &eالساعات"));
        sender.sendMessage(c("    &8→ &7اليوم:    &f" + String.format("%.1f", todayH) + "ه"));
        sender.sendMessage(c("    &8→ &7الأسبوع:  &f" + String.format("%.1f", weekH)
            + (reqH > 0 ? " &8/ &e" + (int) reqH + "ه &8(&" + (weekH >= reqH ? "a✓" : "c✗") + "&8)" : "")));
        sender.sendMessage(c("    &8→ &7الإجمالي: &f" + String.format("%.1f", totalH) + "ه"));
        sender.sendMessage(c(""));
        sender.sendMessage(c("  &8◈ &eالتحذيرات:  " + warnColor(warns.size(), maxW) + warns.size() + "/" + maxW));
        if (!warns.isEmpty()) {
            for (int i = 0; i < warns.size(); i++) {
                Map<?,?> w = warns.get(i);
                sender.sendMessage(c("    &8#" + (i+1) + " &7" + w.get("reason") + " &8| &7" + w.get("by")));
            }
        }
        sender.sendMessage(c(""));
        sender.sendMessage(c("&d&l    ══════════════════════════════"));
        sender.sendMessage(c(""));
    }

    private void showTop(CommandSender sender) {
        int topCount = getConfig().getInt("daily-top.top-count", 10);
        List<Map.Entry<UUID, Long>> sorted = weeklySeconds.entrySet().stream()
            .filter(e -> isStaff(e.getKey()))
            .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
            .limit(topCount)
            .collect(Collectors.toList());

        sender.sendMessage(c(""));
        sender.sendMessage(c("&d&l  ╔═══════════════════════════════╗"));
        sender.sendMessage(c("&d&l  ║   &b&l  🏆 أفضل الإداريين  &d&l       ║"));
        sender.sendMessage(c("&d&l  ╚═══════════════════════════════╝"));
        sender.sendMessage(c(""));

        String[] medals = {"&6🥇","&7🥈","&e🥉","&7④","&7⑤","&7⑥","&7⑦","&7⑧","&7⑨","&7⑩"};
        for (int i = 0; i < sorted.size(); i++) {
            UUID uuid = sorted.get(i).getKey();
            double h  = sorted.get(i).getValue() / 3600.0;
            String n  = playerName(uuid);
            String r  = getStaffRank(uuid);
            sender.sendMessage(c("  " + medals[Math.min(i, medals.length-1)] + " &f"
                + n + " &8[&d" + (r != null ? r : "?") + "&8] &7─ &e"
                + String.format("%.1f", h) + "ه"));
        }
        sender.sendMessage(c(""));
        sender.sendMessage(c("&d&l  ══════════════════════════════════"));
        sender.sendMessage(c(""));
    }

    private void showScan(CommandSender sender) {
        List<String> ranks = getConfig().getStringList("staff-ranks");
        sender.sendMessage(c(""));
        sender.sendMessage(c("&d&l  ╔══════════════════════════════╗"));
        sender.sendMessage(c("&d&l  ║    &b&l🔍 فحص الإداريين        &d&l ║"));
        sender.sendMessage(c("&d&l  ╚══════════════════════════════╝"));
        sender.sendMessage(c(""));

        int total = 0;
        for (String rank : ranks) {
            List<String> members = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers())
                if (rank.equals(getStaffRank(p.getUniqueId())))
                    members.add(c("&a" + p.getName() + " &8(متصل)"));
            luckPerms.getUserManager().getLoadedUsers().forEach(u -> {
                if (Bukkit.getPlayer(u.getUniqueId()) == null && rank.equals(getStaffRank(u.getUniqueId())))
                    members.add(c("&7" + playerName(u.getUniqueId()) + " &8(غير متصل)"));
            });
            if (!members.isEmpty()) {
                sender.sendMessage(c("  &d[" + rankDisplay(rank) + "] &8(" + members.size() + ")"));
                members.forEach(m -> sender.sendMessage(c("    &8▸ " + m)));
                total += members.size();
            }
        }
        sender.sendMessage(c(""));
        sender.sendMessage(c("  &7الإجمالي: &e" + total + " &7إداري"));
        sender.sendMessage(c("&d&l  ══════════════════════════════════"));
    }

    private void sendStatsMessage(Player p, UUID uuid) {
        showStats(p, uuid, p.getName());
    }

    // ════════════════════════════════════════════════════════════════
    //                    DAILY TOP WEBHOOK
    // ════════════════════════════════════════════════════════════════

    private void sendDailyTopWebhook() {
        String url = getConfig().getString("webhooks.daily-top", "");
        if (url.isEmpty()) return;

        int topCount = getConfig().getInt("daily-top.top-count", 10);
        List<Map.Entry<UUID, Long>> sorted = weeklySeconds.entrySet().stream()
            .filter(e -> isStaff(e.getKey()))
            .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
            .limit(topCount)
            .collect(Collectors.toList());

        String[] medals = {"🥇","🥈","🥉","4️⃣","5️⃣","6️⃣","7️⃣","8️⃣","9️⃣","🔟"};
        StringBuilder daily = new StringBuilder("**📅 أفضل إداريي اليوم**\n\n");
        StringBuilder weekly = new StringBuilder("\n\n**📊 إجمالي الأسبوع**\n");

        for (int i = 0; i < sorted.size(); i++) {
            UUID uuid = sorted.get(i).getKey();
            String n  = playerName(uuid);
            String r  = getStaffRank(uuid);
            double wH = sorted.get(i).getValue() / 3600.0;
            double dH = todaySeconds.getOrDefault(uuid, 0L) / 3600.0;
            String medal = i < medals.length ? medals[i] : "▸";

            daily.append(medal).append(" **").append(n).append("** `[")
                 .append(r != null ? r : "?").append("]` ─ ")
                 .append(String.format("%.1f", dH)).append("ه\n");

            weekly.append("▸ **").append(n).append("** ─ ")
                  .append(String.format("%.1f", wH)).append("ه\n");
        }

        String date = DateTimeFormatter.ofPattern("EEEE dd/MM/yyyy", new Locale("ar"))
            .format(LocalDate.now());

        webhookEmbed("daily-top",
            "📊 إحصائيات الإداريين — " + date,
            daily.toString() + weekly.toString(),
            hexInt(cfgStr("embed-design.colors.purple", "9B59B6")));
    }

    // ════════════════════════════════════════════════════════════════
    //                    DISCORD HELPERS
    // ════════════════════════════════════════════════════════════════

    private boolean isLinked(UUID uuid) {
        if (discordLinkPlugin == null || isLinkedMethod == null) return false;
        try { return (boolean) isLinkedMethod.invoke(discordLinkPlugin, uuid); }
        catch (Exception e) { return false; }
    }

    private String getDiscordId(UUID uuid) {
        if (discordLinkPlugin == null || getDiscordIdMethod == null) return null;
        try { return (String) getDiscordIdMethod.invoke(discordLinkPlugin, uuid); }
        catch (Exception e) { return null; }
    }

    private void sendDM(UUID uuid, MessageEmbed embed) {
        if (jda == null) return;
        String discordId = getDiscordId(uuid);
        if (discordId == null) return;
        jda.retrieveUserById(discordId).queue(user ->
            user.openPrivateChannel().queue(ch ->
                ch.sendMessageEmbeds(embed).queue(null, err -> {})
            , err -> {})
        , err -> {});
    }

    private void webhookEmbed(String webhookKey, String title, String desc, int color) {
        String url = getConfig().getString("webhooks." + webhookKey, "");
        if (url.isEmpty()) return;

        String serverName = getConfig().getString("server-name", "CubeMC");
        String time = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").format(LocalDateTime.now());
        String footerText = cfgStr("embed-design.footer-text", "CubeSTAFF • {server} • {time}")
            .replace("{server}", serverName).replace("{time}", time);

        String json = "{"
            + "\"username\":\"CubeSTAFF\","
            + "\"embeds\":[{"
            + "\"title\":\"" + escJson(title) + "\","
            + "\"description\":\"" + escJson(desc) + "\","
            + "\"color\":" + color + ","
            + "\"footer\":{\"text\":\"" + escJson(footerText) + "\"},"
            + "\"timestamp\":\"" + Instant.now() + "\""
            + "}]}";

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        http.newCall(new Request.Builder().url(url).post(body).build())
            .enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {}
                @Override public void onResponse(Call call, Response r) throws IOException { r.close(); }
            });
    }

    // ════════════════════════════════════════════════════════════════
    //                    STAFF HELPERS
    // ════════════════════════════════════════════════════════════════

    private boolean isStaff(UUID uuid) { return getStaffRank(uuid) != null; }

    private String getStaffRank(UUID uuid) {
        List<String> ranks = getConfig().getStringList("staff-ranks");
        net.luckperms.api.model.user.User u = luckPerms.getUserManager().getUser(uuid);
        if (u == null) return null;
        for (String rank : ranks) {
            boolean has = u.getNodes().stream()
                .anyMatch(n -> n.getKey().equalsIgnoreCase("group." + rank));
            if (has) return rank;
        }
        return null;
    }

    private boolean onVacation(UUID uuid) {
        Long end = vacationEnd.get(uuid);
        if (end == null || end == 0) return false;
        if (System.currentTimeMillis() > end) {
            // Vacation expired automatically
            vacationEnd.remove(uuid);
            vacationSetBy.remove(uuid);
            return false;
        }
        return true;
    }

    private void broadcastAdmins(String msg) {
        Bukkit.getOnlinePlayers().stream()
            .filter(p -> p.hasPermission("cubestaff.admin"))
            .forEach(p -> p.sendMessage(msg));
    }

    // ════════════════════════════════════════════════════════════════
    //                    UTILITY METHODS
    // ════════════════════════════════════════════════════════════════

    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private String cfgStr(String path, String def) {
        return getConfig().getString(path, def);
    }

    private boolean cfg(String path, boolean def) {
        return getConfig().getBoolean(path, def);
    }

    private Color hexColor(String hex) {
        try { return Color.decode("#" + hex.replace("#","")); }
        catch (Exception e) { return Color.GRAY; }
    }

    private int hexInt(String hex) {
        try { return Integer.parseInt(hex.replace("#",""), 16); }
        catch (Exception e) { return 0x7289DA; }
    }

    private String rankDisplay(String rank) {
        return getConfig().getString("rank-display-names." + rank, capitalize(rank));
    }

    private String rankHex(String rank) {
        return getConfig().getString("rank-colors." + rank,
            cfgStr("embed-design.colors.info", "3498DB"));
    }

    private String footer() {
        String serverName = getConfig().getString("server-name", "CubeMC");
        String time = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").format(LocalDateTime.now());
        return cfgStr("embed-design.footer-text", "CubeSTAFF • {server} • {time}")
            .replace("{server}", serverName).replace("{time}", time);
    }

    private String playerName(UUID uuid) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString().substring(0, 8);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0,1).toUpperCase() + s.substring(1);
    }

    private String fmtDuration(long seconds) {
        if (seconds <= 0) return "منتهي";
        long d = seconds / 86400, h = (seconds % 86400) / 3600, m = (seconds % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("ي ");
        if (h > 0) sb.append(h).append("س ");
        if (m > 0) sb.append(m).append("د");
        return sb.toString().trim();
    }

    private String warnColor(int count, int max) {
        if (count == 0) return "&a";
        if (count < max - 1) return "&e";
        if (count < max) return "&c";
        return "&4";
    }

    private String centerText(String text, int width) {
        int pad = Math.max(0, (width - text.length()) / 2);
        return " ".repeat(pad) + text;
    }

    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(c(name));
        meta.setLore(lore.stream().map(this::c).collect(Collectors.toList()));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makePlayerHead(OfflinePlayer op, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null) return head;
        meta.setOwningPlayer(op);
        meta.setDisplayName(c("&b" + name));
        meta.setLore(lore.stream().map(this::c).collect(Collectors.toList()));
        head.setItemMeta(meta);
        return head;
    }

    // ════════════════════════════════════════════════════════════════
    //                    TAB COMPLETE
    // ════════════════════════════════════════════════════════════════

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase();
        if (args.length == 1) {
            List<String> players = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName).collect(Collectors.toList());
            switch (name) {
                case "staffwarn": case "staffunwarn": case "vacation":
                case "staffstats": case "staffscan":
                    return players.stream()
                        .filter(p -> p.toLowerCase().startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        if (args.length == 2 && name.equals("vacation")) {
            return Arrays.asList("on","off").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }
}
