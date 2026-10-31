package pl.anarchia.klany;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class AnarchiaKlany extends JavaPlugin implements Listener, TabExecutor {

    private static final String PREFIX = "&8[&6Klan&8] ";
    private static final Pattern TAG_PATTERN = Pattern.compile("[A-Za-z0-9]{1,5}");
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\p{L}0-9 _-]{1,25}");
    private static final long DELETE_CONFIRM_MS = 30_000L;
    private static final long INVITE_MS = 60_000L;
    private static final String TEAM_OWN = "akg_";
    private static final String TEAM_OTHER = "akr_";

    private static final List<String> SUBS =
            List.of("zaloz", "usun", "opusc", "zapros", "wyrzuc", "dolacz");

    private static class Clan {
        final String tag;
        final String name;
        final UUID leader;
        final Map<UUID, String> members = new LinkedHashMap<>();

        Clan(String tag, String name, UUID leader) {
            this.tag = tag;
            this.name = name;
            this.leader = leader;
        }
    }

    private record Invite(String tag, long expires) {
    }

    private final Map<String, Clan> clans = new LinkedHashMap<>();
    private final Map<UUID, String> memberClan = new HashMap<>();
    private final Map<UUID, Long> pendingDelete = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();

    private File dataFile;
    // Ten sam klucz, z ktorego AnarchiaScoreboard czyta {klan}
    private NamespacedKey scoreboardKey;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "klany.yml");
        scoreboardKey = new NamespacedKey("anarchiascoreboard", "clan");
        load();

        Bukkit.getPluginManager().registerEvents(this, this);
        for (String name : List.of("klan", "usun", "opusc", "zaloz", "zapros", "wyrzuc", "dolacz")) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }

        // Stary /klan z AnarchiaScoreboard ma byc zastapiony tym
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                PluginCommand mine = getCommand("klan");
                if (mine != null) {
                    Bukkit.getCommandMap().getKnownCommands().put("klan", mine);
                }
            } catch (Throwable t) {
                getLogger().warning("Nie udalo sie przejac komendy /klan: " + t.getMessage());
            }
        });

        Bukkit.getScheduler().runTaskTimer(this, this::refreshAll, 20L, 20L);
        getLogger().info("AnarchiaKlany wlaczony. Klanow: " + clans.size());
    }

    @Override
    public void onDisable() {
        save();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        clans.clear();
        memberClan.clear();
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = y.getConfigurationSection("clans");
        if (root == null) {
            return;
        }
        for (String tag : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(tag);
            if (s == null) {
                continue;
            }
            try {
                Clan c = new Clan(tag.toUpperCase(Locale.ROOT), s.getString("name", tag),
                        UUID.fromString(s.getString("leader", "")));
                ConfigurationSection m = s.getConfigurationSection("members");
                if (m != null) {
                    for (String id : m.getKeys(false)) {
                        c.members.put(UUID.fromString(id), m.getString(id, "?"));
                    }
                }
                clans.put(c.tag, c);
                for (UUID id : c.members.keySet()) {
                    memberClan.put(id, c.tag);
                }
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Pominieto uszkodzony klan: " + tag);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Clan c : clans.values()) {
            String base = "clans." + c.tag;
            y.set(base + ".name", c.name);
            y.set(base + ".leader", c.leader.toString());
            for (Map.Entry<UUID, String> e : c.members.entrySet()) {
                y.set(base + ".members." + e.getKey(), e.getValue());
            }
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Nie mozna zapisac klany.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void msg(CommandSender to, String text) {
        to.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(PREFIX + text));
    }

    private String tagOf(UUID id) {
        return memberClan.get(id);
    }

    private Clan clanOf(Player p) {
        String tag = memberClan.get(p.getUniqueId());
        return tag == null ? null : clans.get(tag);
    }

    private void broadcastClan(Clan c, String text) {
        for (UUID id : c.members.keySet()) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                msg(online, text);
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Clan c = clanOf(p);
        if (c != null && !p.getName().equals(c.members.get(p.getUniqueId()))) {
            c.members.put(p.getUniqueId(), p.getName());
            save();
        }
        // scoreboard plugin tworzy swoja tablice przy wejsciu, wiec odswiezamy chwile pozniej
        Bukkit.getScheduler().runTaskLater(this, this::refreshAll, 5L);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String sub;
        String[] a;
        if (name.equals("klan")) {
            if (args.length == 0) {
                usage(sender);
                return true;
            }
            sub = args[0].toLowerCase(Locale.ROOT);
            a = Arrays.copyOfRange(args, 1, args.length);
        } else if (name.equals("usun")) {
            sub = "potwierdz-usun"; // samo /usun = potwierdzenie
            a = args;
        } else {
            sub = name;
            a = args;
        }

        if (!(sender instanceof Player p)) {
            msg(sender, "&cTylko dla graczy.");
            return true;
        }

        switch (sub) {
            case "zaloz", "stworz" -> create(p, a);
            case "usun" -> requestDelete(p);
            case "potwierdz-usun" -> confirmDelete(p);
            case "opusc" -> leave(p);
            case "zapros" -> invite(p, a);
            case "wyrzuc" -> kick(p, a);
            case "dolacz" -> join(p);
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        msg(s, "&eUzycie: &f/klan zaloz <skrot> <nazwa> &7| &f/klan usun &7| &f/klan opusc");
        msg(s, "&eLider: &f/klan zapros <nick> &7| &f/klan wyrzuc <nick>&e. Zaproszony: &f/klan dolacz");
    }

    private void create(Player p, String[] a) {
        if (memberClan.containsKey(p.getUniqueId())) {
            msg(p, "&cJuz jestes w klanie. Najpierw /klan opusc.");
            return;
        }
        if (a.length < 2) {
            msg(p, "&eUzycie: /klan zaloz <skrot> <nazwa>");
            return;
        }
        if (!TAG_PATTERN.matcher(a[0]).matches()) {
            msg(p, "&cSkrot: max 5 znakow, tylko litery i cyfry.");
            return;
        }
        String tag = a[0].toUpperCase(Locale.ROOT);
        String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length)).trim();
        if (!NAME_PATTERN.matcher(name).matches()) {
            msg(p, "&cNazwa: max 25 znakow (litery, cyfry, spacja).");
            return;
        }
        if (clans.containsKey(tag)) {
            msg(p, "&cKlan o takim skrocie juz istnieje.");
            return;
        }
        for (Clan c : clans.values()) {
            if (c.name.equalsIgnoreCase(name)) {
                msg(p, "&cKlan o takiej nazwie juz istnieje.");
                return;
            }
        }
        Clan c = new Clan(tag, name, p.getUniqueId());
        c.members.put(p.getUniqueId(), p.getName());
        clans.put(tag, c);
        memberClan.put(p.getUniqueId(), tag);
        save();
        refreshAll();
        msg(p, "&aZalozyles klan &6" + name + " &7[" + tag + "]&a.");
    }

    private void requestDelete(Player p) {
        Clan c = clanOf(p);
        if (c == null) {
            msg(p, "&cNie jestes w klanie.");
            return;
        }
        if (!c.leader.equals(p.getUniqueId())) {
            msg(p, "&cTylko lider moze usunac klan.");
            return;
        }
        pendingDelete.put(p.getUniqueId(), System.currentTimeMillis() + DELETE_CONFIRM_MS);
        msg(p, "&eAby na pewno usunac klan &6" + c.name + "&e, wpisz &c/usun &e(masz 30 sekund).");
    }

    private void confirmDelete(Player p) {
        Clan c = clanOf(p);
        if (c == null) {
            msg(p, "&cNie jestes w klanie.");
            return;
        }
        if (!c.leader.equals(p.getUniqueId())) {
            msg(p, "&cTylko lider moze usunac klan.");
            return;
        }
        Long until = pendingDelete.remove(p.getUniqueId());
        if (until == null || until < System.currentTimeMillis()) {
            msg(p, "&eNajpierw wpisz &f/klan usun&e, potem potwierdz przez &f/usun&e.");
            return;
        }
        broadcastClan(c, "&cKlan &6" + c.name + " &czostal usuniety przez lidera.");
        for (UUID id : new ArrayList<>(c.members.keySet())) {
            memberClan.remove(id);
        }
        clans.remove(c.tag);
        save();
        refreshAll();
    }

    private void leave(Player p) {
        Clan c = clanOf(p);
        if (c == null) {
            msg(p, "&cNie jestes w klanie.");
            return;
        }
        if (c.leader.equals(p.getUniqueId())) {
            msg(p, "&cLider nie moze opuscic klanu. Uzyj &f/klan usun&c.");
            return;
        }
        c.members.remove(p.getUniqueId());
        memberClan.remove(p.getUniqueId());
        save();
        refreshAll();
        msg(p, "&aOpusciles klan &6" + c.name + "&a.");
        broadcastClan(c, "&e" + p.getName() + " &7opuscil klan.");
    }

    private void invite(Player p, String[] a) {
        Clan c = clanOf(p);
        if (c == null) {
            msg(p, "&cNie jestes w klanie.");
            return;
        }
        if (!c.leader.equals(p.getUniqueId())) {
            msg(p, "&cTylko lider moze zapraszac.");
            return;
        }
        if (a.length < 1) {
            msg(p, "&eUzycie: /klan zapros <nick>");
            return;
        }
        Player target = Bukkit.getPlayerExact(a[0]);
        if (target == null) {
            msg(p, "&cGracz &f" + a[0] + " &cjest offline.");
            return;
        }
        if (target.getUniqueId().equals(p.getUniqueId())) {
            msg(p, "&cNie mozesz zaprosic samego siebie.");
            return;
        }
        if (memberClan.containsKey(target.getUniqueId())) {
            msg(p, "&cTen gracz jest juz w klanie.");
            return;
        }
        invites.put(target.getUniqueId(), new Invite(c.tag, System.currentTimeMillis() + INVITE_MS));
        msg(p, "&aWyslano zaproszenie do &f" + target.getName() + "&a.");
        msg(target, "&e" + p.getName() + " &7zaprasza cie do klanu &6" + c.name + " &7[" + c.tag
                + "]&7. Wpisz &a/klan dolacz &7(60 sekund).");
    }

    private void join(Player p) {
        if (memberClan.containsKey(p.getUniqueId())) {
            msg(p, "&cJuz jestes w klanie.");
            return;
        }
        Invite inv = invites.remove(p.getUniqueId());
        if (inv == null || inv.expires() < System.currentTimeMillis()) {
            msg(p, "&cNie masz zadnego waznego zaproszenia.");
            return;
        }
        Clan c = clans.get(inv.tag());
        if (c == null) {
            msg(p, "&cTen klan juz nie istnieje.");
            return;
        }
        c.members.put(p.getUniqueId(), p.getName());
        memberClan.put(p.getUniqueId(), c.tag);
        save();
        refreshAll();
        msg(p, "&aDolaczyles do klanu &6" + c.name + "&a.");
        broadcastClan(c, "&e" + p.getName() + " &7dolaczyl do klanu.");
    }

    private void kick(Player p, String[] a) {
        Clan c = clanOf(p);
        if (c == null) {
            msg(p, "&cNie jestes w klanie.");
            return;
        }
        if (!c.leader.equals(p.getUniqueId())) {
            msg(p, "&cTylko lider moze wyrzucac.");
            return;
        }
        if (a.length < 1) {
            msg(p, "&eUzycie: /klan wyrzuc <nick>");
            return;
        }
        UUID target = null;
        for (Map.Entry<UUID, String> e : c.members.entrySet()) {
            if (e.getValue().equalsIgnoreCase(a[0])) {
                target = e.getKey();
            }
        }
        if (target == null) {
            msg(p, "&cTakiego gracza nie ma w twoim klanie.");
            return;
        }
        if (target.equals(p.getUniqueId())) {
            msg(p, "&cNie mozesz wyrzucic samego siebie. Uzyj &f/klan usun&c.");
            return;
        }
        String targetName = c.members.remove(target);
        memberClan.remove(target);
        save();
        refreshAll();
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            msg(online, "&cZostales wyrzucony z klanu &6" + c.name + "&c.");
        }
        broadcastClan(c, "&e" + targetName + " &7zostal wyrzucony z klanu.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String sub;
        String last;
        int argIndex;
        if (name.equals("klan")) {
            if (args.length == 1) {
                for (String s : SUBS) {
                    if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                        out.add(s);
                    }
                }
                return out;
            }
            sub = args[0].toLowerCase(Locale.ROOT);
            argIndex = args.length - 1;
        } else {
            sub = name;
            argIndex = args.length;
        }
        last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (argIndex != 1) {
            return out;
        }
        if (sub.equals("zapros")) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase(Locale.ROOT).startsWith(last)) {
                    out.add(pl.getName());
                }
            }
        } else if (sub.equals("wyrzuc") && sender instanceof Player p) {
            Clan c = clanOf(p);
            if (c != null) {
                for (String n : c.members.values()) {
                    if (n.toLowerCase(Locale.ROOT).startsWith(last)) {
                        out.add(n);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ nametags

    private boolean isOurTeam(String teamName) {
        return teamName.startsWith(TEAM_OWN) || teamName.startsWith(TEAM_OTHER);
    }

    private void refreshAll() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player target : online) {
            syncScoreboardPlaceholder(target);
        }
        for (Player viewer : online) {
            updateViewer(viewer, online);
        }
    }

    /** Zapisuje tag w kluczu, z ktorego AnarchiaScoreboard czyta {klan}. */
    private void syncScoreboardPlaceholder(Player target) {
        String tag = tagOf(target.getUniqueId());
        PersistentDataContainer pdc = target.getPersistentDataContainer();
        String current = pdc.get(scoreboardKey, PersistentDataType.STRING);
        if (tag == null) {
            if (current != null) {
                pdc.remove(scoreboardKey);
            }
        } else if (!tag.equals(current)) {
            pdc.set(scoreboardKey, PersistentDataType.STRING, tag);
        }
    }

    private void updateViewer(Player viewer, Collection<? extends Player> online) {
        Scoreboard board = viewer.getScoreboard();
        String viewerTag = tagOf(viewer.getUniqueId());

        for (Player target : online) {
            String tag = tagOf(target.getUniqueId());
            boolean same = tag != null && tag.equals(viewerTag);
            String want = tag == null ? null : (same ? TEAM_OWN : TEAM_OTHER) + tag;
            String entry = target.getName();

            Team current = board.getEntryTeam(entry);
            if (current != null && isOurTeam(current.getName()) && !current.getName().equals(want)) {
                current.removeEntry(entry);
            }
            if (want != null) {
                Team team = board.getTeam(want);
                if (team == null) {
                    team = board.registerNewTeam(want);
                    team.prefix(Component.text("[" + tag + "] ", same ? NamedTextColor.GREEN : NamedTextColor.RED));
                }
                if (!team.hasEntry(entry)) {
                    team.addEntry(entry);
                }
            }
        }

        for (Team team : new ArrayList<>(board.getTeams())) {
            if (isOurTeam(team.getName()) && team.getEntries().isEmpty()) {
                team.unregister();
            }
        }
    }
}
