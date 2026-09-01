package art.arcane.iris.engine.object;

import art.arcane.volmlib.util.collection.KList;
import com.google.gson.Gson;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IrisRiverConfigurationTest {
    @Test
    public void defaultsExposeCanonicalHydrologyHierarchy() {
        IrisDimension dimension = new IrisDimension();

        assertNotNull(dimension.getHydrology());
        assertNotNull(dimension.getHydrology().getRivers());
        assertFalse(dimension.getHydrology().getRivers().isEnabled());
        assertNotNull(dimension.getHydrology().getRivers().getRouting());
        assertNotNull(dimension.getHydrology().getRivers().getSurface());
        assertNotNull(dimension.getHydrology().getRivers().getUnderground());
        assertNotNull(dimension.getHydrology().getRivers().getGrottos());
        assertEquals(1, dimension.getHydrology().getRivers().getProfiles().size());
        assertEquals("default", dimension.getHydrology().getRivers().getProfiles().getFirst().getId());
        assertEquals("water", dimension.getHydrology().getRivers().getProfiles().getFirst()
                .getFluidPalette().getPalette().getFirst().getBlock());
        assertTrue(dimension.getHydrology().getDeepFluids().isEmpty());

        IrisRiverRoutingConfig routing = dimension.getHydrology().getRivers().getRouting();
        assertEquals(2048, routing.getTileSize());
        assertEquals(64, routing.getSampleSpacing());
        assertEquals(16384, routing.getMaximumRouteLength());
        assertEquals(384, routing.getMinimumSurfaceCourseLength());
        assertEquals(192, routing.getMinimumUndergroundCourseLength());
        assertEquals(1.5D, routing.getValleyPreference(), 0D);
        assertEquals(24D, routing.getUphillPenalty(), 0D);
        assertEquals(2D, routing.getSlopePenalty(), 0D);
        assertEquals(0.2D, routing.getConfluenceAttraction(), 0D);

        IrisSurfaceRiverConfig surface = dimension.getHydrology().getRivers().getSurface();
        assertTrue(surface.isEnabled());
        assertEquals(0.5D, surface.getSources().getDensity(), 0D);
        assertEquals(0, surface.getSources().getMinimumPerTile());
        assertEquals(384, surface.getSources().getMinimumSpacing());
        assertEquals(4D, surface.getChannel().getWidth().getMin(), 0D);
        assertEquals(8D, surface.getChannel().getWidth().getMax(), 0D);
        assertEquals(2D, surface.getChannel().getDepth().getMin(), 0D);
        assertEquals(4D, surface.getChannel().getDepth().getMax(), 0D);
        assertEquals(1, surface.getChannel().getInset());
        assertEquals(10, surface.getChannel().getMaximumIncision());
        assertEquals(0.25D, surface.getChannel().getRoughness(), 0D);
        assertEquals(16, surface.getChannel().getRoughnessWavelength());
        assertEquals(1, surface.getBanks().getFreeboard());
        assertEquals(1.5D, surface.getBanks().getShoreWidth(), 0D);
        assertEquals(3D, surface.getBanks().getBlendSlope(), 0D);
        assertEquals(4, surface.getBanks().getMinimumBlendWidth());
        assertEquals(32, surface.getBanks().getMaximumBlendWidth());
        assertTrue(surface.getBanks().isExposeCutStrata());
        assertEquals(2, surface.getFlow().getCascadeRun());
        assertEquals(6, surface.getFlow().getWaterfallMinimumDrop());
        assertEquals(1.6D, surface.getMouths().getFlareRatio(), 0D);
        assertEquals(8, surface.getMouths().getMaximumOceanApron());
        IrisRiverDropShapeConfig drops = dimension.getHydrology().getRivers().getGeometry().getDrops();
        assertEquals(2, drops.getCascadeRunPerBlock());
        assertEquals(1.4D, drops.getCascadeExponent(), 0D);
        assertEquals(2, drops.getMaximumCascadeStep());
        assertEquals(0.45D, drops.getFlowWidthRatio(), 0D);
        assertEquals(2, drops.getMaximumFlowDepth());
        assertEquals(1.8D, drops.getBasinWidthRatio(), 0D);
        assertEquals(8, drops.getMaximumBasinDepth());

        IrisUndergroundRiverConfig underground = dimension.getHydrology().getRivers().getUnderground();
        assertTrue(underground.isEnabled());
        assertEquals(0.25D, underground.getSources().getDensity(), 0D);
        assertEquals(0, underground.getSources().getMinimumPerTile());
        assertEquals(512, underground.getSources().getMinimumSpacing());
        assertEquals(-48D, underground.getFluidLevel().getMin(), 0D);
        assertEquals(50D, underground.getFluidLevel().getMax(), 0D);
        assertEquals(3D, underground.getChannelWidth().getMin(), 0D);
        assertEquals(8D, underground.getChannelWidth().getMax(), 0D);
        assertTrue(underground.isConnectToExistingCaves());
        assertEquals(64, underground.getMouthLevelingDistance());

        IrisCoastalRiverGrottoConfig coastal = dimension.getHydrology().getRivers().getGrottos().getCoastal();
        IrisInlandRiverGrottoConfig inland = dimension.getHydrology().getRivers().getGrottos().getInland();
        assertEquals(12, coastal.getHorizontalRadius());
        assertEquals(7, coastal.getVerticalRadius());
        assertEquals(10, coastal.getHeadroom());
        assertEquals(8192, coastal.getMaximumVolume());
        assertEquals(10, inland.getHorizontalRadius());
        assertEquals(6, inland.getVerticalRadius());
        assertEquals(10, inland.getHeadroom());
        assertEquals(8192, inland.getMaximumVolume());
        assertFalse(inland.isConnectSurfaceRivers());

        IrisDeepFluidConfig deepFluid = new IrisDeepFluidConfig();
        assertEquals(0.125D, deepFluid.getDensity(), 0D);
        assertEquals(768, deepFluid.getSpacing());
        assertEquals(14, deepFluid.getHorizontalRadius());
        assertEquals(6, deepFluid.getVerticalRadius());
        assertEquals(3, deepFluid.getChannelWidth());
        assertEquals(1, deepFluid.getDepth());
        assertEquals(6, deepFluid.getHeadroom());
        assertFalse(deepFluid.isShortChannels());
        assertNotNull(dimension.getRiverPolicy());
        assertNull(dimension.getRiverPolicy().getPlacement());
        assertNull(dimension.getRiverPolicy().getRouting());
        assertNull(dimension.getRiverPolicy().getOutletAdmission());
        assertNull(dimension.getRiverPolicy().getBankMultiplier());
        assertNull(new IrisRegion().getRiverPolicy());
        assertNull(new IrisBiome().getRiverPolicy());
    }

    @Test
    public void deserializesCanonicalDimensionRegionAndBiomeConfiguration() {
        Gson gson = new Gson();
        IrisDimension dimension = gson.fromJson("""
                {
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "routing": {
                        "tileSize": 4096,
                        "sampleSpacing": 96,
                        "maximumRouteLength": 32768,
                        "minimumSurfaceCourseLength": 640,
                        "minimumUndergroundCourseLength": 480,
                        "maximumOutletsPerTile": 3,
                        "oceanOutlets": true,
                        "inlandOutlets": ["SINKHOLE_GROTTO"],
                        "valleyPreference": 2.5,
                        "uphillPenalty": 30,
                        "slopePenalty": 3,
                        "confluenceAttraction": 0.4
                      },
                      "geometry": {
                        "drops": {
                          "cascadeRunPerBlock": 5,
                          "cascadeExponent": 2.4,
                          "maximumCascadeStep": 3,
                          "flowWidthRatio": 0.6,
                          "maximumFlowDepth": 4,
                          "basinWidthRatio": 2.2,
                          "maximumBasinDepth": 11
                        }
                      },
                      "surface": {
                        "sources": {"density": 0.75, "minimumElevation": 104, "minimumPerTile": 2,
                          "minimumSpacing": 768},
                        "channel": {
                          "width": {"min": 5, "max": 24},
                          "depth": {"min": 2, "max": 6},
                          "inset": 2,
                          "maximumIncision": 14,
                          "roughness": 0.4,
                          "roughnessWavelength": 24
                        },
                        "banks": {
                          "freeboard": 2,
                          "shoreWidth": 2.5,
                          "blendSlope": 4,
                          "minimumBlendWidth": 6,
                          "maximumBlendWidth": 48,
                          "exposeCutStrata": false
                        },
                        "flow": {"cascadeRun": 3, "waterfallMinimumDrop": 8},
                        "mouths": {"flareRatio": 2.2, "maximumOceanApron": 1}
                      },
                      "underground": {
                        "sources": {"density": 0.4, "minimumPerTile": 3, "minimumSpacing": 896},
                        "fluidLevel": {"min": -96, "max": 12},
                        "channelWidth": {"min": 4, "max": 16},
                        "depth": {"min": 2, "max": 4},
                        "headroom": {"min": 7, "max": 15},
                        "connectToExistingCaves": false,
                        "mouthLevelingDistance": 80
                      },
                      "grottos": {
                        "coastal": {
                          "enabled": true,
                          "poolLevel": "SEA_LEVEL",
                          "horizontalRadius": 40,
                          "verticalRadius": 18,
                          "headroom": 9,
                          "maximumVolume": 70000
                        },
                        "inland": {
                          "enabled": true,
                          "connectSurfaceRivers": true,
                          "horizontalRadius": 30,
                          "verticalRadius": 15,
                          "headroom": 7,
                          "maximumVolume": 60000
                        }
                      },
                      "profiles": [{
                        "id": "underworld",
                        "fluidPalette": {"palette": [{"block": "minecraft:lava"}]}
                      }]
                    },
                    "deepFluids": [{
                      "id": "deep_lava",
                      "fluidPalette": {"palette": [{"block": "minecraft:lava"}]},
                      "density": 0.3,
                      "spacing": 896,
                      "height": {"min": -220, "max": -112},
                      "horizontalRadius": 36,
                      "verticalRadius": 12,
                      "channelWidth": 6,
                      "depth": 3,
                      "headroom": 7,
                      "containedPools": true,
                      "shortChannels": false
                    }]
                  },
                  "riverPolicy": {
                    "placement": "PREFERRED_HEADWATER",
                    "routing": "PREFER",
                    "outletAdmission": false,
                    "profiles": ["underworld"],
                    "surfaceBiomes": ["river/surface"],
                    "shoreBiomes": ["river/shore"],
                    "bankBiomes": ["river/bank"],
                    "widthMultiplier": 1.5,
                    "incisionMultiplier": 0.75,
                    "bankMultiplier": 2
                  }
                }
                """, IrisDimension.class);
        IrisRegion region = gson.fromJson("""
                {"riverPolicy": {
                  "placement": "TRANSIT_ONLY",
                  "routing": "AVOID",
                  "mouthBiomes": ["river/mouth"],
                  "routingMultiplier": 2.5
                }}
                """, IrisRegion.class);
        IrisBiome biome = gson.fromJson("""
                {"riverPolicy": {
                  "placement": "REQUIRED_HEADWATER",
                  "routing": "BLOCK",
                  "floodedCaveBiomes": [],
                  "depthMultiplier": 0.8
                }}
                """, IrisBiome.class);

        IrisRiverHydrology rivers = dimension.getHydrology().getRivers();
        assertTrue(rivers.isEnabled());
        assertEquals(4096, rivers.getRouting().getTileSize());
        assertEquals(96, rivers.getRouting().getSampleSpacing());
        assertEquals(32768, rivers.getRouting().getMaximumRouteLength());
        assertEquals(3, rivers.getRouting().getMaximumOutletsPerTile());
        assertEquals(640, rivers.getRouting().getMinimumSurfaceCourseLength());
        assertEquals(480, rivers.getRouting().getMinimumUndergroundCourseLength());
        assertEquals(2.5D, rivers.getRouting().getValleyPreference(), 0D);
        assertEquals(30D, rivers.getRouting().getUphillPenalty(), 0D);
        assertEquals(3D, rivers.getRouting().getSlopePenalty(), 0D);
        assertEquals(0.4D, rivers.getRouting().getConfluenceAttraction(), 0D);
        assertEquals(new KList<>(IrisRiverInlandOutlet.SINKHOLE_GROTTO), rivers.getRouting().getInlandOutlets());
        assertEquals(24D, rivers.getSurface().getChannel().getWidth().getMax(), 0D);
        assertEquals(2, rivers.getSurface().getChannel().getInset());
        assertEquals(14, rivers.getSurface().getChannel().getMaximumIncision());
        assertEquals(0.4D, rivers.getSurface().getChannel().getRoughness(), 0D);
        assertEquals(24, rivers.getSurface().getChannel().getRoughnessWavelength());
        assertEquals(2, rivers.getSurface().getBanks().getFreeboard());
        assertEquals(2.5D, rivers.getSurface().getBanks().getShoreWidth(), 0D);
        assertEquals(4D, rivers.getSurface().getBanks().getBlendSlope(), 0D);
        assertEquals(6, rivers.getSurface().getBanks().getMinimumBlendWidth());
        assertEquals(48, rivers.getSurface().getBanks().getMaximumBlendWidth());
        assertFalse(rivers.getSurface().getBanks().isExposeCutStrata());
        assertEquals(3, rivers.getSurface().getFlow().getCascadeRun());
        assertEquals(8, rivers.getSurface().getFlow().getWaterfallMinimumDrop());
        assertEquals(2.2D, rivers.getSurface().getMouths().getFlareRatio(), 0D);
        assertEquals(1, rivers.getSurface().getMouths().getMaximumOceanApron());
        assertEquals(768, rivers.getSurface().getSources().getMinimumSpacing());
        assertEquals(5, rivers.getGeometry().getDrops().getCascadeRunPerBlock());
        assertEquals(2.4D, rivers.getGeometry().getDrops().getCascadeExponent(), 0D);
        assertEquals(3, rivers.getGeometry().getDrops().getMaximumCascadeStep());
        assertEquals(0.6D, rivers.getGeometry().getDrops().getFlowWidthRatio(), 0D);
        assertEquals(4, rivers.getGeometry().getDrops().getMaximumFlowDepth());
        assertEquals(2.2D, rivers.getGeometry().getDrops().getBasinWidthRatio(), 0D);
        assertEquals(11, rivers.getGeometry().getDrops().getMaximumBasinDepth());
        assertEquals(-96D, rivers.getUnderground().getFluidLevel().getMin(), 0D);
        assertEquals(896, rivers.getUnderground().getSources().getMinimumSpacing());
        assertFalse(rivers.getUnderground().isConnectToExistingCaves());
        assertEquals(80, rivers.getUnderground().getMouthLevelingDistance());
        assertEquals(40, rivers.getGrottos().getCoastal().getHorizontalRadius());
        assertTrue(rivers.getGrottos().getInland().isEnabled());
        assertTrue(rivers.getGrottos().getInland().isConnectSurfaceRivers());
        assertEquals("underworld", rivers.getProfiles().getFirst().getId());

        IrisDeepFluidConfig configuredDeepFluid = dimension.getHydrology().getDeepFluids().getFirst();
        assertEquals("deep_lava", configuredDeepFluid.getId());
        assertEquals(0.3D, configuredDeepFluid.getDensity(), 0D);
        assertEquals(896, configuredDeepFluid.getSpacing());
        assertEquals(-220D, configuredDeepFluid.getHeight().getMin(), 0D);
        assertEquals(36, configuredDeepFluid.getHorizontalRadius());
        assertEquals(6, configuredDeepFluid.getChannelWidth());
        assertFalse(configuredDeepFluid.isShortChannels());

        assertEquals(IrisRiverPlacementMode.PREFERRED_HEADWATER, dimension.getRiverPolicy().getPlacement());
        assertEquals(IrisRiverRoutingMode.PREFER, dimension.getRiverPolicy().getRouting());
        assertFalse(dimension.getRiverPolicy().getOutletAdmission());
        assertEquals(2D, dimension.getRiverPolicy().getBankMultiplier(), 0D);
        assertEquals(Set.of("river/surface", "river/shore", "river/bank"), dimension.getRiverPolicy().getAllBiomeIds());
        assertEquals(IrisRiverPlacementMode.TRANSIT_ONLY, region.getRiverPolicy().getPlacement());
        assertEquals(new KList<>("river/mouth"), region.getRiverPolicy().getMouthBiomes());
        assertEquals(IrisRiverPlacementMode.REQUIRED_HEADWATER, biome.getRiverPolicy().getPlacement());
        assertTrue(biome.getRiverPolicy().getFloodedCaveBiomes().isEmpty());
    }
}
