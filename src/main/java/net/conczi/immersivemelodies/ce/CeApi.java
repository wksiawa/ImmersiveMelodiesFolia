package net.conczi.immersivemelodies.ce;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.item.CustomItem;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * CraftEngine API 接入层。
 * 仅在 CraftEngine 已启用时才会被调用（由 CraftEngineBridge.isActive 保证），
 * CE 不存在时本类不会被加载，避免 NoClassDefFoundError。
 */
public final class CeApi {

    private static final String NAMESPACE = "immersive_melodies";

    private CeApi() {}

    public static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("CraftEngine");
    }

    /**
     * 从 CraftEngine 注册表中构建乐器物品（含 CE 配置的贴图/模型/数据）。
     * @param instrumentId 乐器 ID，如 "flute"
     * @return CE 构建的 ItemStack；CE 中尚未加载该物品时返回 null
     */
    public static ItemStack buildItem(String instrumentId) {
        CustomItem<ItemStack> item = CraftEngineItems.byId(Key.of(NAMESPACE, instrumentId));
        if (item == null) return null;
        return item.buildItemStack();
    }

    /**
     * 反查 ItemStack 对应的 CE 物品 ID。
     * @return 乐器 ID（如 "flute"）；不是本命名空间的 CE 物品时返回 null
     */
    public static String itemIdOf(ItemStack stack) {
        Key key = CraftEngineItems.getCustomItemId(stack);
        if (key == null) return null;
        String id = key.toString();
        String prefix = NAMESPACE + ":";
        return id.startsWith(prefix) ? id.substring(prefix.length()) : null;
    }
}
