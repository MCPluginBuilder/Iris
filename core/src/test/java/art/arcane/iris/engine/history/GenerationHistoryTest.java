package art.arcane.iris.engine.history;

import art.arcane.iris.util.nbt.common.mca.MCAFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class GenerationHistoryTest {
    private static final int SECTOR_BYTES = 4_096;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void upperContentUpdatesPreserveLayoutAndDistinctActivationsAcrossRestarts() throws Exception {
        Path world = temporaryFolder.newFolder("upper-update-world").toPath();
        Path packA = createPack("upper-update-a", "alpha");
        Path packB = createPack("upper-update-b", "beta");
        GenerationEpoch.DimensionContract contractA = upperContract(fingerprint(packA));
        GenerationEpoch.DimensionContract contractB = upperContract(fingerprint(packB));
        GenerationHistory history = GenerationHistory.create(
                world, packA, fingerprint(packA), 42L, contractA, GenerationRegistryContract.empty());
        String epochA = history.activeEpoch().epochId();
        history.stageUpdate(packB, fingerprint(packB), contractB, GenerationRegistryContract.empty(), 32);
        history.promotePending(List.of());

        GenerationHistory restarted = GenerationHistory.open(world);
        assertEquals(contractB, restarted.activeEpoch().dimensionContract());
        restarted.stageUpdate(packA, fingerprint(packA), contractA, GenerationRegistryContract.empty(), 32);
        restarted.promotePending(List.of());

        GenerationHistory returned = GenerationHistory.open(world);
        assertEquals(3L, returned.activeActivation().activationId());
        assertEquals(epochA, returned.activeEpoch().epochId());
        assertFalse(returned.paths().activationMantleRoot(1L).equals(returned.paths().activationMantleRoot(3L)));
    }

    @Test
    public void createPublishesThePackBeforeTheCanonicalManifest() throws Exception {
        Path world = temporaryFolder.newFolder("create-world").toPath();
        Path pack = createPack("create-pack", "alpha");
        String fingerprint = fingerprint(pack);

        GenerationHistory history = createHistory(world, pack);

        assertTrue(Files.isRegularFile(history.paths().manifest()));
        assertTrue(Files.isRegularFile(history.activePackRoot().resolve("dimensions/main.json")));
        assertEquals(1L, history.activeActivation().activationId());
        assertEquals(1L, history.resolveActivation(40, -70).activationId());
        assertEquals(fingerprint, history.resolveEpoch(40, -70).packFingerprint());
    }

    @Test
    public void failedPackPublicationNeverCreatesAManifest() throws Exception {
        Path world = temporaryFolder.newFolder("failed-create-world").toPath();
        Path pack = createPack("failed-create-pack", "alpha");
        GenerationHistoryPaths paths = GenerationHistoryPaths.forDimension(world);

        assertThrows(
                IOException.class,
                () -> GenerationHistory.create(
                        world,
                        pack,
                        "f".repeat(64),
                        42L,
                        contract(),
                        GenerationRegistryContract.empty()
                )
        );
        assertFalse(Files.exists(paths.manifest()));
    }

    @Test
    public void openFailsClosedWhenAnyReferencedPackIsMissingOrChanged() throws Exception {
        Path missingWorld = temporaryFolder.newFolder("missing-world").toPath();
        Path missingPack = createPack("missing-pack", "alpha");
        GenerationHistory missingHistory = createHistory(missingWorld, missingPack);
        Files.delete(missingHistory.activePackRoot().resolve("dimensions/main.json"));

        assertThrows(IOException.class, () -> GenerationHistory.open(missingWorld));

        Path changedWorld = temporaryFolder.newFolder("changed-world").toPath();
        Path changedPack = createPack("changed-pack", "alpha");
        GenerationHistory changedHistory = createHistory(changedWorld, changedPack);
        Files.writeString(changedHistory.activePackRoot().resolve("dimensions/main.json"), "changed");

        assertThrows(IOException.class, () -> GenerationHistory.open(changedWorld));
    }

    @Test
    public void openRejectsUnsupportedGeneratorAndRngVersions() throws Exception {
        Path abiWorld = temporaryFolder.newFolder("abi-world").toPath();
        Path abiPack = createPack("abi-pack", "alpha");
        initializeRawHistory(abiWorld, abiPack, 2, GenerationKernelRegistry.standard().current().rngVersion());
        IOException abiFailure = assertThrows(IOException.class, () -> GenerationHistory.open(abiWorld));
        assertTrue(abiFailure.getMessage().contains("generation ABI"));

        Path rngWorld = temporaryFolder.newFolder("rng-world").toPath();
        Path rngPack = createPack("rng-pack", "alpha");
        initializeRawHistory(rngWorld, rngPack, GenerationKernelRegistry.standard().current().generatorAbi(), 2);
        IOException rngFailure = assertThrows(IOException.class, () -> GenerationHistory.open(rngWorld));
        assertTrue(rngFailure.getMessage().contains("RNG/seed derivation version"));
    }

    @Test
    public void promotionFreezesAllocatedChunksBeforePublishingTheNewActivation() throws Exception {
        Path world = temporaryFolder.newFolder("promotion-world").toPath();
        Path packA = createPack("promotion-pack-a", "alpha");
        Path packB = createPack("promotion-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        writeRegion(region, new int[][]{{3, 4}, {31, 31}});

        GenerationActivation pending = stage(history, packB);
        GenerationActivation active = history.promotePending(signaturesForChunks(new int[][]{{3, 4}, {31, 31}}));

        assertEquals(2L, pending.activationId());
        assertEquals(pending.activationId(), active.activationId());
        assertEquals(pending.epochId(), active.epochId());
        assertTrue(active.transition().isComplete());
        assertEquals(1L, history.resolveActivation(3, 4).activationId());
        assertEquals(1L, history.resolveActivation(31, 31).activationId());
        assertEquals(2L, history.resolveActivation(32, 31).activationId());
        GenerationBoundary boundary = history.boundary(2L);
        assertTrue(boundary.isHistoricalChunk(3, 4));
        assertTrue(boundary.isHistoricalChunk(31, 31));
        assertFalse(boundary.isHistoricalChunk(32, 31));
        assertEquals(history.paths().packRoot(history.activeEpoch().epochId()), history.activePackRoot());
        assertTrue(Files.isRegularFile(history.packRoot(1L).resolve("dimensions/main.json")));
        assertEquals(256, history.transitionPlan(2L).widthBlocks());
        assertEquals(boundary.exposedBlockColumns().size(), history.terrainSignatures(2L).size());

        GenerationHistory reopened = GenerationHistory.open(world);
        assertEquals(1L, reopened.resolveActivation(3, 4).activationId());
        assertEquals(2L, reopened.resolveActivation(32, 31).activationId());
        assertEquals(2, reopened.explicitChunkCount());
        assertEquals(256, reopened.transitionPlan(2L).widthBlocks());
    }

    @Test
    public void promotionCapturesSignaturesFromTheDurableBoundary() throws Exception {
        Path world = temporaryFolder.newFolder("capture-boundary-world").toPath();
        Path packA = createPack("capture-boundary-pack-a", "alpha");
        Path packB = createPack("capture-boundary-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        writeRegion(region, new int[][]{{3, 4}});
        stage(history, packB);

        GenerationActivation active = history.promotePending(boundary -> {
            assertTrue(boundary.isHistoricalChunk(3, 4));
            assertFalse(boundary.isHistoricalChunk(4, 4));
            return GenerationHistoryTest::signature;
        });

        assertEquals(2L, active.activationId());
        assertEquals(history.boundary(2L).identity(), active.transition().boundaryIdentity());
    }

    @Test
    public void promotionIsIdempotentForEmptyAndRecoveredWorlds() throws Exception {
        Path emptyWorld = temporaryFolder.newFolder("empty-world").toPath();
        Path emptyPackA = createPack("empty-pack-a", "alpha");
        Path emptyPackB = createPack("empty-pack-b", "beta");
        GenerationHistory emptyHistory = createHistory(emptyWorld, emptyPackA);
        stage(emptyHistory, emptyPackB);

        assertEquals(2L, emptyHistory.promotePending(List.of()).activationId());
        assertEquals(2L, emptyHistory.promotePending(List.of()).activationId());
        assertEquals(2L, emptyHistory.resolveActivation(0, 0).activationId());
        assertEquals(0, emptyHistory.explicitChunkCount());

        Path recoveredWorld = temporaryFolder.newFolder("recovered-world").toPath();
        Path recoveredPackA = createPack("recovered-pack-a", "alpha");
        Path recoveredPackB = createPack("recovered-pack-b", "beta");
        GenerationHistory recovered = createHistory(recoveredWorld, recoveredPackA);
        stage(recovered, recoveredPackB);

        GenerationHistory reopened = GenerationHistory.open(recoveredWorld);
        assertEquals(2L, reopened.promotePending(List.of()).activationId());
        assertEquals(2L, reopened.resolveActivation(8, 9).activationId());
    }

    @Test
    public void stageUpdateIsIdempotentAndRejectsIncompatibleCandidates() throws Exception {
        Path world = temporaryFolder.newFolder("stage-world").toPath();
        Path packA = createPack("stage-pack-a", "alpha");
        Path packB = createPack("stage-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);

        GenerationActivation first = stage(history, packB);
        GenerationActivation repeated = stage(history, packB);

        assertEquals(first, repeated);
        assertEquals(2, history.manifest().activations().size());
        GenerationEpoch.DimensionContract incompatible = new GenerationEpoch.DimensionContract(
                "overworld",
                "iris:overworld_type",
                "NORMAL",
                "OVERWORLD",
                127,
                -64,
                512,
                512,
                1D,
                false,
                "none",
                0,
                "0".repeat(64),
                GenerationEpochContractFactory.CURRENT_DIMENSION_TYPE_FINGERPRINT_SCHEMA,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> history.stageUpdate(
                        packB,
                        fingerprint(packB),
                        incompatible,
                        GenerationRegistryContract.empty(),
                        256
                )
        );
    }

    @Test
    public void legacyAdoptionIsDurableExplicitAndIdempotent() throws Exception {
        Path world = temporaryFolder.newFolder("legacy-world").toPath();
        Path legacy = Files.createDirectories(world.resolve("iris/pack/dimensions"));
        Files.writeString(legacy.resolve("main.json"), "legacy");
        Path legacyRoot = world.resolve("iris/pack");
        Path legacyMantle = Files.createDirectories(world.resolve("mantle-hydrology"));
        Files.writeString(legacyMantle.resolve("r.0.0.lz4b"), "mantle");
        String fingerprint = fingerprint(legacyRoot);

        GenerationHistory adopted = GenerationHistory.adoptLegacyPack(
                world,
                fingerprint,
                42L,
                contract(),
                GenerationRegistryContract.empty()
        );

        assertFalse(Files.exists(legacyRoot));
        assertFalse(Files.exists(legacyMantle));
        assertTrue(Files.isDirectory(adopted.activePackRoot()));
        Path activationMantle = adopted.paths().activationMantleRoot(1L);
        assertEquals("mantle", Files.readString(activationMantle.resolve("r.0.0.lz4b")));
        assertEquals(1L, adopted.activeActivation().activationId());
        GenerationHistory repeated = GenerationHistory.adoptLegacyPack(
                world,
                fingerprint,
                42L,
                contract(),
                GenerationRegistryContract.empty()
        );
        assertEquals(adopted.activeEpoch(), repeated.activeEpoch());
        assertEquals("mantle", Files.readString(repeated.paths().activationMantleRoot(1L)
                .resolve("r.0.0.lz4b")));
    }

    @Test
    public void legacyMantleAdoptionFailsClosedOnDualRoots() throws Exception {
        Path world = temporaryFolder.newFolder("legacy-dual-mantle-world").toPath();
        Path legacyPack = Files.createDirectories(world.resolve("iris/pack/dimensions")).getParent();
        Files.writeString(legacyPack.resolve("dimensions/main.json"), "legacy");
        Path legacyMantle = Files.createDirectories(world.resolve("mantle-hydrology"));
        Files.writeString(legacyMantle.resolve("legacy.bin"), "legacy");
        GenerationHistoryPaths paths = GenerationHistoryPaths.forDimension(world);
        Path activationMantle = Files.createDirectories(paths.activationMantleRoot(1L));
        Files.writeString(activationMantle.resolve("activation.bin"), "activation");

        IOException failure = assertThrows(
                IOException.class,
                () -> GenerationHistory.adoptLegacyPack(
                        world,
                        fingerprint(legacyPack),
                        42L,
                        contract(),
                        GenerationRegistryContract.empty()
                )
        );

        assertTrue(failure.getMessage().contains("mantle roots conflict"));
        assertEquals("legacy", Files.readString(legacyMantle.resolve("legacy.bin")));
        assertEquals("activation", Files.readString(activationMantle.resolve("activation.bin")));
    }

    @Test
    public void legacyMantleAdoptionRejectsRootAliases() throws Exception {
        Path world = temporaryFolder.newFolder("legacy-aliased-mantle-world").toPath();
        Path legacyPack = Files.createDirectories(world.resolve("iris/pack/dimensions")).getParent();
        Files.writeString(legacyPack.resolve("dimensions/main.json"), "legacy");
        GenerationHistoryPaths paths = GenerationHistoryPaths.forDimension(world);
        Path activationMantle = Files.createDirectories(paths.activationMantleRoot(1L));
        Path alias = paths.legacyMantleRoot();
        try {
            Files.createSymbolicLink(alias, activationMantle);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThrows(
                IOException.class,
                () -> GenerationHistory.adoptLegacyPack(
                        world,
                        fingerprint(legacyPack),
                        42L,
                        contract(),
                        GenerationRegistryContract.empty()
                )
        );
        assertTrue(Files.isSymbolicLink(alias));
        assertTrue(Files.isDirectory(activationMantle));
    }

    @Test
    public void legacyAdoptionNeverDeletesMismatchedOrUnsafeInput() throws Exception {
        Path mismatchWorld = temporaryFolder.newFolder("legacy-mismatch-world").toPath();
        Path mismatchRoot = Files.createDirectories(mismatchWorld.resolve("iris/pack/dimensions")).getParent();
        Files.writeString(mismatchRoot.resolve("dimensions/main.json"), "legacy");

        assertThrows(
                IOException.class,
                () -> GenerationHistory.adoptLegacyPack(
                        mismatchWorld,
                        "f".repeat(64),
                        42L,
                        contract(),
                        GenerationRegistryContract.empty()
                )
        );
        assertTrue(Files.isDirectory(mismatchRoot));
        assertFalse(Files.exists(GenerationHistoryPaths.forDimension(mismatchWorld).manifest()));

        Path linkedWorld = temporaryFolder.newFolder("legacy-linked-world").toPath();
        Files.createDirectories(linkedWorld.resolve("iris"));
        Path outside = temporaryFolder.newFolder("legacy-outside").toPath();
        Path link = linkedWorld.resolve("iris/pack");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }
        assertThrows(
                IOException.class,
                () -> GenerationHistory.adoptLegacyPack(
                        linkedWorld,
                        "f".repeat(64),
                        42L,
                        contract(),
                        GenerationRegistryContract.empty()
                )
        );
        assertTrue(Files.isDirectory(outside));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    public void openFailsClosedOnDanglingOwnershipAndPartialHistory() throws Exception {
        Path danglingWorld = temporaryFolder.newFolder("dangling-world").toPath();
        Path pack = createPack("dangling-pack", "alpha");
        GenerationHistory history = createHistory(danglingWorld, pack);
        ChunkGenerationOwnership ownership = ChunkGenerationOwnership.load(history.paths().ownershipRoot());
        ownership.assign(1, 2, 99L);
        ownership.persist();

        assertThrows(IOException.class, () -> GenerationHistory.open(danglingWorld));

        Path absentWorld = temporaryFolder.newFolder("absent-world").toPath();
        Optional<GenerationHistory> absent = GenerationHistory.openIfPresent(absentWorld);
        assertTrue(absent.isEmpty());

        Path partialWorld = temporaryFolder.newFolder("partial-world").toPath();
        Files.createDirectories(GenerationHistoryPaths.forDimension(partialWorld).generationRoot());
        assertThrows(NoSuchFileException.class, () -> GenerationHistory.openIfPresent(partialWorld));
    }

    @Test
    public void stagingTheActiveEpochIsANoOp() throws Exception {
        Path world = temporaryFolder.newFolder("same-epoch-world").toPath();
        Path pack = createPack("same-epoch-pack", "alpha");
        GenerationHistory history = createHistory(world, pack);

        GenerationActivation activation = stage(history, pack);

        assertEquals(history.activeActivation(), activation);
        assertTrue(history.pendingActivation().isEmpty());
        assertEquals(1, history.manifest().activations().size());
    }

    @Test
    public void returningToAnEarlierEpochCreatesANewActivation() throws Exception {
        Path world = temporaryFolder.newFolder("return-world").toPath();
        Path packA = createPack("return-pack-a", "alpha");
        Path packB = createPack("return-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        String epochA = history.activeEpoch().epochId();

        stage(history, packB);
        history.promotePending(List.of());
        GenerationActivation activationA2 = stage(history, packA);
        history.promotePending(List.of());

        assertEquals(3L, activationA2.activationId());
        assertEquals(epochA, activationA2.epochId());
        assertEquals(2, history.manifest().epochs().size());
        assertEquals(3, history.manifest().activations().size());
    }

    @Test
    public void retainedAbiRegistryReopensEveryHistoricalEpoch() throws Exception {
        Path world = temporaryFolder.newFolder("retained-abi-world").toPath();
        Path packA = createPack("retained-abi-pack-a", "alpha");
        Path packB = createPack("retained-abi-pack-b", "beta");
        GenerationKernelRegistry.Version versionOne = new GenerationKernelRegistry.Version(1, 1, 1);
        GenerationKernelRegistry.Version versionTwo = new GenerationKernelRegistry.Version(2, 1, 1);
        GenerationKernelRegistry kernels = new GenerationKernelRegistry(
                versionTwo,
                List.of(
                        new GenerationKernelRegistry.Kernel(
                                1,
                                "1".repeat(64),
                                Map.of(
                                        new GenerationKernelRegistry.AlgorithmVersion(1, 1),
                                        (engine, transitionPlan) -> {
                                            throw new AssertionError("History-only kernel factory was invoked.");
                                        }
                                )
                        ),
                        new GenerationKernelRegistry.Kernel(
                                2,
                                "2".repeat(64),
                                Map.of(
                                        new GenerationKernelRegistry.AlgorithmVersion(1, 1),
                                        (engine, transitionPlan) -> {
                                            throw new AssertionError("History-only kernel factory was invoked.");
                                        }
                                )
                        )
                )
        );
        GenerationHistory history = GenerationHistory.create(
                world,
                packA,
                fingerprint(packA),
                42L,
                contract(),
                GenerationRegistryContract.empty(),
                versionOne,
                kernels
        );

        history.stageUpdate(
                packB,
                fingerprint(packB),
                contract(),
                GenerationRegistryContract.empty(),
                256,
                versionTwo
        );
        history.promotePending(List.of());

        assertThrows(IOException.class, () -> GenerationHistory.open(world));
        GenerationHistory reopened = GenerationHistory.open(world, kernels);
        assertEquals(2, reopened.activeEpoch().generatorAbi());
        assertEquals(1, reopened.manifest().activation(1L).orElseThrow().activationId());
    }

    @Test
    public void openRejectsWorldSeedMismatch() throws Exception {
        Path world = temporaryFolder.newFolder("seed-world").toPath();
        Path pack = createPack("seed-pack", "alpha");
        createHistory(world, pack);

        assertThrows(IOException.class, () -> GenerationHistory.open(world, 43L));
        assertEquals(42L, GenerationHistory.open(world, 42L).activeEpoch().worldSeed());
    }

    @Test
    public void openFailsClosedWhenAnOwnershipShardDisappears() throws Exception {
        Path world = temporaryFolder.newFolder("missing-ownership-world").toPath();
        Path packA = createPack("missing-ownership-pack-a", "alpha");
        Path packB = createPack("missing-ownership-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        writeRegion(region, new int[][]{{1, 2}});
        stage(history, packB);
        history.promotePending(signaturesForChunks(new int[][]{{1, 2}}));
        Files.delete(history.paths().ownershipRoot().resolve(RegionGenerationOwnership.fileName(0, 0)));

        assertThrows(IOException.class, () -> GenerationHistory.open(world));
    }

    @Test
    public void pendingCutoverResumesAfterPartialOutgoingOwnershipPublication() throws Exception {
        Path world = temporaryFolder.newFolder("partial-cutover-world").toPath();
        Path packA = createPack("partial-cutover-pack-a", "alpha");
        Path packB = createPack("partial-cutover-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        int[][] chunks = {{1, 2}, {3, 4}};
        writeRegion(region, chunks);
        stage(history, packB);

        ChunkGenerationOwnership partial = ChunkGenerationOwnership.load(history.paths().ownershipRoot());
        assertTrue(partial.assign(1, 2, 1L));
        partial.persist();

        GenerationHistory recovered = GenerationHistory.open(world);
        assertEquals(1, recovered.explicitChunkCount());
        assertEquals(2L, recovered.promotePending(signaturesForChunks(chunks)).activationId());
        assertEquals(2, GenerationHistory.open(world).explicitChunkCount());
    }

    @Test
    public void pendingCutoverStillRejectsMissingOlderOwnership() throws Exception {
        Path world = temporaryFolder.newFolder("pending-missing-old-world").toPath();
        Path packA = createPack("pending-missing-old-pack-a", "alpha");
        Path packB = createPack("pending-missing-old-pack-b", "beta");
        Path packC = createPack("pending-missing-old-pack-c", "gamma");
        GenerationHistory history = createHistory(world, packA);
        Path region = Files.createDirectories(world.resolve("region")).resolve("r.0.0.mca");
        writeRegion(region, new int[][]{{1, 2}});
        stage(history, packB);
        history.promotePending(signaturesForChunks(new int[][]{{1, 2}}));
        stage(history, packC);
        Files.delete(history.paths().ownershipRoot().resolve(RegionGenerationOwnership.fileName(0, 0)));

        assertThrows(IOException.class, () -> GenerationHistory.open(world));
    }

    @Test
    public void pendingCutoverRejectsOwnershipForUngeneratedChunks() throws Exception {
        Path world = temporaryFolder.newFolder("pending-forged-world").toPath();
        Path packA = createPack("pending-forged-pack-a", "alpha");
        Path packB = createPack("pending-forged-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        stage(history, packB);
        ChunkGenerationOwnership forged = ChunkGenerationOwnership.load(history.paths().ownershipRoot());
        assertTrue(forged.assign(9, 9, 1L));
        forged.persist();

        assertThrows(IOException.class, () -> GenerationHistory.open(world));
    }

    @Test
    public void sealedGenerationClaimSurvivesCrashBeforeChunkAllocationAndJoinsCutoverOwnership() throws Exception {
        Path world = temporaryFolder.newFolder("semantic-claim-cutover-world").toPath();
        Path packA = createPack("semantic-claim-cutover-pack-a", "alpha");
        Path packB = createPack("semantic-claim-cutover-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        GenerationHistory.GenerationStage generation = history.openStage(9, 9);
        ChunkGenerationSemantics claim = ChunkGenerationSemantics.builder(9, 9, 1L)
                .addSurfaceBiome("iris:forest")
                .seal()
                .build();

        assertTrue(history.claimGeneratedSemantics(generation, claim));
        assertFalse(history.claimGeneratedSemantics(generation, claim));
        generation.close();
        assertThrows(IllegalStateException.class, () -> history.claimGeneratedSemantics(generation, claim));
        assertFalse(Files.exists(world.resolve("region")));
        stage(history, packB);

        Path recoveredWorld = temporaryFolder.newFolder("restored-semantic-claim-world").toPath();
        try (Stream<Path> entries = Files.walk(world)) {
            for (Path source : entries.toList()) {
                Path target = recoveredWorld.resolve(world.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        GenerationHistory recovered = GenerationHistory.open(recoveredWorld);
        recovered.promotePending(signaturesForChunks(new int[][]{{9, 9}}));

        assertEquals(1L, recovered.resolveActivation(9, 9).activationId());
        assertEquals(2L, recovered.resolveActivation(10, 9).activationId());
        assertEquals(1, recovered.explicitChunkCount());
        GenerationHistory reopened = GenerationHistory.open(recoveredWorld);
        assertEquals(Optional.of(claim), reopened.semantics(9, 9));
        assertEquals(1L, reopened.resolveActivation(9, 9).activationId());
    }

    @Test
    public void openFailsClosedOnSemanticActivationMismatch() throws Exception {
        Path world = temporaryFolder.newFolder("semantic-mismatch-world").toPath();
        Path packA = createPack("semantic-mismatch-pack-a", "alpha");
        Path packB = createPack("semantic-mismatch-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        stage(history, packB);
        history.promotePending(List.of());
        GenerationSemanticIndex index = GenerationSemanticIndex.loadRequired(world);
        index.recordAndPersist(ChunkGenerationSemantics.builder(7, 8, 1L)
                .addSurfaceBiome("iris:old")
                .seal()
                .build());

        assertThrows(IOException.class, () -> GenerationHistory.open(world));
    }

    @Test
    public void promotionIsRejectedAfterGenerationAdmissionOpens() throws Exception {
        Path world = temporaryFolder.newFolder("admission-world").toPath();
        Path packA = createPack("admission-pack-a", "alpha");
        Path packB = createPack("admission-pack-b", "beta");
        GenerationHistory history = createHistory(world, packA);
        stage(history, packB);
        GenerationHistory.GenerationStage generationStage = history.openStage(2, 3);
        assertEquals(1L, generationStage.activation().activationId());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> history.promotePending(List.of())
        );
        assertTrue(failure.getMessage().contains("before generation admission opens"));
        generationStage.close();
        assertThrows(IllegalStateException.class, () -> history.promotePending(List.of()));
    }

    @Test
    public void openFailsClosedOnDeletedOrCorruptTransitionSnapshots() throws Exception {
        Path deletedWorld = temporaryFolder.newFolder("deleted-snapshot-world").toPath();
        Path deletedPackA = createPack("deleted-snapshot-pack-a", "alpha");
        Path deletedPackB = createPack("deleted-snapshot-pack-b", "beta");
        GenerationHistory deletedHistory = createHistory(deletedWorld, deletedPackA);
        stage(deletedHistory, deletedPackB);
        deletedHistory.promotePending(List.of());
        Files.delete(new TerrainBoundarySignatureStore(deletedWorld).snapshotPath(2L));

        assertThrows(IOException.class, () -> GenerationHistory.open(deletedWorld));

        Path corruptWorld = temporaryFolder.newFolder("corrupt-snapshot-world").toPath();
        Path corruptPackA = createPack("corrupt-snapshot-pack-a", "alpha");
        Path corruptPackB = createPack("corrupt-snapshot-pack-b", "beta");
        GenerationHistory corruptHistory = createHistory(corruptWorld, corruptPackA);
        stage(corruptHistory, corruptPackB);
        corruptHistory.promotePending(List.of());
        Files.write(new GenerationBoundaryStore(corruptWorld).snapshotPath(2L), new byte[]{1, 2, 3});

        assertThrows(IOException.class, () -> GenerationHistory.open(corruptWorld));
    }

    private void initializeRawHistory(
            Path world,
            Path pack,
            int generatorAbi,
            int rngVersion
    ) throws IOException {
        String fingerprint = fingerprint(pack);
        GenerationEpoch epoch = GenerationEpoch.create(new GenerationEpoch.Spec(
                fingerprint,
                GenerationPackFingerprint.CURRENT_VERSION,
                42L,
                GenerationEpoch.CURRENT_SEED_DERIVATION_VERSION,
                generatorAbi,
                rngVersion,
                GenerationKernelV1.IMPLEMENTATION_FINGERPRINT,
                contract(),
                GenerationRegistryContract.empty()
        ));
        GenerationPackRepository repository = new GenerationPackRepository(world);
        repository.publish(
                epoch.epochId(),
                fingerprint,
                GenerationPackFingerprint.CURRENT_VERSION,
                pack
        );
        GenerationSemanticIndex.initialize(world);
        GenerationHistoryStore.initialize(repository.generationRoot(), epoch);
    }

    private Path createPack(String name, String content) throws IOException {
        Path pack = temporaryFolder.newFolder(name).toPath();
        Files.createDirectories(pack.resolve("dimensions"));
        Files.writeString(pack.resolve("dimensions/main.json"), content);
        return pack;
    }

    private static String fingerprint(Path pack) throws IOException {
        return GenerationPackFingerprint.compute(pack, GenerationPackFingerprint.CURRENT_VERSION);
    }

    private static GenerationEpoch.DimensionContract upperContract(String packFingerprint) {
        GenerationEpoch.DimensionContract base = contract();
        return new GenerationEpoch.DimensionContract(
                base.dimensionKey(), base.dimensionTypeKey(), base.environment(), base.generationMode(),
                base.internalFluidHeight(), base.minHeight(), base.height(), base.logicalHeight(),
                base.coordinateScale(), true, "ceiling", 32, packFingerprint,
                base.dimensionTypeFingerprintSchema(), base.dimensionTypeFingerprint());
    }

    private static GenerationEpoch.DimensionContract contract() {
        return new GenerationEpoch.DimensionContract(
                "overworld",
                "iris:overworld_type",
                "NORMAL",
                "OVERWORLD",
                127,
                -64,
                384,
                384,
                1D,
                false,
                "none",
                0,
                "0".repeat(64),
                GenerationEpochContractFactory.CURRENT_DIMENSION_TYPE_FINGERPRINT_SCHEMA,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        );
    }

    private static GenerationHistory createHistory(Path world, Path pack) throws IOException {
        return GenerationHistory.create(
                world,
                pack,
                fingerprint(pack),
                42L,
                contract(),
                GenerationRegistryContract.empty()
        );
    }

    private static GenerationActivation stage(GenerationHistory history, Path pack) throws IOException {
        return history.stageUpdate(
                pack,
                fingerprint(pack),
                contract(),
                GenerationRegistryContract.empty(),
                256
        );
    }

    private static List<TerrainBoundarySignature> signaturesForChunks(int[][] chunks) {
        ArrayList<GenerationBoundary.ChunkCoordinate> coordinates = new ArrayList<>(chunks.length);
        for (int[] chunk : chunks) {
            coordinates.add(new GenerationBoundary.ChunkCoordinate(chunk[0], chunk[1]));
        }
        GenerationBoundary boundary = GenerationBoundary.freeze("test-boundary", coordinates);
        ArrayList<TerrainBoundarySignature> signatures = new ArrayList<>(boundary.exposedBlockColumns().size());
        for (GenerationBoundary.BlockColumn column : boundary.exposedBlockColumns()) {
            signatures.add(signature(column.blockX(), column.blockZ()));
        }
        return List.copyOf(signatures);
    }

    private static TerrainBoundarySignature signature(int blockX, int blockZ) {
        return new TerrainBoundarySignature(
                new TerrainBoundarySignature.Column(blockX, blockZ, 64, 63, OptionalInt.empty(), OptionalInt.empty()),
                new TerrainBoundarySignature.Samples(
                        new TerrainBoundarySignature.VerticalLayout(-64, 64, 2),
                        new TerrainBoundarySignature.BiomeEncoding(
                                List.of("iris:test"),
                                new short[]{0, 0}
                        )
                )
        );
    }

    private static void writeRegion(Path file, int[][] chunks) throws IOException {
        try (RandomAccessFile output = new RandomAccessFile(file.toFile(), "rw")) {
            output.setLength((long) (2 + chunks.length) * SECTOR_BYTES);
            for (int index = 0; index < chunks.length; index++) {
                int chunkIndex = MCAFile.getChunkIndex(chunks[index][0], chunks[index][1]);
                output.seek((long) chunkIndex * Integer.BYTES);
                output.writeInt((2 + index) << Byte.SIZE | 1);
            }
        }
    }
}
