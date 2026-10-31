# Xaero's World Map 1.47.0 区块绘图 API 调查

调查日期：2026-10-28  
适用项目：Natural Disaster（NeoForge 1.21.1）

## 1. 调查范围与方法

本调查只分析项目内的闭源 JAR：

- 文件：[`libs/xaeroworldmap-neoforge-1.21.1-1.47.0.jar`](../libs/xaeroworldmap-neoforge-1.21.1-1.47.0.jar)
- Mod：Xaero's World Map
- Mod 版本：1.47.0
- 环境：Minecraft 1.21.1、NeoForge
- SHA-256：`6A42B6F01BD96B496E5FCC07D4541BAD1A699D9EC8AD58AD40D5DCFE23A7D4C7`

分析方法：

1. 使用 Vineflower 1.10.1 反编译该 JAR。
2. 使用 `javap -public` 核对关键类和方法的字节码签名。
3. 追踪 `GuiMap`、`WorldMapSession`、`MapProcessor` 以及 Xaero 自带模组兼容层的实际调用链。

没有使用外部源码、在线文档或反混淆源码，也没有进行游戏内运行测试。证据定位以类名、方法签名和 JAR 哈希为准；项目文件行号以当前工作区为准。

## 2. 最终结论

**存在不用反射或 Mixin 就能对指定区块绘图的 public 扩展入口：**

```java
WorldMap.mapElementRenderHandler.add(customElementRenderer);
```

通过自定义 `ElementRenderer + ElementReader + ElementRenderProvider`，可以在全屏世界地图上绘制随地图移动和缩放的 16×16 方块区块覆盖层。

但必须准确区分：

- 这是 JAR 暴露的 **public 内部实现扩展点**。
- JAR 中没有独立的 `xaero.map.api` 包，也没有发现第三方高亮注册事件或 IMC 接口。
- 仅凭反编译不能证明作者承诺该接口跨版本稳定，因此应按 1.47.0 做针对性验证。

三条相关机制的结论如下：

| 机制 | 第三方仅使用 public 调用能否完整接入 | 适用性 |
|---|---:|---|
| `WorldMap.mapElementRenderHandler.add(...)` | **可以** | 推荐；全屏世界地图动态覆盖层 |
| `ChunkHighlighter` / `HighlighterRegistry` | **不能正常完成注册** | 适合纹理化区块高亮，但需要 Mixin 在注册表封存前接入 |
| `MapRenderHelper.renderDynamicChunkHighlight(...)` | 可以调用，但它只是绘图辅助函数 | 需要已有正确的渲染上下文，不是持久高亮注册 API |

## 3. 推荐路线：MapElement 动态渲染器

### 3.1 公开注册入口

`xaero.map.WorldMap` 中存在：

```java
public static boolean loaded;
public static WorldMapClientOnly worldMapClientOnly;
public static MapElementRenderHandler mapElementRenderHandler;
```

`xaero.map.element.MapElementRenderHandler` 中存在：

```java
public void add(ElementRenderer<?, ?, ?> renderer) {
    this.renderers.add(renderer);
}
```

处理器在 `WorldMap.loadLater()` 中创建：

```java
mapElementRenderHandler = MapElementRenderHandler.Builder.begin().build();
// ...
loaded = true;
```

因此过早注册会得到 `null`；必须等第二阶段客户端初始化完成。

该入口有真实的跨 Mod 使用证据。Xaero 自己的小地图兼容层在 `RadarRendererWrapperHelper.createWrapper(...)` 中执行：

```java
WorldMap.mapElementRenderHandler.add(
    MinimapElementRendererWrapper.Builder.begin(radarRenderer)
        // ...
        .build()
);
```

这说明 `add(...)` 不是无法触达的内部死代码。

### 3.2 每帧调用链

全屏地图 `GuiMap.render(...)` 调用：

```text
WorldMap.mapElementRenderHandler.render(...)
```

之后每个渲染器的执行链为：

```text
shouldRender
→ preRender
→ provider.begin
→ provider.hasNext / setupContextAndGetNext
→ reader.isHidden / reader.isOnScreen
→ renderElementShadow 或 renderElement
→ provider.end
→ postRender
```

`ElementRenderInfo` 提供绘制所需的关键上下文：

```java
public final Vec3 renderPos;                 // 地图相机坐标
public final double mouseX;
public final double mouseZ;
public final double scale;                  // 世界坐标到屏幕的缩放
public final double screenSizeBasedScale;   // UI 尺寸缩放，不等于世界尺寸缩放
public final boolean cave;
public final float partialTicks;
public final ResourceKey<Level> mapDimension;
```

### 3.3 推荐继承的新式接口

`add(...)` 接受的是 `xaero.map.element.render.ElementRenderer`。第三方可以直接继承：

```text
xaero.map.element.render.ElementRenderer
xaero.map.element.render.ElementReader
xaero.map.element.render.ElementRenderProvider
```

这样可以避开旧式 `MapElementRenderer / MapElementReader / MapElementRenderProvider` 中一组已经标记 `@Deprecated` 的 `int location` 抽象方法。

核心类型关系：

```java
public abstract class ElementRenderer<E, C, R extends ElementRenderer<E, C, R>> {
    protected ElementRenderer(
        C context,
        ElementRenderProvider<E, C> provider,
        ElementReader<E, C, R> reader
    );
}
```

建议的自定义模型：

```text
E = ChunkElement
  - ResourceKey<Level> dimension
  - int chunkX, chunkZ
  - 颜色或灾害状态

C = ChunkRenderContext
  - 当前 renderInfo.mapDimension
  - 当前 cave 状态（需要时）
  - VertexConsumer overlayConsumer
  - Provider 所需的客户端数据快照

R = DisasterChunkRenderer
```

### 3.4 注册时机与去重

`add(...)` 没有去重，也没有公开的 `remove/unregister`。渲染列表会在绘制时原地排序，不是并发集合。因此应当：

- 只在客户端主线程注册；
- 等 `WorldMap.loaded == true` 且 handler 非空；
- 按 handler 实例只注册一次；
- 用 `shouldRender(...)` 或空 Provider 控制启停，而不是反复增删渲染器。

示例骨架：

```java
private static MapElementRenderHandler registeredHandler;

public static void registerIfReady() {
    MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
    if (!WorldMap.loaded || handler == null || registeredHandler == handler) {
        return;
    }

    handler.add(DisasterChunkRenderer.create());
    registeredHandler = handler;
}
```

不要依赖不同 Mod 的 `FMLClientSetupEvent.enqueueWork(...)` 执行顺序。可在客户端 tick 中调用上述惰性注册方法。

Xaero 缺失时，Natural Disaster 仍应正常加载。因此直接引用 Xaero 类型的代码应隔离在客户端可选集成类中，只在 `ModList.get().isLoaded("xaeroworldmap")` 后加载/调用。

## 4. 用 MapElement 绘制指定区块

### 4.1 元素锚点

该框架原生模型偏向“一个世界坐标锚点 + 一个局部图形”，但 Renderer 可以自行绘制任意四边形，所以能够用于区块填色。

单区块元素应把 Reader 锚点放在区块中心：

```java
@Override
public double getRenderX(ChunkElement e, ChunkRenderContext ctx, float partialTicks) {
    return (e.chunkX() << 4) + 8.0;
}

@Override
public double getRenderZ(ChunkElement e, ChunkRenderContext ctx, float partialTicks) {
    return (e.chunkZ() << 4) + 8.0;
}
```

这与 Xaero 自带的区域中心公式一致：区域左右边界相同时，中心就是 `chunkX * 16 + 8`。

### 4.2 维度处理

在 `preRender(...)` 中保存：

```java
context.mapDimension = renderInfo.mapDimension;
```

Reader 的 `isHidden(...)` 排除其他维度：

```java
return !element.dimension().equals(context.mapDimension);
```

若元素坐标已经是“当前显示地图维度”的方块坐标，可仿照 OPAC 的区域结果渲染器：

```java
@Override
public boolean shouldBeDimScaled() {
    return false;
}
```

若启用维度缩放，则元素位置和色块边长必须使用同一 divisor，不能只缩放其中一个。

### 4.3 色块缩放

地图元素的局部图形默认保持屏幕尺寸，这是点状图标所需的行为。区块覆盖层必须自行乘 `renderInfo.scale`，不能使用 `optionalScale` 或 `screenSizeBasedScale` 代替世界缩放。

一个色块的核心绘制骨架：

```java
@Override
public boolean renderElement(
    ChunkElement element,
    boolean hovered,
    double optionalDepth,
    float optionalScale,
    double partialX,
    double partialY,
    ElementRenderInfo renderInfo,
    GuiGraphics guiGraphics,
    MultiBufferSource.BufferSource buffers,
    MultiTextureRenderTypeRendererProvider rendererProvider
) {
    PoseStack pose = guiGraphics.pose();
    pose.translate(partialX, partialY, 0.0);

    float mapScale = (float) renderInfo.scale;
    pose.scale(mapScale, mapScale, 1.0F);

    MapRenderHelper.fillIntoExistingBuffer(
        pose.last().pose(),
        context.overlayConsumer,
        -8, -8, 8, 8,
        element.red(), element.green(), element.blue(), element.alpha()
    );

    return false;
}
```

因为锚点是区块中心，`[-8, -8, 8, 8]` 对应 16×16 世界方块；经过 `mapScale` 后与地图缩放一致。

半透明填色可以使用：

```java
CustomRenderTypes.MAP_COLOR_OVERLAY
```

该 RenderType 使用 `POSITION_COLOR` 和默认半透明混合。可仿照 OPAC 渲染器，在 `preRender(...)` 从 Xaero 的 `CustomVertexConsumers` 取得 buffer，在 `postRender(...)` 执行 `endBatch()`。

### 4.4 渲染位置与层级

被动色块通常只需要普通 pass：

```java
@Override
public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
    return location == ElementRenderLocation.WORLD_MAP && !shadow;
}
```

`renderElementShadow(...)` 可以留空。`ElementReader.isInteractable(...)` 默认返回 `false`，所以被动色块默认不抢悬停和右键交互。

Renderer 使用 `getOrder()` 排序，默认值为 0。JAR 中的内建顺序包括：

- 雷达：100
- OPAC 区域操作结果：150
- 路径点和玩家：200

灾害填色通常应保持较低顺序，让图标和路径点位于色块之上。

### 4.5 裁剪与交互框

`ElementReader.isOnScreen(...)` 默认根据 Reader 的 `getRenderBox*()` 判断元素是否可见。区块色块大小随 `renderInfo.scale` 改变后，固定的图标包围盒会不准确，可能导致屏幕边缘提前裁剪。

可采用以下任一方案：

1. Provider 只提供已计算为可见的区块，Reader 使用保守的裁剪策略。
2. 覆写 `isOnScreen(...)`，按区块锚点、16 方块尺寸和当前 `scale` 计算。
3. 同步调整 `getRenderBox*()` 以及需要交互时的 `isHoveredOnMap(...)`。

如果启用悬停、tooltip 或右键菜单，交互框也必须使用同一套区块尺寸逻辑。

## 5. ChunkHighlighter：功能匹配，但公开注册被封存

### 5.1 它原本能做什么

`xaero.map.highlight.AbstractHighlighter` 提供：

```java
public abstract int calculateRegionHash(
    ResourceKey<Level> dimension, int regionX, int regionZ);

public abstract boolean regionHasHighlights(
    ResourceKey<Level> dimension, int regionX, int regionZ);

public abstract boolean chunkIsHighlit(
    ResourceKey<Level> dimension, int chunkX, int chunkZ);

public abstract int[] getChunkHighlitColor(
    ResourceKey<Level> dimension, int chunkX, int chunkZ);
```

`ChunkHighlighter` 进一步要求：

```java
protected abstract int[] getColors(
    ResourceKey<Level> dimension, int chunkX, int chunkZ);
```

返回数组的前五项依次是：

```text
[center, top, right, bottom, left]
```

基类把它们展开成每区块 16×16、共 256 个颜色值；纯色区块只需让五项相同。OPAC 的 `ClaimsHighlighter` 是完整实现样本。

区域坐标含义：

- 一个 region 是 32×32 chunks，即 512×512 blocks。
- `regionX = chunkX >> 5`，局部坐标使用 `& 31`。
- 负坐标应沿用移位和掩码，不能使用截断除法。

颜色整数位布局为：

```text
A = bits 0..7
R = bits 8..15
G = bits 16..23
B = bits 24..31

color = (b << 24) | (g << 16) | (r << 8) | a
```

即 `0xBBGGRRAA`，不是常见的 `0xAARRGGBB`。

构造器 `super(true)` 表示允许覆盖未发现区域，`super(false)` 表示只覆盖已发现区域。

### 5.2 无法正常第三方注册的证据

`WorldMapSession.init(...)` 的实际顺序是：

```text
new HighlighterRegistry
→ 若 OPAC 存在，硬编码注册 ClaimsHighlighter
→ highlightRegistry.end()
→ 构造 MapProcessor
```

而 `HighlighterRegistry.end()` 执行：

```java
this.highlighters = Collections.unmodifiableList(this.highlighters);
```

虽然之后可以通过以下 public getter 取得注册表：

```java
WorldMapSession.getCurrentSession()
    .getMapProcessor()
    .getHighlighterRegistry()
```

但取得时 `end()` 已经执行。再调用：

```java
registry.register(customHighlighter);
```

会在 `List.add(...)` 处抛出 `UnsupportedOperationException`。

反编译树中没有发现：

- 第三方 highlighter 注册事件；
- 其他 `registerHighlighters(...)` 调用；
- `unregister/begin` 等重新开放注册表的接口。

`TestHighlighter` 虽存在，但全 JAR 没有实例化或注册它。

因此，**完整 highlighter 接入需要 Mixin 在 `WorldMapSession.init(...)` 的 `end()` 之前注册，或者使用反射/等价内部修改。不能把 public getter 当作可用的晚注册 API。**

### 5.3 如果接受 Mixin，仍需处理的限制

Highlighter 进入的是地图纹理生成和缓存管线，不是即时逐帧覆盖层：

1. `calculateRegionHash(...)` 必须覆盖所有影响颜色的状态，包括邻块边界、RGB 和 alpha。
2. 数据变化后需要调用当前维度 `DimensionHighlighterHandler` 的：
   - `clearCachedHash(regionX, regionZ)`，或
   - `clearCachedHashes()`。
3. 如果边色读取四个相邻区块，单区块更新需要失效本 region 以及受影响的正交相邻 region。
4. 纹理刷新是异步流程，不是调用后立即重绘。
5. `resultStore` 是共享的可变 `int[256]`，不能并发重入或长期保存返回值。
6. `MapRegionHighlightsPreparer.prepare(...)` 强制客户端主线程；其他缓存结构也不是并发集合。外部更新与失效调用宜集中到客户端主线程，并为回调准备一致的数据快照。
7. 回调只提供维度和坐标，没有 `Level`、Y、caveLayer 或 multiworld 参数。

这条路线适合大量、持久、需要进入地图纹理管线的区块着色；对于频繁变化且只要求全屏地图显示的稀疏灾害覆盖层，缓存维护成本通常不值得。

## 6. `renderDynamicChunkHighlight(...)` 的定位

`xaero.map.graphics.MapRenderHelper` 中存在公开静态方法：

```java
public static void renderDynamicChunkHighlight(
    int fromChunkX,
    int toChunkX,
    int fromChunkZ,
    int toChunkZ,
    int flooredCameraX,
    int flooredCameraZ,
    float sideR, float sideG, float sideB, float sideA,
    float centerR, float centerG, float centerB, float centerA,
    PoseStack matrixStack,
    VertexConsumer overlayBuffer
);
```

它接收闭区间区块坐标，可以绘制区块范围的中心和四边。`GuiMap` 自己用它绘制 OPAC 可认领范围及服务端区块半径。

但它不是“给定区块后便持续显示”的注册 API：

- 需要正确的 `PoseStack`、`VertexConsumer` 和相机坐标；
- 必须在合适的地图渲染阶段调用；
- 在 MapElement 已变换过的局部坐标系中直接套用该方法会重复计入相机位移。

因此，第三方 MapElement Renderer 中更简单、可靠的方式是按本文第 4 节自行绘制局部四边形。

## 7. 对 Natural Disaster 的落地建议

### 推荐方案

使用：

```text
WorldMap.mapElementRenderHandler.add(customRenderer)
```

并实现：

```text
客户端可选集成入口
└── DisasterChunkRenderer
    ├── ChunkRenderContext
    ├── DisasterChunkProvider
    └── DisasterChunkReader
```

Provider 应只提供：

- 当前 `renderInfo.mapDimension` 的区块；
- 当前屏幕范围加必要效果半径后的可见区块；
- 当前客户端数据快照中确实需要显示的元素。

不应：

- 枚举全世界、全部中心或全部历史；
- 对每个方块逐一提交元素；
- 在渲染回调中触发无界计算或强制加载区块；
- 在渲染线程修改共享的灾害状态。

天灾中心按 20 tick 步进变化时，动态 Provider 可以在下一次绘制时读取新快照，不需要维护 Xaero 的区域纹理 hash。服务器权威数据若不能直接由客户端重算，需要先提供合适的客户端同步快照；Xaero 的渲染 API 本身不解决数据同步。

### 方案边界

MapElement 公开 Handler 的位置固定为 `ElementRenderLocation.WORLD_MAP`，所以该入口：

- 会显示在全屏世界地图上；
- 不会自动进入小地图或世界内渲染；
- 不会写入 Xaero 的持久地图纹理；
- 不会自动进入 PNG 导出；
- 不应被当成服务端或客户端共用的逻辑 API。

如果未来需要大量全图持久着色、小地图/导出共享同一颜色数据，再评估 Mixin + ChunkHighlighter。

### 项目现有依赖配置

项目已经具备：

- [`build.gradle`](../build.gradle) 第 29–30 行：Xaero JAR 的 `compileOnly` 和 `runtimeOnly`。
- [`src/main/templates/META-INF/neoforge.mods.toml`](../src/main/templates/META-INF/neoforge.mods.toml) 第 28–33 行：`xaeroworldmap` optional 软依赖。

集成仍应隔离到客户端专用代码。当前 optional 版本范围为 `[1.47.0,)`，而本文只验证 1.47.0；升级 Xaero 后应重新核对公开字段、泛型签名、坐标变换和初始化时序。

## 8. 关键证据类索引

以下均指 JAR 内类：

| 类 | 证据用途 |
|---|---|
| `xaero.map.WorldMap` | `mapElementRenderHandler` 公开字段、`loadLater()` 初始化、`loaded` 标志 |
| `xaero.map.element.MapElementRenderHandler` | 公开 `add(...)`、每帧渲染循环、坐标变换、层级排序 |
| `xaero.map.element.render.ElementRenderer` | 推荐继承的新式渲染器基类 |
| `xaero.map.element.render.ElementReader` | 锚点、裁剪、悬停、tooltip 和右键扩展 |
| `xaero.map.element.render.ElementRenderProvider` | 每帧元素提供器 |
| `xaero.map.element.render.ElementRenderInfo` | 相机、缩放和当前地图维度 |
| `xaero.map.gui.GuiMap` | 全屏地图调用公开 Handler；动态区块辅助函数的实际用例 |
| `xaero.map.mods.minimap.element.RadarRendererWrapperHelper` | 初始化后调用公开 `add(...)` 的跨 Mod 证据 |
| `xaero.map.mods.pac.gui.claim.element.ClaimResultElementRenderer` | context/provider/reader 组合、buffer 获取与刷新、维度缩放范例 |
| `xaero.map.mods.pac.gui.claim.element.ClaimResultElementRenderReader` | 区域中心坐标、维度过滤、包围盒范例 |
| `xaero.map.graphics.MapRenderHelper` | 四边形填色和动态区块高亮辅助函数 |
| `xaero.map.graphics.CustomRenderTypes` | `MAP_COLOR_OVERLAY` 等可用 RenderType |
| `xaero.map.WorldMapSession` | Highlighter 注册后随即 `end()` 的初始化顺序 |
| `xaero.map.highlight.HighlighterRegistry` | `register(...)` 与不可修改列表封存 |
| `xaero.map.highlight.ChunkHighlighter` | 中心/四边颜色展开成 16×16 区块像素 |
| `xaero.map.highlight.DimensionHighlighterHandler` | 颜色混合、区域 hash 缓存与失效接口 |
| `xaero.map.highlight.MapRegionHighlightsPreparer` | 客户端主线程要求和纹理准备过程 |
| `xaero.map.mods.pac.highlight.ClaimsHighlighter` | 区块颜色、邻块边界和区域 hash 的完整范例 |
