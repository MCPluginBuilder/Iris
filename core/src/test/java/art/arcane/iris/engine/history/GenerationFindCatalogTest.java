package art.arcane.iris.engine.history;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.ResourceLoader;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisObject;
import art.arcane.iris.engine.object.IrisObjectPlacement;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisStructurePlacement;
import art.arcane.iris.util.common.director.specialhandlers.LocatableObjectHandler;
import art.arcane.iris.util.common.director.specialhandlers.ReachableBiomeHandler;
import art.arcane.iris.util.common.director.specialhandlers.ReachableRegionHandler;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GenerationFindCatalogTest {
    @Test
    public void parsesRemovedNamesFromRetainedPackWithoutBuildingRuntime() throws Exception {
        Fixture fixture = new Fixture();
        try (MockedStatic<IrisData> data = mockStatic(IrisData.class)) {
            data.when(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile())).thenReturn(fixture.oldData);

            assertSame(fixture.meadow, fixture.biomeHandler().parse("meadow", false));
            assertSame(fixture.oldRegion, fixture.regionHandler().parse("old-region", false));
            assertEquals("old-tree", fixture.objectHandler().parse("old-tree", false));
            assertTrue(GenerationFindCatalog.hasObjectPlacement(fixture.engine, "/OLD-TREE.iob"));
            assertFalse(GenerationFindCatalog.hasObjectPlacement(fixture.engine, "unplaced"));
            assertEquals("old meadow", GenerationFindCatalog.biome(fixture.engine, "meadow").getName());

            data.verify(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile()), times(1));
            data.verifyNoMoreInteractions();
            verify(fixture.oldData).close();
            verify(fixture.history, never()).packRoot(2L);
            verify(fixture.engine, never()).getActiveGenerationRuntimeBinding();
        }
    }

    @Test
    public void activeDefinitionsWinHistoricalKeyCollisions() throws Exception {
        Fixture fixture = new Fixture();
        IrisBiome activeMeadow = biome("meadow", "new meadow");
        IrisRegion activeRegion = region("old-region");
        when(fixture.engine.getAllBiomes()).thenReturn(new KList<>(activeMeadow));
        when(fixture.activeDimension.getAllRegions(fixture.engine)).thenReturn(new KList<>(activeRegion));
        try (MockedStatic<IrisData> data = mockStatic(IrisData.class)) {
            data.when(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile())).thenReturn(fixture.oldData);

            assertSame(activeMeadow, fixture.biomeHandler().parse("MEADOW", false));
            assertSame(activeRegion, fixture.regionHandler().parse("OLD-REGION", false));
            assertEquals(1, GenerationFindCatalog.biomes(fixture.engine).size());
            assertEquals(1, GenerationFindCatalog.regions(fixture.engine).size());
        }
    }

    @Test
    public void unrelatedAuthoringAndUnreachableFilesAreNotFallbackCandidates() throws Exception {
        Fixture fixture = new Fixture();
        try (MockedStatic<IrisData> data = mockStatic(IrisData.class)) {
            data.when(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile())).thenReturn(fixture.oldData);

            assertThrows(DirectorParsingException.class, () -> fixture.biomeHandler().parse("unused", false));
            assertThrows(DirectorParsingException.class, () -> fixture.regionHandler().parse("unused", false));
            assertThrows(DirectorParsingException.class, () -> fixture.objectHandler().parse("unplaced", false));
            verify(fixture.biomeLoader, never()).load("unused");
            data.verify(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile()));
            data.verifyNoMoreInteractions();
        }
    }

    @Test
    public void missingRetainedPackFailsInsteadOfSearchingAuthoringPacks() throws Exception {
        Fixture fixture = new Fixture();
        IOException missingPack = new IOException("missing immutable pack");
        when(fixture.history.packRoot(1L)).thenThrow(missingPack);

        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> GenerationFindCatalog.biomes(fixture.engine));

        assertSame(missingPack, failure.getCause());
    }

    @Test
    public void retainsEditableStructureKeysFromEveryOwningPlacementScope() throws Exception {
        Fixture fixture = new Fixture();
        fixture.oldDimension.setStructures(new KList<>(structure("old-house")));
        fixture.oldRegion.setStructures(new KList<>(structure("old-ruin")));
        fixture.meadow.setStructures(new KList<>(structure("old-tower")));
        try (MockedStatic<IrisData> data = mockStatic(IrisData.class)) {
            data.when(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile())).thenReturn(fixture.oldData);

            assertTrue(GenerationFindCatalog.hasRetainedStructurePlacement(fixture.engine, "OLD-HOUSE"));
            assertTrue(GenerationFindCatalog.hasRetainedStructurePlacement(fixture.engine, "old-ruin"));
            assertTrue(GenerationFindCatalog.hasRetainedStructurePlacement(fixture.engine, "old-tower"));
            assertFalse(GenerationFindCatalog.hasRetainedStructurePlacement(fixture.engine, "unused"));
            assertEquals(3, GenerationFindCatalog.retainedStructureKeys(fixture.engine).size());
        }
    }

    @Test
    public void parsesRemovedObjectsFromTheSeparateRetainedUpperDimension() throws Exception {
        Fixture fixture = new Fixture();
        fixture.oldDimension.setUpperDimension("ceiling");
        IrisDimension upper = new IrisDimension().setRegions(new KList<>("sky-region"));
        IrisRegion skyRegion = region("sky-region").setLandBiomes(new KList<>("sky-biome"));
        IrisBiome skyBiome = biome("sky-biome", "ceiling biome");
        skyRegion.setObjects(new KList<>(object("upper-region-object")));
        skyBiome.setObjects(new KList<>(object("upper-biome-object")));
        when(fixture.dimensionLoader.load("ceiling", false)).thenReturn(upper);
        when(fixture.regionLoader.load("sky-region")).thenReturn(skyRegion);
        when(fixture.biomeLoader.load("sky-biome")).thenReturn(skyBiome);
        try (MockedStatic<IrisData> data = mockStatic(IrisData.class)) {
            data.when(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile())).thenReturn(fixture.oldData);

            assertEquals("upper-biome-object", fixture.objectHandler().parse("upper-biome-object", false));
            assertEquals("upper-region-object", fixture.objectHandler().parse("upper-region-object", false));
            assertTrue(GenerationFindCatalog.hasObjectPlacement(fixture.engine, "upper-biome-object"));
            assertTrue(GenerationFindCatalog.hasObjectPlacement(fixture.engine, "upper-region-object"));
            assertNull(GenerationFindCatalog.biome(fixture.engine, "sky-biome"));
            assertNull(GenerationFindCatalog.region(fixture.engine, "sky-region"));
            data.verify(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile()));
            data.verifyNoMoreInteractions();
            verify(fixture.oldData).close();
        }
    }

    @Test
    public void missingRetainedUpperDimensionDoesNotFallBackToAnotherPack() throws Exception {
        Fixture fixture = new Fixture();
        fixture.oldDimension.setUpperDimension("missing-upper");
        try (MockedStatic<IrisData> data = mockStatic(IrisData.class)) {
            data.when(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile())).thenReturn(fixture.oldData);

            assertThrows(IllegalStateException.class, () -> GenerationFindCatalog.objectKeys(fixture.engine));
            data.verify(() -> IrisData.openDatapackCompiler(fixture.oldPack.toFile()));
            data.verifyNoMoreInteractions();
            verify(fixture.oldData).close();
        }
    }

    private static IrisStructurePlacement structure(String key) {
        return new IrisStructurePlacement().setStructures(new KList<>(key));
    }

    private static IrisObjectPlacement object(String key) {
        return new IrisObjectPlacement().setPlace(new KList<>(key));
    }

    private static IrisBiome biome(String key, String name) {
        IrisBiome biome = new IrisBiome().setName(name);
        biome.setLoadKey(key);
        return biome;
    }

    private static IrisRegion region(String key) {
        IrisRegion region = new IrisRegion();
        region.setLoadKey(key);
        return region;
    }

    private static final class Fixture {
        private final IrisEngine engine = mock(IrisEngine.class);
        private final GenerationHistory history = mock(GenerationHistory.class);
        private final IrisDimension activeDimension = mock(IrisDimension.class);
        private final IrisData oldData = mock(IrisData.class);
        private final ResourceLoader<IrisDimension> dimensionLoader;
        private final ResourceLoader<IrisRegion> regionLoader;
        private final ResourceLoader<IrisBiome> biomeLoader;
        private final IrisDimension oldDimension = new IrisDimension().setRegions(new KList<>("old-region"));
        private final Path oldPack = Path.of("retained-test-pack").toAbsolutePath();
        private final IrisBiome meadow = biome("meadow", "old meadow");
        private final IrisRegion oldRegion = region("old-region");

        @SuppressWarnings("unchecked")
        private Fixture() throws IOException {
            GenerationHistoryRuntimeRouter router = mock(GenerationHistoryRuntimeRouter.class);
            GenerationManifest manifest = mock(GenerationManifest.class);
            GenerationActivation active = mock(GenerationActivation.class);
            GenerationActivation old = mock(GenerationActivation.class);
            GenerationEpoch epoch = mock(GenerationEpoch.class);
            GenerationEpoch.DimensionContract contract = mock(GenerationEpoch.DimensionContract.class);
            when(engine.getGenerationHistoryRuntimeRouter()).thenReturn(Optional.of(router));
            when(router.history()).thenReturn(history);
            when(history.manifest()).thenReturn(manifest);
            when(manifest.activeActivation()).thenReturn(active);
            when(active.activationId()).thenReturn(2L);
            when(active.parentActivationId()).thenReturn(1L);
            when(manifest.activation(1L)).thenReturn(Optional.of(old));
            when(old.epochId()).thenReturn("old");
            when(old.parentActivationId()).thenReturn(null);
            when(manifest.epoch("old")).thenReturn(Optional.of(epoch));
            when(epoch.dimensionContract()).thenReturn(contract);
            when(epoch.packFingerprint()).thenReturn("old-pack");
            when(contract.dimensionKey()).thenReturn("world");
            when(history.packRoot(1L)).thenReturn(oldPack);

            IrisData activeData = mock(IrisData.class);
            ResourceLoader<IrisObject> objectLoader = mock(ResourceLoader.class);
            when(engine.getData()).thenReturn(activeData);
            when(activeData.getObjectLoader()).thenReturn(objectLoader);
            when(objectLoader.getPossibleKeys()).thenReturn(new String[]{"new-tree"});
            when(engine.getDimension()).thenReturn(activeDimension);
            when(engine.getAllBiomes()).thenReturn(new KList<>(biome("plateau", "new plateau")));
            when(activeDimension.getAllRegions(engine)).thenReturn(new KList<>(region("new-region")));

            dimensionLoader = mock(ResourceLoader.class);
            regionLoader = mock(ResourceLoader.class);
            biomeLoader = mock(ResourceLoader.class);
            oldRegion.setLandBiomes(new KList<>("meadow"));
            meadow.setObjects(new KList<>(new IrisObjectPlacement().setPlace(new KList<>("old-tree"))));
            when(oldData.getDimensionLoader()).thenReturn(dimensionLoader);
            when(oldData.getRegionLoader()).thenReturn(regionLoader);
            when(oldData.getBiomeLoader()).thenReturn(biomeLoader);
            when(dimensionLoader.load("world", false)).thenReturn(oldDimension);
            when(regionLoader.load("old-region")).thenReturn(oldRegion);
            when(biomeLoader.load("meadow")).thenReturn(meadow);
        }

        private ReachableBiomeHandler biomeHandler() {
            return new ReachableBiomeHandler() {
                @Override
                public Engine engine() {
                    return engine;
                }

                @Override
                public IrisData data() {
                    throw new AssertionError("Find must not use the authoring context");
                }
            };
        }

        private ReachableRegionHandler regionHandler() {
            return new ReachableRegionHandler() {
                @Override
                public Engine engine() {
                    return engine;
                }

                @Override
                public IrisData data() {
                    throw new AssertionError("Find must not use the authoring context");
                }
            };
        }

        private LocatableObjectHandler objectHandler() {
            return new LocatableObjectHandler() {
                @Override
                public Engine engine() {
                    return engine;
                }

                @Override
                public IrisData data() {
                    throw new AssertionError("Find must not use the authoring context");
                }
            };
        }
    }
}
