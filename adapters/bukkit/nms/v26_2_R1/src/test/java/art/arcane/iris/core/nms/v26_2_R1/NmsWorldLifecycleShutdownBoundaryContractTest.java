package art.arcane.iris.core.nms.v26_2_R1;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class NmsWorldLifecycleShutdownBoundaryContractTest {
    @Test
    public void shutdownClassificationUsesTheServerStopFlagInsteadOfPluginEnablement() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsBindingSource")).resolveSibling("NmsWorldLifecycle.java"));
        String stopping = section(source, "public boolean isServerStopping()", "public ServerShutdownBoundary createServerShutdownBoundary");

        assertTrue(stopping.contains("((CraftServer) Bukkit.getServer()).getServer().hasStopped()"));
        assertFalse(stopping.contains("isEnabled()"));
        assertFalse(stopping.contains("isRunning()"));
    }

    @Test
    public void pluginLoaderGuardUsesSystemAgentIdentityAndIsRemovedAtRelease() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsBindingSource")).resolveSibling("NmsWorldLifecycle.java"));
        String injection = section(source, "public boolean injectBukkit()", "public void ensureServerLevelInjection()");
        String guard = section(source, "private static class PluginClassLoaderCloseAdvice", "private static class LevelStorageAccessAdvice");
        String release = section(source, "public void releasePluginClassLoaderClose()", "private static final class PluginClassLoaderInjectionListener");

        assertTrue(injection.contains("Agent.requireClassLoaderCloseDeferral()"));
        assertTrue(injection.contains("getMethod(\"close\").getDeclaringClass()"));
        assertTrue(injection.contains("Advice.to(PluginClassLoaderCloseAdvice.class)"));
        assertTrue(guard.contains("skipOn = Advice.OnNonDefaultValue.class"));
        assertTrue(guard.contains("ClassLoader.getSystemClassLoader()"));
        assertTrue(guard.contains("getMethod(\"deferClassLoaderClose\", ClassLoader.class)"));
        assertTrue(guard.contains(".invoke(null, loader)"));
        assertFalse(guard.contains("Bukkit.getPluginManager()"));
        assertTrue(release.indexOf("transformer.reset(") > release.indexOf("pluginClassLoaderCloseDeferred = false"));
        assertTrue(release.indexOf("Agent.releaseClassLoader(") > release.indexOf("transformer.reset("));
    }

    @Test
    public void shutdownBoundaryUsesPaperFullyShutdownStateAndServerThread() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("iris.nmsBindingSource")).resolveSibling("NmsWorldLifecycle.java")).replace("\r\n", "\n");
        String boundary = section(source, "public ServerShutdownBoundary createServerShutdownBoundary", "private static final class PluginClassLoaderInjectionListener");

        assertTrue(boundary.contains("new ServerShutdownBoundary("));
        assertTrue(boundary.contains("server.hasFullyShutdown"));
        assertTrue(boundary.contains("server.getRunningThread()"));
        assertTrue(boundary.contains("((CraftServer) Bukkit.getServer()).getServer()"));
        assertFalse(boundary.contains("getHandle()"));
    }

    private static String section(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue("Missing source section starting with " + startMarker, start >= 0);
        assertTrue("Missing source section ending with " + endMarker, end > start);
        return source.substring(start, end);
    }
}
