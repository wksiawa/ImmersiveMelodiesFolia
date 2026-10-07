package net.conczi.immersivemelodies;

import net.conczi.immersivemelodies.ce.CraftEngineBridge;
import net.conczi.immersivemelodies.command.ImCommand;
import net.conczi.immersivemelodies.crafting.RecipeManager;
import net.conczi.immersivemelodies.hud.HudManager;
import net.conczi.immersivemelodies.listener.InstrumentListener;
import net.conczi.immersivemelodies.listener.UploadListener;
import net.conczi.immersivemelodies.melody.MelodyManager;
import net.conczi.immersivemelodies.pack.ResourcePackBuilder;
import net.conczi.immersivemelodies.play.PlaybackManager;
import net.conczi.immersivemelodies.text.TextAssetGenerator;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class ImmersiveMelodiesPlugin extends JavaPlugin {

    private static ImmersiveMelodiesPlugin instance;
    private MelodyManager melodyManager;
    private PlaybackManager playbackManager;
    private HudManager hudManager;
    private CraftEngineBridge craftEngineBridge;
    private TextAssetGenerator textAssetGenerator;
    private net.conczi.immersivemelodies.gui.MelodyGui melodyGui;

    public static ImmersiveMelodiesPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.melodyManager = new MelodyManager(this);
        this.melodyManager.loadAll();

        this.playbackManager = new PlaybackManager(this);
        this.hudManager = new HudManager(this);

        // 资源包：生成独立 zip；若装了 CraftEngine 则同时注入其资源包目录
        ResourcePackBuilder packBuilder = new ResourcePackBuilder(this);
        packBuilder.build();

        this.craftEngineBridge = new CraftEngineBridge(this);
        craftEngineBridge.setup();
        // 将 CE 桥接注入物品工具类：/im give 走 CE 注册物品，识别支持 CE 反查
        net.conczi.immersivemelodies.instrument.InstrumentItems.init(craftEngineBridge);

        // 字体图集生成（CE 注入之后执行）：裁剪 GUI 背景/按钮图标、渲染歌名字贴、生成 hud.json
        this.textAssetGenerator = new TextAssetGenerator(this);
        textAssetGenerator.generateAll();

        this.melodyGui = new net.conczi.immersivemelodies.gui.MelodyGui(this);
        getServer().getPluginManager().registerEvents(melodyGui, this);

        getServer().getPluginManager().registerEvents(new InstrumentListener(this), this);
        getServer().getPluginManager().registerEvents(new UploadListener(this), this);

        // 合成配方：必须等 CraftEngine 物品注册完成后再登记，
        // 否则配方结果退化为普通纸（无模型贴图）。
        registerRecipesWhenReady();

        PluginCommand command = Objects.requireNonNull(getCommand("immersivemelodies"), "immersivemelodies command missing");
        ImCommand executor = new ImCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        // CE 已加载时重载一次，使 hud.json/字贴/按钮模型进入资源包（仅当 CE 处于启用状态）
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            Bukkit.getGlobalRegionScheduler().run(this, task ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ce reload all"));
        }

        getLogger().info("ImmersiveMelodies Folia 已启用，加载了 " + melodyManager.getMelodyNames().size() + " 首旋律");
    }

    @Override
    public void onDisable() {
        if (playbackManager != null) {
            playbackManager.stopAll();
        }
        instance = null;
    }

    /**
     * 在 CraftEngine 物品就绪后注册合成配方。
     * 策略：
     * 1) 监听 CraftEngineReloadEvent（CE 每次加载/重载完成时触发）；
     * 2) 同时轮询兜底（每 20 tick 检查 CE 物品是否可构建，最多 2 分钟）；
     * 3) 未安装 CE 时立即注册（结果为回退纸物品）。
     */
    private void registerRecipesWhenReady() {
        RecipeManager recipeManager = new RecipeManager(this);
        if (craftEngineBridge == null || !craftEngineBridge.isCraftEnginePresent()) {
            recipeManager.registerAll();
            return;
        }
        // 直接引用 CE API 的监听器仅在 CE 存在时注册
        getServer().getPluginManager().registerEvents(
                new net.conczi.immersivemelodies.ce.CraftEngineStateListener(this), this);

        final int[] attempts = {0};
        final int maxAttempts = 120; // 120 × 20 tick ≈ 2 分钟
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            attempts[0]++;
            if (craftEngineBridge.buildInstrumentItem("flute") != null) {
                recipeManager.registerAll();
                task.cancel();
            } else if (attempts[0] >= maxAttempts) {
                getLogger().warning("等待 CraftEngine 物品注册超时，合成配方未登记");
                task.cancel();
            }
        }, 1L, 20L);
    }

    public MelodyManager getMelodyManager() {
        return melodyManager;
    }

    public PlaybackManager getPlaybackManager() {
        return playbackManager;
    }

    public HudManager getHudManager() {
        return hudManager;
    }

    public CraftEngineBridge getCraftEngineBridge() {
        return craftEngineBridge;
    }

    public TextAssetGenerator getTextAssetGenerator() {
        return textAssetGenerator;
    }

    public net.conczi.immersivemelodies.gui.MelodyGui getMelodyGui() {
        return melodyGui;
    }
}
