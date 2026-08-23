package art.arcane.iris.core.runtime;

import art.arcane.iris.core.project.IrisProject;
import art.arcane.iris.core.tools.IrisCreator;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StudioOpenCoordinatorOpenKindTest {
    @Test
    public void standardStudioOwnsWorkspaceAndEntryTeleport() {
        StudioOpenCoordinator.StudioOpenKind kind = StudioOpenCoordinator.StudioOpenKind.STANDARD;

        assertTrue(kind.openWorkspace());
        assertTrue(kind.teleportThroughStandardEntry());
        assertTrue(kind.prepareGeneratorState());
        assertEquals(
                IrisCreator.DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY,
                kind.datapackPreparation());
    }

    @Test
    public void bothStudioKindsReuseOnlyAnUnchangedLoadedRuntime() {
        for (StudioOpenCoordinator.StudioOpenKind kind : StudioOpenCoordinator.StudioOpenKind.values()) {
            assertEquals(
                    IrisCreator.DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY,
                    kind.datapackPreparation());
        }
    }

    @Test
    public void jigsawStudioLeavesWorkspaceClosedAndUsesItsDestinationTeleport() {
        StudioOpenCoordinator.StudioOpenKind kind = StudioOpenCoordinator.StudioOpenKind.JIGSAW;

        assertFalse(kind.openWorkspace());
        assertFalse(kind.teleportThroughStandardEntry());
        assertFalse(kind.prepareGeneratorState());
        assertEquals(
                IrisCreator.DatapackPreparation.REUSE_LOADED_RUNTIME_IF_READY,
                kind.datapackPreparation());
    }

    @Test
    public void studioRequestRetainsExplicitOpenKind() {
        IrisProject project = new IrisProject(new File("overworld"));
        StudioOpenCoordinator.StudioOpenRequest request =
                StudioOpenCoordinator.StudioOpenRequest.studioProject(
                        project,
                        null,
                        1337L,
                        StudioOpenCoordinator.StudioOpenKind.JIGSAW,
                        null,
                        null);

        assertEquals(StudioOpenCoordinator.StudioOpenKind.JIGSAW, request.openKind());
    }

    @Test
    public void onlyAStandardPlayerOpenLoadsTheEntry() {
        assertTrue(StudioOpenCoordinator.requiresLoadedEntry(request(
                StudioOpenCoordinator.StudioOpenKind.STANDARD,
                "Magic_Psycho")));
        assertFalse(StudioOpenCoordinator.requiresLoadedEntry(request(
                StudioOpenCoordinator.StudioOpenKind.STANDARD,
                null)));
        assertFalse(StudioOpenCoordinator.requiresLoadedEntry(request(
                StudioOpenCoordinator.StudioOpenKind.STANDARD,
                "   ")));
        assertFalse(StudioOpenCoordinator.requiresLoadedEntry(request(
                StudioOpenCoordinator.StudioOpenKind.JIGSAW,
                "Magic_Psycho")));
    }

    @Test
    public void activeStudioTeleportIsSerializedAndBoundedBeforeNativeDelegation() throws Exception {
        String coordinator = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java"))
                .replace("\r\n", "\n");
        String service = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/service/StudioSVC.java"))
                .replace("\r\n", "\n");
        int coordinatorStart = coordinator.indexOf(
                "public CompletableFuture<Boolean> teleportPlayerToProject(");
        int coordinatorEnd = coordinator.indexOf("private void executeOpen", coordinatorStart);
        String coordinatorMethod = coordinator.substring(coordinatorStart, coordinatorEnd);
        int serviceStart = service.indexOf(
                "public CompletableFuture<Boolean> teleportToActiveProject(Player player)");
        int serviceEnd = service.indexOf("public void open(VolmitSender", serviceStart);
        String serviceMethod = service.substring(serviceStart, serviceEnd);
        int transitionAdmission = serviceMethod.indexOf("studioTransitions.submit(() ->");
        int projectCapture = serviceMethod.indexOf("IrisProject project = activeProject");
        int publicDeadline = serviceMethod.indexOf(
                "transition.orTimeout(STUDIO_PLAYER_TELEPORT_TIMEOUT_SECONDS");
        int admissionClose = serviceMethod.indexOf(
                "transition.whenComplete((ignored, failure) -> admission.set(false))");
        int nativeClaim = coordinatorMethod.indexOf(
                "activeAdmission.compareAndSet(true, false)");
        int nativeDelegation = coordinatorMethod.indexOf(
                "WorldRuntimeControlService.get().teleport(player, entry)");

        assertTrue(transitionAdmission >= 0);
        assertTrue(projectCapture > transitionAdmission);
        assertTrue(publicDeadline > projectCapture);
        assertTrue(admissionClose > publicDeadline);
        assertTrue(serviceMethod.contains("deadlineNanos"));
        assertTrue(coordinatorMethod.contains("beforeStudioTeleportDeadline("));
        assertTrue(coordinatorMethod.contains("CompletableFuture<T> bounded = new CompletableFuture<>()"));
        assertTrue(coordinatorMethod.contains(
                "CompletableFuture.delayedExecutor(remainingNanos, TimeUnit.NANOSECONDS)"));
        assertFalse(coordinatorMethod.contains("stage.orTimeout("));
        assertTrue(coordinatorMethod.contains("CompletableFuture<EntryChunkLease> entryLease"));
        assertTrue(coordinatorMethod.contains("releaseLeaseAfterSettlement("));
        assertTrue(nativeClaim >= 0);
        assertTrue(nativeDelegation > nativeClaim);
    }

    @Test
    public void entryTicketLeaseIsReleasedThroughTheSchedulerBeforeFailureCleanup() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java"))
                .replace("\r\n", "\n");
        int leaseStart = source.indexOf("private static final class EntryChunkLease");
        int registryStart = source.indexOf("static final class EntryLoadRegistry", leaseStart);
        String lease = source.substring(leaseStart, registryStart);
        int executeStart = source.indexOf("private void executeOpen(");
        int executeEnd = source.indexOf("static boolean requiresLoadedEntry", executeStart);
        String execute = source.substring(executeStart, executeEnd);
        int catchStart = execute.indexOf("} catch (Throwable e) {");
        int settleUse = execute.indexOf(
                "settleEntryUseAfterOperation(entryUseFuture, nativeTeleportFuture)",
                catchStart);
        int cleanup = execute.indexOf("updateStage(request, \"cleanup\", 1.00D)", catchStart);

        assertTrue(source.contains("boolean acquired = world.addPluginChunkTicket("));
        assertTrue(source.contains("if (!acquired)"));
        assertTrue(lease.contains("CompletableFuture<Void> scheduledRelease = J.sfut("));
        assertTrue(lease.contains("world.removePluginChunkTicket("));
        assertTrue(settleUse > catchStart);
        assertTrue(cleanup > settleUse);
    }

    @Test
    public void studioTimingSeparatesOrderedLifecyclePhases() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int previous = -1;
        for (String phase : List.of(
                "resolve_dimension_and_cleanup",
                "create_world_total",
                "apply_world_rules",
                "prepare_generator",
                "resolve_entry_anchor",
                "load_entry_chunk",
                "resolve_safe_entry",
                "teleport_standard_entry",
                "finalize_open")) {
            int current = source.indexOf("\"" + phase + "\"", previous + 1);
            assertTrue("Missing or out-of-order Studio timing phase " + phase, current > previous);
            previous = current;
        }
        assertTrue(source.contains("long openStart = System.nanoTime();"));
    }

    @Test
    public void structureStateActivatesAfterStandardTeleportBeforeFinalization() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int entryReady = source.indexOf(
                "entryLeaseFuture.get(STUDIO_ENTRY_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)");
        int teleport = source.indexOf(
                "WorldRuntimeControlService.get().teleport(player, safeEntry)", entryReady);
        int completionCall = source.indexOf("endStudioEntryBootstrap(world, provider)", teleport);
        int finalizeOpen = source.indexOf(
                "updateStage(request, \"finalize_open\", 1.00D)", completionCall);
        int futureComplete = source.indexOf(
                "future.complete(new StudioOpenResult(world, safeEntry))", finalizeOpen);
        int methodStart = source.indexOf(
                "private void endStudioEntryBootstrap(World world, PlatformChunkGenerator provider)");
        int methodEnd = source.indexOf("private void abandonStudioEntryBootstrap", methodStart);
        String method = source.substring(methodStart, methodEnd);
        int scheduled = method.indexOf("J.sfut(() ->");
        int claim = method.indexOf("activationClaim.compareAndSet(true, false)");
        int activation = method.indexOf("INMS.get().completeStudioStructureBootstrap(world)");
        int gateRelease = method.indexOf("bukkitGenerator::endStudioEntryBootstrap");

        assertTrue(entryReady >= 0);
        assertTrue(teleport > entryReady);
        assertTrue(completionCall > teleport);
        assertTrue(finalizeOpen > completionCall);
        assertTrue(futureComplete > finalizeOpen);
        assertTrue(scheduled >= 0);
        assertTrue(claim > scheduled);
        assertTrue(activation > claim);
        assertTrue(gateRelease > activation);
        assertTrue(source.contains("abandonStudioEntryBootstrap(world, e);"));
    }

    @Test
    public void failedOpenMarshalsRetainedStateAbandonmentToTheServerScheduler() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int methodStart = source.indexOf(
                "private void abandonStudioEntryBootstrap(World world, Throwable failure)");
        int methodEnd = source.indexOf("private void deferFailedOpenCleanup", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(method.contains("if (J.isPrimaryThread())"));
        assertTrue(method.contains("CompletableFuture<Void> abandonment = J.sfut("));
        assertTrue(method.contains("abandonment.get(STUDIO_STRUCTURE_ACTIVATION_TIMEOUT_SECONDS"));
    }

    @Test
    public void queuedRestartDefersFailedOpenCleanupWithoutAcquiringALiveCloseLease() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int openCatch = source.indexOf("} catch (Throwable e) {");
        int restartCheck = source.indexOf(
                ".active(LifecycleOperationCoordinator.Domain.SERVER_LIFECYCLE)",
                openCatch);
        int restartDeferral = source.indexOf(
                "deferFailedOpenCleanupToRestart(",
                restartCheck);
        int liveCleanup = source.indexOf("cleanupFailedOpen(", restartDeferral);
        int methodStart = source.indexOf(
                "private void deferFailedOpenCleanupToRestart(",
                restartDeferral);
        int methodEnd = source.indexOf("private boolean transientWorldStorageExists", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(restartCheck > openCatch);
        assertTrue(restartDeferral > restartCheck);
        assertTrue(liveCleanup > restartDeferral);
        assertTrue(method.contains("queueStartupCleanup("));
        assertFalse(method.contains("closeWorldCoordinated("));
    }

    @Test
    public void openFinalizerReturnsToTheServerThreadBeforeCompletion() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/art/arcane/iris/core/runtime/StudioOpenCoordinator.java")).replace("\r\n", "\n");
        int finalizerCall = source.indexOf("runOpenFinalizer(request.onDone(), world);");
        int futureCompletion = source.indexOf(
                "future.complete(new StudioOpenResult(world, safeEntry))", finalizerCall);
        int methodStart = source.indexOf(
                "private void runOpenFinalizer(Consumer<World> finalizer, World world)");
        int methodEnd = source.indexOf("private long elapsedMillis", methodStart);
        String method = source.substring(methodStart, methodEnd);

        assertTrue(finalizerCall >= 0);
        assertTrue(futureCompletion > finalizerCall);
        assertTrue(method.contains("if (J.isPrimaryThread())"));
        assertTrue(method.contains("J.sfut(() -> finalizer.accept(world))"));
        assertTrue(method.contains(
                "completion.get(STUDIO_STRUCTURE_ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)"));
    }

    private static StudioOpenCoordinator.StudioOpenRequest request(
            StudioOpenCoordinator.StudioOpenKind openKind,
            String playerName
    ) {
        return new StudioOpenCoordinator.StudioOpenRequest(
                "overworld",
                null,
                null,
                1337L,
                "iris-test",
                playerName,
                openKind,
                false,
                null,
                null);
    }
}
