package net.conczi.immersivemelodies.crafting;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentItems;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

import java.util.List;

/**
 * 注册模组原版 11 种乐器的合成配方。
 * 结果物品通过 CraftEngine API 生成，确保模型与名称正确。
 */
public final class RecipeManager {

    private static final List<Material> PLANKS = List.of(
            Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS,
            Material.JUNGLE_PLANKS, Material.ACACIA_PLANKS, Material.DARK_OAK_PLANKS,
            Material.MANGROVE_PLANKS, Material.CHERRY_PLANKS, Material.BAMBOO_PLANKS,
            Material.CRIMSON_PLANKS, Material.WARPED_PLANKS
    );

    private static final List<Material> LOGS = List.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
            Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG,
            Material.MANGROVE_LOG, Material.CHERRY_LOG, Material.BAMBOO_BLOCK,
            Material.CRIMSON_STEM, Material.WARPED_STEM
    );

    private final ImmersiveMelodiesPlugin plugin;

    public RecipeManager(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册全部配方。
     * 装了 CraftEngine 时，必须等 CE 物品加载完成后再注册，
     * 否则配方结果会退化成普通纸（无模型贴图）。
     *
     * @return true=已注册；false=CE 物品尚未就绪，调用方应稍后重试
     */
    public boolean registerAll() {
        if (plugin.getCraftEngineBridge() != null && plugin.getCraftEngineBridge().isCraftEnginePresent()
                && plugin.getCraftEngineBridge().buildInstrumentItem("flute") == null) {
            plugin.getLogger().warning("CraftEngine 物品尚未加载完成，跳过配方注册（稍后重试）");
            return false;
        }
        doRegister();
        plugin.getLogger().info("已注册 11 种乐器合成配方（结果物品为 CraftEngine 注册物品）");
        return true;
    }

    private void doRegister() {
        shaped("bagpipe", InstrumentType.BAGPIPE, new String[]{"FF ", "LLF", "LLL"})
                .setIngredient('F', new RecipeChoice.ExactChoice(InstrumentItems.createItem(InstrumentType.FLUTE, null)))
                .setIngredient('L', Material.LEATHER)
                .register();

        shaped("didgeridoo", InstrumentType.DIDGERIDOO, new String[]{"P  ", " PL", "  P"})
                .setIngredient('P', new RecipeChoice.MaterialChoice(PLANKS))
                .setIngredient('L', Material.LAPIS_LAZULI)
                .register();

        shaped("ender_bass", InstrumentType.ENDER_BASS, new String[]{"NEN", "   ", "   "})
                .setIngredient('N', Material.NOTE_BLOCK)
                .setIngredient('E', Material.ENDER_PEARL)
                .register();

        shaped("flute", InstrumentType.FLUTE, new String[]{"  B", " S ", "S  "})
                .setIngredient('B', Material.BONE)
                .setIngredient('S', Material.STICK)
                .register();

        shaped("handpan", InstrumentType.HANDPAN, new String[]{"BBB", "CCC", "   "})
                .setIngredient('B', Material.COPPER_INGOT)
                .setIngredient('C', Material.COPPER_BLOCK)
                .register();

        shaped("lute", InstrumentType.LUTE, new String[]{"  I", "PS ", "PP "})
                .setIngredient('I', Material.STICK)
                .setIngredient('P', new RecipeChoice.MaterialChoice(PLANKS))
                .setIngredient('S', Material.STRING)
                .register();

        shaped("piano", InstrumentType.PIANO, new String[]{"BBB", "NNN", "   "})
                .setIngredient('B', Material.BONE)
                .setIngredient('N', Material.NOTE_BLOCK)
                .register();

        shaped("tiny_drum", InstrumentType.TINY_DRUM, new String[]{"LLL", "SWS", "   "})
                .setIngredient('L', Material.LEATHER)
                .setIngredient('S', Material.STRING)
                .setIngredient('W', new RecipeChoice.MaterialChoice(LOGS))
                .register();

        shaped("triangle", InstrumentType.TRIANGLE, new String[]{" C ", "C C", "CCC"})
                .setIngredient('C', Material.COPPER_INGOT)
                .register();

        shaped("trumpet", InstrumentType.TRUMPET, new String[]{"ICC", " IC", "   "})
                .setIngredient('I', Material.IRON_INGOT)
                .setIngredient('C', Material.COPPER_INGOT)
                .register();

        shaped("vielle", InstrumentType.VIELLE, new String[]{"  I", "WS ", "WW "})
                .setIngredient('I', Material.STICK)
                .setIngredient('W', new RecipeChoice.MaterialChoice(LOGS))
                .setIngredient('S', Material.STRING)
                .register();
    }

    private Builder shaped(String key, InstrumentType type, String[] pattern) {
        return new Builder(key, type, pattern);
    }

    private class Builder {
        private final ShapedRecipe recipe;

        Builder(String key, InstrumentType type, String[] pattern) {
            NamespacedKey nsKey = new NamespacedKey(plugin, key);
            ItemStack result = InstrumentItems.createItem(type, null);
            this.recipe = new ShapedRecipe(nsKey, result);
            this.recipe.shape(pattern);
        }

        Builder setIngredient(char key, Material material) {
            recipe.setIngredient(key, material);
            return this;
        }

        Builder setIngredient(char key, RecipeChoice choice) {
            recipe.setIngredient(key, choice);
            return this;
        }

        void register() {
            if (plugin.getServer().getRecipe(recipe.getKey()) != null) {
                plugin.getServer().removeRecipe(recipe.getKey());
            }
            plugin.getServer().addRecipe(recipe);
        }
    }
}
