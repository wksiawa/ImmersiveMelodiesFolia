# ImmersiveMelodies Folia

[Immersive Melodies](https://github.com/Luke100000/ImmersiveMelodies) 0.8.0（Minecraft 1.21.1 Fabric 模组）的服务端移植版，运行于 **Folia / Paper 1.21.11**，玩家无需安装任何模组。

原模组作者：**Luke100000**（GPL-3.0）。本项目为非官方衍生作品，遵循相同协议开源。

## 特性

- **11 种乐器**：风笛、迪吉里杜管、长笛、琉特琴、钢琴、三角铁、小号、小鼓、提琴、末影贝斯、手碟
- 乐器材质、3D 手持模型、GUI 图标、音效**全部取自原版模组**，经 [CraftEngine](https://github.com/Xiao-MoMi/CraftEngine) 自托管资源包自动下发
- 右键乐器打开乐谱选择界面（羊皮纸背景 + 模组原版按钮图集 + 歌名字体贴渲染 + 上下滚动）
- 55 首内置 MIDI 旋律，支持聊天栏粘贴直链**上传自定义 MIDI**
- 11 种乐器的**原版合成配方**
- 乐器出现在创造模式物品栏（装备分类），可搜索
- Folia 区域调度器适配，线程安全

## 依赖

| 依赖 | 版本 | 说明 |
|---|---|---|
| Folia / Paper | 1.21.11 | `folia-supported: true` |
| CraftEngine | 0.0.67+ | 软依赖；未安装时乐器退化为自定义 ModelData 纸物品 |
| Java | 21 | 编译与运行 |

## 构建

```bash
mvn package
```

产物：`target/ImmersiveMelodiesFolia-0.8.0.jar`

## 安装

1. 将 CraftEngine 与本插件 jar 放入 `plugins/`
2. 启动服务器：插件会自动向 CraftEngine 注入乐器/按钮配置、模型、贴图、音效、字体与语言文件，并触发资源包重新生成
3. 资源包由 CraftEngine 自托管分发（默认 8163 端口）；对外开放服务器时需在 CraftEngine 配置中将托管地址改为公网 IP/域名

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/im give <乐器>` | `immersivemelodies.admin` | 获取乐器 |
| `/im list` | `immersivemelodies.use` | 列出旋律 |
| `/im play <旋律>` | `immersivemelodies.use` | 演奏 |
| `/im stop` | `immersivemelodies.use` | 停止演奏 |
| `/im reload` | `immersivemelodies.admin` | 重载旋律 |

## 上传自定义音乐

在乐谱界面点击上传按钮，60 秒内在聊天栏发送 `.mid/.midi` 文件的直链（需为可直接下载的 URL），插件会下载、解析并即时加入列表。

## 目录结构

```
src/main/java/net/conczi/immersivemelodies/
  ce/          CraftEngine API 桥接、配置生成、重载监听
  crafting/    11 种乐器合成配方（CE 物品就绪后注册）
  gui/         乐谱选择界面
  hud/         演奏 HUD
  instrument/  乐器物品/类型
  listener/    乐器交互、MIDI 上传
  melody/      MIDI 解析与旋律管理
  pack/        资源包构建与注入
  play/        演奏调度
  text/        歌名字体贴/背景/按钮图集运行时生成
src/main/resources/
  pack/assets/immersive_melodies/  原版模组模型/贴图/音效/字体/语言
  melodies/                        内置 55 首 MIDI
```

## 协议

[GPL-3.0](LICENSE)，与原模组 Immersive Melodies 保持一致。所有美术与音频资源版权归原作者 Luke100000 所有。
