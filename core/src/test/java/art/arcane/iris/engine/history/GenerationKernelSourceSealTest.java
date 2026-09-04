package art.arcane.iris.engine.history;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

public class GenerationKernelSourceSealTest {
    @Test
    public void fingerprintUsesTheCompleteManifest() throws Exception {
        byte[] initial = seal("a".repeat(64));
        assertEquals(GenerationKernelSourceSeal.fingerprint(1, "example.KernelV1", initial),
                GenerationKernelSourceSeal.fingerprint(1, "example.KernelV1", initial));
        assertNotEquals(GenerationKernelSourceSeal.fingerprint(1, "example.KernelV1", initial),
                GenerationKernelSourceSeal.fingerprint(1, "example.KernelV1", seal("b".repeat(64))));
    }

    @Test
    public void rejectsMismatchedAbiFactoryAndMalformedHashes() {
        assertThrows(IOException.class,
                () -> GenerationKernelSourceSeal.fingerprint(2, "example.KernelV1", seal("a".repeat(64))));
        assertThrows(IOException.class,
                () -> GenerationKernelSourceSeal.fingerprint(1, "example.OtherKernel", seal("a".repeat(64))));
        assertThrows(IOException.class,
                () -> GenerationKernelSourceSeal.fingerprint(1, "example.KernelV1", seal("not-a-hash")));
        assertThrows(IOException.class,
                () -> GenerationKernelSourceSeal.fingerprint(1, "example.KernelV1", new byte[0]));
    }

    @Test
    public void rejectsDuplicateAndUnsortedEntries() {
        String source = new String(seal("a".repeat(64)), StandardCharsets.UTF_8);
        String duplicate = source + "dependency\tmath\t" + "a".repeat(64) + "\n";
        assertThrows(IOException.class, () -> GenerationKernelSourceSeal.fingerprint(1,
                "example.KernelV1", duplicate.getBytes(StandardCharsets.UTF_8)));
        String unordered = source + "scope\tother\n";
        assertThrows(IOException.class, () -> GenerationKernelSourceSeal.fingerprint(1,
                "example.KernelV1", unordered.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void standardKernelUsesThePackagedSourceSeal() {
        assertEquals(GenerationKernelSourceSeal.requireFingerprint(1, GenerationKernelV1.class),
                GenerationKernelV1.IMPLEMENTATION_FINGERPRINT);
    }

    private static byte[] seal(String fingerprint) {
        return ("iris-generation-kernel-seal-v1\nabi\t1\nfactory\texample.KernelV1\n"
                + "algorithm\t1\t1\nscope\tgeneration\nsource\tgeneration/Noise.java\t" + fingerprint
                + "\ndependency\tmath\t" + "c".repeat(64) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
