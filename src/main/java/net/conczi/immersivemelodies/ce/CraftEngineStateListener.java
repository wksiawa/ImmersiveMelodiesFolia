package net.conczi.immersivemelodies.ce;

import net.conczi.immersivemelodies.ImmersiveMelodiesPlugin;
import net.conczi.immersivemelodies.crafting.RecipeManager;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 监听 CraftEngine 配置加载/重载完成事件。
 * 必须在 CE 物品注册完成后再登记合成配方，配方结果才能携带正确的 CE 模型数据。
 * 本类直接引用 CE API，仅当 CraftEngine 存在时才会被注册，避免 NoClassDefFoundError。
 */
public final class CraftEngineStateListener implements Listener {

    private final ImmersiveMelodiesPlugin plugin;

    public CraftEngineStateListener(ImmersiveMelodiesPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        // CE 重载后物品注册表已刷新，重新登记全部配方（先移除旧的同名配方）
        new RecipeManager(plugin).registerAll();
    }
}
