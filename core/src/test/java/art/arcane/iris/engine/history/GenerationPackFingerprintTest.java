package art.arcane.iris.engine.history;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class GenerationPackFingerprintTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void versionOneMatchesItsGoldenContentAddress() throws Exception {
        Path pack = temporaryFolder.newFolder("golden-pack").toPath();
        Files.createDirectories(pack.resolve("objects"));
        Files.writeString(pack.resolve("objects/tree.json"), "oak");
        Files.createDirectories(pack.resolve("dimensions"));
        Files.writeString(pack.resolve("dimensions/main.json"), "{}");
        Files.createDirectories(pack.resolve(".git"));
        Files.writeString(pack.resolve(".git/config"), "ignored");
        Files.writeString(pack.resolve("workspace.code-workspace"), "ignored");

        String fingerprint = GenerationPackFingerprint.compute(
                pack,
                GenerationPackFingerprint.CURRENT_VERSION
        );

        assertEquals("ad7d39c2b95bd3001248778911ce6bbd6b4cbdcece28145838c2b923a0bf73b3", fingerprint);
    }

    @Test
    public void contentChangesProduceDifferentAddresses() throws Exception {
        Path pack = temporaryFolder.newFolder("changed-pack").toPath();
        Files.writeString(pack.resolve("dimension.json"), "first");
        String first = GenerationPackFingerprint.compute(pack, GenerationPackFingerprint.CURRENT_VERSION);
        Files.writeString(pack.resolve("dimension.json"), "second");

        String second = GenerationPackFingerprint.compute(pack, GenerationPackFingerprint.CURRENT_VERSION);

        assertNotEquals(first, second);
    }

    @Test
    public void unsupportedVersionsAndSymbolicLinksFailClosed() throws Exception {
        Path pack = temporaryFolder.newFolder("unsafe-pack").toPath();
        assertThrows(IOException.class, () -> GenerationPackFingerprint.compute(pack, 2));

        Path target = temporaryFolder.newFile("outside.json").toPath();
        try {
            Files.createSymbolicLink(pack.resolve("linked.json"), target);
        } catch (IOException | UnsupportedOperationException exception) {
            Assume.assumeNoException(exception);
        }
        assertThrows(
                IOException.class,
                () -> GenerationPackFingerprint.compute(pack, GenerationPackFingerprint.CURRENT_VERSION)
        );
    }
}
