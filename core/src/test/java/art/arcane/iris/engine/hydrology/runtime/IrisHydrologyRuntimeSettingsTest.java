package art.arcane.iris.engine.hydrology.runtime;

import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.object.IrisDeepFluidConfig;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRiverHydrology;
import art.arcane.iris.engine.object.IrisRiverInlandOutlet;
import art.arcane.iris.engine.object.IrisRiverRoutingConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisHydrologyRuntimeSettingsTest {
    @Test
    public void deepChannelsRemainLocalWhenConfiguredSpacingIsLarge() {
        IrisDeepFluidConfig deepFluid = new IrisDeepFluidConfig()
                .setShortChannels(true)
                .setSpacing(8192);
        IrisRiverRoutingConfig routing = new IrisRiverRoutingConfig()
                .setTileSize(256);

        assertEquals(128, IrisHydrologyRuntime.maximumDeepChannelLength(deepFluid, routing));
    }

    @Test
    public void disabledDeepChannelsHaveNoPublicationReach() {
        IrisDeepFluidConfig deepFluid = new IrisDeepFluidConfig()
                .setShortChannels(false)
                .setSpacing(8192);
        IrisRiverRoutingConfig routing = new IrisRiverRoutingConfig()
                .setTileSize(256);

        assertEquals(0, IrisHydrologyRuntime.maximumDeepChannelLength(deepFluid, routing));
    }

    @Test
    public void surfaceSinkholeSettingRequiresTheCanonicalEnabledInlandOutlet() {
        IrisDimension dimension = new IrisDimension();
        IrisRiverHydrology rivers = dimension.getHydrology().getRivers();
        rivers.setEnabled(true);
        rivers.getGrottos().getInland().setEnabled(true).setConnectSurfaceRivers(true);
        rivers.getRouting().getInlandOutlets().add(IrisRiverInlandOutlet.SINKHOLE_GROTTO);

        assertTrue(IrisHydrologyRuntime.createSettings(dimension, dimension.getHydrology())
                .outlets().surfaceSinkholesEnabled());

        rivers.getRouting().getInlandOutlets().clear();

        assertFalse(IrisHydrologyRuntime.createSettings(dimension, dimension.getHydrology())
                .outlets().surfaceSinkholesEnabled());
    }

    @Test
    public void denseRiverSourcesRemainDistinctAndCanFillTheirTileQuota() {
        IrisDimension dimension = new IrisDimension();
        IrisRiverHydrology rivers = dimension.getHydrology().getRivers();
        rivers.setEnabled(true);
        rivers.getSurface().setEnabled(true);
        rivers.getSurface().getSources().setDensity(4.5D);
        rivers.getUnderground().setEnabled(true);
        rivers.getUnderground().getSources().setDensity(4.5D);
        rivers.getRouting().setSampleSpacing(64);

        assertEquals(5, IrisHydrologyRuntime.createSettings(dimension, dimension.getHydrology())
                .surface().sources().maximumPerTile());
        assertEquals(384, IrisHydrologyRuntime.createSettings(dimension, dimension.getHydrology())
                .surface().sources().minimumSpacing());
        assertEquals(5, IrisHydrologyRuntime.createSettings(dimension, dimension.getHydrology())
                .underground().sources().maximumPerTile());
        assertEquals(512, IrisHydrologyRuntime.createSettings(dimension, dimension.getHydrology())
                .underground().sources().minimumSpacing());
    }

    @Test
    public void surfaceBankAndFlowConfigurationMapsIntoPlannerSettings() {
        IrisDimension dimension = new IrisDimension();
        IrisRiverHydrology rivers = dimension.getHydrology().getRivers();
        rivers.getSurface().getChannel().setInset(2).setMaximumIncision(12);
        rivers.getSurface().getBanks()
                .setFreeboard(2)
                .setShoreWidth(2.5D)
                .setBlendSlope(4D)
                .setMinimumBlendWidth(6)
                .setMaximumBlendWidth(48)
                .setExposeCutStrata(false);
        rivers.getSurface().getFlow().setCascadeRun(3).setWaterfallMinimumDrop(8);
        rivers.getSurface().getMouths().setFlareRatio(2.2D);
        rivers.getUnderground().setMouthLevelingDistance(96);
        rivers.getRouting()
                .setSampleSpacing(32)
                .setMinimumSurfaceCourseLength(500)
                .setMinimumUndergroundCourseLength(250)
                .setValleyPreference(2.5D)
                .setUphillPenalty(30D)
                .setSlopePenalty(3D)
                .setConfluenceAttraction(0.4D);

        HydrologyPlannerSettings settings = IrisHydrologyRuntime.createSettings(dimension, dimension.getHydrology());
        HydrologyPlannerSettings.Banks banks = settings.surface().banks();

        assertEquals(2, banks.inset());
        assertEquals(2, banks.freeboard());
        assertEquals(4D, banks.blendSlope(), 0D);
        assertEquals(6, banks.minimumBlendWidth());
        assertEquals(48, banks.maximumBlendWidth());
        assertEquals(3, banks.cascadeRun());
        assertEquals(8, banks.waterfallMinimumDrop());
        assertEquals(2.2D, banks.mouthFlareRatio(), 0D);
        assertFalse(banks.exposeCutStrata());
        assertEquals(12, settings.surface().maximumIncision());
        assertEquals(2.5D, settings.surface().shoreWidth(), 0D);
        assertEquals(8, settings.hydraulics().waterfallMinimumDrop());
        assertEquals(96, settings.outlets().mouthLevelingDistance());
        assertEquals(4, settings.routing().refinementSpacing());
        assertEquals(500, settings.routing().branching().minimumSurfaceCourseLength());
        assertEquals(250, settings.routing().branching().minimumUndergroundCourseLength());
        assertEquals(2.5D, settings.routing().valleyPreference(), 0D);
        assertEquals(30D, settings.routing().uphillPenalty(), 0D);
        assertEquals(3D, settings.routing().slopePenalty(), 0D);
        assertEquals(0.4D, settings.routing().confluenceAttraction(), 0D);
        assertEquals(2, IrisHydrologyRuntime.refinementSpacing(50));
        assertEquals(1, IrisHydrologyRuntime.refinementSpacing(45));
    }

    @Test
    public void routingOutletLimitControlsDrainageRootCount() {
        IrisDimension dimension = new IrisDimension();
        dimension.getHydrology().getRivers().getRouting().setMaximumOutletsPerTile(2);

        assertEquals(2, IrisHydrologyRuntime.createSettings(dimension, dimension.getHydrology())
                .outlets().maximumPerTile());
    }

    @Test
    public void organicGeometryConfigurationMapsWithoutLosingPrecision() {
        IrisDimension dimension = new IrisDimension();
        IrisRiverHydrology rivers = dimension.getHydrology().getRivers();
        rivers.getGeometry().getMeanders()
                .setPrimaryWavelength(47)
                .setDetailWavelength(9)
                .setPrimaryStrength(0.31D)
                .setDetailStrength(0.53D)
                .setMaximumOffsetRatio(0.44D)
                .setSmoothingPasses(2)
                .setMaximumTurnDegrees(76D);
        rivers.getSurface().getChannel()
                .setRoughness(0.37D)
                .setRoughnessWavelength(13);
        rivers.getGeometry().getDrops()
                .setCascadeRunPerBlock(4)
                .setCascadeExponent(2.2D)
                .setMaximumCascadeStep(1)
                .setFlowWidthRatio(0.81D)
                .setMaximumFlowDepth(3)
                .setBasinWidthRatio(2.1D)
                .setMaximumBasinDepth(9);

        HydrologyPlannerSettings.Geometry geometry = IrisHydrologyRuntime
                .createSettings(dimension, dimension.getHydrology())
                .geometry();

        assertEquals(47, geometry.meanders().primaryWavelength());
        assertEquals(9, geometry.meanders().detailWavelength());
        assertEquals(0.31D, geometry.meanders().primaryStrength(), 0D);
        assertEquals(0.53D, geometry.meanders().detailStrength(), 0D);
        assertEquals(0.44D, geometry.meanders().maximumOffsetRatio(), 0D);
        assertEquals(2, geometry.meanders().smoothingPasses());
        assertEquals(76D, geometry.meanders().maximumTurnDegrees(), 0D);
        assertEquals(0.37D, geometry.surface().bedRoughness(), 0D);
        assertEquals(0.37D, geometry.surface().wallRoughness(), 0D);
        assertEquals(13, geometry.surface().roughnessWavelength());
        assertEquals(4, geometry.drops().cascadeRunPerBlock());
        assertEquals(2.2D, geometry.drops().cascadeExponent(), 0D);
        assertEquals(1, geometry.drops().maximumCascadeStep());
        assertEquals(0.81D, geometry.drops().flowWidthRatio(), 0D);
        assertEquals(3, geometry.drops().maximumFlowDepth());
        assertEquals(2.1D, geometry.drops().basinWidthRatio(), 0D);
        assertEquals(9, geometry.drops().maximumBasinDepth());
    }
}
