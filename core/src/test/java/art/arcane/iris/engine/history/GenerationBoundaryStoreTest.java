package art.arcane.iris.engine.history;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GenerationBoundaryStoreTest {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void publishesDeterministicNegativeCoordinateSnapshot() throws Exception {
        Path firstRoot = temporaryFolder.newFolder("first-dimension").toPath();
        Path secondRoot = temporaryFolder.newFolder("second-dimension").toPath();
        GenerationBoundaryStore firstStore = new GenerationBoundaryStore(firstRoot);
        GenerationBoundaryStore secondStore = new GenerationBoundaryStore(secondRoot);
        List<GenerationBoundary.ChunkCoordinate> chunks = List.of(
                new GenerationBoundary.ChunkCoordinate(2, -3),
                new GenerationBoundary.ChunkCoordinate(Integer.MIN_VALUE, Integer.MAX_VALUE),
                new GenerationBoundary.ChunkCoordinate(-1, -1),
                new GenerationBoundary.ChunkCoordinate(2, -3)
        );

        GenerationBoundary published = firstStore.publish(42L, chunks);
        GenerationBoundary reordered = secondStore.publish(42L, List.of(
                chunks.get(3),
                chunks.get(2),
                chunks.get(1),
                chunks.get(0)
        ));
        GenerationBoundary loaded = firstStore.load(42L);

        assertEquals(firstRoot.toAbsolutePath().resolve("iris/generation/boundaries"), firstStore.directory());
        assertTrue(SHA_256.matcher(published.identity()).matches());
        assertEquals(published.identity(), reordered.identity());
        assertEquals(published.identity(), loaded.identity());
        assertEquals(3, loaded.historicalChunkCount());
        assertTrue(loaded.isHistoricalChunk(2, -3));
        assertTrue(loaded.isHistoricalChunk(-1, -1));
        assertTrue(loaded.isHistoricalChunk(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertArrayEquals(
                Files.readAllBytes(firstStore.snapshotPath(42L)),
                Files.readAllBytes(secondStore.snapshotPath(42L))
        );
    }

    @Test
    public void keepsPublishedActivationImmutableAndAllowsIdempotentPublication() throws Exception {
        Path dimensionRoot = temporaryFolder.newFolder("immutable-dimension").toPath();
        GenerationBoundaryStore store = new GenerationBoundaryStore(dimensionRoot);
        long[] original = {
                GenerationBoundary.packChunk(-2, 7),
                GenerationBoundary.packChunk(0, 0)
        };

        GenerationBoundary first = store.publishPacked(7L, original);
        GenerationBoundary repeated = store.publishPacked(7L, original);

        assertEquals(first.identity(), repeated.identity());
        assertThrows(
                FileAlreadyExistsException.class,
                () -> store.publishPacked(7L, new long[]{GenerationBoundary.packChunk(9, 9)})
        );
        assertArrayEquals(first.packedHistoricalChunks(), store.load(7L).packedHistoricalChunks());
    }

    @Test
    public void failsClosedForCorruptionAndTruncation() throws Exception {
        Path dimensionRoot = temporaryFolder.newFolder("corrupt-dimension").toPath();
        GenerationBoundaryStore store = new GenerationBoundaryStore(dimensionRoot);
        store.publishPacked(3L, new long[]{GenerationBoundary.packChunk(-4, 8)});
        Path corrupted = store.snapshotPath(3L);
        byte[] corruptedBytes = Files.readAllBytes(corrupted);
        corruptedBytes[8] ^= 0x01;
        Files.write(corrupted, corruptedBytes);

        IOException checksumFailure = assertThrows(IOException.class, () -> store.load(3L));
        assertTrue(checksumFailure.getMessage().contains("checksum"));

        store.publishPacked(4L, new long[]{GenerationBoundary.packChunk(5, -6)});
        Path truncated = store.snapshotPath(4L);
        byte[] complete = Files.readAllBytes(truncated);
        Files.write(truncated, Arrays.copyOf(complete, complete.length - 3));
        assertThrows(IOException.class, () -> store.load(4L));
    }

    @Test
    public void verifiesContentIdentityAfterChecksumValidation() throws Exception {
        Path dimensionRoot = temporaryFolder.newFolder("identity-dimension").toPath();
        GenerationBoundaryStore store = new GenerationBoundaryStore(dimensionRoot);
        store.publishPacked(11L, new long[]{GenerationBoundary.packChunk(-8, -13)});
        Path snapshot = store.snapshotPath(11L);
        byte[] encoded = Files.readAllBytes(snapshot);
        encoded[20] ^= 0x01;
        updateChecksum(encoded);
        Files.write(snapshot, encoded);

        IOException failure = assertThrows(IOException.class, () -> store.load(11L));
        assertTrue(failure.getMessage().contains("identity mismatch"));
    }

    @Test
    public void rejectsInvalidActivationAndDeclaredBounds() throws Exception {
        Path dimensionRoot = temporaryFolder.newFolder("bounds-dimension").toPath();
        GenerationBoundaryStore store = new GenerationBoundaryStore(dimensionRoot);
        assertThrows(IllegalArgumentException.class, () -> store.publishPacked(0L, new long[0]));
        store.publishPacked(5L, new long[0]);
        Path snapshot = store.snapshotPath(5L);
        byte[] encoded = Files.readAllBytes(snapshot);
        ByteBuffer.wrap(encoded).putInt(16, Integer.MAX_VALUE);
        updateChecksum(encoded);
        Files.write(snapshot, encoded);

        assertThrows(IOException.class, () -> store.load(5L));
    }

    @Test
    public void rejectsSymbolicLinksAtEveryStorageComponent() throws Exception {
        Path probe = temporaryFolder.newFolder("boundary-symlink-probe").toPath();
        Path probeTarget = temporaryFolder.newFolder("boundary-symlink-probe-target").toPath();
        try {
            Files.createSymbolicLink(probe.resolve("link"), probeTarget);
        } catch (IOException | UnsupportedOperationException exception) {
            Assume.assumeNoException(exception);
        }

        for (String component : new String[]{"dimension", "iris", "generation", "boundaries"}) {
            assertStorageLinkRejected(component);
        }
    }

    @Test
    public void concurrentStoreInstancesCannotClobberABoundary() throws Exception {
        Path dimensionRoot = temporaryFolder.newFolder("boundary-race").toPath();
        GenerationBoundaryStore firstStore = new GenerationBoundaryStore(dimensionRoot);
        GenerationBoundaryStore secondStore = new GenerationBoundaryStore(dimensionRoot);
        long firstChunk = GenerationBoundary.packChunk(1, 2);
        long secondChunk = GenerationBoundary.packChunk(3, 4);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(
                    () -> publishAfterBarrier(firstStore, 21L, firstChunk, barrier)
            );
            Future<Boolean> second = executor.submit(
                    () -> publishAfterBarrier(secondStore, 21L, secondChunk, barrier)
            );

            assertTrue(first.get() ^ second.get());
            GenerationBoundary loaded = firstStore.load(21L);
            assertTrue(loaded.isHistoricalChunk(1, 2) ^ loaded.isHistoricalChunk(3, 4));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void contentAddressedRegionMasksAreReusedAcrossActivationHistory() throws Exception {
        Path dimensionRoot = temporaryFolder.newFolder("boundary-history-reuse").toPath();
        GenerationBoundaryStore store = new GenerationBoundaryStore(dimensionRoot);
        store.publishPacked(1L, new long[]{
                GenerationBoundary.packChunk(0, 0),
                GenerationBoundary.packChunk(32, 0)
        });
        store.publishPacked(2L, new long[]{
                GenerationBoundary.packChunk(0, 0),
                GenerationBoundary.packChunk(1, 0),
                GenerationBoundary.packChunk(32, 0)
        });
        store.publishPacked(3L, new long[]{
                GenerationBoundary.packChunk(0, 0),
                GenerationBoundary.packChunk(1, 0),
                GenerationBoundary.packChunk(32, 0),
                GenerationBoundary.packChunk(33, 0)
        });

        long maskCount;
        try (Stream<Path> files = Files.list(store.directory())) {
            maskCount = files.filter(path -> path.getFileName().toString().endsWith(".irbm")).count();
        }
        assertEquals(2L, maskCount);
        GenerationBoundary reopened = new GenerationBoundaryStore(dimensionRoot).load(3L);
        assertEquals(4, reopened.historicalChunkCount());
        assertTrue(reopened.isHistoricalChunk(33, 0));
    }

    @Test
    public void loadedBoundaryKeepsOnlyAFixedNumberOfRegionMasksResident() throws Exception {
        Path dimensionRoot = temporaryFolder.newFolder("boundary-cache").toPath();
        GenerationBoundaryStore store = new GenerationBoundaryStore(dimensionRoot);
        long[] chunks = new long[80];
        for (int regionX = 0; regionX < chunks.length; regionX++) {
            chunks[regionX] = GenerationBoundary.packChunk(regionX << 5, 0);
        }
        store.publishPacked(1L, chunks);
        GenerationBoundary boundary = new GenerationBoundaryStore(dimensionRoot).load(1L);

        for (int regionX = 0; regionX < chunks.length; regionX++) {
            assertTrue(boundary.isHistoricalChunk(regionX << 5, 0));
            assertTrue(boundary.cachedRegionCount() <= 64);
        }
        assertTrue(boundary.isHistoricalChunk(0, 0));
        assertTrue(boundary.cachedRegionCount() <= 64);
    }

    private void assertStorageLinkRejected(String component) throws Exception {
        Path container = temporaryFolder.newFolder("boundary-linked-" + component).toPath();
        Path dimensionRoot = container.resolve("dimension");
        Path external = temporaryFolder.newFolder("boundary-linked-target-" + component).toPath();
        if (component.equals("dimension")) {
            Files.createSymbolicLink(dimensionRoot, external);
        } else {
            Files.createDirectory(dimensionRoot);
            Path iris = dimensionRoot.resolve("iris");
            if (component.equals("iris")) {
                Files.createSymbolicLink(iris, external);
            } else {
                Files.createDirectory(iris);
                Path generation = iris.resolve("generation");
                if (component.equals("generation")) {
                    Files.createSymbolicLink(generation, external);
                } else {
                    Files.createDirectory(generation);
                    Files.createSymbolicLink(generation.resolve("boundaries"), external);
                }
            }
        }
        GenerationBoundaryStore store = new GenerationBoundaryStore(dimensionRoot);

        assertThrows(IOException.class, () -> store.publishPacked(1L, new long[0]));
    }

    private static boolean publishAfterBarrier(
            GenerationBoundaryStore store,
            long activationId,
            long chunk,
            CyclicBarrier barrier
    ) throws Exception {
        barrier.await();
        try {
            store.publishPacked(activationId, new long[]{chunk});
            return true;
        } catch (IOException expected) {
            return false;
        }
    }

    private static void updateChecksum(byte[] encoded) {
        int bodyLength = encoded.length - Integer.BYTES;
        CRC32 checksum = new CRC32();
        checksum.update(encoded, 0, bodyLength);
        ByteBuffer.wrap(encoded, bodyLength, Integer.BYTES).putInt((int) checksum.getValue());
    }
}
