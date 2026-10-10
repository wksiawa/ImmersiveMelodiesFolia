package net.conczi.immersivemelodies.mob;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentItems;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * 可演奏生物管理器：
 * - 读取 config.yml 的 mob-instruments 映射（实体类型 → 生成概率），可随时增删改
 * - 自然生成时按概率给生物主手装备随机乐器
 * - 提供合奏搜索（附近是否有同曲目演奏者）
 *
 * 对应原模组 Config.mobInstrumentFactors + EntityEquiper。
 */
public final class MobInstrumentManager {

    private final ImmersiveMelodiesPlugin plugin;
    private final Random random = new Random();
    /** 实体类型 → 生成概率（0~1），仅包含配置中列出的类型 */
    private final Map<EntityType, Double> factors = new EnumMap<>(EntityType.class);

    public MobInstrumentManager(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** 重新从 config.yml 加载 mob-instruments 配置（运行时改配置后调用） */
    public void reload() {
        factors.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("mob-instruments");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            EntityType type = EntityType.fromName(key.replace("minecraft:", ""));
            if (type != null) {
                factors.put(type, section.getDouble(key, 0.0));
            }
        }
    }

    /** 该实体类型是否在可演奏列表中（概率 > 0） */
    public boolean isInstrumentable(EntityType type) {
        Double f = factors.get(type);
        return f != null && f > 0;
    }

    /** 获取某实体类型的生成概率（未配置返回 0） */
    public double getChance(EntityType type) {
        return factors.getOrDefault(type, 0.0);
    }

    /** 随机判定是否应给该生物装备乐器（与原模组 EntityEquiper.shouldEquip 一致） */
    public boolean shouldEquip(LivingEntity entity) {
        if (hasInstrument(entity)) {
            return false;
        }
        double chance = getChance(entity.getType());
        return chance > 0 && random.nextDouble() < chance;
    }

    /** 生物手中是否已有乐器 */
    public boolean hasInstrument(LivingEntity entity) {
        return InstrumentItems.isInstrument(entity.getEquipment() != null
                ? entity.getEquipment().getItemInMainHand() : null)
            || InstrumentItems.isInstrument(entity.getEquipment() != null
                ? entity.getEquipment().getItemInOffHand() : null);
    }

    /**
     * 给生物主手装备一件随机乐器（与原模组 EntityEquiper.equip 一致）。
     * @return 装备的乐器物品，未装备返回 null
     */
    public ItemStack equipRandomInstrument(LivingEntity entity) {
        if (entity.getEquipment() == null) {
            return null;
        }
        InstrumentType[] types = InstrumentType.values();
        InstrumentType type = types[random.nextInt(types.length)];
        ItemStack instrument = InstrumentItems.createItem(type, null);
        entity.getEquipment().setItemInMainHand(instrument);
        // 掉落概率与原模组 mobInstrumentDropFactor 一致（默认装备掉落率）
        entity.getEquipment().setItemInMainHandDropChance(0.085f);
        return instrument;
    }

    /** 获取随机乐器类型（用于 /im mob 等调试命令） */
    public InstrumentType randomInstrumentType() {
        InstrumentType[] types = InstrumentType.values();
        return types[random.nextInt(types.length)];
    }

    /** 当前配置的可演奏生物数量 */
    public int configuredCount() {
        return factors.size();
    }
}
