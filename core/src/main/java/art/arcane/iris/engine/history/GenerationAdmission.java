package art.arcane.iris.engine.history;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class GenerationAdmission {
    private static final ConcurrentHashMap<Path, State> STATES = new ConcurrentHashMap<>();

    private final State state;

    public GenerationAdmission(Path dimensionRoot) {
        Path root = Objects.requireNonNull(dimensionRoot, "dimensionRoot")
                .toAbsolutePath()
                .normalize();
        this.state = STATES.computeIfAbsent(root, ignored -> new State());
    }

    public StageLease enterStage() {
        state.enterStage();
        return new StageLease(state);
    }

    public CutoverLease beginCutover() {
        state.beginCutover(false);
        return new CutoverLease(state);
    }

    CutoverLease beginStartupCutover() {
        state.beginCutover(true);
        return new CutoverLease(state);
    }

    public static final class StageLease implements AutoCloseable {
        private final State state;
        private final AtomicBoolean closed;

        private StageLease(State state) {
            this.state = state;
            this.closed = new AtomicBoolean();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.leaveStage();
            }
        }
    }

    public static final class CutoverLease implements AutoCloseable {
        private final State state;
        private final AtomicBoolean closed;

        private CutoverLease(State state) {
            this.state = state;
            this.closed = new AtomicBoolean();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.endCutover();
            }
        }
    }

    private static final class State {
        private final ReentrantLock lock;
        private final Condition changed;
        private int activeStages;
        private int waitingCutovers;
        private boolean cutoverActive;
        private boolean generationAdmissionOpened;

        private State() {
            this.lock = new ReentrantLock(true);
            this.changed = lock.newCondition();
        }

        private void enterStage() {
            lock.lock();
            try {
                generationAdmissionOpened = true;
                while (cutoverActive || waitingCutovers > 0) {
                    changed.awaitUninterruptibly();
                }
                activeStages++;
            } finally {
                lock.unlock();
            }
        }

        private void leaveStage() {
            lock.lock();
            try {
                if (activeStages < 1) {
                    throw new IllegalStateException("No generation stage is active.");
                }
                activeStages--;
                if (activeStages == 0) {
                    changed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        private void beginCutover(boolean startupOnly) {
            lock.lock();
            try {
                if (startupOnly && generationAdmissionOpened) {
                    throw new IllegalStateException(
                            "Generation activation promotion is only allowed before generation admission opens."
                    );
                }
                waitingCutovers++;
                try {
                    while (cutoverActive || activeStages > 0) {
                        changed.awaitUninterruptibly();
                    }
                    cutoverActive = true;
                } finally {
                    waitingCutovers--;
                }
            } finally {
                lock.unlock();
            }
        }

        private void endCutover() {
            lock.lock();
            try {
                if (!cutoverActive) {
                    throw new IllegalStateException("No generation cutover is active.");
                }
                cutoverActive = false;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
