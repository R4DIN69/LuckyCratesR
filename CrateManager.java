package com.luckycrates.manager;

import com.luckycrates.LuckyCratesPlugin;
import com.luckycrates.util.ColorUtil;
import com.luckycrates.util.FontUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class CrateManager {
    private final LuckyCratesPlugin plugin;
    private final Set<Location> crateLocations = new HashSet<>();
    private final Map<Location, String> crateNames = new HashMap<>();
    private final Map<Location, String> crateColors = new HashMap<>();
    private final Map<Integer, ItemStack> slotRewards = new HashMap<>();
    private final Map<Location, ArmorStand> keyLineStands = new HashMap<>();
    private File cratesFile;
    private FileConfiguration cratesConfig;
    private BukkitTask holoUpdateTask;

    private static final Map<String, Material> COLOR_SHULKERS = new HashMap<>();
    private static final Map<String, String> COLOR_CODES = new HashMap<>();

    static {
        registerColor("red", Material.RED_SHULKER_BOX, "&c");
        registerColor("orange", Material.ORANGE_SHULKER_BOX, "&6");
        registerColor("yellow", Material.YELLOW_SHULKER_BOX, "&e");
        registerColor("lime", Material.LIME_SHULKER_BOX, "&a");
        registerColor("green", Material.GREEN_SHULKER_BOX, "&2");
        registerColor("cyan", Material.CYAN_SHULKER_BOX, "&3");
        registerColor("light_blue", Material.LIGHT_BLUE_SHULKER_BOX, "&b");
        registerColor("blue", Material.BLUE_SHULKER_BOX, "&9");
        registerColor("purple", Material.PURPLE_SHULKER_BOX, "&5");
        registerColor("magenta", Material.MAGENTA_SHULKER_BOX, "&d");
        registerColor("pink", Material.PINK_SHULKER_BOX, "&d");
        registerColor("white", Material.WHITE_SHULKER_BOX, "&f");
        registerColor("gray", Material.GRAY_SHULKER_BOX, "&7");
        registerColor("black", Material.BLACK_SHULKER_BOX, "&8");
    }

    private static void registerColor(String name, Material mat, String colorCode) {
        COLOR_SHULKERS.put(name.toLowerCase(), mat);
        COLOR_CODES.put(name.toLowerCase(), colorCode);
    }

    public CrateManager(LuckyCratesPlugin plugin) {
        this.plugin = plugin;
        loadCrates();
        startHologramKeyUpdater();
    }

    public void loadCrates() {
        crateLocations.clear();
        crateNames.clear();
        crateColors.clear();
        slotRewards.clear();
        keyLineStands.clear();

        cratesFile = new File(plugin.getDataFolder(), "crates.yml");
        if (!cratesFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                cratesFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create crates.yml: " + e.getMessage());
            }
        }
        cratesConfig = YamlConfiguration.loadConfiguration(cratesFile);

        List<String> rawLocs = cratesConfig.getStringList("locations");
        for (String raw : rawLocs) {
            Location loc = parseLocation(raw);
            if (loc != null) {
                crateLocations.add(loc);
                String name = cratesConfig.getString("names." + raw, "Lucky Crate");
                String color = cratesConfig.getString("colors." + raw, "purple");
                crateNames.put(loc, name);
                crateColors.put(loc, color);
            }
        }

        if (cratesConfig.isConfigurationSection("slot-rewards")) {
            for (String key : cratesConfig.getConfigurationSection("slot-rewards").getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    ItemStack item = cratesConfig.getItemStack("slot-rewards." + key);
                    if (item != null && item.getType() != Material.AIR) {
                        slotRewards.put(slot, item);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAllHolograms, 20L);
    }

    public void saveCrates() {
        if (cratesConfig == null || cratesFile == null) return;

        List<String> rawLocs = new ArrayList<>();
        for (Location loc : crateLocations) {
            String raw = serializeLocation(loc);
            rawLocs.add(raw);
            cratesConfig.set("names." + raw, crateNames.getOrDefault(loc, "Lucky Crate"));
            cratesConfig.set("colors." + raw, crateColors.getOrDefault(loc, "purple"));
        }
        cratesConfig.set("locations", rawLocs);

        cratesConfig.set("slot-rewards", null);
        for (Map.Entry<Integer, ItemStack> entry : slotRewards.entrySet()) {
            cratesConfig.set("slot-rewards." + entry.getKey(), entry.getValue());
        }

        try {
            cratesConfig.save(cratesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save crates.yml: " + e.getMessage());
        }
    }

    public Location placeCrateStructure(Block targetBlock, String name) {
        return placeCrateStructure(targetBlock, name, "purple");
    }

    public Location placeCrateStructure(Block targetBlock, String name, String color) {
        Location rodLoc = targetBlock.getLocation().clone().add(0, 1, 0);
        Location shulkerLoc = rodLoc.clone().add(0, 1, 0);

        rodLoc.getBlock().setType(Material.END_ROD);

        Material shulkerMat = COLOR_SHULKERS.getOrDefault(color.toLowerCase(), Material.PURPLE_SHULKER_BOX);
        shulkerLoc.getBlock().setType(shulkerMat);

        addCrate(shulkerLoc, name, color);
        spawnHologram(shulkerLoc);
        return shulkerLoc;
    }

    public boolean removeCrateStructure(Location shulkerLoc) {
        if (!isCrate(shulkerLoc)) return false;

        removeHologram(shulkerLoc);
        shulkerLoc.getBlock().setType(Material.AIR);

        Location rodLoc = shulkerLoc.clone().subtract(0, 1, 0);
        if (rodLoc.getBlock().getType() == Material.END_ROD) {
            rodLoc.getBlock().setType(Material.AIR);
        }

        removeCrate(shulkerLoc);
        return true;
    }

    public void addCrate(Location loc, String name) {
        addCrate(loc, name, "purple");
    }

    public void addCrate(Location loc, String name, String color) {
        Location blockLoc = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        crateLocations.add(blockLoc);
        crateNames.put(blockLoc, name != null ? name : "Lucky Crate");
        crateColors.put(blockLoc, color != null ? color : "purple");
        saveCrates();
        spawnHologram(blockLoc);
    }

    public boolean setCrateColor(Location loc, String colorName) {
        Location blockLoc = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        Material mat = COLOR_SHULKERS.get(colorName.toLowerCase());
        if (mat == null) return false;

        blockLoc.getBlock().setType(mat);
        crateColors.put(blockLoc, colorName.toLowerCase());
        saveCrates();
        spawnHologram(blockLoc);
        return true;
    }

    public void setCrateName(Location loc, String name) {
        Location blockLoc = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        crateNames.put(blockLoc, name);
        saveCrates();
        spawnHologram(blockLoc);
    }

    public String getCrateName(Location loc) {
        Location blockLoc = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return crateNames.getOrDefault(blockLoc, "Lucky Crate");
    }

    public String getCrateColor(Location loc) {
        Location blockLoc = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return crateColors.getOrDefault(blockLoc, "purple");
    }

    public String getColorCode(String colorName) {
        return COLOR_CODES.getOrDefault(colorName.toLowerCase(), "&5");
    }

    // ------------------------------------------------------------------------
    // Dynamic Hologram Spawning & Real-Time Key Count Updates
    // ------------------------------------------------------------------------
    public void spawnHologram(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        removeHologram(loc);

        String name = getCrateName(loc);
        String color = getCrateColor(loc);
        String colorCode = getColorCode(color);

        World world = loc.getWorld();
        double x = loc.getBlockX() + 0.5;
        double y = loc.getBlockY();
        double z = loc.getBlockZ() + 0.5;

        // Line 1: Colored Crate Name
        spawnHoloLine(world, x, y + 1.85, z, ColorUtil.color(colorCode + "&l" + name));
        
        // Line 2: Dynamic Keys Count Line (stored for real-time updating)
        ArmorStand keyStand = spawnHoloLine(world, x, y + 1.57, z, ColorUtil.color("&7You have Keys: &e0"));
        if (keyStand != null) {
            keyLineStands.put(loc, keyStand);
        }

        // Line 3: Left-click preview
        spawnHoloLine(world, x, y + 1.29, z, ColorUtil.color("&7Left-Click to Preview"));
        
        // Line 4: Right-click open
        spawnHoloLine(world, x, y + 1.01, z, ColorUtil.color("&6Right-Click with Key to Open"));
    }

    private ArmorStand spawnHoloLine(World world, double x, double y, double z, String text) {
        Location spawnLoc = new Location(world, x, y, z);
        return world.spawn(spawnLoc, ArmorStand.class, as -> {
            as.setVisible(false);
            as.setGravity(false);
            as.setMarker(true);
            as.setCustomName(text);
            as.setCustomNameVisible(true);
            as.getScoreboardTags().add("luckycrate_holo");
            as.setInvulnerable(true);
        });
    }

    public void removeHologram(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        keyLineStands.remove(loc);
        World world = loc.getWorld();
        for (Entity entity : world.getNearbyEntities(loc.clone().add(0.5, 1.5, 0.5), 1.5, 2.5, 1.5)) {
            if (entity instanceof ArmorStand && entity.getScoreboardTags().contains("luckycrate_holo")) {
                entity.remove();
            }
        }
    }

    public void refreshAllHolograms() {
        for (Location loc : crateLocations) {
            spawnHologram(loc);
        }
    }

    public void removeAllHolograms() {
        if (holoUpdateTask != null) {
            holoUpdateTask.cancel();
        }
        for (Location loc : crateLocations) {
            removeHologram(loc);
        }
        keyLineStands.clear();
    }

    /**
     * Periodically updates the "You have Keys: X" hologram line based on nearby players
     */
    private void startHologramKeyUpdater() {
        holoUpdateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<Location, ArmorStand> entry : keyLineStands.entrySet()) {
                Location loc = entry.getKey();
                ArmorStand stand = entry.getValue();

                if (stand == null || !stand.isValid()) continue;

                Player nearest = null;
                double nearestDist = 8.0 * 8.0;

                for (Player p : loc.getWorld().getPlayers()) {
                    double dist = p.getLocation().distanceSquared(loc);
                    if (dist < nearestDist) {
                        nearest = p;
                        nearestDist = dist;
                    }
                }

                int count = 0;
                if (nearest != null) {
                    for (ItemStack item : nearest.getInventory().getContents()) {
                        if (plugin.getKeyManager().isKey(item)) {
                            count += item.getAmount();
                        }
                    }
                }

                String newLine = ColorUtil.color("&7You have Keys: &e" + count);
                if (!newLine.equals(stand.getCustomName())) {
                    stand.setCustomName(newLine);
                }
            }
        }, 10L, 10L); // Check twice every second
    }

    public boolean isCrate(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Location blockLoc = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        return crateLocations.contains(blockLoc);
    }

    public boolean removeCrate(Location loc) {
        Location blockLoc = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        boolean removed = crateLocations.remove(blockLoc);
        crateNames.remove(blockLoc);
        crateColors.remove(blockLoc);
        keyLineStands.remove(blockLoc);
        if (removed) saveCrates();
        return removed;
    }

    // ------------------------------------------------------------------------
    // Rarity Calculation (<=5% Mythic, <=15% Uncommon, >15% Common)
    // ------------------------------------------------------------------------
    public double getSlotWeight(int slot) {
        if (slot <= 0) return 0.1;
        return 0.1 + Math.pow(slot, 1.6) * 0.4;
    }

    public double calculatePercentage(int slot) {
        if (!slotRewards.containsKey(slot)) return 0.0;
        double total = 0.0;
        for (int s : slotRewards.keySet()) {
            total += getSlotWeight(s);
        }
        if (total <= 0) return 0.0;
        return (getSlotWeight(slot) / total) * 100.0;
    }

    public ItemStack applyRarityLore(ItemStack original, int slot, double chance, String crateName) {
        return applyRarityLore(original, slot, chance, crateName, "&6");
    }

    public ItemStack applyRarityLore(ItemStack original, int slot, double chance, String crateName, String colorCode) {
        if (original == null || original.getType() == Material.AIR) return original;
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String rawName = meta.hasDisplayName() ? meta.getDisplayName() : original.getType().name().replace('_', ' ');
        String cleanName = rawName.replaceAll("(?i)(★[^★]+★\s*)+", "").replaceAll("&[0-9a-fk-or]", "").trim();
        if (cleanName.isEmpty()) cleanName = original.getType().name().replace('_', ' ');

        meta.setDisplayName(ColorUtil.color(colorCode + "&l" + cleanName));

        String tierName;
        String tierColor;
        if (chance <= 5.0) {
            tierName = "Mythic";
            tierColor = "&d";
        } else if (chance <= 15.0) {
            tierName = "Uncommon";
            tierColor = "&b";
        } else {
            tierName = "Common";
            tierColor = "&f";
        }

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.removeIf(l -> l.contains("Rarity:"));

        String formattedChance = String.format(Locale.US, "%.2f%%", chance);
        String rarityLine = ColorUtil.color("&7» &fRarity: " + tierColor + tierName + " &8→ &a" + formattedChance);
        lore.add(rarityLine);

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack pickWeightedReward() {
        if (slotRewards.isEmpty()) return new ItemStack(Material.DIRT);

        double totalWeight = 0.0;
        for (int slot : slotRewards.keySet()) totalWeight += getSlotWeight(slot);

        double rand = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0.0;

        for (Map.Entry<Integer, ItemStack> entry : slotRewards.entrySet()) {
            cumulative += getSlotWeight(entry.getKey());
            if (rand <= cumulative) return entry.getValue().clone();
        }

        return slotRewards.values().iterator().next().clone();
    }

    public List<ItemStack> getAllRewardItems() {
        return new ArrayList<>(slotRewards.values());
    }

    public Map<Integer, ItemStack> getSlotRewards() {
        return Collections.unmodifiableMap(slotRewards);
    }

    public void setSlotRewards(Map<Integer, ItemStack> newRewards) {
        slotRewards.clear();
        for (Map.Entry<Integer, ItemStack> entry : newRewards.entrySet()) {
            if (entry.getValue() != null && entry.getValue().getType() != Material.AIR) {
                slotRewards.put(entry.getKey(), entry.getValue().clone());
            }
        }
        saveCrates();
    }

    public Set<Location> getCrateLocations() {
        return Collections.unmodifiableSet(crateLocations);
    }

    public Set<String> getAvailableColors() {
        return Collections.unmodifiableSet(COLOR_SHULKERS.keySet());
    }

    private String serializeLocation(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location parseLocation(String str) {
        String[] parts = str.split(",");
        if (parts.length != 4) return null;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
