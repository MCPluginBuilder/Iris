package art.arcane.iris.engine.hydrology.runtime;

import art.arcane.iris.engine.hydrology.HydrologyColumnLayer;
import art.arcane.iris.engine.hydrology.HydrologyColumnSample;
import art.arcane.iris.engine.hydrology.HydrologyDiagnosticCandidate;
import art.arcane.iris.engine.hydrology.HydrologyDiagnosticRenderSample;
import art.arcane.iris.engine.hydrology.HydrologyFeatureQuery;
import art.arcane.iris.engine.hydrology.HydrologyFeatureRef;
import art.arcane.iris.engine.hydrology.HydrologyFeatureType;
import art.arcane.iris.engine.hydrology.HydrologyGeometrySampler;
import art.arcane.iris.engine.hydrology.HydrologyPlanner;
import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyRenderSample;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;
import art.arcane.iris.engine.hydrology.HydrologyTile;
import art.arcane.iris.engine.hydrology.HydrologyTileCache;
import art.arcane.iris.engine.hydrology.HydrologyTileKey;
import art.arcane.iris.engine.hydrology.cave.CavePosition;
import art.arcane.iris.engine.hydrology.cave.HydrologyCavePlan;
import art.arcane.iris.engine.hydrology.policy.EffectiveRiverPolicy;
import art.arcane.iris.engine.hydrology.policy.RiverPolicyResolver;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisCoastalRiverGrottoConfig;
import art.arcane.iris.engine.object.IrisDeepFluidConfig;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisHydrology;
import art.arcane.iris.engine.object.IrisInlandRiverGrottoConfig;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverGrottoConfig;
import art.arcane.iris.engine.object.IrisRiverChannelShapeConfig;
import art.arcane.iris.engine.object.IrisRiverDropShapeConfig;
import art.arcane.iris.engine.object.IrisRiverGeometryConfig;
import art.arcane.iris.engine.object.IrisRiverHydraulicsConfig;
import art.arcane.iris.engine.object.IrisRiverHydrology;
import art.arcane.iris.engine.object.IrisRiverInlandOutlet;
import art.arcane.iris.engine.object.IrisRiverMeanderConfig;
import art.arcane.iris.engine.object.IrisRiverProfile;
import art.arcane.iris.engine.object.IrisRiverRoutingConfig;
import art.arcane.iris.engine.object.IrisRiverRoutingMode;
import art.arcane.iris.engine.object.IrisStyledRange;
import art.arcane.iris.engine.object.IrisSurfaceRiverChannelConfig;
import art.arcane.iris.engine.object.IrisSurfaceRiverConfig;
import art.arcane.iris.engine.object.IrisSurfaceRiverSourceConfig;
import art.arcane.iris.engine.object.IrisUndergroundRiverConfig;
import art.arcane.iris.engine.object.IrisUndergroundRiverSourceConfig;
import art.arcane.iris.util.project.stream.ProceduralStream;
import art.arcane.volmlib.util.math.RNG;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class IrisHydrologyRuntime implements AutoCloseable {
    private static final int MAXIMUM_CACHE_TILES = 64;
    private static final int MAXIMUM_TERRAIN_SAMPLES = 65_536;
    private static final int MAXIMUM_FEATURE_SEARCH_TILES = 1_089;
    private static final int BIOME_PATCH_SCALE = 32;
    private static final long SURFACE_WIDTH_SALT = 0x5355524657494454L;
    private static final long SURFACE_DEPTH_SALT = 0x5355524644455054L;
    private static final long SURFACE_INSET_SALT = 0x53555246494e5345L;
    private static final long SURFACE_BLEND_SALT = 0x53555246424c454eL;
    private static final long TARGET_POOL_SALT = 0x544152474554504fL;
    private static final long UNDERGROUND_LEVEL_SALT = 0x554e4445524c564cL;
    private static final long UNDERGROUND_WIDTH_SALT = 0x554e444552574944L;
    private static final long UNDERGROUND_DEPTH_SALT = 0x554e444552444550L;
    private static final long UNDERGROUND_HEADROOM_SALT = 0x554e444552484541L;
    private static final long DEEP_HEIGHT_SALT = 0x4445455048454947L;

    private final IrisHydrologyRuntimeContext context;
    private final IrisRiverHydrology rivers;
    private final HydrologyPlannerSettings settings;
    private final HydrologyTileCache cache;
    private final IrisHydrologyRoutingTerrainSampler routingTerrainSampler;
    private final Set<String> profileKeys;
    private final String defaultProfileKey;
    private final Object terrainSampleLock;
    private final LinkedHashMap<Long, HydrologyTerrainSample> terrainSamples;

    public IrisHydrologyRuntime(IrisHydrologyRuntimeContext context) {
        this.context = Objects.requireNonNull(context);
        IrisHydrology hydrology = Objects.requireNonNull(context.dimension().getHydrology());
        this.rivers = Objects.requireNonNull(hydrology.getRivers());
        this.profileKeys = profileKeys(rivers.getProfiles());
        this.defaultProfileKey = profileKeys.isEmpty() ? "default" : profileKeys.iterator().next();
        this.terrainSampleLock = new Object();
        this.terrainSamples = new LinkedHashMap<>(MAXIMUM_TERRAIN_SAMPLES, 0.75F, true);
        this.settings = createSettings(context.dimension(), hydrology);
        HydrologyGeometrySampler geometrySampler = geometrySampler(context, hydrology);
        IrisHydrologyRoutingTerrainSampler.Sources terrainSources = new IrisHydrologyRoutingTerrainSampler.Sources(
                (int x, int z, double rawNaturalHeight) -> ProceduralStream.bypass2DCaches(
                        () -> createTerrainBasis(x, z, rawNaturalHeight)
                ),
                (int x, int z) -> context.naturalHeightProvider().sample(x, z),
                (int x, int z) -> ProceduralStream.bypass2DCaches(
                        () -> context.naturalOceanClassifier().isOcean(x, z)
                )
        );
        this.routingTerrainSampler = new IrisHydrologyRoutingTerrainSampler(
                terrainSources,
                IrisHydrologyRoutingTerrainSampler.SamplingOptions.production(MAXIMUM_TERRAIN_SAMPLES)
        );
        HydrologyPlanner planner = new HydrologyPlanner(
                context.seed(),
                settings,
                this::sampleTerrain,
                routingTerrainSampler,
                geometrySampler,
                0,
                context.caveViewFactory()
        );
        this.cache = new HydrologyTileCache(planner, MAXIMUM_CACHE_TILES);
    }

    public HydrologyPlannerSettings settings() {
        return settings;
    }

    public HydrologyTile tile(HydrologyTileKey key) {
        return cache.get(key);
    }

    public Optional<HydrologyColumnSample> sample(double x, double z) {
        int blockX = (int) StrictMath.floor(x);
        int blockZ = (int) StrictMath.floor(z);
        return cache.columnAt(blockX, blockZ);
    }

    public void prepareChunkColumns(int blockX, int blockZ) {
        cache.prepareChunkColumns(blockX, blockZ);
    }

    public HydrologyRenderSample renderSample(double x, double z) {
        int blockX = (int) StrictMath.floor(x);
        int blockZ = (int) StrictMath.floor(z);
        return cache.renderAt(blockX, blockZ);
    }

    public boolean hasAcceptedSurfaceBiomeInChunk(String biomeKey, int chunkX, int chunkZ) {
        if (biomeKey == null || biomeKey.isBlank()) {
            return false;
        }
        int minimumX = chunkX << 4;
        int minimumZ = chunkZ << 4;
        int maximumX = Math.addExact(minimumX, 16);
        int maximumZ = Math.addExact(minimumZ, 16);
        int tileSize = settings.routing().tileSize();
        int publicationRadius = settings.publicationRadius();
        int minimumTileX = tileCoordinate((long) minimumX - publicationRadius, tileSize);
        int maximumTileX = tileCoordinate((long) maximumX - 1L + publicationRadius, tileSize);
        int minimumTileZ = tileCoordinate((long) minimumZ - publicationRadius, tileSize);
        int maximumTileZ = tileCoordinate((long) maximumZ - 1L + publicationRadius, tileSize);
        for (int tileZ = minimumTileZ; tileZ <= maximumTileZ; tileZ++) {
            for (int tileX = minimumTileX; tileX <= maximumTileX; tileX++) {
                HydrologyTile tile = cache.get(new HydrologyTileKey(tileX, tileZ));
                for (HydrologyColumnSample column : tile.footprint().columnsIn(
                        minimumX,
                        minimumZ,
                        maximumX,
                        maximumZ
                )) {
                    HydrologyColumnLayer layer = column.primarySurfaceLayer().orElse(null);
                    if (layer != null && biomeKey.equals(layer.biomeKey())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public List<HydrologyCavePlan> cavePlansIn(
            int minimumX,
            int minimumZ,
            int maximumX,
            int maximumZ
    ) {
        if (maximumX <= minimumX || maximumZ <= minimumZ) {
            return List.of();
        }
        int tileSize = settings.routing().tileSize();
        int publicationRadius = settings.publicationRadius();
        int minimumTileX = tileCoordinate((long) minimumX - publicationRadius, tileSize);
        int maximumTileX = tileCoordinate((long) maximumX - 1L + publicationRadius, tileSize);
        int minimumTileZ = tileCoordinate((long) minimumZ - publicationRadius, tileSize);
        int maximumTileZ = tileCoordinate((long) maximumZ - 1L + publicationRadius, tileSize);
        LinkedHashMap<Long, HydrologyCavePlan> plans = new LinkedHashMap<>();
        for (int tileZ = minimumTileZ; tileZ <= maximumTileZ; tileZ++) {
            for (int tileX = minimumTileX; tileX <= maximumTileX; tileX++) {
                HydrologyTile tile = cache.get(new HydrologyTileKey(tileX, tileZ));
                for (HydrologyCavePlan plan : tile.cavePlans()) {
                    HydrologyCavePlan existing = plans.putIfAbsent(plan.source().sourceId(), plan);
                    if (existing != null && !existing.equals(plan)) {
                        throw new IllegalStateException("Hydrology cave plan id collision.");
                    }
                }
            }
        }
        return List.copyOf(plans.values());
    }

    public HydrologyRenderSample sampleRenderFootprint(
            double minimumX,
            double minimumZ,
            double maximumX,
            double maximumZ
    ) {
        int minX = (int) StrictMath.floor(Math.min(minimumX, maximumX));
        int minZ = (int) StrictMath.floor(Math.min(minimumZ, maximumZ));
        int maxX = (int) StrictMath.ceil(Math.max(minimumX, maximumX));
        int maxZ = (int) StrictMath.ceil(Math.max(minimumZ, maximumZ));
        if (maxX <= minX) {
            maxX = minX + 1;
        }
        if (maxZ <= minZ) {
            maxZ = minZ + 1;
        }
        LinkedHashMap<Long, HydrologyFeatureRef> features = new LinkedHashMap<>();
        int tileSize = settings.routing().tileSize();
        int publicationRadius = settings.publicationRadius();
        int minimumTileX = tileCoordinate((long) minX - publicationRadius, tileSize);
        int maximumTileX = tileCoordinate((long) maxX - 1L + publicationRadius, tileSize);
        int minimumTileZ = tileCoordinate((long) minZ - publicationRadius, tileSize);
        int maximumTileZ = tileCoordinate((long) maxZ - 1L + publicationRadius, tileSize);
        for (int tileZ = minimumTileZ; tileZ <= maximumTileZ; tileZ++) {
            for (int tileX = minimumTileX; tileX <= maximumTileX; tileX++) {
                HydrologyTile tile = cache.get(new HydrologyTileKey(tileX, tileZ));
                for (HydrologyColumnSample column : tile.footprint().columnsIn(minX, minZ, maxX, maxZ)) {
                    for (HydrologyFeatureRef feature : column.renderSample().features()) {
                        HydrologyFeatureRef existing = features.putIfAbsent(feature.id(), feature);
                        if (existing != null && !existing.equals(feature)) {
                            throw new IllegalStateException("Hydrology feature id collision in renderer footprint.");
                        }
                    }
                }
            }
        }
        return new HydrologyRenderSample(minX, minZ, List.copyOf(features.values()));
    }

    public HydrologyDiagnosticRenderSample sampleDiagnosticFootprint(
            double minimumX,
            double minimumZ,
            double maximumX,
            double maximumZ
    ) {
        int minX = (int) StrictMath.floor(Math.min(minimumX, maximumX));
        int minZ = (int) StrictMath.floor(Math.min(minimumZ, maximumZ));
        int maxX = (int) StrictMath.ceil(Math.max(minimumX, maximumX));
        int maxZ = (int) StrictMath.ceil(Math.max(minimumZ, maximumZ));
        if (maxX <= minX) {
            maxX = minX + 1;
        }
        if (maxZ <= minZ) {
            maxZ = minZ + 1;
        }
        int tileSize = settings.routing().tileSize();
        int publicationRadius = settings.publicationRadius();
        int minimumTileX = tileCoordinate((long) minX - publicationRadius, tileSize);
        int maximumTileX = tileCoordinate((long) maxX - 1L + publicationRadius, tileSize);
        int minimumTileZ = tileCoordinate((long) minZ - publicationRadius, tileSize);
        int maximumTileZ = tileCoordinate((long) maxZ - 1L + publicationRadius, tileSize);
        LinkedHashMap<Long, HydrologyDiagnosticCandidate> candidates = new LinkedHashMap<>();
        for (int tileZ = minimumTileZ; tileZ <= maximumTileZ; tileZ++) {
            for (int tileX = minimumTileX; tileX <= maximumTileX; tileX++) {
                HydrologyTile tile = cache.get(new HydrologyTileKey(tileX, tileZ));
                for (HydrologyDiagnosticCandidate candidate : tile.diagnosticCandidates()) {
                    if (candidate.point().x() < minX || candidate.point().x() >= maxX
                            || candidate.point().z() < minZ || candidate.point().z() >= maxZ) {
                        continue;
                    }
                    HydrologyDiagnosticCandidate existing = candidates.putIfAbsent(candidate.id(), candidate);
                    if (existing != null && !existing.equals(candidate)) {
                        throw new IllegalStateException("Hydrology diagnostic candidate id collision.");
                    }
                }
            }
        }
        return new HydrologyDiagnosticRenderSample(minX, minZ, List.copyOf(candidates.values()));
    }

    public Optional<HydrologyFeatureRef> nearestFeature(
            Set<HydrologyFeatureType> types,
            String profileKey,
            int x,
            int z,
            int maximumDistance
    ) {
        Objects.requireNonNull(types);
        if (types.isEmpty() || maximumDistance < 0) {
            return Optional.empty();
        }
        int tileSize = settings.routing().tileSize();
        int publicationRadius = settings.publicationRadius();
        int minimumTileX = tileCoordinate((long) x - maximumDistance - publicationRadius, tileSize);
        int maximumTileX = tileCoordinate((long) x + maximumDistance + publicationRadius, tileSize);
        int minimumTileZ = tileCoordinate((long) z - maximumDistance - publicationRadius, tileSize);
        int maximumTileZ = tileCoordinate((long) z + maximumDistance + publicationRadius, tileSize);
        long tileCount = HydrologyFeatureSearchBounds.tileCount(
                x, z, maximumDistance, tileSize, publicationRadius);
        if (tileCount > MAXIMUM_FEATURE_SEARCH_TILES) {
            throw new IllegalArgumentException("Hydrology feature search exceeds the bounded tile limit.");
        }
        HydrologyFeatureRef nearest = null;
        long nearestDistanceSquared = Long.MAX_VALUE;
        for (int tileZ = minimumTileZ; tileZ <= maximumTileZ; tileZ++) {
            for (int tileX = minimumTileX; tileX <= maximumTileX; tileX++) {
                HydrologyTile tile = cache.get(new HydrologyTileKey(tileX, tileZ));
                HydrologyFeatureRef feature = tile.nearestFeature(
                        types, profileKey, x, z, maximumDistance).orElse(null);
                if (feature != null) {
                    long deltaX = (long) feature.x() - x;
                    long deltaZ = (long) feature.z() - z;
                    long distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
                    if (nearest == null || distanceSquared < nearestDistanceSquared
                            || distanceSquared == nearestDistanceSquared && feature.id() < nearest.id()) {
                        nearest = feature;
                        nearestDistanceSquared = distanceSquared;
                    }
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    public int maximumFeatureSearchDistance(int x, int z, int requestedDistance) {
        return HydrologyFeatureSearchBounds.maximumDistance(
                x,
                z,
                requestedDistance,
                settings.routing().tileSize(),
                settings.publicationRadius(),
                MAXIMUM_FEATURE_SEARCH_TILES
        );
    }

    public Set<String> profileKeys() {
        return profileKeys;
    }

    public List<String> featureQueryKeys() {
        ArrayList<String> deepFluidIds = new ArrayList<>(settings.deepFluids().size());
        for (HydrologyPlannerSettings.DeepFluid deepFluid : settings.deepFluids()) {
            deepFluidIds.add(deepFluid.id());
        }
        return HydrologyFeatureQuery.suggestions(deepFluidIds);
    }

    @Override
    public void close() {
        cache.close();
        routingTerrainSampler.close();
        synchronized (terrainSampleLock) {
            terrainSamples.clear();
        }
    }

    private HydrologyTerrainSample sampleTerrain(int x, int z) {
        long packed = ((long) x << 32) ^ (z & 0xffffffffL);
        synchronized (terrainSampleLock) {
            HydrologyTerrainSample cached = terrainSamples.get(packed);
            if (cached != null) {
                return cached;
            }
        }
        HydrologyTerrainSample sampled = ProceduralStream.bypass2DCaches(() -> createDetailedTerrainSample(x, z));
        synchronized (terrainSampleLock) {
            HydrologyTerrainSample existing = terrainSamples.get(packed);
            if (existing != null) {
                return existing;
            }
            terrainSamples.put(packed, sampled);
            if (terrainSamples.size() > MAXIMUM_TERRAIN_SAMPLES) {
                Iterator<Long> iterator = terrainSamples.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
        return sampled;
    }

    private HydrologyTerrainSample createDetailedTerrainSample(int x, int z) {
        IrisHydrologyRoutingTerrainSampler.TerrainBasis basis = routingTerrainSampler.basis(x, z);
        double slope = routingTerrainSampler.localSlope(x, z, basis.naturalHeight());
        return basis.terrain().withSlope(slope);
    }

    private IrisHydrologyRoutingTerrainSampler.TerrainBasis createTerrainBasis(
            int x,
            int z,
            double rawNaturalHeight
    ) {
        IrisHydrologyNaturalSample naturalSample = Objects.requireNonNull(
                context.naturalSampleProvider().sample(x, z, rawNaturalHeight),
                "Hydrology natural sample provider returned null at " + x + "," + z
        );
        double sampledNaturalHeight = naturalSample.naturalHeight();
        double resolvedNaturalHeight = Double.isFinite(sampledNaturalHeight)
                ? sampledNaturalHeight
                : rawNaturalHeight;
        if (!Double.isFinite(resolvedNaturalHeight)) {
            throw new IllegalStateException("Hydrology natural height was not finite at " + x + "," + z);
        }
        int naturalHeight = (int) StrictMath.round(resolvedNaturalHeight);
        boolean ocean = naturalSample.ocean();
        IrisBiome biome = naturalSample.biome();
        IrisRegion region = naturalSample.region();
        EffectiveRiverPolicy policy = RiverPolicyResolver.resolve(context.dimension(), region, biome);
        String parentBiomeKey = requireBiomeKey(biome);
        List<String> profiles = validProfiles(policy.profiles());
        int configuredFluidY = settings.underground().minimumFluidY()
                + (settings.underground().maximumFluidY() - settings.underground().minimumFluidY()) / 2;
        if (!rivers.getUnderground().getFluidLevel().isFlat()) {
            int lowestFluidY = Math.max(
                    settings.underground().minimumFluidY(),
                    settings.underground().maximumDepth() + 1
            );
            int highestFluidY = Math.min(
                    settings.underground().maximumFluidY(),
                    naturalHeight - settings.underground().minimumHeadroom() - 1
            );
            if (lowestFluidY <= highestFluidY) {
                configuredFluidY = lowestFluidY + (highestFluidY - lowestFluidY) / 2;
            }
        }
        int caveFloorY = configuredFluidY - settings.underground().maximumDepth();
        boolean caveAvailable = caveFloorY > 0
                && configuredFluidY + settings.underground().maximumHeadroom() < naturalHeight;
        boolean routingAllowed = policy.allowsTransit() && policy.allowsRouting() && !ocean;
        boolean sourceAllowed = routingAllowed && policy.allowsSources();
        double sourceWeight = switch (policy.placement()) {
            case DISABLED, TRANSIT_ONLY -> 0D;
            case NATURAL -> 1D;
            case PREFERRED_HEADWATER -> 4D;
            case REQUIRED_HEADWATER -> 8D;
        };
        double routingCost = policy.routing() == IrisRiverRoutingMode.AVOID ? 1024D : 0D;
        double routingPreference = policy.routing() == IrisRiverRoutingMode.PREFER ? 0.5D : 1D;
        double biomePatchNoise = coherentPatchNoise(context.seed(), x, z);
        HydrologyTerrainSample terrain = new HydrologyTerrainSample(
                naturalHeight,
                0D,
                ocean,
                caveAvailable,
                caveFloorY,
                configuredFluidY,
                routingAllowed,
                routingAllowed && policy.outletAdmission(),
                sourceAllowed,
                sourceAllowed && policy.requiresHeadwaters(),
                sourceAllowed && caveAvailable,
                sourceAllowed && caveAvailable && policy.requiresHeadwaters(),
                routingCost,
                sourceWeight,
                sourceWeight,
                policy.widthMultiplier(),
                policy.depthMultiplier(),
                policy.incisionMultiplier(),
                policy.routingMultiplier() * routingPreference,
                parentBiomeKey,
                selectKey(policy.surfaceBiomes(), parentBiomeKey, biomePatchNoise, 1),
                selectKey(policy.mouthBiomes(), parentBiomeKey, biomePatchNoise, 2),
                selectKey(policy.shoreBiomes(), parentBiomeKey, biomePatchNoise, 3),
                selectKey(policy.dryBiomes(), parentBiomeKey, biomePatchNoise, 4),
                selectKey(policy.floodedCaveBiomes(), parentBiomeKey, biomePatchNoise, 5),
                profiles
        );
        return new IrisHydrologyRoutingTerrainSampler.TerrainBasis(resolvedNaturalHeight, terrain);
    }

    private List<String> validProfiles(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of(defaultProfileKey);
        }
        ArrayList<String> valid = new ArrayList<>();
        for (String key : requested) {
            if (key != null && profileKeys.contains(key) && !valid.contains(key)) {
                valid.add(key);
            }
        }
        return valid.isEmpty() ? List.of(defaultProfileKey) : List.copyOf(valid);
    }

    private String selectKey(List<String> keys, String fallback, double patchNoise, int salt) {
        if (keys == null || keys.isEmpty()) {
            return fallback;
        }
        return keys.get(keyIndex(patchNoise, salt, keys.size()));
    }

    static int coherentKeyIndex(long seed, int x, int z, int salt, int keyCount) {
        if (keyCount <= 0) {
            throw new IllegalArgumentException("keyCount must be positive");
        }
        if (keyCount == 1) {
            return 0;
        }
        return keyIndex(coherentPatchNoise(seed, x, z), salt, keyCount);
    }

    private static int keyIndex(double patchNoise, int salt, int keyCount) {
        double shifted = patchNoise + salt * 0.3819660112501051D;
        shifted -= StrictMath.floor(shifted);
        return Math.min(keyCount - 1, (int) StrictMath.floor(shifted * keyCount));
    }

    private static double coherentPatchNoise(long seed, int x, int z) {
        int cellX = Math.floorDiv(x, BIOME_PATCH_SCALE);
        int cellZ = Math.floorDiv(z, BIOME_PATCH_SCALE);
        double localX = Math.floorMod(x, BIOME_PATCH_SCALE) / (double) BIOME_PATCH_SCALE;
        double localZ = Math.floorMod(z, BIOME_PATCH_SCALE) / (double) BIOME_PATCH_SCALE;
        double smoothX = localX * localX * (3D - 2D * localX);
        double smoothZ = localZ * localZ * (3D - 2D * localZ);
        double top = interpolate(
                keyNoise(seed, cellX, cellZ),
                keyNoise(seed, cellX + 1, cellZ),
                smoothX
        );
        double bottom = interpolate(
                keyNoise(seed, cellX, cellZ + 1),
                keyNoise(seed, cellX + 1, cellZ + 1),
                smoothX
        );
        return interpolate(top, bottom, smoothZ);
    }

    private static double keyNoise(long seed, int cellX, int cellZ) {
        long mixed = avalanche(seed ^ 0x9e3779b97f4a7c15L);
        mixed = avalanche(mixed ^ avalanche(cellX + 0x9e3779b97f4a7c15L));
        mixed = avalanche(mixed ^ avalanche(cellZ + 0x9e3779b97f4a7c15L));
        return (avalanche(mixed) >>> 11) * 0x1.0p-53;
    }

    private static long avalanche(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    private static double interpolate(double first, double second, double progress) {
        return first + (second - first) * progress;
    }

    private static HydrologyGeometrySampler geometrySampler(
            IrisHydrologyRuntimeContext context,
            IrisHydrology hydrology
    ) {
        IrisRiverHydrology rivers = hydrology.getRivers();
        IrisSurfaceRiverConfig surface = rivers.getSurface();
        IrisSurfaceRiverChannelConfig channel = surface.getChannel();
        IrisRiverHydraulicsConfig hydraulics = surface.getHydraulics();
        IrisUndergroundRiverConfig underground = rivers.getUnderground();
        ProceduralStream<Double> surfaceWidth = styledStream(context, channel.getWidth(), SURFACE_WIDTH_SALT);
        ProceduralStream<Double> surfaceDepth = styledStream(context, channel.getDepth(), SURFACE_DEPTH_SALT);
        ProceduralStream<Double> surfaceInset = styledStream(context, channel.getSurfaceInset(), SURFACE_INSET_SALT);
        ProceduralStream<Double> surfaceBlend = styledStream(
                context,
                channel.getTerrainBlendWidth(),
                SURFACE_BLEND_SALT
        );
        ProceduralStream<Double> targetPool = styledStream(
                context,
                hydraulics.getTargetPoolLength(),
                TARGET_POOL_SALT
        );
        ProceduralStream<Double> undergroundLevel = styledStream(
                context,
                underground.getFluidLevel(),
                UNDERGROUND_LEVEL_SALT
        );
        ProceduralStream<Double> undergroundWidth = styledStream(
                context,
                underground.getChannelWidth(),
                UNDERGROUND_WIDTH_SALT
        );
        ProceduralStream<Double> undergroundDepth = styledStream(
                context,
                underground.getDepth(),
                UNDERGROUND_DEPTH_SALT
        );
        ProceduralStream<Double> undergroundHeadroom = styledStream(
                context,
                underground.getHeadroom(),
                UNDERGROUND_HEADROOM_SALT
        );
        LinkedHashMap<String, ProceduralStream<Double>> deepHeights = new LinkedHashMap<>();
        for (IrisDeepFluidConfig deepFluid : hydrology.getDeepFluids()) {
            long profileSalt = DEEP_HEIGHT_SALT ^ deepFluid.getId().hashCode();
            deepHeights.put(deepFluid.getId(), styledStream(context, deepFluid.getHeight(), profileSalt));
        }
        int minimumWorldY = context.dimension().getMinHeight();
        return request -> {
            ProceduralStream<Double> stream = switch (request.field()) {
                case SURFACE_WIDTH -> surfaceWidth;
                case SURFACE_DEPTH -> surfaceDepth;
                case SURFACE_INSET -> surfaceInset;
                case SURFACE_BLEND_WIDTH -> surfaceBlend;
                case TARGET_POOL_LENGTH -> targetPool;
                case UNDERGROUND_FLUID_LEVEL -> undergroundLevel;
                case UNDERGROUND_WIDTH -> undergroundWidth;
                case UNDERGROUND_DEPTH -> undergroundDepth;
                case UNDERGROUND_HEADROOM -> undergroundHeadroom;
                case DEEP_FLUID_HEIGHT -> Objects.requireNonNull(
                        deepHeights.get(request.profileKey()),
                        "Missing deep-fluid height style for " + request.profileKey() + "."
                );
            };
            double sampled = stream.get(request.x(), request.z());
            if (!Double.isFinite(sampled)) {
                throw new IllegalStateException(
                        "Hydrology geometry style returned a non-finite value for " + request.field() + "."
                );
            }
            long rounded = StrictMath.round(sampled);
            if (request.field() == HydrologyGeometrySampler.Field.UNDERGROUND_FLUID_LEVEL
                    || request.field() == HydrologyGeometrySampler.Field.DEEP_FLUID_HEIGHT) {
                rounded = Math.subtractExact(rounded, minimumWorldY);
            }
            int value = Math.toIntExact(rounded);
            return Math.max(request.minimum(), Math.min(request.maximum(), value));
        };
    }

    private static ProceduralStream<Double> styledStream(
            IrisHydrologyRuntimeContext context,
            IrisStyledRange range,
            long salt
    ) {
        return range.stream(new RNG(context.seed() ^ salt), context.data());
    }

    static HydrologyPlannerSettings createSettings(
            IrisDimension dimension,
            IrisHydrology hydrology
    ) {
        IrisRiverHydrology rivers = hydrology.getRivers();
        IrisRiverRoutingConfig routing = rivers.getRouting();
        IrisSurfaceRiverConfig surface = rivers.getSurface();
        IrisSurfaceRiverSourceConfig surfaceSources = surface.getSources();
        IrisSurfaceRiverChannelConfig channel = surface.getChannel();
        IrisRiverHydraulicsConfig hydraulics = surface.getHydraulics();
        IrisUndergroundRiverConfig underground = rivers.getUnderground();
        IrisUndergroundRiverSourceConfig undergroundSources = underground.getSources();
        int routeNodes = maximumRouteNodes(routing);
        int minimumWorldY = dimension.getMinHeight();
        boolean riversEnabled = rivers.isEnabled();
        boolean surfaceEnabled = riversEnabled && surface.isEnabled();
        boolean undergroundEnabled = riversEnabled && underground.isEnabled();
        HydrologyPlannerSettings.Routing plannerRouting = new HydrologyPlannerSettings.Routing(
                routing.getTileSize(),
                routing.getSampleSpacing(),
                routing.getRefinementSpacing(),
                routeNodes,
                routing.getMaximumRouteLength(),
                new HydrologyPlannerSettings.Branching(
                        routing.getBranching().getMinimumSurfaceCourseLength(),
                        routing.getBranching().getMinimumUndergroundCourseLength()
                ),
                1.5D,
                24D,
                2D,
                0.2D
        );
        HydrologyPlannerSettings.Source plannerSurfaceSources = new HydrologyPlannerSettings.Source(
                surfaceEnabled,
                surfaceSources.getDensity(),
                surfaceSources.getMinimumElevation() - minimumWorldY,
                surfaceSources.getMinimumPerTile(),
                maximumSources(surfaceSources.getDensity(), surfaceSources.getMinimumPerTile()),
                surfaceSources.getMinimumSpacing()
        );
        HydrologyPlannerSettings.Surface plannerSurface = new HydrologyPlannerSettings.Surface(
                surfaceEnabled,
                plannerSurfaceSources,
                minimumInt(channel.getWidth()),
                maximumInt(channel.getWidth()),
                minimumInt(channel.getDepth()),
                maximumInt(channel.getDepth()),
                minimumInt(channel.getSurfaceInset()),
                maximumInt(channel.getSurfaceInset()),
                channel.getMaximumIncision(),
                channel.getShoreWidth(),
                minimumInt(channel.getTerrainBlendWidth()),
                maximumInt(channel.getTerrainBlendWidth()),
                surface.getRidgeTunnels().isEnabled(),
                surface.getRidgeTunnels().getMaximumLength(),
                surface.getRidgeTunnels().getHeadroom()
        );
        HydrologyPlannerSettings.Hydraulics plannerHydraulics = new HydrologyPlannerSettings.Hydraulics(
                minimumInt(hydraulics.getTargetPoolLength()),
                maximumInt(hydraulics.getTargetPoolLength()),
                hydraulics.getRiffleDrop(),
                hydraulics.getMaximumGradualDrop(),
                hydraulics.getMaximumGradualLength(),
                hydraulics.getWaterfallMinimumDrop()
        );
        HydrologyPlannerSettings.Source plannerUndergroundSources = new HydrologyPlannerSettings.Source(
                undergroundEnabled,
                undergroundSources.getDensity(),
                Integer.MIN_VALUE,
                undergroundSources.getMinimumPerTile(),
                maximumSources(undergroundSources.getDensity(), undergroundSources.getMinimumPerTile()),
                undergroundSources.getMinimumSpacing()
        );
        HydrologyPlannerSettings.Underground plannerUnderground = new HydrologyPlannerSettings.Underground(
                undergroundEnabled,
                plannerUndergroundSources,
                minimumInt(underground.getFluidLevel()) - minimumWorldY,
                maximumInt(underground.getFluidLevel()) - minimumWorldY,
                minimumInt(underground.getChannelWidth()),
                maximumInt(underground.getChannelWidth()),
                minimumInt(underground.getDepth()),
                maximumInt(underground.getDepth()),
                minimumInt(underground.getHeadroom()),
                maximumInt(underground.getHeadroom()),
                underground.isConnectToExistingCaves()
        );
        IrisRiverGrottoConfig grottos = rivers.getGrottos();
        IrisCoastalRiverGrottoConfig coastal = grottos.getCoastal();
        IrisInlandRiverGrottoConfig inland = grottos.getInland();
        boolean inlandEnabled = inland.isEnabled()
                && routing.getInlandOutlets().contains(IrisRiverInlandOutlet.SINKHOLE_GROTTO);
        HydrologyPlannerSettings.Outlets plannerOutlets = new HydrologyPlannerSettings.Outlets(
                routing.isOceanOutlets(),
                grotto(coastal.isEnabled(), coastal.getHorizontalRadius(), coastal.getVerticalRadius(),
                        coastal.getHeadroom(), coastal.getMaximumVolume()),
                grotto(inlandEnabled, inland.getHorizontalRadius(), inland.getVerticalRadius(),
                        inland.getHeadroom(), inland.getMaximumVolume()),
                inlandEnabled && inland.isConnectSurfaceRivers(),
                Math.max(4, coastal.getVerticalRadius()),
                surface.getMouths().getLevelingDistance(),
                surface.getMouths().getMaximumOceanApron(),
                routing.getMaximumOutletsPerTile()
        );
        HydrologyPlannerSettings.Geometry plannerGeometry = geometry(rivers.getGeometry());
        return new HydrologyPlannerSettings(
                dimension.getFluidHeight(),
                plannerRouting,
                plannerSurface,
                plannerHydraulics,
                plannerUnderground,
                plannerOutlets,
                plannerGeometry,
                deepFluids(hydrology.getDeepFluids(), minimumWorldY, routing)
        );
    }

    private static HydrologyPlannerSettings.Geometry geometry(IrisRiverGeometryConfig geometry) {
        IrisRiverMeanderConfig meanders = geometry.getMeanders();
        IrisRiverDropShapeConfig drops = geometry.getDrops();
        return new HydrologyPlannerSettings.Geometry(
                new HydrologyPlannerSettings.Meanders(
                        meanders.getPrimaryWavelength(),
                        meanders.getDetailWavelength(),
                        meanders.getPrimaryStrength(),
                        meanders.getDetailStrength(),
                        meanders.getMaximumOffsetRatio(),
                        meanders.getSmoothingPasses(),
                        meanders.getMaximumTurnDegrees()
                ),
                channelShape(geometry.getSurface()),
                channelShape(geometry.getUnderground()),
                channelShape(geometry.getGrottos()),
                new HydrologyPlannerSettings.Drops(
                        drops.getCascadeRunPerBlock(),
                        drops.getCascadeExponent(),
                        drops.getMaximumCascadeStep(),
                        drops.getFlowWidthRatio(),
                        drops.getMaximumFlowDepth(),
                        drops.getBasinWidthRatio(),
                        drops.getMaximumBasinDepth()
                )
        );
    }

    private static HydrologyPlannerSettings.ChannelShape channelShape(IrisRiverChannelShapeConfig shape) {
        return new HydrologyPlannerSettings.ChannelShape(
                shape.getBedRoundness(),
                shape.getBedRoughness(),
                shape.getWallRoughness(),
                shape.getRoughnessWavelength()
        );
    }

    private static HydrologyPlannerSettings.Grotto grotto(
            boolean enabled,
            int horizontalRadius,
            int verticalRadius,
            int headroom,
            int maximumVolume
    ) {
        return new HydrologyPlannerSettings.Grotto(
                enabled,
                horizontalRadius,
                verticalRadius,
                headroom,
                maximumVolume
        );
    }

    private static List<HydrologyPlannerSettings.DeepFluid> deepFluids(
            List<IrisDeepFluidConfig> configurations,
            int minimumWorldY,
            IrisRiverRoutingConfig routing
    ) {
        ArrayList<HydrologyPlannerSettings.DeepFluid> deepFluids = new ArrayList<>();
        for (IrisDeepFluidConfig configuration : configurations) {
            int radius = configuration.getHorizontalRadius();
            int verticalRadius = configuration.getVerticalRadius();
            int maximumVolume = boundedVolume(radius, verticalRadius, configuration.getHeadroom());
            int maximumPerTile = Math.min(64, maximumSources(configuration.getDensity(), 0));
            int maximumChannelLength = maximumDeepChannelLength(configuration, routing);
            int minimumChannelLength = maximumChannelLength == 0
                    ? 0
                    : Math.min(maximumChannelLength, Math.max(routing.getRefinementSpacing(), radius));
            deepFluids.add(new HydrologyPlannerSettings.DeepFluid(
                    configuration.getId(),
                    configuration.getDensity() > 0D
                            && (configuration.isContainedPools() || configuration.isShortChannels()),
                    configuration.getDensity(),
                    configuration.getSpacing(),
                    minimumInt(configuration.getHeight()) - minimumWorldY,
                    maximumInt(configuration.getHeight()) - minimumWorldY,
                    radius,
                    radius,
                    verticalRadius,
                    verticalRadius,
                    minimumChannelLength,
                    maximumChannelLength,
                    configuration.getChannelWidth(),
                    configuration.getDepth(),
                    configuration.getHeadroom(),
                    maximumVolume,
                    maximumPerTile,
                    configuration.isContainedPools(),
                    configuration.isShortChannels()
            ));
        }
        return List.copyOf(deepFluids);
    }

    static int maximumDeepChannelLength(
            IrisDeepFluidConfig configuration,
            IrisRiverRoutingConfig routing
    ) {
        if (!configuration.isShortChannels()) {
            return 0;
        }
        int configuredLength = Math.max(
                routing.getRefinementSpacing(), configuration.getSpacing() / 3);
        return Math.min(configuredLength, routing.getTileSize() / 2);
    }

    private static int maximumRouteNodes(IrisRiverRoutingConfig routing) {
        int halo = Math.min(routing.getMaximumRouteLength(), routing.getTileSize() / 2);
        int alignedHalo = Math.floorDiv(halo, routing.getSampleSpacing()) * routing.getSampleSpacing();
        int width = routing.getTileSize() / routing.getSampleSpacing() + 1
                + alignedHalo * 2 / routing.getSampleSpacing();
        long nodes = (long) width * width;
        if (nodes > 1_000_000L) {
            throw new IllegalArgumentException("Hydrology routing lattice exceeds one million nodes.");
        }
        return (int) nodes;
    }

    private static int tileCoordinate(long blockCoordinate, int tileSize) {
        return Math.toIntExact(Math.floorDiv(blockCoordinate, tileSize));
    }

    private static int maximumSources(double density, int minimumPerTile) {
        int expected = (int) Math.ceil(Math.max(0D, density));
        return Math.min(64, Math.max(minimumPerTile, expected));
    }

    private static int boundedVolume(int radius, int verticalRadius, int headroom) {
        long diameter = radius * 2L + 1L;
        long volume = diameter * diameter * (verticalRadius + headroom + 1L);
        return (int) Math.min(1_048_576L, Math.max(64L, volume));
    }

    private static int minimumInt(IrisStyledRange range) {
        return (int) StrictMath.floor(Math.min(range.getMin(), range.getMax()));
    }

    private static int maximumInt(IrisStyledRange range) {
        return (int) StrictMath.ceil(Math.max(range.getMin(), range.getMax()));
    }

    private static Set<String> profileKeys(List<IrisRiverProfile> profiles) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (IrisRiverProfile profile : profiles) {
            if (profile != null && profile.getId() != null && !profile.getId().isBlank()) {
                keys.add(profile.getId().trim());
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(keys));
    }

    private static String requireBiomeKey(IrisBiome biome) {
        if (biome == null || biome.getLoadKey() == null || biome.getLoadKey().isBlank()) {
            throw new IllegalStateException("Hydrology terrain sampling requires a loaded parent biome.");
        }
        return biome.getLoadKey();
    }
}
