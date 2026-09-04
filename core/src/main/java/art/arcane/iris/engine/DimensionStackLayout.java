package art.arcane.iris.engine;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.spi.PlatformBlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DimensionStackLayout {
    private final List<Layer> layersBottomToTop;
    private final List<Layer> layersTopToBottom;
    private final int stackTerrainTopY;
    private final int stackTopY;
    private final int clippedStackTerrainTopY;
    private final int clippedStackTopY;

    private DimensionStackLayout(LayoutState state) {
        layersBottomToTop = List.copyOf(state.layersBottomToTop());
        ArrayList<Layer> reversed = new ArrayList<>(layersBottomToTop);
        Collections.reverse(reversed);
        layersTopToBottom = List.copyOf(reversed);
        stackTerrainTopY = state.stackTerrainTopY();
        stackTopY = state.stackTopY();
        clippedStackTerrainTopY = state.clippedStackTerrainTopY();
        clippedStackTopY = state.clippedStackTopY();
    }

    static DimensionStackLayout create(
            int outputHeight,
            int spacer,
            List<LayerInput> layerInputsBottomToTop,
            int[] seamOffsetsBottomToTop
    ) {
        if (outputHeight < 1) {
            throw new IllegalArgumentException("Output height must be positive");
        }
        if (spacer < 0) {
            throw new IllegalArgumentException("Dimension stack spacer cannot be negative");
        }
        if (layerInputsBottomToTop.isEmpty()) {
            throw new IllegalArgumentException("Dimension stack requires at least one layer");
        }
        if (seamOffsetsBottomToTop.length != layerInputsBottomToTop.size() - 1) {
            throw new IllegalArgumentException("Dimension stack seam offset count does not match its layers");
        }

        int outputMaxY = outputHeight - 1;
        int baseY = 0;
        int stackTerrainTopY = Integer.MIN_VALUE;
        int stackTopY = Integer.MIN_VALUE;
        int renderedTerrainTopY = Integer.MIN_VALUE;
        int renderedContentTopY = Integer.MIN_VALUE;
        ArrayList<Layer> layers = new ArrayList<>(layerInputsBottomToTop.size());
        for (int layerIndex = 0; layerIndex < layerInputsBottomToTop.size(); layerIndex++) {
            LayerInput input = layerInputsBottomToTop.get(layerIndex);
            int seamOffsetBelow = layerIndex == 0 ? 0 : seamOffsetsBottomToTop[layerIndex - 1];
            int spacerBelow = layerIndex == 0 ? 0 : spacer;
            if (layerIndex > 0) {
                Layer lower = layers.get(layerIndex - 1);
                int gapMinY = (int) Math.max(0L, (long) lower.contentTopY() + 1L);
                int gapMaxY = (int) Math.min((long) outputMaxY, (long) baseY - 1L);
                renderedTerrainTopY = eraseHeightThroughGap(
                        renderedTerrainTopY,
                        gapMinY,
                        gapMaxY,
                        visibleTerrainY(lower)
                );
                renderedContentTopY = eraseHeightThroughGap(
                        renderedContentTopY,
                        gapMinY,
                        gapMaxY,
                        lower.visible() ? lower.renderMaxY() : Integer.MIN_VALUE
                );
            }
            int surfaceY = saturatedAdd(baseY, input.normalTerrainHeight());
            int fluidY = saturatedAdd(baseY, input.fluidHeight());
            int contentTopY = Math.max(surfaceY, fluidY);
            int renderMinY = Math.max(0, baseY);
            int renderMaxY = Math.min(outputMaxY, contentTopY);
            boolean visible = renderMinY <= renderMaxY;
            Layer layer = new Layer(
                    input.terrainContext(),
                    input.biome(),
                    input.region(),
                    input.rockBlock(),
                    input.fluidBlock(),
                    input.surfaceBlock(),
                    baseY,
                    input.normalTerrainHeight(),
                    input.fluidHeight(),
                    surfaceY,
                    fluidY,
                    contentTopY,
                    spacerBelow,
                    seamOffsetBelow,
                    renderMinY,
                    renderMaxY,
                    clip(surfaceY, outputMaxY),
                    clip(fluidY, outputMaxY),
                    visible
            );
            layers.add(layer);
            stackTerrainTopY = Math.max(stackTerrainTopY, surfaceY);
            stackTopY = Math.max(stackTopY, contentTopY);
            if (visible) {
                renderedContentTopY = Math.max(renderedContentTopY, renderMaxY);
                renderedTerrainTopY = mergeVisibleTerrainHeight(
                        renderedTerrainTopY,
                        renderMinY,
                        renderMaxY,
                        surfaceY,
                        outputMaxY
                );
            }
            if (layerIndex + 1 < layerInputsBottomToTop.size()) {
                baseY = saturatedAdd(
                        contentTopY,
                        saturatedAdd(spacer, saturatedAdd(1, seamOffsetsBottomToTop[layerIndex]))
                );
            }
        }

        return new DimensionStackLayout(new LayoutState(
                layers,
                stackTerrainTopY,
                stackTopY,
                Math.max(0, renderedTerrainTopY),
                Math.max(0, renderedContentTopY)
        ));
    }

    private static int saturatedAdd(int left, int right) {
        long sum = (long) left + right;
        if (sum > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (sum < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) sum;
    }

    private static int clip(int value, int outputMaxY) {
        return Math.max(0, Math.min(outputMaxY, value));
    }

    static int mergeVisibleTerrainHeight(
            int currentTerrainTopY,
            int renderMinY,
            int renderMaxY,
            int surfaceY,
            int outputMaxY
    ) {
        int terrainTopY = currentTerrainTopY;
        if (terrainTopY >= renderMinY && terrainTopY <= renderMaxY) {
            terrainTopY = Integer.MIN_VALUE;
        }
        if (surfaceY >= renderMinY) {
            terrainTopY = Math.max(terrainTopY, Math.min(outputMaxY, surfaceY));
        }
        return terrainTopY;
    }

    static int eraseHeightThroughGap(
            int currentTopY,
            int gapMinY,
            int gapMaxY,
            int fallbackTopY
    ) {
        if (currentTopY >= gapMinY && currentTopY <= gapMaxY) {
            return fallbackTopY;
        }
        return currentTopY;
    }

    private static int visibleTerrainY(Layer layer) {
        if (!layer.visible() || layer.surfaceY() < layer.renderMinY()) {
            return Integer.MIN_VALUE;
        }
        return layer.clippedSurfaceY();
    }

    public List<Layer> layersBottomToTop() {
        return layersBottomToTop;
    }

    public List<Layer> layersTopToBottom() {
        return layersTopToBottom;
    }

    public int stackTerrainTopY() {
        return stackTerrainTopY;
    }

    public int stackTopY() {
        return stackTopY;
    }

    public int clippedStackTerrainTopY() {
        return clippedStackTerrainTopY;
    }

    public int clippedStackTopY() {
        return clippedStackTopY;
    }

    public Layer topTerrainLayer() {
        Layer highest = null;
        int highestSurfaceY = Integer.MIN_VALUE;
        for (int layerIndex = 0; layerIndex < layersBottomToTop.size(); layerIndex++) {
            Layer layer = layersBottomToTop.get(layerIndex);
            if (layerIndex > 0) {
                Layer lower = layersBottomToTop.get(layerIndex - 1);
                if ((long) highestSurfaceY > lower.contentTopY()
                        && (long) highestSurfaceY < layer.localBaseY()) {
                    highest = visibleTerrainY(lower) == Integer.MIN_VALUE ? null : lower;
                    highestSurfaceY = visibleTerrainY(lower);
                }
            }
            if (layer.containsRenderedY(highestSurfaceY)) {
                highest = null;
                highestSurfaceY = Integer.MIN_VALUE;
            }
            if (!layer.visible() || layer.surfaceY() < layer.renderMinY()) {
                continue;
            }
            if (layer.clippedSurfaceY() >= highestSurfaceY) {
                highest = layer;
                highestSurfaceY = layer.clippedSurfaceY();
            }
        }
        return highest;
    }

    public Layer surfaceLayer() {
        Layer terrainLayer = topTerrainLayer();
        return terrainLayer == null ? topVisibleLayer() : terrainLayer;
    }

    private Layer topVisibleLayer() {
        Layer highest = null;
        int highestContentY = Integer.MIN_VALUE;
        for (int layerIndex = 0; layerIndex < layersBottomToTop.size(); layerIndex++) {
            Layer layer = layersBottomToTop.get(layerIndex);
            if (layerIndex > 0) {
                Layer lower = layersBottomToTop.get(layerIndex - 1);
                if ((long) highestContentY > lower.contentTopY()
                        && (long) highestContentY < layer.localBaseY()) {
                    highest = lower.visible() ? lower : null;
                    highestContentY = lower.visible()
                            ? lower.renderMaxY()
                            : Integer.MIN_VALUE;
                }
            }
            if (layer.visible() && layer.renderMaxY() >= highestContentY) {
                highest = layer;
                highestContentY = layer.renderMaxY();
            }
        }
        return highest;
    }

    public Layer layerAt(int y) {
        Layer bottom = layersBottomToTop.get(0);
        Layer owner = bottom;
        for (int layerIndex = 1; layerIndex < layersBottomToTop.size(); layerIndex++) {
            Layer lower = layersBottomToTop.get(layerIndex - 1);
            Layer layer = layersBottomToTop.get(layerIndex);
            if ((long) y > lower.contentTopY() && (long) y < layer.localBaseY()) {
                owner = bottom;
            }
            if (layer.containsRenderedY(y)) {
                owner = layer;
            }
        }
        return owner;
    }

    public boolean containsUpperLayerY(int y) {
        for (int layerIndex = 1; layerIndex < layersBottomToTop.size(); layerIndex++) {
            if (layersBottomToTop.get(layerIndex).containsRenderedY(y)) {
                return true;
            }
        }
        return false;
    }

    public boolean isHostFeatureProtectedY(int y) {
        for (int layerIndex = 1; layerIndex < layersBottomToTop.size(); layerIndex++) {
            Layer lower = layersBottomToTop.get(layerIndex - 1);
            Layer upper = layersBottomToTop.get(layerIndex);
            if (upper.containsRenderedY(y)
                    || ((long) y > lower.contentTopY() && (long) y < upper.localBaseY())) {
                return true;
            }
        }
        return false;
    }

    static record LayerInput(
            DimensionTerrainContext terrainContext,
            IrisBiome biome,
            IrisRegion region,
            PlatformBlockState rockBlock,
            PlatformBlockState fluidBlock,
            PlatformBlockState surfaceBlock,
            int normalTerrainHeight,
            int fluidHeight
    ) {
    }

    public record Layer(
            DimensionTerrainContext terrainContext,
            IrisBiome biome,
            IrisRegion region,
            PlatformBlockState rockBlock,
            PlatformBlockState fluidBlock,
            PlatformBlockState surfaceBlock,
            int localBaseY,
            int normalTerrainHeight,
            int fluidHeight,
            int surfaceY,
            int fluidY,
            int contentTopY,
            int spacerBelow,
            int seamOffsetBelow,
            int renderMinY,
            int renderMaxY,
            int clippedSurfaceY,
            int clippedFluidY,
            boolean visible
    ) {
        public boolean containsRenderedY(int y) {
            return visible && y >= renderMinY && y <= renderMaxY;
        }

    }

    private record LayoutState(
            List<Layer> layersBottomToTop,
            int stackTerrainTopY,
            int stackTopY,
            int clippedStackTerrainTopY,
            int clippedStackTopY
    ) {
    }
}
