package net.conczi.immersivemelodies.listener;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.instrument.InstrumentItems;
import net.conczi.immersivemelodies.instrument.InstrumentType;
import net.conczi.immersivemelodies.melody.Melody;
import net.conczi.immersivemelodies.play.PlaybackSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 监听玩家与乐器物品的交互。
 */
public class InstrumentListener implements Listener {

    private final ImmersiveMelodiesPlugin plugin;

    public InstrumentListener(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 右键使用乐器：
     * - 若未绑定旋律，打开 GUI 让玩家选择
     * - 若已绑定，开始/停止演奏
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack stack = event.getItem();
        if (stack == null || !InstrumentItems.isInstrument(stack)) {
            return;
        }

        event.setCancelled(true);

        // 正在演奏时右键 = 停止（清理双手所有乐器状态）
        if (plugin.getPlaybackManager().isPlaying(player)) {
            plugin.getPlaybackManager().stop(player);
            ItemStack main = player.getInventory().getItemInMainHand();
            if (InstrumentItems.isInstrument(main)) InstrumentItems.setPlaying(main, false);
            ItemStack off = player.getInventory().getItemInOffHand();
            if (InstrumentItems.isInstrument(off)) InstrumentItems.setPlaying(off, false);
            player.sendActionBar(Component.text("已停止演奏", NamedTextColor.YELLOW));
            return;
        }

        // 未在演奏：打开乐谱 GUI（与原模组一致）
        openMelodySelector(player, stack);
    }

    /**
     * 切换快捷栏：若从手中移走了正在演奏的乐器，则停止。
     */
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        if (previous != null && InstrumentItems.isInstrument(previous) && InstrumentItems.isPlaying(previous)) {
            InstrumentItems.setPlaying(previous, false);
            plugin.getPlaybackManager().stop(player);
        }
    }

    /**
     * 打开乐谱选择 GUI（复刻原模组界面：paper.png 背景 + 曲目列表）。
     */
    private void openMelodySelector(Player player, ItemStack stack) {
        InstrumentType instrument = InstrumentItems.getInstrument(stack);
        if (instrument == null) {
            return;
        }
        plugin.getMelodyGui().open(player, instrument, 0);
    }
}
