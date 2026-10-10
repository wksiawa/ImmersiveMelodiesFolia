package net.conczi.immersivemelodies.mob;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentItems;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDropItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * 生物乐器相关事件：
 * 1. 自然生成时按概率装备乐器（原模组 EntityEquiper）
 * 2. 持有乐器的生物不攻击玩家（服务端适配，原模组怪物仍会攻击）
 * 3. 右键夺取生物手中的乐器（原模组 MobEntityMixin.interact）
 */
public class MobInstrumentListener implements Listener {

    private final ImmersiveMelodiesPlugin plugin;

    public MobInstrumentListener(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /** 生物生成：按配置概率装备随机乐器 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        // 仅自然生成/刷怪笼等触发，避免 /summon 等命令也被装备
        switch (event.getSpawnReason()) {
            case NATURAL, SPAWNER, CHUNK_GEN, NETHER_PORTAL, DISPENSE_EGG, JOCKEY -> { }
            default -> { return; }
        }
        if (plugin.getMobInstrumentManager().shouldEquip(entity)) {
            ItemStack instrument = plugin.getMobInstrumentManager().equipRandomInstrument(entity);
            if (instrument != null) {
                // 启动自动演奏（稍延迟，等生成完成）
                InstrumentType type = InstrumentItems.getInstrument(instrument);
                if (type != null) {
                    entity.getScheduler().runDelayed(plugin, t -> {
                        if (entity.isValid() && !entity.isDead()) {
                            plugin.getMobPlaybackManager().startRandom(entity, type);
                        }
                    }, null, 20L);
                }
            }
        }
    }

    /** 持有乐器的生物不攻击玩家（仅当 mob-instrument-peaceful = true） */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!plugin.getConfig().getBoolean("mob-instrument-peaceful", true)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        if (!(event.getTarget() instanceof Player)) {
            return;
        }
        if (plugin.getMobInstrumentManager().hasInstrument(entity)) {
            event.setCancelled(true);
        }
    }

    /**
     * 右键夺取生物手中的乐器（对应原模组 MobEntityMixin.interact）。
     * 将乐器从生物主手移除并掉落到地面，玩家可拾取。
     */
    @EventHandler
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof LivingEntity entity)) {
            return;
        }
        EntityEquipment eq = entity.getEquipment();
        if (eq == null) {
            return;
        }
        ItemStack hand = eq.getItemInMainHand();
        if (!InstrumentItems.isInstrument(hand)) {
            return;
        }
        // 停止该生物的演奏
        plugin.getMobPlaybackManager().stop(entity);
        // 掉落乐器到地面（与原模组 spawnAtLocation 一致）
        entity.getWorld().dropItemNaturally(entity.getLocation(), hand.clone());
        eq.setItemInMainHand(null);
        event.setCancelled(true);
    }

    /** 生物死亡或丢弃物品时，若掉落的是乐器，停止其演奏会话 */
    @EventHandler
    public void onDropItem(EntityDropItemEvent event) {
        if (event.getEntity() instanceof LivingEntity entity
                && InstrumentItems.isInstrument(event.getItemDrop().getItemStack())) {
            plugin.getMobPlaybackManager().stop(entity);
        }
    }
}
