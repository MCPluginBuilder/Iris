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
                    "bankBiomes": ["river_dry"]
                  }
                }
                """);
        write(pack, "biomes/river_shore.json",
                "{\"name\":\"Shore\",\"derivative\":\"minecraft:river\"}");
        write(pack, "biomes/river_dry.json",
                "{\"name\":\"Dry\",\"derivative\":\"minecraft:plains\"}");

        PackHydrologyValidator.Validation result = validate(pack);

        assertTrue(result.errors().toString(), result.errors().isEmpty());
        assertTrue(result.warnings().toString(), result.warnings().isEmpty());
    }

    @Test
    public void rejectsRoutingGeometryHydraulicComplexityAndCapabilityFailures() throws Exception {
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
                        "refinementSpacing": 3,
                        "branching": {
                          "minimumSurfaceCourseLength": 32769,
                          "minimumUndergroundCourseLength": 32769
                        },
                        "maximumRouteLength": 32768,
                        "oceanOutlets": false,
                        "inlandOutlets": []
                      },
                      "geometry": {
                        "meanders": {
                          "primaryWavelength": 7,
                          "detailWavelength": 129,
                          "maximumTurnDegrees": 151
                        },
                        "surface": {
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
                          "minimumSpacing": 8193},
                        "channel": {
                          "width": {"min": 20, "max": 2},
                          "depth": {"min": 2, "max": 5},
                          "surfaceInset": {"min": 0, "max": 65},
                          "maximumIncision": 65,
                          "shoreWidth": 3,
                          "terrainBlendWidth": {"min": 3, "max": 65}
                        },
                        "hydraulics": {
                          "targetPoolLength": {"min": 8, "max": 32},
                          "riffleDrop": 5,
                          "maximumGradualDrop": 3,
                          "maximumGradualLength": 2,
                          "waterfallMinimumDrop": 8
                        },
                        "ridgeTunnels": {"maximumLength": 4097, "headroom": 4},
                        "mouths": {"levelingDistance": 4, "maximumOceanApron": 8}
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

        assertContains(result.errors(), "sampleSpacing must be divisible by refinementSpacing");
        assertContains(result.errors(), "primaryWavelength must be at least 8");
        assertContains(result.errors(), "detailWavelength must be at most 128");
        assertContains(result.errors(), "detailWavelength must not exceed primaryWavelength");
        assertContains(result.errors(), "maximumTurnDegrees must be at most 150");
        assertContains(result.errors(), "bedRoundness must be at most 6");
        assertContains(result.errors(), "bedRoughness must be at least 0");
        assertContains(result.errors(), "wallRoughness must be at most 1");
        assertContains(result.errors(), "roughnessWavelength must be at least 3");
        assertContains(result.errors(), "minimumSurfaceCourseLength must be at most 32768");
        assertContains(result.errors(), "minimumUndergroundCourseLength must be at most 32768");
        assertContains(result.errors(), "cascadeRunPerBlock must be at most 16");
        assertContains(result.errors(), "cascadeExponent must be at least 0.25");
        assertContains(result.errors(), "maximumCascadeStep must be at most 4");
        assertContains(result.errors(), "flowWidthRatio must be at least 0.25");
        assertContains(result.errors(), "maximumFlowDepth must be at most 16");
        assertContains(result.errors(), "basinWidthRatio must be at most 4");
        assertContains(result.errors(), "maximumBasinDepth must be at most 32");
        assertContains(result.errors(), "minimumSpacing must be at most 8192");
        assertContains(result.errors(), "must enable oceanOutlets or select at least one");
        assertContains(result.errors(), "shoreWidth must be at most 2");
        assertContains(result.errors(), "terrainBlendWidth.min must be at least 4");
        assertContains(result.errors(), "terrainBlendWidth.max must be at most 64");
        assertContains(result.errors(), "width.min must not exceed max");
        assertContains(result.errors(), "surfaceInset.min must be at least 1");
        assertContains(result.errors(), "surfaceInset.max must be at most 64");
        assertContains(result.errors(), "maximumIncision must be at most 64");
        assertContains(result.errors(), "riffleDrop must not exceed maximumGradualDrop");
        assertContains(result.errors(), "waterfallMinimumDrop must equal maximumGradualDrop plus one");
        assertContains(result.errors(), "maximumGradualLength must be at least maximumGradualDrop");
        assertContains(result.errors(), "maximumLength must be at most 4096");
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
                    "bankBiomes": [17],
                    "floodedCaveBiomes": ["../escape"],
                    "widthMultiplier": 0,
                    "depthMultiplier": 17,
                    "incisionMultiplier": -1,
                    "routingMultiplier": -1
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
        assertContains(result.errors(), "bankBiomes[0] must name a biome resource");
        assertContains(result.errors(), "floodedCaveBiomes[0] must name a biome resource");
        assertContains(result.errors(), "widthMultiplier must be at least");
        assertContains(result.errors(), "depthMultiplier must be at most");
        assertContains(result.errors(), "incisionMultiplier must be at least");
        assertContains(result.errors(), "routingMultiplier must be at least");
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
                        "tileSize": 2048,
                        "sampleSpacing": 63,
                        "refinementSpacing": 8
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
                        "refinementSpacing": 8,
                        "maximumRouteLength": 8192,
                        "oceanOutlets": true,
                        "inlandOutlets": ["SINKHOLE_GROTTO"]
                      },
                      "surface": {
                        "enabled": true,
                        "sources": {
                          "density": 0.5,
                          "minimumElevation": 88,
                          "minimumPerTile": 1
                        },
                        "channel": {
                          "width": {"min": 4, "max": 32, "style": {"style": "STATIC"}},
                          "depth": {"min": 2, "max": 5},
                          "maximumIncision": 12,
                          "shoreWidth": 1.5,
                          "terrainBlendWidth": {"min": 4, "max": 10}
                        },
                        "hydraulics": {
                          "targetPoolLength": {"min": 48, "max": 160},
                          "riffleDrop": 1,
                          "maximumGradualDrop": 3,
                          "maximumGradualLength": 10,
                          "waterfallMinimumDrop": 4
                        },
                        "ridgeTunnels": {
                          "enabled": true,
                          "maximumLength": 192,
                          "headroom": 10
                        },
                        "mouths": {
                          "levelingDistance": 64,
                          "maximumOceanApron": 2
                        }
                      },
                      "underground": {
                        "enabled": true,
                        "sources": {"density": 0.5, "minimumPerTile": 1},
                        "fluidLevel": {"min": -48, "max": 50},
                        "channelWidth": {"min": 4, "max": 18},
                        "depth": {"min": 2, "max": 5},
                        "headroom": {"min": 6, "max": 14},
                        "connectToExistingCaves": true
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
                    "bankBiomes": ["river_dry"],
                    "floodedCaveBiomes": ["river_dry"],
                    "widthMultiplier": 1,
                    "depthMultiplier": 1,
                    "incisionMultiplier": 1,
                    "routingMultiplier": 1
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
