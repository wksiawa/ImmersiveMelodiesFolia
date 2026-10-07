package net.conczi.immersivemelodies.instrument;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;

/**
 * 乐器物品工具类。
 * 物品材质由 CraftEngine 通过 CustomModelData 实现（基准值 10000 + 乐器序号）。
 * 逻辑识别使用 PDC。
 */
public final class InstrumentItems {

    public static final int CUSTOM_MODEL_DATA_BASE = 10000;

    private static final NamespacedKey KEY_INSTRUMENT =
            NamespacedKey.fromString("immersive_melodies:instrument");
    private static final NamespacedKey KEY_PLAYING =
            NamespacedKey.fromString("immersive_melodies:playing");
    private static final NamespacedKey KEY_MELODY =
            NamespacedKey.fromString("immersive_melodies:melody");

    private InstrumentItems() {
    }

    /** CraftEngine 桥接（由插件启动时注入，CE 不存在时为 null） */
    private static net.conczi.immersivemelodies.ce.CraftEngineBridge ceBridge;

    public static void init(net.conczi.immersivemelodies.ce.CraftEngineBridge bridge) {
        ceBridge = bridge;
    }

    /**
     * 创建乐器物品（供 /im give 使用）。
     * CE 已注册该乐器时，以 CE 构建的物品为基础（获得 CE 的模型/数据），
     * 再叠加本插件的 PDC 逻辑标记。
     */
    public static ItemStack createItem(InstrumentType type, String melodyName) {
        ItemStack stack = null;
        boolean ceItem = false;
        if (ceBridge != null) {
            stack = ceBridge.buildInstrumentItem(type.getId());
            ceItem = stack != null;
        }
        if (stack == null) {
            stack = new ItemStack(Material.PAPER);
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        if (!meta.hasDisplayName()) {
            meta.displayName(Component.text(formatName(type), NamedTextColor.GOLD));
        }
        // CMD 10000 仅用于无 CE 的回退纸物品；CE 物品使用自身的数据组件，不能覆盖
        if (!ceItem && !meta.hasCustomModelData()) {
            meta.setCustomModelData(CUSTOM_MODEL_DATA_BASE + type.ordinal());
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_INSTRUMENT, PersistentDataType.STRING, type.name());
        pdc.set(KEY_PLAYING, PersistentDataType.BOOLEAN, false);
        if (melodyName != null) {
            pdc.set(KEY_MELODY, PersistentDataType.STRING, melodyName);
        }

        stack.setItemMeta(meta);
        return stack;
    }

    public static boolean isInstrument(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.getPersistentDataContainer().has(KEY_INSTRUMENT, PersistentDataType.STRING)) {
            return true;
        }
        // CE 识别：通过 CE API 反查（例如 /ce give 直接给出的乐器）
        return getInstrumentViaCe(stack) != null;
    }

    public static InstrumentType getInstrument(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String value = meta.getPersistentDataContainer().get(KEY_INSTRUMENT, PersistentDataType.STRING);
            InstrumentType type = InstrumentType.fromKey(value);
            if (type != null) {
                return type;
            }
        }
        return getInstrumentViaCe(stack);
    }

    /** 通过 CE API 反查乐器类型（CE 不存在或不是 CE 乐器时返回 null） */
    private static InstrumentType getInstrumentViaCe(ItemStack stack) {
        if (ceBridge == null) {
            return null;
        }
        String id = ceBridge.detectInstrumentId(stack);
        return id == null ? null : InstrumentType.fromKey(id);
    }

    public static boolean isPlaying(ItemStack stack) {
        if (!isInstrument(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        return Boolean.TRUE.equals(meta.getPersistentDataContainer()
                .get(KEY_PLAYING, PersistentDataType.BOOLEAN));
    }

    public static void setPlaying(ItemStack stack, boolean playing) {
        if (!isInstrument(stack)) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(KEY_PLAYING, PersistentDataType.BOOLEAN, playing);
        stack.setItemMeta(meta);
    }

    public static String getMelody(ItemStack stack) {
        if (!isInstrument(stack)) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(KEY_MELODY, PersistentDataType.STRING);
    }

    public static void setMelody(ItemStack stack, String melodyName) {
        if (!isInstrument(stack)) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(KEY_MELODY, PersistentDataType.STRING, melodyName);
        stack.setItemMeta(meta);
    }

    /**
     * 从玩家主手或副手寻找乐器（优先主手）。
     */
    public static ItemStack findInstrumentInHands(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (isInstrument(main)) {
            return main;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (isInstrument(off)) {
            return off;
        }
        return null;
    }

    private static String formatName(InstrumentType type) {
        String lower = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : lower.toCharArray()) {
            if (upper && Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
            if (c == ' ') {
                upper = true;
            }
        }
        return sb.toString();
    }
}
