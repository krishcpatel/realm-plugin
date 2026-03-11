package com.krishcpatel.realm.jobs.util;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility for adding coarse selectors such as {@code #LOGS} or {@code #HOSTILE}.
 */
public final class JobActionGroups {
    private JobActionGroups() {
    }

    /**
     * Returns normalized group selectors that apply to a material.
     *
     * @param material material to classify
     * @return immutable set of matching selectors
     */
    public static Set<String> forMaterial(Material material) {
        Set<String> groups = new HashSet<>();

        if (Tag.LOGS.isTagged(material)) {
            groups.add("#LOGS");
        }
        if (Tag.CROPS.isTagged(material)) {
            groups.add("#CROPS");
        }
        if (Tag.PLANKS.isTagged(material) || Tag.WOODEN_STAIRS.isTagged(material) || Tag.WOODEN_SLABS.isTagged(material)) {
            groups.add("#BUILDING_BLOCKS");
        }
        if (isOre(material)) {
            groups.add("#ORES");
        }
        if (isDiggable(material)) {
            groups.add("#DIGGABLE");
        }
        if (isWeapon(material)) {
            groups.add("#WEAPONS");
        }
        if (isWeapon(material) && isQualityWeapon(material)) {
            groups.add("#QUALITY_WEAPONS");
            groups.add("#QUALITY_GEAR");
        }
        if (isTool(material)) {
            groups.add("#TOOLS");
        }
        if (isTool(material) && isQualityTool(material)) {
            groups.add("#QUALITY_TOOLS");
            groups.add("#QUALITY_GEAR");
        }
        if (isArmor(material)) {
            groups.add("#ARMOR");
        }
        if (isArmor(material) && isQualityArmor(material)) {
            groups.add("#QUALITY_ARMOR");
            groups.add("#QUALITY_GEAR");
        }
        if (isUtilityStation(material)) {
            groups.add("#UTILITY_STATIONS");
        }
        if (isPotion(material)) {
            groups.add("#POTIONS");
        }

        return Set.copyOf(groups);
    }

    /**
     * Returns normalized group selectors that apply to an entity type.
     *
     * @param type entity type to classify
     * @return immutable set of matching selectors
     */
    public static Set<String> forEntity(EntityType type) {
        Set<String> groups = new HashSet<>();

        if (isHostile(type)) {
            groups.add("#HOSTILE");
        }
        if (isFarmAnimal(type)) {
            groups.add("#FARM_ANIMALS");
        }
        if (isFish(type)) {
            groups.add("#FISH");
        }

        return Set.copyOf(groups);
    }

    private static boolean isOre(Material material) {
        return switch (material) {
            case COAL_ORE, DEEPSLATE_COAL_ORE,
                    COPPER_ORE, DEEPSLATE_COPPER_ORE,
                    IRON_ORE, DEEPSLATE_IRON_ORE,
                    GOLD_ORE, DEEPSLATE_GOLD_ORE,
                    REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE,
                    LAPIS_ORE, DEEPSLATE_LAPIS_ORE,
                    DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                    EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                    NETHER_GOLD_ORE, NETHER_QUARTZ_ORE, ANCIENT_DEBRIS -> true;
            default -> false;
        };
    }

    private static boolean isDiggable(Material material) {
        return switch (material) {
            case DIRT, GRASS_BLOCK, COARSE_DIRT, ROOTED_DIRT, MUD, CLAY, GRAVEL, SAND, RED_SAND, SOUL_SAND, SOUL_SOIL -> true;
            default -> false;
        };
    }

    private static boolean isWeapon(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || material == Material.BOW
                || material == Material.CROSSBOW
                || material == Material.TRIDENT
                || material == Material.MACE;
    }

    private static boolean isQualityWeapon(Material material) {
        String name = material.name();
        return name.startsWith("IRON_")
                || name.startsWith("DIAMOND_")
                || name.startsWith("NETHERITE_")
                || material == Material.BOW
                || material == Material.CROSSBOW
                || material == Material.TRIDENT
                || material == Material.MACE;
    }

    private static boolean isTool(Material material) {
        String name = material.name();
        return name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_AXE");
    }

    private static boolean isQualityTool(Material material) {
        String name = material.name();
        return name.startsWith("IRON_")
                || name.startsWith("DIAMOND_")
                || name.startsWith("NETHERITE_");
    }

    private static boolean isArmor(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS");
    }

    private static boolean isQualityArmor(Material material) {
        String name = material.name();
        return name.startsWith("IRON_")
                || name.startsWith("DIAMOND_")
                || name.startsWith("NETHERITE_")
                || material == Material.TURTLE_HELMET;
    }

    private static boolean isUtilityStation(Material material) {
        return switch (material) {
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                    BLAST_FURNACE, BREWING_STAND, CARTOGRAPHY_TABLE, COMPOSTER,
                    ENCHANTING_TABLE, FLETCHING_TABLE, FURNACE, GRINDSTONE,
                    LOOM, SMITHING_TABLE, SMOKER, STONECUTTER -> true;
            default -> false;
        };
    }

    private static boolean isPotion(Material material) {
        return material == Material.POTION
                || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION;
    }

    private static boolean isHostile(EntityType type) {
        return switch (type) {
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED, SKELETON, STRAY, WITHER_SKELETON,
                    SPIDER, CAVE_SPIDER, CREEPER, ENDERMAN, BLAZE, GHAST, MAGMA_CUBE,
                    SLIME, WITCH, PILLAGER, VINDICATOR, EVOKER, RAVAGER, PHANTOM,
                    GUARDIAN, ELDER_GUARDIAN, SHULKER, PIGLIN_BRUTE, HOGLIN, ZOGLIN -> true;
            default -> false;
        };
    }

    private static boolean isFarmAnimal(EntityType type) {
        return switch (type) {
            case COW, SHEEP, PIG, CHICKEN, RABBIT, MOOSHROOM, HORSE, DONKEY, MULE, GOAT, CAMEL, LLAMA -> true;
            default -> false;
        };
    }

    private static boolean isFish(EntityType type) {
        return switch (type) {
            case COD, SALMON, TROPICAL_FISH, PUFFERFISH -> true;
            default -> false;
        };
    }
}
