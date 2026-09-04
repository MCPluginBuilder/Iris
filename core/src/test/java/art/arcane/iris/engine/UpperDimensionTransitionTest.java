package art.arcane.iris.engine;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.history.TerrainBoundarySignature;
import art.arcane.iris.engine.history.TransitionGenerationPlan;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.util.project.stream.ProceduralStream;
import org.junit.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UpperDimensionTransitionTest {
    @Test
    public void gapLimitedTargetDoesNotCreateAnIntermediateCeilingDip() {
        IrisDimension dimension = mock(IrisDimension.class);
        when(dimension.getLoadKey()).thenReturn("main");
        when(dimension.getUpperDimensionGap()).thenReturn(32);
        IrisComplex complex = mock(IrisComplex.class);
        when(complex.getUnblendedNaturalHeightStream()).thenReturn(ProceduralStream.ofDouble((x, z) -> 139D));
        when(complex.getHeightStream()).thenReturn(ProceduralStream.ofDouble((x, z) -> 75D + 4D * x));
        TransitionGenerationPlan plan = mock(TransitionGenerationPlan.class);
        when(plan.terrainSampleAt(0, 8)).thenReturn(sample(75D, 0D));
        when(plan.terrainSampleAt(8, 8)).thenReturn(sample(75D, 0.5D));
        when(plan.terrainSampleAt(16, 8)).thenReturn(sample(75D, 1D));
        when(complex.getTransitionGenerationPlan()).thenReturn(plan);
        Engine engine = mock(Engine.class);
        when(engine.getDimension()).thenReturn(dimension);
        when(engine.getComplex()).thenReturn(complex);
        when(engine.getHeight()).thenReturn(256);

        UpperDimensionContext context = UpperDimensionContext.create(engine, dimension);

        assertEquals(180, context.getEffectiveSurfaceY(0, 8));
        assertEquals(175, context.getEffectiveSurfaceY(8, 8));
        assertEquals(171, context.getEffectiveSurfaceY(16, 8));
    }

    @Test
    public void blendsFrozenEffectiveCeilingTowardTheUnblendedSelfReference() {
        IrisDimension dimension = mock(IrisDimension.class);
        when(dimension.getLoadKey()).thenReturn("main");
        when(dimension.getUpperDimensionGap()).thenReturn(32);
        IrisComplex complex = mock(IrisComplex.class);
        when(complex.getUnblendedNaturalHeightStream()).thenReturn(ProceduralStream.ofDouble((x, z) -> 80D));
        when(complex.getHeightStream()).thenReturn(ProceduralStream.ofDouble((x, z) -> 64D));
        TransitionGenerationPlan plan = mock(TransitionGenerationPlan.class);
        when(plan.terrainSampleAt(-17, 8)).thenReturn(sample(40D, 0.5D));
        when(complex.getTransitionGenerationPlan()).thenReturn(plan);
        Engine engine = mock(Engine.class);
        when(engine.getDimension()).thenReturn(dimension);
        when(engine.getComplex()).thenReturn(complex);
        when(engine.getHeight()).thenReturn(256);

        UpperDimensionContext context = UpperDimensionContext.create(engine, dimension);

        assertEquals(195, context.getEffectiveSurfaceY(-17, 8));
        verify(complex, never()).getNaturalHeightStream();
        verify(complex, never()).getNaturalTrueBiomeStream();
    }

    @Test
    public void preservesBoundaryAndCurrentEndpointsAndFixedGap() {
        UpperDimensionContext.CeilingLayout layout = new UpperDimensionContext.CeilingLayout(384, 32);
        double frozen = UpperDimensionContext.blendCeilingDepth(180D, sample(60D, 0D));
        double current = UpperDimensionContext.blendCeilingDepth(180D, sample(60D, 1D));

        assertEquals(323, UpperDimensionContext.effectiveSurfaceY(frozen, layout, 100));
        assertEquals(203, UpperDimensionContext.effectiveSurfaceY(current, layout, 100));
        assertEquals(252, UpperDimensionContext.effectiveSurfaceY(current, layout, 220));
        assertEquals(188, -64 + UpperDimensionContext.effectiveSurfaceY(current, layout, 220));
        assertEquals(384, UpperDimensionContext.effectiveSurfaceY(current, layout, Integer.MAX_VALUE));
    }

    @Test
    public void absentCeilingsRemainOutsideTheHunkAndAppearGradually() {
        UpperDimensionContext.CeilingLayout layout = new UpperDimensionContext.CeilingLayout(256, 32);
        assertEquals(256, UpperDimensionContext.effectiveSurfaceY(
                UpperDimensionContext.blendCeilingDepth(80D, sample(0D, 0D)), layout, 64));
        assertEquals(215, UpperDimensionContext.effectiveSurfaceY(
                UpperDimensionContext.blendCeilingDepth(80D, sample(0D, 0.5D)), layout, 64));
        assertEquals(215, UpperDimensionContext.effectiveSurfaceY(
                UpperDimensionContext.blendCeilingDepth(0D, sample(80D, 0.5D)), layout, 64));
        assertEquals(256, UpperDimensionContext.effectiveSurfaceY(
                UpperDimensionContext.blendCeilingDepth(0D, sample(80D, 1D)), layout, 64));
        assertEquals(256, UpperDimensionContext.effectiveSurfaceY(0.49D, layout, 64));
        assertEquals(254, UpperDimensionContext.effectiveSurfaceY(0.51D, layout, 64));
    }

    @Test
    public void rejectsInvalidCeilingLayouts() {
        assertThrows(IllegalArgumentException.class, () -> new UpperDimensionContext.CeilingLayout(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new UpperDimensionContext.CeilingLayout(256, -1));
    }

    private static TransitionGenerationPlan.TerrainSample sample(double depth, double weight) {
        TerrainBoundarySignature signature = new TerrainBoundarySignature(
                new TerrainBoundarySignature.Column(15, 8, 64, 64, OptionalInt.empty(),
                        depth == 0D ? OptionalInt.empty() : OptionalInt.of((int) depth)),
                new TerrainBoundarySignature.Samples(
                        new TerrainBoundarySignature.VerticalLayout(-64, 4, 1),
                        new TerrainBoundarySignature.BiomeEncoding(List.of("minecraft:plains"), new short[]{0})));
        return new TransitionGenerationPlan.TerrainSample(8D, weight, 0D, signature, 64D, 64D, depth);
    }
}
