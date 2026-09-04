package art.arcane.iris.engine.history;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GenerationAdmissionTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void cutoverWaitsForEveryOpenGenerationStageAcrossInstances() throws Exception {
        GenerationAdmission first = new GenerationAdmission(temporaryFolder.getRoot().toPath());
        GenerationAdmission second = new GenerationAdmission(temporaryFolder.getRoot().toPath().resolve(".").normalize());
        GenerationAdmission.StageLease stage = first.enterStage();
        CountDownLatch requested = new CountDownLatch(1);
        CompletableFuture<Void> cutover = CompletableFuture.runAsync(() -> {
            requested.countDown();
            try (GenerationAdmission.CutoverLease ignored = second.beginCutover()) {
            }
        });

        assertTrue(requested.await(5L, TimeUnit.SECONDS));
        Thread.sleep(100L);
        assertFalse(cutover.isDone());
        stage.close();
        cutover.get(5L, TimeUnit.SECONDS);
    }

    @Test
    public void aWaitingCutoverPreventsLaterStagesFromOvertakingIt() throws Exception {
        GenerationAdmission admission = new GenerationAdmission(temporaryFolder.getRoot().toPath());
        GenerationAdmission.StageLease firstStage = admission.enterStage();
        CountDownLatch cutoverEntered = new CountDownLatch(1);
        CountDownLatch releaseCutover = new CountDownLatch(1);
        CompletableFuture<Void> cutover = CompletableFuture.runAsync(() -> {
            try (GenerationAdmission.CutoverLease ignored = admission.beginCutover()) {
                cutoverEntered.countDown();
                await(releaseCutover);
            }
        });
        CompletableFuture<Void> laterStage = CompletableFuture.runAsync(() -> {
            try (GenerationAdmission.StageLease ignored = admission.enterStage()) {
            }
        });

        firstStage.close();
        assertTrue(cutoverEntered.await(5L, TimeUnit.SECONDS));
        assertFalse(laterStage.isDone());
        releaseCutover.countDown();
        cutover.get(5L, TimeUnit.SECONDS);
        laterStage.get(5L, TimeUnit.SECONDS);
    }

    @Test
    public void aStageMayCompleteOnAThreadOtherThanItsCaller() throws Exception {
        GenerationAdmission admission = new GenerationAdmission(temporaryFolder.getRoot().toPath());
        GenerationAdmission.StageLease stage = admission.enterStage();
        CompletableFuture<Void> close = CompletableFuture.runAsync(stage::close);
        close.get(5L, TimeUnit.SECONDS);

        CompletableFuture<Void> cutover = CompletableFuture.runAsync(() -> {
            try (GenerationAdmission.CutoverLease ignored = admission.beginCutover()) {
            }
        });
        cutover.get(5L, TimeUnit.SECONDS);
    }

    @Test
    public void startupCutoverIsPermanentlyClosedByFirstStageAdmission() {
        GenerationAdmission admission = new GenerationAdmission(
                temporaryFolder.getRoot().toPath().resolve("startup-only")
        );
        try (GenerationAdmission.CutoverLease ignored = admission.beginStartupCutover()) {
        }
        try (GenerationAdmission.StageLease ignored = admission.enterStage()) {
        }

        assertThrows(IllegalStateException.class, admission::beginStartupCutover);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch.", exception);
        }
    }
}
