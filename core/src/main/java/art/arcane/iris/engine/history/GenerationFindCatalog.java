package art.arcane.iris.engine.history;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.volmlib.util.collection.KList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class GenerationFindCatalog {
    private static final Map<GenerationHistory, RetainedCatalog> RETAINED = new WeakHashMap<>();
    private static final RetainedCatalog EMPTY = new RetainedCatalog(0L);

    private GenerationFindCatalog() {
    }

    public static KList<IrisBiome> biomes(Engine engine) {
        return merge(engine.getAllBiomes(), retained(engine).biomes);
    }

    public static IrisBiome biome(Engine engine, String key) {
        return find(biomes(engine), key);
    }

    public static KList<IrisRegion> regions(Engine engine) {
        return merge(engine.getDimension().getAllRegions(engine), retained(engine).regions);
    }

    public static IrisRegion region(Engine engine, String key) {
        return find(regions(engine), key);
    }

    public static KList<String> objectKeys(Engine engine) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : engine.getData().getObjectLoader().getPossibleKeys()) {
            keys.add(key);
        }
        keys.addAll(retained(engine).objects);
        return new KList<>(keys);
    }

    public static boolean hasObjectPlacement(Engine engine, String key) {
        return engine.hasObjectPlacement(key) || retained(engine).objects.contains(normalizeObjectKey(key));
    }

    public static KList<String> retainedStructureKeys(Engine engine) {
        return new KList<>(retained(engine).structures);
    }

    public static boolean hasRetainedStructurePlacement(Engine engine, String key) {
        return retained(engine).structures.contains(normalizeKey(key));
    }

    private static RetainedCatalog retained(Engine engine) {
        if (!(engine instanceof IrisEngine irisEngine)) {
            return EMPTY;
        }
        return irisEngine.getGenerationHistoryRuntimeRouter()
                .map(router -> retained(router.history()))
                .orElse(EMPTY);
    }

    private static RetainedCatalog retained(GenerationHistory history) {
        GenerationManifest manifest = history.manifest();
        synchronized (RETAINED) {
            RetainedCatalog catalog = RETAINED.get(history);
            if (catalog != null && catalog.activeActivationId == manifest.activeActivation().activationId()) {
                return catalog;
            }
            catalog = loadRetained(history, manifest);
            RETAINED.put(history, catalog);
            return catalog;
        }
    }

    private static RetainedCatalog loadRetained(GenerationHistory history, GenerationManifest manifest) {
        RetainedCatalog catalog = new RetainedCatalog(manifest.activeActivation().activationId());
        Set<String> loadedPacks = new HashSet<>();
        Long activationId = manifest.activeActivation().parentActivationId();
        while (activationId != null) {
            GenerationActivation activation = manifest.activation(activationId).orElseThrow();
            GenerationEpoch epoch = manifest.epoch(activation.epochId()).orElseThrow();
            String dimensionKey = epoch.dimensionContract().dimensionKey();
            if (loadedPacks.add(epoch.packFingerprint() + ":" + dimensionKey)) {
                try {
                    loadPack(catalog, history.packRoot(activationId), dimensionKey);
                } catch (IOException error) {
                    throw new UncheckedIOException("Unable to read retained Iris find catalog for activation "
                            + activationId, error);
                }
            }
            activationId = activation.parentActivationId();
        }
        return catalog;
    }

    private static void loadPack(RetainedCatalog catalog, Path packRoot, String dimensionKey) {
        IrisData data = IrisData.openDatapackCompiler(packRoot.toFile());
        try {
            IrisDimension dimension = data.getDimensionLoader().load(dimensionKey, false);
            if (dimension == null) {
                throw new IllegalStateException("Retained Iris dimension is absent: " + dimensionKey);
            }
            addStructures(catalog.structures, dimension.getStructures());
            for (IrisBiome biome : dimension.getReachableBiomes(() -> data)) {
                catalog.biomes.putIfAbsent(normalizeKey(biome.getLoadKey()), biome);
                addObjects(catalog.objects, biome.getObjects());
                addStructures(catalog.structures, biome.getStructures());
            }
            for (IrisRegion region : dimension.getAllRegions(() -> data)) {
                catalog.regions.putIfAbsent(normalizeKey(region.getLoadKey()), region);
                addObjects(catalog.objects, region.getObjects());
                addStructures(catalog.structures, region.getStructures());
            }
            if (dimension.hasUpperDimension() && !dimensionKey.equals(dimension.getUpperDimension())) {
                IrisDimension upper = data.getDimensionLoader().load(dimension.getUpperDimension(), false);
                if (upper == null) {
                    throw new IllegalStateException("Retained Iris upper dimension is absent: "
                            + dimension.getUpperDimension());
                }
                for (IrisBiome biome : upper.getReachableBiomes(() -> data)) {
                    addObjects(catalog.objects, biome.getSurfaceObjects());
                }
                for (IrisRegion region : upper.getAllRegions(() -> data)) {
                    addObjects(catalog.objects, region.getSurfaceObjects());
                }
            }
        } finally {
            data.close();
        }
    }

    private static void addStructures(Set<String> structures, Iterable<IrisStructurePlacement> placements) {
        for (IrisStructurePlacement placement : placements) {
            if (placement == null) {
                continue;
            }
            for (String key : placement.getStructures()) {
                String normalized = normalizeKey(key);
                if (!normalized.isEmpty()) {
                    structures.add(normalized);
                }
            }
        }
    }

    private static void addObjects(Set<String> objects, Iterable<IrisObjectPlacement> placements) {
        for (IrisObjectPlacement placement : placements) {
            if (placement == null || placement.getPlace() == null) {
                continue;
            }
            for (String key : placement.getPlace()) {
                String normalized = normalizeObjectKey(key);
                if (!normalized.isEmpty()) {
                    objects.add(normalized);
                }
            }
        }
    }

    private static <T extends IrisRegistrant> KList<T> merge(Iterable<T> active, Map<String, T> historical) {
        Map<String, T> merged = new LinkedHashMap<>();
        for (T registrant : active) {
            merged.putIfAbsent(normalizeKey(registrant.getLoadKey()), registrant);
        }
        historical.forEach(merged::putIfAbsent);
        return new KList<>(merged.values());
    }

    private static <T extends IrisRegistrant> T find(Iterable<T> entries, String key) {
        String normalized = normalizeKey(key);
        for (T entry : entries) {
            if (normalizeKey(entry.getLoadKey()).equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeObjectKey(String key) {
        String normalized = normalizeKey(key).replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith(".iob") ? normalized.substring(0, normalized.length() - 4) : normalized;
    }

    private static final class RetainedCatalog {
        private final long activeActivationId;
        private final Map<String, IrisBiome> biomes = new LinkedHashMap<>();
        private final Map<String, IrisRegion> regions = new LinkedHashMap<>();
        private final Set<String> objects = new LinkedHashSet<>();
        private final Set<String> structures = new LinkedHashSet<>();

        private RetainedCatalog(long activeActivationId) {
            this.activeActivationId = activeActivationId;
        }
    }
}
