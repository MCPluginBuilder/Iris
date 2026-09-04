import art.arcane.iris.buildtools.GenerationKernelSeal;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GenerationKernelSealTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void verifiesUnchangedSourceAndCanonicalDependencyContents() throws Exception {
        Fixture fixture = fixture();
        GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies());
        String original = GenerationKernelSeal.artifactHash(fixture.dependency());
        artifact(fixture.dependency(), "same code", "another version", 1_700_000_000_000L);
        assertEquals(original, GenerationKernelSeal.artifactHash(fixture.dependency()));
        GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies());
        assertEquals(GenerationKernelSeal.fingerprint(GenerationKernelSeal.read(fixture.seal())),
                GenerationKernelSeal.fingerprint(GenerationKernelSeal.capture(fixture.options(), fixture.dependencies())));
    }

    @Test
    public void rejectsChangedAddedAndDeletedGenerationSources() throws Exception {
        Fixture fixture = fixture();
        Path source = fixture.root().resolve("generation/Noise.java");
        Files.writeString(source, "class Noise { int value = 2; }");
        assertTrue(assertThrows(IOException.class,
                () -> GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies()))
                .getMessage().contains("source changed"));
        Files.writeString(source, "class Noise { int value = 1; }");
        Path added = fixture.root().resolve("generation/Added.java");
        Files.writeString(added, "class Added {}");
        assertTrue(assertThrows(IOException.class,
                () -> GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies()))
                .getMessage().contains("source added"));
        Files.delete(added);
        Files.delete(source);
        assertThrows(IOException.class,
                () -> GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies()));
    }

    @Test
    public void rejectsChangedAddedAndDeletedDependencies() throws Exception {
        Fixture fixture = fixture();
        String original = GenerationKernelSeal.artifactHash(fixture.dependency());
        artifact(fixture.dependency(), "changed code", "same version", 0L);
        assertNotEquals(original, GenerationKernelSeal.artifactHash(fixture.dependency()));
        assertTrue(assertThrows(IOException.class,
                () -> GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies()))
                .getMessage().contains("dependency changed"));
        artifact(fixture.dependency(), "same code", "same version", 0L);
        assertThrows(IOException.class, () -> GenerationKernelSeal.verify(fixture.root(), fixture.seal(),
                Map.of("math", fixture.dependency(), "new-math", fixture.dependency())));
        assertThrows(IOException.class,
                () -> GenerationKernelSeal.verify(fixture.root(), fixture.seal(), Map.of()));
    }

    @Test
    public void rejectsMultiReleaseManifestChanges() throws Exception {
        Fixture fixture = fixture();
        String original = GenerationKernelSeal.artifactHash(fixture.dependency());
        artifact(fixture.dependency(), "same code", "same version\r\nMulti-Release: true", 0L);
        assertNotEquals(original, GenerationKernelSeal.artifactHash(fixture.dependency()));
        assertThrows(IOException.class,
                () -> GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies()));
    }

    @Test
    public void rejectsAgentEntrypointAndCapabilityChanges() throws Exception {
        Fixture fixture = fixture();
        String original = GenerationKernelSeal.artifactHash(fixture.dependency());
        for (String attribute : List.of("Agent-Class", "Premain-Class", "Launcher-Agent-Class", "Boot-Class-Path",
                "Can-Redefine-Classes", "Can-Retransform-Classes", "Can-Set-Native-Method-Prefix")) {
            artifact(fixture.dependency(), "same code", "same version\r\n" + attribute + ": changed", 0L);
            assertNotEquals(attribute, original, GenerationKernelSeal.artifactHash(fixture.dependency()));
            assertThrows(IOException.class,
                    () -> GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies()));
        }
    }

    @Test
    public void sealsExplicitAgentBuildInputsAndExecutableSources() throws Exception {
        Fixture fixture = fixture();
        Path agentSource = fixture.root().resolve("agent/src/main/java/Installer.java");
        Path agentBuild = fixture.root().resolve("agent/build.gradle");
        Files.createDirectories(agentSource.getParent());
        Files.writeString(agentSource, "class Installer {}");
        Files.writeString(agentBuild, "plugins { id 'java' }");
        GenerationKernelSeal.CaptureOptions options = new GenerationKernelSeal.CaptureOptions(
                fixture.root(), 1, "example.KernelV1", List.of(new GenerationKernelSeal.AlgorithmVersion(1, 1)),
                List.of("generation", "agent/build.gradle", "agent/src/main/java"), List.of("generation/kernels"));
        Path seal = fixture.root().resolve("agent.seal");
        GenerationKernelSeal.writeNew(seal, GenerationKernelSeal.capture(options, fixture.dependencies()));
        GenerationKernelSeal.verify(fixture.root(), seal, fixture.dependencies());
        Files.writeString(agentBuild, "plugins { id 'application' }");
        assertThrows(IOException.class,
                () -> GenerationKernelSeal.verify(fixture.root(), seal, fixture.dependencies()));
        Files.writeString(agentBuild, "plugins { id 'java' }");
        Files.writeString(agentSource, "class Installer { boolean defers; }");
        assertThrows(IOException.class,
                () -> GenerationKernelSeal.verify(fixture.root(), seal, fixture.dependencies()));
    }

    @Test
    public void checksTheActualPlatformDependencySubset() throws Exception {
        Fixture fixture = fixture();
        GenerationKernelSeal.verifyDependencySubset(fixture.seal(), fixture.dependencies());
        assertThrows(IOException.class, () -> GenerationKernelSeal.verifyDependencySubset(fixture.seal(),
                Map.of("unknown-library", fixture.dependency())));
        artifact(fixture.dependency(), "platform override", "same version", 0L);
        assertThrows(IOException.class,
                () -> GenerationKernelSeal.verifyDependencySubset(fixture.seal(), fixture.dependencies()));
    }

    @Test
    public void catalogCannotSelectAnUnsealedAlgorithmTuple() throws Exception {
        Fixture fixture = fixture();
        Path catalog = fixture.root().resolve("catalog.tsv");
        Files.writeString(catalog, "iris-generation-kernel-catalog-v1\ncurrent\t1\t1\t1\nkernel\t1\n");
        Map<Integer, GenerationKernelSeal.SourceManifest> manifests = Map.of(1, GenerationKernelSeal.read(fixture.seal()));
        GenerationKernelSeal.verifyCatalog(catalog, manifests);
        Files.writeString(catalog, "iris-generation-kernel-catalog-v1\ncurrent\t1\t2\t1\nkernel\t1\n");
        assertThrows(IOException.class, () -> GenerationKernelSeal.verifyCatalog(catalog, manifests));
        Files.writeString(catalog, "iris-generation-kernel-catalog-v1\ncurrent\t1\t1\t1\nkernel\t1\nkernel\t2\n");
        assertThrows(IOException.class, () -> GenerationKernelSeal.verifyCatalog(catalog, manifests));
    }

    @Test
    public void permitsSeparatelyScopedFutureKernelsWithoutChangingRetainedSeal() throws Exception {
        Fixture fixture = fixture();
        Path future = fixture.root().resolve("generation/kernels/v2/NewMath.java");
        Files.createDirectories(future.getParent());
        Files.writeString(future, "class NewMath {}");
        GenerationKernelSeal.verify(fixture.root(), fixture.seal(), fixture.dependencies());
        GenerationKernelSeal.SourceManifest next = GenerationKernelSeal.capture(new GenerationKernelSeal.CaptureOptions(
                fixture.root(), 2, "example.KernelV2", List.of(new GenerationKernelSeal.AlgorithmVersion(2, 3)),
                List.of("generation/kernels/v2"), List.of()), fixture.dependencies());
        assertNotEquals(GenerationKernelSeal.fingerprint(next),
                GenerationKernelSeal.fingerprint(GenerationKernelSeal.read(fixture.seal())));
        assertThrows(IOException.class,
                () -> GenerationKernelSeal.writeNew(fixture.seal(), next));
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.createDirectories(root.resolve("generation"));
        Files.writeString(root.resolve("generation/Noise.java"), "class Noise { int value = 1; }");
        Path dependency = root.resolve("math.jar");
        artifact(dependency, "same code", "same version", 0L);
        GenerationKernelSeal.CaptureOptions options = new GenerationKernelSeal.CaptureOptions(
                root, 1, "example.KernelV1", List.of(new GenerationKernelSeal.AlgorithmVersion(1, 1)),
                List.of("generation"), List.of("generation/kernels"));
        Path seal = root.resolve("abi-1.seal");
        Map<String, Path> dependencies = Map.of("math", dependency);
        GenerationKernelSeal.writeNew(seal, GenerationKernelSeal.capture(options, dependencies));
        return new Fixture(root, seal, dependency, dependencies, options);
    }

    private static void artifact(Path path, String content, String version, long timestamp) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            ZipEntry metadata = new ZipEntry("META-INF/MANIFEST.MF");
            metadata.setTime(timestamp);
            output.putNextEntry(metadata);
            output.write(("Manifest-Version: 1.0\r\nImplementation-Version: " + version + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            ZipEntry code = new ZipEntry("example/Math.class");
            code.setTime(timestamp);
            output.putNextEntry(code);
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private record Fixture(Path root, Path seal, Path dependency, Map<String, Path> dependencies,
                           GenerationKernelSeal.CaptureOptions options) {
    }
}
