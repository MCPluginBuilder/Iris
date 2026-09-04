package art.arcane.iris.core.tools;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IrisToolbeltShutdownContractTest {
    @Test
    public void disabledPluginDoesNotImplyServerShutdown() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.irisToolbeltSource")));
        int start = source.indexOf("public static boolean isServerStopping()");
        int end = source.indexOf("public static void beginWorldMaintenance", start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        String stopping = source.substring(start, end);

        assertTrue(stopping.contains("return INMS.isBound() && INMS.get().isServerStopping();"));
        assertFalse(stopping.contains("isEnabled()"));
        assertFalse(stopping.contains("hasPlugin()"));
        assertFalse(stopping.contains("getMethod("));
    }
}
