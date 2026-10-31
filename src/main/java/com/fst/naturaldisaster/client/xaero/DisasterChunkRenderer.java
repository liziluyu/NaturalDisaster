package com.fst.naturaldisaster.client.xaero;

import com.fst.naturaldisaster.client.ClientDisasterState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * Draws translucent 16x16 block quads over disaster chunks: yellow during the warning phase and
 * red while the disaster's effect is active (growing or decaying).
 *
 * <p>This class and its nested types directly reference Xaero's map-element API, so it must only be
 * loaded from the guarded, client-only integration entry point. The provider serves chunks from the
 * immutable {@link ClientDisasterState} snapshot, clipped to the current map dimension and to the
 * visible map rectangle; the renderer itself never queries the world, loads chunks or mutates the
 * snapshot.
 */
public final class DisasterChunkRenderer
        extends ElementRenderer<DisasterChunkRenderer.ChunkElement, DisasterChunkRenderer.RenderContext, DisasterChunkRenderer> {
    private static final float WARNING_RED = 1.0F;
    private static final float WARNING_GREEN = 1.0F;
    private static final float WARNING_BLUE = 0.0F;
    private static final float EFFECT_RED = 1.0F;
    private static final float EFFECT_GREEN = 0.0F;
    private static final float EFFECT_BLUE = 0.0F;
    private static final float ALPHA = 0.35F;
    private static final int ORDER = -100;
    private static final double CHUNK_HALF_SIZE = 8.0;

    private final RenderContext renderContext;

    private DisasterChunkRenderer(RenderContext context, ElementRenderProvider<ChunkElement, RenderContext> provider,
                                  ElementReader<ChunkElement, RenderContext, DisasterChunkRenderer> reader) {
        super(context, provider, reader);
        this.renderContext = context;
    }

    public static DisasterChunkRenderer create() {
        RenderContext context = new RenderContext();
        return new DisasterChunkRenderer(context, new Provider(), new Reader());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public boolean shouldBeDimScaled() {
        // Chunk coordinates are already in the displayed map dimension's block space.
        return false;
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
        return location == ElementRenderLocation.WORLD_MAP && !shadow;
    }

    @Override
    public void preRender(ElementRenderInfo renderInfo, MultiBufferSource.BufferSource buffers,
                          MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
        double scale = renderInfo.scale > 0.0 ? renderInfo.scale : 1.0;
        renderContext.mapDimension = renderInfo.mapDimension;
        renderContext.scale = scale;
        renderContext.cameraX = renderInfo.renderPos.x;
        renderContext.cameraZ = renderInfo.renderPos.z;

        double halfWidth = Minecraft.getInstance().getWindow().getWidth() / (2.0 * scale) + 32.0;
        double halfHeight = Minecraft.getInstance().getWindow().getHeight() / (2.0 * scale) + 32.0;
        renderContext.minBlockX = renderContext.cameraX - halfWidth;
        renderContext.maxBlockX = renderContext.cameraX + halfWidth;
        renderContext.minBlockZ = renderContext.cameraZ - halfHeight;
        renderContext.maxBlockZ = renderContext.cameraZ + halfHeight;
        renderContext.overlay = buffers.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY);
    }

    @Override
    public void postRender(ElementRenderInfo renderInfo, MultiBufferSource.BufferSource buffers,
                           MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
        buffers.endBatch();
    }

    @Override
    public void renderElementShadow(ChunkElement element, boolean hovered, float optionalScale,
                                    double partialX, double partialY, ElementRenderInfo renderInfo,
                                    GuiGraphics guiGraphics, MultiBufferSource.BufferSource buffers,
                                    MultiTextureRenderTypeRendererProvider rendererProvider) {
        // The overlay is a passive fill; it never casts an icon shadow.
    }

    @Override
    public boolean renderElement(ChunkElement element, boolean hovered, double optionalDepth, float optionalScale,
                                 double partialX, double partialY, ElementRenderInfo renderInfo,
                                 GuiGraphics guiGraphics, MultiBufferSource.BufferSource buffers,
                                 MultiTextureRenderTypeRendererProvider rendererProvider) {
        VertexConsumer overlay = renderContext.overlay;
        if (overlay == null) {
            return false;
        }

        float scale = (float) renderContext.scale;
        boolean effect = element.effect();
        float red = effect ? EFFECT_RED : WARNING_RED;
        float green = effect ? EFFECT_GREEN : WARNING_GREEN;
        float blue = effect ? EFFECT_BLUE : WARNING_BLUE;
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(partialX, partialY, 0.0);
        pose.scale(scale, scale, 1.0F);
        MapRenderHelper.fillIntoExistingBuffer(pose.last().pose(), overlay,
                (int) -CHUNK_HALF_SIZE, (int) -CHUNK_HALF_SIZE,
                (int) CHUNK_HALF_SIZE, (int) CHUNK_HALF_SIZE,
                red, green, blue, ALPHA);
        pose.popPose();
        return false;
    }

    /**
     * One active chunk, anchored at its centre for rendering. The provider reuses a single instance
     * because the map handler consumes it immediately; this keeps the per-frame path allocation-free.
     */
    static final class ChunkElement {
        private ResourceKey<Level> dimension;
        private int chunkX;
        private int chunkZ;
        private boolean effect;

        void set(ResourceKey<Level> dimension, int chunkX, int chunkZ, boolean effect) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.effect = effect;
        }

        boolean effect() {
            return effect;
        }

        ResourceKey<Level> dimension() {
            return dimension;
        }

        int chunkX() {
            return chunkX;
        }

        int chunkZ() {
            return chunkZ;
        }
    }

    /** Per-pass state shared by the provider, reader and renderer. */
    static final class RenderContext {
        ResourceKey<Level> mapDimension;
        double scale = 1.0;
        double cameraX;
        double cameraZ;
        double minBlockX;
        double maxBlockX;
        double minBlockZ;
        double maxBlockZ;
        VertexConsumer overlay;
        final ChunkElement element = new ChunkElement();
        long[] warningChunks;
        long[] effectChunks;
        int cursor;
        boolean effectPass;
        boolean hasCurrent;
    }

    /**
     * Supplies the visible subset of the current dimension's snapshot for one render pass.
     * The snapshot itself is bounded on the server; this only clips it to the map view.
     */
    private static final class Provider extends ElementRenderProvider<ChunkElement, RenderContext> {
        @Override
        public void begin(ElementRenderLocation location, RenderContext context) {
            context.cursor = 0;
            context.effectPass = false;
            context.hasCurrent = false;
            if (context.mapDimension == null) {
                context.warningChunks = null;
                context.effectChunks = null;
            } else {
                context.warningChunks = ClientDisasterState.warningChunks(context.mapDimension);
                context.effectChunks = ClientDisasterState.effectChunks(context.mapDimension);
            }
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, RenderContext context) {
            if (context.hasCurrent) {
                return true;
            }
            while (true) {
                long[] chunks = context.effectPass ? context.effectChunks : context.warningChunks;
                while (chunks != null && context.cursor < chunks.length) {
                    long packed = chunks[context.cursor++];
                    int chunkX = ChunkPos.getX(packed);
                    int chunkZ = ChunkPos.getZ(packed);
                    double minX = chunkX * 16.0;
                    double minZ = chunkZ * 16.0;
                    double maxX = minX + 16.0;
                    double maxZ = minZ + 16.0;
                    if (maxX < context.minBlockX || minX > context.maxBlockX
                            || maxZ < context.minBlockZ || minZ > context.maxBlockZ) {
                        continue;
                    }
                    context.element.set(context.mapDimension, chunkX, chunkZ, context.effectPass);
                    context.hasCurrent = true;
                    return true;
                }
                if (context.effectPass) {
                    return false;
                }
                // Effect chunks are drawn after warning chunks, so red always wins an overlap.
                context.effectPass = true;
                context.cursor = 0;
            }
        }

        @Override
        public ChunkElement getNext(ElementRenderLocation location, RenderContext context) {
            context.hasCurrent = false;
            return context.element;
        }

        @Override
        public void end(ElementRenderLocation location, RenderContext context) {
            context.warningChunks = null;
            context.effectChunks = null;
            context.hasCurrent = false;
            context.effectPass = false;
            context.cursor = 0;
        }
    }

    /**
     * Anchors elements at chunk centres and culls by the scaled block rectangle instead of a
     * fixed icon box, so blocks are not clipped early at the map edges.
     */
    private static final class Reader extends ElementReader<ChunkElement, RenderContext, DisasterChunkRenderer> {
        @Override
        public boolean isHidden(ChunkElement element, RenderContext context) {
            return !element.dimension().equals(context.mapDimension);
        }

        @Override
        public double getRenderX(ChunkElement element, RenderContext context, float partialTicks) {
            return element.chunkX() * 16.0 + 8.0;
        }

        @Override
        public double getRenderZ(ChunkElement element, RenderContext context, float partialTicks) {
            return element.chunkZ() * 16.0 + 8.0;
        }

        @Override
        public boolean isOnScreen(ChunkElement element, double cameraX, double cameraZ, int width, int height,
                                  double scale, double screenSizeBasedScale, double dimScale,
                                  RenderContext context, float partialTicks) {
            double safeScale = scale > 0.0 ? scale : 1.0;
            double screenX = (getRenderX(element, context, partialTicks) - cameraX) * safeScale + width / 2.0;
            double screenZ = (getRenderZ(element, context, partialTicks) - cameraZ) * safeScale + height / 2.0;
            double half = CHUNK_HALF_SIZE * safeScale;
            return screenX + half >= 0.0 && screenX - half <= width
                    && screenZ + half >= 0.0 && screenZ - half <= height;
        }

        @Override
        public int getInteractionBoxLeft(ChunkElement element, RenderContext context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxRight(ChunkElement element, RenderContext context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxTop(ChunkElement element, RenderContext context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxBottom(ChunkElement element, RenderContext context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxLeft(ChunkElement element, RenderContext context, float partialTicks) {
            return -8;
        }

        @Override
        public int getRenderBoxRight(ChunkElement element, RenderContext context, float partialTicks) {
            return 8;
        }

        @Override
        public int getRenderBoxTop(ChunkElement element, RenderContext context, float partialTicks) {
            return -8;
        }

        @Override
        public int getRenderBoxBottom(ChunkElement element, RenderContext context, float partialTicks) {
            return 8;
        }

        @Override
        public int getLeftSideLength(ChunkElement element, Minecraft minecraft) {
            return 0;
        }

        @Override
        public String getMenuName(ChunkElement element) {
            return "";
        }

        @Override
        public String getFilterName(ChunkElement element) {
            return "";
        }

        @Override
        public int getMenuTextFillLeftPadding(ChunkElement element) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(ChunkElement element) {
            return 0;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return false;
        }
    }
}
