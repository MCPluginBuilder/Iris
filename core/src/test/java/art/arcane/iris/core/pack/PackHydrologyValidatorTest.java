package art.arcane.iris.core.pack;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PackHydrologyValidatorTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsCanonicalHydrologyAndRecursivePolicyClosure() throws Exception {
        File pack = pack(canonicalDimension());
        write(pack, "regions/region.json", """
                {
                  "landBiomes": ["biome"],
                  "seaBiomes": [],
                  "shoreBiomes": [],
                  "caveBiomes": [],
                  "riverPolicy": {
                    "placement": "NATURAL",
                    "profiles": ["default"],
                    "surfaceBiomes": ["river_surface"]
                  }
                }
                """);
        write(pack, "biomes/biome.json", """
                {
                  "name": "Biome",
                  "derivative": "minecraft:plains",
                  "riverPolicy": {
                    "routing": "PREFER",
                    "outletAdmission": null,
                    "shoreBiomes": ["river_shore"]
                  }
                }
                """);
        write(pack, "biomes/river_surface.json", """
                {
                  "name": "Surface",
                  "derivative": "minecraft:river",
                  "riverPolicy": {
                    "profiles": ["default"],
                    "bankBiomes": ["river_bank"],
                    "bankMultiplier": 1.25
                  }
                }
                """);
        write(pack, "biomes/river_shore.json",
                "{\"name\":\"Shore\",\"derivative\":\"minecraft:river\"}");
        write(pack, "biomes/river_bank.json",
                "{\"name\":\"Bank\",\"derivative\":\"minecraft:plains\"}");

        PackHydrologyValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertTrue(result.warnings().toString(), result.warnings().isEmpty());
    }

    @Test
    public void acceptsEverySchemaBoundAtItsMinimum() throws Exception {
        File pack = pack(riversDimension("""
                {
                  "enabled": true,
                  "routing": {
                    "tileSize": 256,
                    "sampleSpacing": 8,
                    "maximumRouteLength": 256,
                    "minimumSurfaceCourseLength": 0,
                    "minimumUndergroundCourseLength": 0,
                    "maximumOutletsPerTile": 1,
                    "oceanOutlets": true,
                    "valleyPreference": 0,
                    "uphillPenalty": 0,
                    "slopePenalty": 0,
                    "confluenceAttraction": 0
                  },
                  "surface": {
                    "channel": {
                      "width": {"min": 1, "max": 1},
                      "depth": {"min": 1, "max": 1},
                      "inset": 0,
                      "maximumIncision": 1,
                      "roughness": 0,
                      "roughnessWavelength": 4
                    },
                    "banks": {
                      "freeboard": 0,
                      "shoreWidth": 0.5,
                      "blendSlope": 0.5,
                      "minimumBlendWidth": 1,
                      "maximumBlendWidth": 1,
                      "exposeCutStrata": false
                    },
                    "flow": {"cascadeRun": 1, "waterfallMinimumDrop": 2},
                    "mouths": {"flareRatio": 1, "maximumOceanApron": 0}
                  },
                  "underground": {"mouthLevelingDistance": 16}
                }
                """, "\"bankMultiplier\": 0"));

        PackHydrologyValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void bedRulesRejectBadPaddingAndFluidPaddingBlocks() throws Exception {
        File pack = pack(riversDimension("""
                {
                  "enabled": true,
                  "routing": {"tileSize": 1024, "sampleSpacing": 64, "oceanOutlets": true},
                  "surface": {
                    "bed": {
                      "allowGravityBlocks": false,
                      "padding": 9,
                      "paddingPalette": {"palette": [{"block": "minecraft:water"}]}
                    }
                  }
                }
                """, ""));

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), "padding must be at most 8");
        assertContains(result.errors(), "paddingPalette.palette[0].block must not be a fluid");
    }

    @Test
    public void bedRulesAcceptSolidPaddingBlocks() throws Exception {
        File pack = pack(riversDimension("""
                {
                  "enabled": true,
                  "routing": {"tileSize": 1024, "sampleSpacing": 64, "oceanOutlets": true},
                  "surface": {
                    "bed": {
                      "allowGravityBlocks": true,
                      "padding": 0,
                      "paddingPalette": {"palette": [{"block": "minecraft:clay"}, {"block": "minecraft:dirt", "weight": 2}]}
                    }
                  }
                }
                """, ""));

        PackHydrologyValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().stream().noneMatch((String error) -> error.contains(".bed")));
    }

    @Test
    public void rejectsMouthLevelingDistanceBeyondTheRouteLength() throws Exception {
        File pack = pack(riversDimension("""
                {
                  "enabled": true,
                  "routing": {"tileSize": 1024, "sampleSpacing": 64, "maximumRouteLength": 256, "oceanOutlets": true},
                  "underground": {"mouthLevelingDistance": 512}
                }
                """, ""));

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), "mouthLevelingDistance must not exceed routing.maximumRouteLength");
    }

    @Test
    public void acceptsEverySchemaBoundAtItsMaximum() throws Exception {
        File pack = pack(riversDimension("""
                {
                  "enabled": true,
                  "routing": {
                    "tileSize": 8192,
                    "sampleSpacing": 64,
                    "maximumRouteLength": 32768,
                    "minimumSurfaceCourseLength": 32768,
                    "minimumUndergroundCourseLength": 32768,
                    "maximumOutletsPerTile": 256,
                    "oceanOutlets": true,
                    "valleyPreference": 8,
                    "uphillPenalty": 128,
                    "slopePenalty": 16,
                    "confluenceAttraction": 1
                  },
                  "surface": {
                    "channel": {
                      "width": {"min": 128, "max": 128},
                      "depth": {"min": 64, "max": 64},
                      "inset": 3,
                      "maximumIncision": 32,
                      "roughness": 1,
                      "roughnessWavelength": 64
                    },
                    "banks": {
                      "freeboard": 4,
                      "shoreWidth": 6,
                      "blendSlope": 12,
                      "minimumBlendWidth": 64,
                      "maximumBlendWidth": 64,
                      "exposeCutStrata": true
                    },
                    "flow": {"cascadeRun": 8, "waterfallMinimumDrop": 32},
                    "mouths": {"flareRatio": 4, "maximumOceanApron": 32}
                  },
                  "underground": {"mouthLevelingDistance": 512}
                }
                """, "\"bankMultiplier\": 4"));

        PackHydrologyValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsRoutingWeightsSpacingAndCourseLengths() throws Exception {
        File above = pack(riversDimension("""
                {
                  "enabled": true,
                  "routing": {
                    "tileSize": 2048,
                    "sampleSpacing": 24,
                    "maximumRouteLength": 256,
                    "minimumSurfaceCourseLength": 512,
                    "minimumUndergroundCourseLength": 512,
                    "maximumOutletsPerTile": 0,
                    "valleyPreference": 8.5,
                    "uphillPenalty": 128.5,
                    "slopePenalty": 16.5,
                    "confluenceAttraction": 1.5,
                    "oceanOutlets": false,
                    "inlandOutlets": []
                  }
                }
                """));
        File below = pack(riversDimension("""
                {
                  "enabled": true,
                  "routing": {
                    "tileSize": 128,
                    "sampleSpacing": 4,
                    "maximumRouteLength": 128,
                    "valleyPreference": -0.5,
                    "uphillPenalty": -0.5,
                    "slopePenalty": -0.5,
                    "confluenceAttraction": -0.5
                  }
                }
                """));

        PackHydrologyValidator.Validation aboveResult = validate(above);
        PackHydrologyValidator.Validation belowResult = validate(below);

        assertContains(aboveResult.errors(), ".sampleSpacing must be one of [8, 16, 32, 64].");
        assertContains(aboveResult.errors(), "tileSize must be divisible by sampleSpacing");
        assertContains(aboveResult.errors(), "minimumSurfaceCourseLength must not exceed maximumRouteLength");
        assertContains(aboveResult.errors(), "minimumUndergroundCourseLength must not exceed maximumRouteLength");
        assertContains(aboveResult.errors(), "maximumOutletsPerTile must be at least 1");
        assertContains(aboveResult.errors(), "valleyPreference must be at most 8.0");
        assertContains(aboveResult.errors(), "uphillPenalty must be at most 128.0");
        assertContains(aboveResult.errors(), "slopePenalty must be at most 16.0");
        assertContains(aboveResult.errors(), "confluenceAttraction must be at most 1.0");
        assertContains(aboveResult.errors(), "must enable oceanOutlets or select at least one");
        assertContains(belowResult.errors(), "tileSize must be at least 256");
        assertContains(belowResult.errors(), "sampleSpacing must be at least 8");
        assertContains(belowResult.errors(), "maximumRouteLength must be at least 256");
        assertContains(belowResult.errors(), "valleyPreference must be at least 0.0");
        assertContains(belowResult.errors(), "uphillPenalty must be at least 0.0");
        assertContains(belowResult.errors(), "slopePenalty must be at least 0.0");
        assertContains(belowResult.errors(), "confluenceAttraction must be at least 0.0");
    }

    @Test
    public void rejectsSurfaceChannelValuesOutsideTheirBounds() throws Exception {
        File above = pack(riversDimension("""
                {
                  "enabled": true,
                  "surface": {
                    "channel": {
                      "width": {"min": 20, "max": 2},
                      "depth": {"min": 2, "max": 65},
                      "inset": 4,
                      "maximumIncision": 33,
                      "roughness": 1.5,
                      "roughnessWavelength": 65
                    }
                  }
                }
                """));
        File below = pack(riversDimension("""
                {
                  "enabled": true,
                  "surface": {
                    "channel": {
                      "width": {"min": 0, "max": 8},
                      "depth": {"min": 0, "max": 4},
                      "inset": -1,
                      "maximumIncision": 0,
                      "roughness": -0.1,
                      "roughnessWavelength": 3
                    }
                  }
                }
                """));

        PackHydrologyValidator.Validation aboveResult = validate(above);
        PackHydrologyValidator.Validation belowResult = validate(below);

        assertContains(aboveResult.errors(), "width.min must not exceed max");
        assertContains(aboveResult.errors(), "depth.max must be at most 64.0");
        assertContains(aboveResult.errors(), "inset must be at most 3");
        assertContains(aboveResult.errors(), "maximumIncision must be at most 32");
        assertContains(aboveResult.errors(), "roughness must be at most 1.0");
        assertContains(aboveResult.errors(), "roughnessWavelength must be at most 64");
        assertContains(belowResult.errors(), "width.min must be at least 1.0");
        assertContains(belowResult.errors(), "depth.min must be at least 1.0");
        assertContains(belowResult.errors(), "inset must be at least 0");
        assertContains(belowResult.errors(), "maximumIncision must be at least 1");
        assertContains(belowResult.errors(), "roughness must be at least 0.0");
        assertContains(belowResult.errors(), "roughnessWavelength must be at least 4");
    }

    @Test
    public void rejectsSurfaceBankValuesOutsideTheirBounds() throws Exception {
        File above = pack(riversDimension("""
                {
                  "enabled": true,
                  "surface": {
                    "banks": {
                      "freeboard": 5,
                      "shoreWidth": 6.5,
                      "blendSlope": 12.5,
                      "minimumBlendWidth": 40,
                      "maximumBlendWidth": 65,
                      "exposeCutStrata": "yes"
                    }
                  }
                }
                """));
        File below = pack(riversDimension("""
                {
                  "enabled": true,
                  "surface": {
                    "banks": {
                      "freeboard": -1,
                      "shoreWidth": 0.4,
                      "blendSlope": 0.4,
                      "minimumBlendWidth": 0,
                      "maximumBlendWidth": 8
                    }
                  }
                }
                """));
        File unordered = pack(riversDimension("""
                {
                  "enabled": true,
                  "surface": {
                    "banks": {"minimumBlendWidth": 20, "maximumBlendWidth": 10}
                  }
                }
                """));

        PackHydrologyValidator.Validation aboveResult = validate(above);
        PackHydrologyValidator.Validation belowResult = validate(below);
        PackHydrologyValidator.Validation unorderedResult = validate(unordered);

        assertContains(aboveResult.errors(), "freeboard must be at most 4");
        assertContains(aboveResult.errors(), "shoreWidth must be at most 6.0");
        assertContains(aboveResult.errors(), "blendSlope must be at most 12.0");
        assertContains(aboveResult.errors(), "maximumBlendWidth must be at most 64");
        assertContains(aboveResult.errors(), "exposeCutStrata must be a boolean");
        assertContains(belowResult.errors(), "freeboard must be at least 0");
        assertContains(belowResult.errors(), "shoreWidth must be at least 0.5");
        assertContains(belowResult.errors(), "blendSlope must be at least 0.5");
        assertContains(belowResult.errors(), "minimumBlendWidth must be at least 1");
        assertContains(unorderedResult.errors(), "minimumBlendWidth must not exceed maximumBlendWidth");
    }

    @Test
    public void rejectsSurfaceFlowAndMouthValuesOutsideTheirBounds() throws Exception {
        File above = pack(riversDimension("""
                {
                  "enabled": true,
                  "surface": {
                    "flow": {"cascadeRun": 9, "waterfallMinimumDrop": 33},
                    "mouths": {"flareRatio": 4.5, "maximumOceanApron": 33}
                  }
                }
                """));
        File below = pack(riversDimension("""
                {
                  "enabled": true,
                  "surface": {
                    "flow": {"cascadeRun": 0, "waterfallMinimumDrop": 1},
                    "mouths": {"flareRatio": 0.5, "maximumOceanApron": -1}
                  }
                }
                """));

        PackHydrologyValidator.Validation aboveResult = validate(above);
        PackHydrologyValidator.Validation belowResult = validate(below);

        assertContains(aboveResult.errors(), "cascadeRun must be at most 8");
        assertContains(aboveResult.errors(), "waterfallMinimumDrop must be at most 32");
        assertContains(aboveResult.errors(), "flareRatio must be at most 4.0");
        assertContains(aboveResult.errors(), "maximumOceanApron must be at most 32");
        assertContains(belowResult.errors(), "cascadeRun must be at least 1");
        assertContains(belowResult.errors(), "waterfallMinimumDrop must be at least 2");
        assertContains(belowResult.errors(), "flareRatio must be at least 1.0");
        assertContains(belowResult.errors(), "maximumOceanApron must be at least 0");
    }

    @Test
    public void rejectsUndergroundMouthLevelingDistanceOutsideItsBounds() throws Exception {
        File above = pack(riversDimension("""
                {
                  "enabled": true,
                  "underground": {"mouthLevelingDistance": 513}
                }
                """));
        File below = pack(riversDimension("""
                {
                  "enabled": true,
                  "underground": {"mouthLevelingDistance": 15}
                }
                """));

        PackHydrologyValidator.Validation aboveResult = validate(above);
        PackHydrologyValidator.Validation belowResult = validate(below);

        assertContains(aboveResult.errors(), "mouthLevelingDistance must be at most 512");
        assertContains(belowResult.errors(), "mouthLevelingDistance must be at least 16");
    }

    @Test
    public void rejectsGeometryComplexityAndCapabilityFailures() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -64, "max": 128},
                  "useMantle": false,
                  "carvingEnabled": false,
                  "disabledComponents": ["CARVED", "RIVER_HYDROLOGY"],
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "routing": {
                        "tileSize": 8192,
                        "sampleSpacing": 8,
                        "maximumRouteLength": 32768
                      },
                      "geometry": {
                        "meanders": {
                          "primaryWavelength": 7,
                          "detailWavelength": 129,
                          "maximumTurnDegrees": 151
                        },
                        "underground": {
                          "bedRoundness": 7,
                          "bedRoughness": -0.1,
                          "wallRoughness": 1.1,
                          "roughnessWavelength": 2
                        },
                        "drops": {
                          "cascadeRunPerBlock": 17,
                          "cascadeExponent": 0.2,
                          "maximumCascadeStep": 5,
                          "flowWidthRatio": 0.2,
                          "maximumFlowDepth": 17,
                          "basinWidthRatio": 4.1,
                          "maximumBasinDepth": 33
                        }
                      },
                      "surface": {
                        "sources": {"density": 64, "minimumElevation": 128, "minimumPerTile": 64,
                          "minimumSpacing": 8193}
                      },
                      "underground": {
                        "sources": {"density": 64, "minimumPerTile": 64},
                        "fluidLevel": {"min": -64, "max": 127},
                        "channelWidth": {"min": 4, "max": 18},
                        "depth": {"min": 2, "max": 5},
                        "headroom": {"min": 6, "max": 14}
                      },
                      "profiles": [
                        {
                          "id": "default",
                          "fluidPalette": {"palette": [{"block": "minecraft:water"}]}
                        }
                      ]
                    }
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), "primaryWavelength must be at least 8");
        assertContains(result.errors(), "detailWavelength must be at most 128");
        assertContains(result.errors(), "detailWavelength must not exceed primaryWavelength");
        assertContains(result.errors(), "maximumTurnDegrees must be at most 150");
        assertContains(result.errors(), "bedRoundness must be at most 6");
        assertContains(result.errors(), "bedRoughness must be at least 0");
        assertContains(result.errors(), "wallRoughness must be at most 1");
        assertContains(result.errors(), "roughnessWavelength must be at least 3");
        assertContains(result.errors(), "cascadeRunPerBlock must be at most 16");
        assertContains(result.errors(), "cascadeExponent must be at least 0.25");
        assertContains(result.errors(), "maximumCascadeStep must be at most 4");
        assertContains(result.errors(), "flowWidthRatio must be at least 0.25");
        assertContains(result.errors(), "maximumFlowDepth must be at most 16");
        assertContains(result.errors(), "basinWidthRatio must be at most 4");
        assertContains(result.errors(), "maximumBasinDepth must be at most 32");
        assertContains(result.errors(), "minimumSpacing must be at most 8192");
        assertContains(result.errors(), "coarse lattice nodes");
        assertContains(result.errors(), "route samples per tile");
        assertContains(result.errors(), "requires useMantle to be true");
        assertContains(result.errors(), "requires carvingEnabled to be true");
        assertContains(result.errors(), "requires CARVED to remain enabled");
        assertContains(result.errors(), "requires RIVER_HYDROLOGY to remain enabled");
        assertContains(result.errors(), "minimumElevation must be below dimensionHeight.max");
        assertContains(result.errors(), "fluidLevel.min and depth.max");
        assertContains(result.errors(), "fluidLevel.max and headroom.max");
    }

    @Test
    public void rejectsInvalidProfilesPalettesAndPolicyProfileReferences() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "profiles": [
                        {
                          "id": "water",
                          "fluidPalette": {"palette": [{"block": "minecraft:water"}]}
                        },
                        {
                          "id": "water",
                          "fluidPalette": {"palette": []}
                        },
                        {"id": "Bad Profile"},
                        {
                          "id": "stone",
                          "fluidPalette": {"palette": [{"block": "minecraft:stone"}]}
                        }
                      ]
                    }
                  },
                  "riverPolicy": {
                    "placement": "REQUIRED_HEADWATER",
                    "routing": "ALLOW",
                    "outletAdmission": true,
                    "profiles": ["missing"]
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), "id must be unique inside hydrology.rivers.profiles");
        assertContains(result.errors(), "fluidPalette.palette must contain at least one fluid block");
        assertContains(result.errors(), "id must use 1 to 64 lowercase");
        assertContains(result.errors(), "fluidPalette must be configured");
        assertContains(result.errors(), "may contain only fluid blocks");
        assertContains(result.errors(), "references unknown river profile 'missing'");
    }

    @Test
    public void validatesAllCurrentPolicyFieldsAndBiomeExistence() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "profiles": [
                        {
                          "id": "default",
                          "fluidPalette": {"palette": [{"block": "minecraft:water"}]}
                        }
                      ]
                    }
                  }
                }
                """);
        write(pack, "regions/region.json", """
                {
                  "landBiomes": ["biome"],
                  "riverPolicy": {
                    "placement": "SOURCE",
                    "routing": "CHEAP",
                    "outletAdmission": "yes",
                    "profiles": "default",
                    "surfaceBiomes": ["missing"],
                    "mouthBiomes": "river",
                    "shoreBiomes": ["biome", "biome"],
                    "bankBiomes": ["missing_bank"],
                    "floodedCaveBiomes": ["../escape"],
                    "widthMultiplier": 0,
                    "depthMultiplier": 17,
                    "incisionMultiplier": -1,
                    "routingMultiplier": -1,
                    "bankMultiplier": 4.5
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), ".placement must be one of");
        assertContains(result.errors(), ".routing must be one of");
        assertContains(result.errors(), ".outletAdmission must be a boolean or null");
        assertContains(result.errors(), ".profiles must be an array or null");
        assertContains(result.errors(), "surfaceBiomes[0] references missing biome 'missing'");
        assertContains(result.errors(), ".mouthBiomes must be an array or null");
        assertContains(result.errors(), "shoreBiomes[1] duplicates biome 'biome'");
        assertContains(result.errors(), "bankBiomes[0] references missing biome 'missing_bank'");
        assertContains(result.errors(), "floodedCaveBiomes[0] must name a biome resource");
        assertContains(result.errors(), "widthMultiplier must be at least");
        assertContains(result.errors(), "depthMultiplier must be at most");
        assertContains(result.errors(), "incisionMultiplier must be at least");
        assertContains(result.errors(), "routingMultiplier must be at least");
        assertContains(result.errors(), "bankMultiplier must be at most 4.0");
    }

    @Test
    public void rejectsNegativeBankMultiplierAndNonBiomeBankReferences() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "hydrology": {
                    "rivers": {"enabled": true}
                  },
                  "riverPolicy": {
                    "bankBiomes": [17],
                    "bankMultiplier": -0.5
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), "bankBiomes[0] must name a biome resource");
        assertContains(result.errors(), "bankMultiplier must be at least 0.0");
    }

    @Test
    public void validatesProfileReferencesReachedOnlyThroughAnotherPolicy() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "profiles": [
                        {
                          "id": "default",
                          "fluidPalette": {"palette": [{"block": "minecraft:water"}]}
                        }
                      ]
                    }
                  }
                }
                """);
        write(pack, "biomes/biome.json", """
                {
                  "name": "Root",
                  "derivative": "minecraft:plains",
                  "riverPolicy": {"surfaceBiomes": ["indirect"]}
                }
                """);
        write(pack, "biomes/indirect.json", """
                {
                  "name": "Indirect",
                  "derivative": "minecraft:river",
                  "riverPolicy": {"profiles": ["not_in_dimension"]}
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(
                result.errors(),
                "Biome 'indirect'.riverPolicy.profiles[0] references unknown river profile 'not_in_dimension'"
        );
    }

    @Test
    public void validatesProfileReferencesReachedOnlyThroughBiomeImageMapTargets() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "imageMaps": [
                    {"key": "biome", "map": "policy-map", "application": "BIOME"}
                  ],
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "profiles": [
                        {
                          "id": "default",
                          "fluidPalette": {"palette": [{"block": "minecraft:water"}]}
                        }
                      ]
                    }
                  }
                }
                """);
        write(pack, "image-maps/policy-map.json", """
                {
                  "colors": {"#123456": "iris:mapped_color"},
                  "fallbackTarget": "mapped_fallback"
                }
                """);
        write(pack, "biomes/mapped_color.json", """
                {
                  "name": "Mapped Color",
                  "derivative": "minecraft:river",
                  "riverPolicy": {"profiles": ["missing_color_profile"]}
                }
                """);
        write(pack, "biomes/mapped_fallback.json", """
                {
                  "name": "Mapped Fallback",
                  "derivative": "minecraft:river",
                  "riverPolicy": {"profiles": ["missing_fallback_profile"]}
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(
                result.errors(),
                "Biome 'mapped_color'.riverPolicy.profiles[0] references unknown river profile 'missing_color_profile'"
        );
        assertContains(
                result.errors(),
                "Biome 'mapped_fallback'.riverPolicy.profiles[0] references unknown river profile 'missing_fallback_profile'"
        );
    }

    @Test
    public void rejectsUnsafeDeepFluidConfiguration() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -64, "max": 128},
                  "hydrology": {
                    "deepFluids": [
                      {
                        "id": "deep_lava",
                        "fluidPalette": {"palette": [{"block": "minecraft:lava"}]},
                        "height": {"min": 40, "max": -40},
                        "density": 1,
                        "spacing": 16,
                        "horizontalRadius": 20,
                        "verticalRadius": 3,
                        "channelWidth": 8,
                        "depth": 4,
                        "headroom": 4,
                        "containedPools": "yes",
                        "shortChannels": 1
                      },
                      {
                        "id": "deep_lava",
                        "fluidPalette": {"palette": []},
                        "height": {"min": -63, "max": 127}
                      }
                    ]
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), "height.min must not exceed max");
        assertContains(result.errors(), "spacing must be at least");
        assertContains(result.errors(), "depth and headroom must fit inside verticalRadius");
        assertContains(result.errors(), "containedPools must be a boolean");
        assertContains(result.errors(), "shortChannels must be a boolean");
        assertContains(result.errors(), "id must be unique inside hydrology.deepFluids");
        assertContains(result.errors(), "fluidPalette.palette must contain at least one fluid block");
        assertContains(result.errors(), "height.min and lower footprint envelope must remain above dimensionHeight.min");
        assertContains(result.errors(), "height.max and headroom must remain below dimensionHeight.max");
    }

    @Test
    public void rejectsDeepPoolWhoseVerticalRadiusCrossesTheDimensionFloor() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -64, "max": 128},
                  "hydrology": {
                    "deepFluids": [
                      {
                        "id": "deep_lava",
                        "fluidPalette": {"palette": [{"block": "minecraft:lava"}]},
                        "height": {"min": -60, "max": -40},
                        "density": 1,
                        "spacing": 64,
                        "horizontalRadius": 8,
                        "verticalRadius": 8,
                        "channelWidth": 4,
                        "depth": 2,
                        "headroom": 4,
                        "containedPools": true,
                        "shortChannels": false
                      }
                    ]
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(
                result.errors(),
                "height.min and lower footprint envelope must remain above dimensionHeight.min"
        );
    }

    @Test
    public void rejectsFluidIdsSharedByRiverAndDeepProfiles() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -256, "max": 512},
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "profiles": [
                        {
                          "id": "shared",
                          "fluidPalette": {"palette": [{"block": "minecraft:water"}]}
                        }
                      ]
                    },
                    "deepFluids": [
                      {
                        "id": "shared",
                        "fluidPalette": {"palette": [{"block": "minecraft:lava"}]},
                        "height": {"min": -192, "max": 32},
                        "density": 0.1,
                        "spacing": 1024,
                        "horizontalRadius": 18,
                        "verticalRadius": 8,
                        "channelWidth": 4,
                        "depth": 2,
                        "headroom": 4,
                        "containedPools": true,
                        "shortChannels": true
                      }
                    ]
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), "must not duplicate a hydrology.rivers profile id");
    }

    @Test
    public void rejectsDeepOnlyIdSharedByTheImplicitDefaultRiverProfile() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -256, "max": 512},
                  "hydrology": {
                    "deepFluids": [
                      {
                        "id": "default",
                        "fluidPalette": {"palette": [{"block": "minecraft:lava"}]},
                        "density": 0.5
                      }
                    ]
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertContains(result.errors(), "must not duplicate a hydrology.rivers profile id");
    }

    @Test
    public void omittedDeepGeometryUsesRuntimeDefaults() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -256, "max": 512},
                  "hydrology": {
                    "deepFluids": [
                      {
                        "id": "deep_lava",
                        "fluidPalette": {"palette": [{"block": "minecraft:lava"}]},
                        "density": 0.5
                      }
                    ]
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void omittedGrottoGeometryUsesRuntimeDefaults() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -256, "max": 512},
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "grottos": {
                        "coastal": {"verticalRadius": 6},
                        "inland": {"verticalRadius": 6}
                      }
                    }
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void validatesSurfaceSinkholeConfigurationAsACanonicalInlandOutlet() throws Exception {
        File missingOutlet = pack("""
                {
                  "regions": ["region"],
                  "hydrology": {
                    "rivers": {
                      "grottos": {
                        "inland": {
                          "enabled": false,
                          "connectSurfaceRivers": true
                        }
                      }
                    }
                  }
                }
                """);
        File invalidType = pack("""
                {
                  "regions": ["region"],
                  "hydrology": {
                    "rivers": {
                      "grottos": {
                        "inland": {
                          "enabled": true,
                          "connectSurfaceRivers": "yes"
                        }
                      }
                    }
                  }
                }
                """);

        PackHydrologyValidator.Validation missingOutletResult = validate(missingOutlet);
        PackHydrologyValidator.Validation invalidTypeResult = validate(invalidType);

        assertContains(missingOutletResult.errors(), "connectSurfaceRivers requires the inland grotto to be enabled");
        assertContains(missingOutletResult.errors(), "requires routing.inlandOutlets to select SINKHOLE_GROTTO");
        assertContains(invalidTypeResult.errors(), "connectSurfaceRivers must be a boolean");
    }

    @Test
    public void shapeDisabledDeepEntryDoesNotRequireMantleCapabilities() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -256, "max": 512},
                  "useMantle": false,
                  "carvingEnabled": false,
                  "disabledComponents": ["CARVED", "RIVER_HYDROLOGY"],
                  "hydrology": {
                    "deepFluids": [
                      {
                        "id": "deep_lava",
                        "fluidPalette": {"palette": [{"block": "minecraft:lava"}]},
                        "density": 1,
                        "containedPools": false,
                        "shortChannels": false
                      }
                    ]
                  }
                }
                """);

        PackHydrologyValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
    }

    @Test
    public void rejectsEveryReservedHydrologyFeatureKeywordAsADeepFluidId() throws Exception {
        for (String id : List.of(
                "surface",
                "waterfall",
                "sinkhole",
                "underground",
                "grotto",
                "coastal_grotto",
                "coastal-grotto",
                "inland_grotto",
                "inland-grotto",
                "mouth",
                "ridge_tunnel",
                "ridge-tunnel",
                "deep"
        )) {
            File pack = pack("""
                    {
                      "regions": ["region"],
                      "dimensionHeight": {"min": -256, "max": 512},
                      "hydrology": {
                        "deepFluids": [
                          {
                            "id": "%s",
                            "fluidPalette": {"palette": [{"block": "minecraft:lava"}]},
                            "height": {"min": -192, "max": 32},
                            "density": 0.1,
                            "spacing": 1024,
                            "horizontalRadius": 18,
                            "verticalRadius": 8,
                            "channelWidth": 4,
                            "depth": 2,
                            "headroom": 4,
                            "containedPools": true,
                            "shortChannels": true
                          }
                        ]
                      }
                    }
                    """.formatted(id));

            PackHydrologyValidator.Validation result = validate(pack);

            assertContains(result.errors(), "id must not use a reserved hydrology feature keyword");
        }
    }

    @Test
    public void packValidatorRegistersCanonicalHydrologyValidator() throws Exception {
        File pack = pack("""
                {
                  "regions": ["region"],
                  "logicalHeight": 256,
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "routing": {
                        "tileSize": 1000,
                        "sampleSpacing": 64
                      },
                      "profiles": [
                        {
                          "id": "default",
                          "fluidPalette": {"palette": [{"block": "minecraft:water"}]}
                        }
                      ]
                    }
                  }
                }
                """);

        PackValidationResult result = PackValidator.validateForPackaging(pack);

        assertFalse(result.getBlockingErrors().toString(), result.isLoadable());
        assertContains(result.getBlockingErrors(), "tileSize must be divisible by sampleSpacing");
    }

    private PackHydrologyValidator.Validation validate(File pack) {
        File[] dimensions = new File(pack, "dimensions").listFiles(
                file -> file.isFile() && file.getName().endsWith(".json"));
        return PackHydrologyValidator.validate(pack, dimensions);
    }

    private File pack(String dimensionJson) throws Exception {
        File pack = temporaryFolder.newFolder("pack-" + System.nanoTime());
        write(pack, "dimensions/main.json", dimensionJson);
        write(pack, "regions/region.json", """
                {
                  "landBiomes": ["biome"],
                  "seaBiomes": [],
                  "shoreBiomes": [],
                  "caveBiomes": []
                }
                """);
        write(pack, "biomes/biome.json",
                "{\"name\":\"Biome\",\"derivative\":\"minecraft:plains\"}");
        return pack;
    }

    private String riversDimension(String riversJson) {
        return riversDimension(riversJson, null);
    }

    private String riversDimension(String riversJson, String policyEntries) {
        String policy = policyEntries == null ? "" : ",\n\"riverPolicy\": {" + policyEntries + "}";
        return """
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -256, "max": 512},
                  "hydrology": {
                    "rivers": %s
                  }%s
                }
                """.formatted(riversJson, policy);
    }

    private String canonicalDimension() {
        return """
                {
                  "regions": ["region"],
                  "dimensionHeight": {"min": -256, "max": 512},
                  "hydrology": {
                    "rivers": {
                      "enabled": true,
                      "routing": {
                        "tileSize": 2048,
                        "sampleSpacing": 64,
                        "maximumRouteLength": 8192,
                        "minimumSurfaceCourseLength": 384,
                        "minimumUndergroundCourseLength": 192,
                        "maximumOutletsPerTile": 4,
                        "oceanOutlets": true,
                        "inlandOutlets": ["SINKHOLE_GROTTO"],
                        "valleyPreference": 1.5,
                        "uphillPenalty": 24,
                        "slopePenalty": 2,
                        "confluenceAttraction": 0.2
                      },
                      "geometry": {
                        "meanders": {
                          "primaryWavelength": 64,
                          "detailWavelength": 12,
                          "primaryStrength": 0.34,
                          "detailStrength": 0.42,
                          "maximumOffsetRatio": 0.48,
                          "smoothingPasses": 1,
                          "maximumTurnDegrees": 82
                        },
                        "underground": {
                          "bedRoundness": 2.4,
                          "bedRoughness": 0.28,
                          "wallRoughness": 0.24,
                          "roughnessWavelength": 11
                        },
                        "grottos": {"bedRoundness": 2.4},
                        "drops": {
                          "cascadeRunPerBlock": 2,
                          "cascadeExponent": 1.4,
                          "maximumCascadeStep": 2,
                          "flowWidthRatio": 0.45,
                          "maximumFlowDepth": 2,
                          "basinWidthRatio": 1.8,
                          "maximumBasinDepth": 8
                        }
                      },
                      "surface": {
                        "enabled": true,
                        "sources": {
                          "density": 0.5,
                          "minimumElevation": 88,
                          "minimumPerTile": 1,
                          "minimumSpacing": 384
                        },
                        "channel": {
                          "width": {"min": 4, "max": 8, "style": {"style": "STATIC"}},
                          "depth": {"min": 2, "max": 4},
                          "inset": 1,
                          "maximumIncision": 10,
                          "roughness": 0.25,
                          "roughnessWavelength": 16
                        },
                        "banks": {
                          "freeboard": 1,
                          "shoreWidth": 1.5,
                          "blendSlope": 3,
                          "minimumBlendWidth": 4,
                          "maximumBlendWidth": 32,
                          "exposeCutStrata": true
                        },
                        "flow": {
                          "cascadeRun": 2,
                          "waterfallMinimumDrop": 6
                        },
                        "mouths": {
                          "flareRatio": 1.6,
                          "maximumOceanApron": 8
                        }
                      },
                      "underground": {
                        "enabled": true,
                        "sources": {"density": 0.5, "minimumPerTile": 1, "minimumSpacing": 512},
                        "fluidLevel": {"min": -48, "max": 50},
                        "channelWidth": {"min": 3, "max": 8},
                        "depth": {"min": 1, "max": 3},
                        "headroom": {"min": 6, "max": 14},
                        "connectToExistingCaves": true,
                        "mouthLevelingDistance": 64
                      },
                      "grottos": {
                        "coastal": {
                          "enabled": true,
                          "poolLevel": "SEA_LEVEL",
                          "horizontalRadius": 12,
                          "verticalRadius": 7,
                          "headroom": 4,
                          "maximumVolume": 8192
                        },
                        "inland": {
                          "enabled": true,
                          "connectSurfaceRivers": true,
                          "horizontalRadius": 10,
                          "verticalRadius": 6,
                          "headroom": 4,
                          "maximumVolume": 8192
                        }
                      },
                      "profiles": [
                        {
                          "id": "default",
                          "fluidPalette": {
                            "palette": [{"block": "minecraft:water", "weight": 1}]
                          }
                        }
                      ]
                    },
                    "deepFluids": [
                      {
                        "id": "deep_lava",
                        "fluidPalette": {
                          "palette": [{"block": "minecraft:lava"}]
                        },
                        "height": {"min": -192, "max": 32},
                        "density": 0.5,
                        "spacing": 384,
                        "horizontalRadius": 18,
                        "verticalRadius": 8,
                        "channelWidth": 4,
                        "depth": 2,
                        "headroom": 4,
                        "containedPools": true,
                        "shortChannels": true
                      }
                    ]
                  },
                  "riverPolicy": {
                    "placement": "NATURAL",
                    "routing": "ALLOW",
                    "outletAdmission": true,
                    "profiles": ["default"],
                    "surfaceBiomes": ["river_surface"],
                    "mouthBiomes": ["river_surface"],
                    "shoreBiomes": ["river_shore"],
                    "bankBiomes": ["river_bank"],
                    "floodedCaveBiomes": ["river_bank"],
                    "widthMultiplier": 1,
                    "depthMultiplier": 1,
                    "incisionMultiplier": 1,
                    "routingMultiplier": 1,
                    "bankMultiplier": 1
                  }
                }
                """;
    }

    private void assertContains(List<String> messages, String fragment) {
        assertTrue(messages.toString(), contains(messages, fragment));
    }

    private boolean contains(List<String> messages, String fragment) {
        for (String message : messages) {
            if (message.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private void write(File root, String relative, String content) throws Exception {
        Path target = new File(root, relative).toPath();
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
