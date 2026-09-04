package art.arcane.iris.engine.history;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.framework.EngineTarget;
import art.arcane.iris.engine.object.IrisDimension;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class GenerationHistoryRuntimeRouter implements AutoCloseable {
    public static final int DEFAULT_TRANSITION_WIDTH_BLOCKS = 256;
    public static final int DEFAULT_RUNTIME_CACHE_CAPACITY = 4;

    private final IrisEngine engine;
    private final GenerationHistory history;
    private final GenerationBoundarySignatureSampler signatureSampler;
    private final ActivationRuntimeFactory runtimeFactory;
    private final LinkedHashMap<Long, RuntimeCacheEntry> bindings;
    private final Map<Long, RuntimeRetirement> retiringBindings;
    private final int runtimeCacheCapacity;
    private final ReentrantLock stateLock;
    private final Condition inactive;
    private final ThreadLocal<Integer> operationDepth;
    private final ThreadLocal<RuntimeRoute> scopedRoute;
    private int activeOperations;
    private int activeLoads;
    private boolean closed;
    private boolean closeComplete;
    private boolean closeCleanupInProgress;
    private Throwable closeFailure;

    private GenerationHistoryRuntimeRouter(
            IrisEngine engine,
            GenerationHistory history,
            GenerationBoundarySignatureSampler signatureSampler,
            ActivationRuntimeFactory runtimeFactory,
            int runtimeCacheCapacity
    ) throws IOException {
        this.engine = Objects.requireNonNull(engine, "Iris engine");
        this.history = Objects.requireNonNull(history, "generation history");
        this.signatureSampler = Objects.requireNonNull(signatureSampler, "boundary signature sampler");
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "activation runtime factory");
        if (runtimeCacheCapacity < 1) {
            throw new IllegalArgumentException("Generation runtime cache capacity must be positive.");
        }
        this.runtimeCacheCapacity = runtimeCacheCapacity;
        this.bindings = new LinkedHashMap<>(runtimeCacheCapacity, 0.75F, true);
        this.retiringBindings = new LinkedHashMap<>();
        this.stateLock = new ReentrantLock(true);
        this.inactive = stateLock.newCondition();
        this.operationDepth = ThreadLocal.withInitial(() -> 0);
        this.scopedRoute = new ThreadLocal<>();

        GenerationActivation active = history.activeActivation();
        GenerationEpoch epoch = requireEpoch(active);
        IrisEngine.GenerationRuntimeBinding base = engine.getActiveGenerationRuntimeBinding();
        runtimeFactory.validateBase(engine, history, active, epoch, base);
        requireBindingContract(active, epoch, base);
        RuntimeCacheEntry baseEntry = new RuntimeCacheEntry(active.activationId());
        baseEntry.binding = base;
        baseEntry.defaultRuntime = true;
        bindings.put(active.activationId(), baseEntry);
        try {
            engine.attachGenerationHistoryRuntimeRouter(this);
        } catch (Throwable failure) {
            stateLock.lock();
            try {
                bindings.clear();
                closed = true;
                closeComplete = true;
            } finally {
                stateLock.unlock();
            }
            throw propagate(failure, "Unable to attach the generation-history runtime router.");
        }
    }

    public static GenerationHistoryRuntimeRouter attach(
            IrisEngine engine,
            GenerationHistory history,
            GenerationBoundarySignatureSampler signatureSampler
    ) throws IOException {
        return attach(engine, history, signatureSampler, new DefaultActivationRuntimeFactory());
    }

    public static GenerationHistoryRuntimeRouter attachAndPromotePending(
            IrisEngine engine,
            GenerationHistory history,
            GenerationBoundarySignatureSampler signatureSampler
    ) throws IOException {
        return attachAndPromotePending(
                engine,
                history,
                signatureSampler,
                DEFAULT_TRANSITION_WIDTH_BLOCKS
        );
    }

    public static GenerationHistoryRuntimeRouter attachAndPromotePending(
            IrisEngine engine,
            GenerationHistory history,
            GenerationBoundarySignatureSampler signatureSampler,
            int transitionWidthBlocks
    ) throws IOException {
        return attachAndPromotePending(
                engine,
                history,
                signatureSampler,
                transitionWidthBlocks,
                new DefaultActivationRuntimeFactory(),
                DEFAULT_RUNTIME_CACHE_CAPACITY
        );
    }

    static GenerationHistoryRuntimeRouter attachAndPromotePending(
            IrisEngine engine,
            GenerationHistory history,
            GenerationBoundarySignatureSampler signatureSampler,
            int transitionWidthBlocks,
            ActivationRuntimeFactory runtimeFactory
    ) throws IOException {
        return attachAndPromotePending(
                engine,
                history,
                signatureSampler,
                transitionWidthBlocks,
                runtimeFactory,
                DEFAULT_RUNTIME_CACHE_CAPACITY
        );
    }

    static GenerationHistoryRuntimeRouter attachAndPromotePending(
            IrisEngine engine,
            GenerationHistory history,
            GenerationBoundarySignatureSampler signatureSampler,
            int transitionWidthBlocks,
            ActivationRuntimeFactory runtimeFactory,
            int runtimeCacheCapacity
    ) throws IOException {
        GenerationHistoryRuntimeRouter router = attach(
                engine,
                history,
                signatureSampler,
                runtimeFactory,
                runtimeCacheCapacity
        );
        try {
            router.promotePendingAndReconcileCurrentKernel(transitionWidthBlocks);
            return router;
        } catch (Throwable failure) {
            try {
                router.close();
            } catch (Throwable closeFailure) {
                if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw propagate(failure, "Unable to promote the pending generation activation.");
        }
    }

    static GenerationHistoryRuntimeRouter attach(
            IrisEngine engine,
            GenerationHistory history,
            GenerationBoundarySignatureSampler signatureSampler,
            ActivationRuntimeFactory runtimeFactory
    ) throws IOException {
        return attach(
                engine,
                history,
                signatureSampler,
                runtimeFactory,
                DEFAULT_RUNTIME_CACHE_CAPACITY
        );
    }

    static GenerationHistoryRuntimeRouter attach(
            IrisEngine engine,
            GenerationHistory history,
            GenerationBoundarySignatureSampler signatureSampler,
            ActivationRuntimeFactory runtimeFactory,
            int runtimeCacheCapacity
    ) throws IOException {
        return new GenerationHistoryRuntimeRouter(
                engine,
                history,
                signatureSampler,
                runtimeFactory,
                runtimeCacheCapacity
        );
    }

    public IrisEngine engine() {
        return engine;
    }

    public GenerationHistory history() {
        return history;
    }

    public Optional<RuntimeOwnership> currentRuntimeOwnership() {
        RuntimeRoute current = scopedRoute.get();
        return current == null ? Optional.empty() : Optional.of(current.ownership);
    }

    public RuntimeRoute openRoute(int chunkX, int chunkZ) throws IOException {
        enterRouteOperation();
        try {
            GenerationHistory.GenerationStage stage = history.openStage(chunkX, chunkZ);
            try {
                RuntimeLease lease = acquireRuntime(stage.activation(), stage.epoch());
                return new RuntimeRoute(this, stage, lease);
            } catch (Throwable failure) {
                stage.close();
                throw failure;
            }
        } catch (Throwable failure) {
            leaveRouteOperation();
            throw propagate(failure, "Unable to open an Iris generation-history stage.");
        }
    }

    public RuntimeStage openStage(int chunkX, int chunkZ) throws IOException {
        RuntimeRoute route = openRoute(chunkX, chunkZ);
        try {
            RuntimeRoute.RuntimeScope runtimeScope = route.openRuntimeScope();
            return new RuntimeStage(route, runtimeScope, Thread.currentThread());
        } catch (Throwable failure) {
            route.close();
            throw propagate(failure, "Unable to scope an Iris generation-history stage.");
        }
    }

    public CoordinateScope openCoordinateScope(int blockX, int blockZ) throws IOException {
        int chunkX = Math.floorDiv(blockX, GenerationBoundary.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(blockZ, GenerationBoundary.CHUNK_SIZE);
        RuntimeRoute current = scopedRoute.get();
        if (current != null) {
            if (current.chunkX() == chunkX && current.chunkZ() == chunkZ) {
                return new CoordinateScope(blockX, blockZ, current, null);
            }
            RuntimeStage stage = openStage(chunkX, chunkZ);
            return new CoordinateScope(blockX, blockZ, stage.route, stage);
        }
        if (engine.hasGenerationRuntimeScope()) {
            return new CoordinateScope(blockX, blockZ, null, null);
        }
        RuntimeStage stage = openStage(chunkX, chunkZ);
        return new CoordinateScope(blockX, blockZ, stage.route, stage);
    }

    public void preloadActiveRuntimes() throws IOException {
        enterOperation();
        try {
            GenerationActivation active = history.activeActivation();
            try (RuntimeLease ignored = acquireRuntime(active, requireEpoch(active))) {
            }
        } finally {
            leaveOperation();
        }
    }

    private GenerationActivation promotePending() throws IOException {
        enterOperation();
        try {
            GenerationActivation activated = history.promotePending(this::captureBoundarySignatures);
            GenerationEpoch epoch = requireEpoch(activated);
            try (RuntimeLease lease = acquireRuntime(activated, epoch)) {
                engine.setDefaultGenerationRuntime(lease.binding());
                retireBindings(pinDefault(lease.entry));
                return activated;
            }
        } finally {
            leaveOperation();
        }
    }

    private GenerationActivation promotePendingAndReconcileCurrentKernel(
            int transitionWidthBlocks
    ) throws IOException {
        GenerationActivation activated = promotePending();
        while (!history.activeEpoch().kernelVersion().equals(history.currentKernelVersion())) {
            history.stageCurrentKernel(transitionWidthBlocks);
            activated = promotePending();
        }
        return activated;
    }

    public boolean isClosed() {
        stateLock.lock();
        try {
            return closed;
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void close() {
        if (operationDepth.get() > 0) {
            throw new IllegalStateException("Cannot close the generation-history router from an active route.");
        }
        List<RuntimeRetirement> retired = null;
        stateLock.lock();
        try {
            closed = true;
            while (true) {
                if (closeComplete) {
                    rethrowCloseFailure(closeFailure);
                    return;
                }
                if (closeCleanupInProgress || activeOperations > 0 || activeLoads > 0) {
                    inactive.awaitUninterruptibly();
                    continue;
                }
                closeCleanupInProgress = true;
                retired = new ArrayList<>(bindings.size());
                for (RuntimeCacheEntry entry : bindings.values()) {
                    if (!entry.defaultRuntime && entry.binding != null) {
                        retired.add(scheduleRetirementLocked(entry));
                    }
                }
                bindings.clear();
                break;
            }
        } finally {
            stateLock.unlock();
        }

        Throwable failure = null;
        try {
            engine.detachGenerationHistoryRuntimeRouter(this);
        } catch (Throwable detachFailure) {
            failure = detachFailure;
        }
        try {
            retireBindings(retired);
        } catch (Throwable retirementFailure) {
            failure = appendFailure(failure, retirementFailure);
        }

        stateLock.lock();
        try {
            closeFailure = failure;
            retiringBindings.clear();
            closeCleanupInProgress = false;
            closeComplete = true;
            inactive.signalAll();
        } finally {
            stateLock.unlock();
        }
        rethrowCloseFailure(failure);
    }

    private TerrainBoundarySignatureStore.SignatureSampler captureBoundarySignatures(
            GenerationBoundary boundary
    ) {
        return new BoundaryCapture();
    }

    private RuntimeLease acquireRuntime(
            GenerationActivation activation,
            GenerationEpoch epoch
    ) throws IOException {
        RuntimeCacheEntry entry;
        boolean loader = false;
        stateLock.lock();
        try {
            awaitRetirementLocked(activation.activationId());
            entry = bindings.get(activation.activationId());
            if (entry == null) {
                entry = new RuntimeCacheEntry(activation.activationId());
                entry.loading = true;
                bindings.put(activation.activationId(), entry);
                activeLoads++;
                loader = true;
            }
            entry.leases++;
        } finally {
            stateLock.unlock();
        }

        if (loader) {
            loadRuntime(entry, activation, epoch);
        }
        return awaitRuntime(entry, activation.activationId());
    }

    private void awaitRetirementLocked(long activationId) throws IOException {
        while (true) {
            if (closed) {
                throw new IllegalStateException("Generation-history runtime router is closed.");
            }
            RuntimeRetirement retirement = retiringBindings.get(activationId);
            if (retirement == null) {
                return;
            }
            if (retirement.failure != null) {
                throw new IOException("Generation runtime for activation " + activationId
                        + " could not retire safely.", retirement.failure);
            }
            try {
                inactive.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while awaiting generation runtime retirement for activation "
                        + activationId + ".", interrupted);
            }
        }
    }

    private void loadRuntime(
            RuntimeCacheEntry entry,
            GenerationActivation activation,
            GenerationEpoch epoch
    ) {
        IrisEngine.GenerationRuntimeBinding loaded = null;
        Throwable failure = null;
        try {
            loaded = Objects.requireNonNull(
                    runtimeFactory.load(engine, history, activation, epoch),
                    "activation runtime binding"
            );
            requireBindingContract(activation, epoch, loaded);
        } catch (Throwable loadFailure) {
            failure = loadFailure;
        }
        if (failure != null && loaded != null) {
            try {
                engine.closeDetachedGenerationRuntime(loaded);
            } catch (Throwable closeFailure) {
                failure = appendFailure(failure, closeFailure);
            }
        }

        stateLock.lock();
        try {
            entry.binding = failure == null ? loaded : null;
            entry.loadFailure = failure;
            entry.loading = false;
            activeLoads--;
            if (failure != null && bindings.get(entry.activationId) == entry) {
                bindings.remove(entry.activationId);
            }
            inactive.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    private RuntimeLease awaitRuntime(RuntimeCacheEntry entry, long activationId) throws IOException {
        IrisEngine.GenerationRuntimeBinding binding;
        Throwable failure;
        stateLock.lock();
        try {
            while (entry.loading) {
                try {
                    inactive.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    entry.leases--;
                    inactive.signalAll();
                    throw new IOException("Interrupted while loading generation runtime for activation "
                            + activationId + ".", interrupted);
                }
            }
            failure = entry.loadFailure;
            binding = entry.binding;
            if (failure != null || binding == null) {
                entry.leases--;
                inactive.signalAll();
            }
        } finally {
            stateLock.unlock();
        }
        if (failure != null || binding == null) {
            throw propagate(failure == null
                            ? new IllegalStateException("Generation runtime load returned no binding.")
                            : failure,
                    "Unable to load generation runtime for activation " + activationId + ".");
        }
        return new RuntimeLease(this, entry, binding);
    }

    private List<RuntimeRetirement> pinDefault(RuntimeCacheEntry nextDefault) {
        stateLock.lock();
        try {
            for (RuntimeCacheEntry entry : bindings.values()) {
                entry.defaultRuntime = entry == nextDefault;
            }
            return collectEvictionsLocked();
        } finally {
            stateLock.unlock();
        }
    }

    private void releaseRuntime(RuntimeCacheEntry entry) {
        List<RuntimeRetirement> retired;
        stateLock.lock();
        try {
            if (entry.leases <= 0) {
                throw new IllegalStateException("Generation runtime cache entry has no active lease.");
            }
            entry.leases--;
            retired = collectEvictionsLocked();
            inactive.signalAll();
        } finally {
            stateLock.unlock();
        }
        retireBindings(retired);
    }

    private List<RuntimeRetirement> collectEvictionsLocked() {
        ArrayList<RuntimeRetirement> retired = new ArrayList<>();
        while (bindings.size() > runtimeCacheCapacity) {
            RuntimeCacheEntry candidate = null;
            Iterator<Map.Entry<Long, RuntimeCacheEntry>> iterator = bindings.entrySet().iterator();
            while (iterator.hasNext()) {
                RuntimeCacheEntry entry = iterator.next().getValue();
                if (entry.defaultRuntime || entry.loading || entry.leases > 0 || entry.binding == null) {
                    continue;
                }
                candidate = entry;
                iterator.remove();
                break;
            }
            if (candidate == null) {
                break;
            }
            retired.add(scheduleRetirementLocked(candidate));
        }
        return retired;
    }

    private RuntimeRetirement scheduleRetirementLocked(RuntimeCacheEntry entry) {
        RuntimeRetirement retirement = new RuntimeRetirement(entry.activationId, entry.binding);
        if (retiringBindings.putIfAbsent(entry.activationId, retirement) != null) {
            throw new IllegalStateException("Generation runtime already retiring for activation "
                    + entry.activationId + ".");
        }
        return retirement;
    }

    private void retireBindings(List<RuntimeRetirement> retired) {
        Throwable failure = null;
        for (RuntimeRetirement retirement : retired) {
            Throwable retirementFailure = null;
            try {
                engine.closeDetachedGenerationRuntime(retirement.binding);
            } catch (Throwable closeFailure) {
                retirementFailure = closeFailure;
                failure = appendFailure(failure, closeFailure);
            } finally {
                stateLock.lock();
                try {
                    retirement.failure = retirementFailure;
                    if (retirementFailure == null) {
                        retiringBindings.remove(retirement.activationId, retirement);
                    }
                    inactive.signalAll();
                } finally {
                    stateLock.unlock();
                }
            }
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to retire cached generation runtimes.", failure);
        }
    }

    private void requireBindingContract(
            GenerationActivation activation,
            GenerationEpoch epoch,
            IrisEngine.GenerationRuntimeBinding binding
    ) throws IOException {
        if (!epoch.kernelVersion().equals(binding.kernelVersion())) {
            throw new IOException("Generation runtime for activation " + activation.activationId()
                    + " uses kernel " + binding.kernelVersion() + " instead of " + epoch.kernelVersion() + ".");
        }
        GenerationKernelRegistry.RuntimeKernel runtimeKernel = binding.runtimeKernel();
        if (runtimeKernel == null || !epoch.kernelVersion().equals(runtimeKernel.version())) {
            throw new IOException("Generation runtime for activation " + activation.activationId()
                    + " has no matching executable kernel for " + epoch.kernelVersion() + ".");
        }
        if (!epoch.kernelImplementationFingerprint().equals(runtimeKernel.implementationFingerprint())) {
            throw new IOException("Generation runtime for activation " + activation.activationId()
                    + " uses kernel implementation " + runtimeKernel.implementationFingerprint()
                    + " instead of " + epoch.kernelImplementationFingerprint() + ".");
        }
        Path expectedMantle = history.paths().activationMantleRoot(activation.activationId());
        if (!expectedMantle.equals(binding.mantleStorageDirectory())) {
            throw new IOException("Generation runtime for activation " + activation.activationId()
                    + " uses mantle " + binding.mantleStorageDirectory() + " instead of " + expectedMantle + ".");
        }
        TransitionGenerationPlan actualPlan = binding.transitionPlan();
        if (activation.isInitial()) {
            if (actualPlan != null) {
                throw new IOException("Initial generation activation cannot use a transition plan.");
            }
            return;
        }
        TransitionGenerationPlan expectedPlan = history.transitionPlan(activation.activationId());
        if (actualPlan == null || !expectedPlan.specification().equals(actualPlan.specification())) {
            throw new IOException("Generation runtime transition plan does not match activation "
                    + activation.activationId() + ".");
        }
    }

    private GenerationEpoch requireEpoch(GenerationActivation activation) throws IOException {
        return history.manifest().epoch(activation.epochId()).orElseThrow(() -> new IOException(
                "Generation activation " + activation.activationId() + " references missing epoch "
                        + activation.epochId() + "."
        ));
    }

    private void enterOperation() {
        stateLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Generation-history runtime router is closed.");
            }
            activeOperations++;
            operationDepth.set(operationDepth.get() + 1);
        } finally {
            stateLock.unlock();
        }
    }

    private void enterRouteOperation() {
        stateLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Generation-history runtime router is closed.");
            }
            activeOperations++;
        } finally {
            stateLock.unlock();
        }
    }

    private void leaveOperation() {
        stateLock.lock();
        try {
            int depth = operationDepth.get();
            if (depth <= 0 || activeOperations <= 0) {
                throw new IllegalStateException("No generation-history route is active.");
            }
            if (depth == 1) {
                operationDepth.remove();
            } else {
                operationDepth.set(depth - 1);
            }
            activeOperations--;
            if (activeOperations == 0) {
                inactive.signalAll();
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void leaveRouteOperation() {
        stateLock.lock();
        try {
            if (activeOperations <= 0) {
                throw new IllegalStateException("No generation-history route is active.");
            }
            activeOperations--;
            if (activeOperations == 0) {
                inactive.signalAll();
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void enterRuntimeScope() {
        operationDepth.set(operationDepth.get() + 1);
    }

    private RuntimeRoute installScopedRoute(RuntimeRoute route) {
        RuntimeRoute previous = scopedRoute.get();
        scopedRoute.set(route);
        return previous;
    }

    private void restoreScopedRoute(RuntimeRoute route, RuntimeRoute previous) {
        if (scopedRoute.get() != route) {
            throw new IllegalStateException("Generation-history runtime scopes closed out of order.");
        }
        if (previous == null) {
            scopedRoute.remove();
        } else {
            scopedRoute.set(previous);
        }
    }

    private void leaveRuntimeScope() {
        int depth = operationDepth.get();
        if (depth <= 0) {
            throw new IllegalStateException("No generation-history runtime scope is active.");
        }
        if (depth == 1) {
            operationDepth.remove();
            return;
        }
        operationDepth.set(depth - 1);
    }

    private static IOException propagate(Throwable failure, String message) {
        if (failure instanceof IOException ioFailure) {
            return ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IOException(message, failure);
    }

    private static Throwable appendFailure(Throwable existing, Throwable added) {
        if (added == null) {
            return existing;
        }
        if (existing == null) {
            return added;
        }
        if (existing != added) {
            existing.addSuppressed(added);
        }
        return existing;
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Failed to close the generation-history runtime router.", failure);
    }

    private static final class RuntimeCacheEntry {
        private final long activationId;
        private IrisEngine.GenerationRuntimeBinding binding;
        private Throwable loadFailure;
        private int leases;
        private boolean loading;
        private boolean defaultRuntime;

        private RuntimeCacheEntry(long activationId) {
            this.activationId = activationId;
        }
    }

    private static final class RuntimeRetirement {
        private final long activationId;
        private final IrisEngine.GenerationRuntimeBinding binding;
        private Throwable failure;

        private RuntimeRetirement(long activationId, IrisEngine.GenerationRuntimeBinding binding) {
            this.activationId = activationId;
            this.binding = binding;
        }
    }

    private static final class RuntimeLease implements AutoCloseable {
        private final GenerationHistoryRuntimeRouter router;
        private final RuntimeCacheEntry entry;
        private final IrisEngine.GenerationRuntimeBinding binding;
        private boolean closed;

        private RuntimeLease(
                GenerationHistoryRuntimeRouter router,
                RuntimeCacheEntry entry,
                IrisEngine.GenerationRuntimeBinding binding
        ) {
            this.router = router;
            this.entry = entry;
            this.binding = binding;
        }

        private IrisEngine.GenerationRuntimeBinding binding() {
            return binding;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            router.releaseRuntime(entry);
        }
    }

    private final class BoundaryCapture implements TerrainBoundarySignatureStore.SignatureSampler {
        private RuntimeLease lease;
        private IrisEngine.GenerationRuntimeScope scope;
        private long activationId;

        @Override
        public TerrainBoundarySignature sample(int blockX, int blockZ) throws IOException {
            GenerationActivation activation = history.resolveActivation(
                    Math.floorDiv(blockX, GenerationBoundary.CHUNK_SIZE),
                    Math.floorDiv(blockZ, GenerationBoundary.CHUNK_SIZE)
            );
            if (lease == null || activationId != activation.activationId()) {
                close();
                lease = acquireRuntime(activation, requireEpoch(activation));
                try {
                    scope = engine.openGenerationRuntimeScope(lease.binding());
                    activationId = activation.activationId();
                } catch (Throwable failure) {
                    close();
                    throw propagate(failure, "Unable to scope boundary capture.");
                }
            }
            return signatureSampler.sample(engine, blockX, blockZ);
        }

        @Override
        public void close() {
            try {
                if (scope != null) {
                    IrisEngine.GenerationRuntimeScope closing = scope;
                    scope = null;
                    closing.close();
                }
            } finally {
                if (lease != null) {
                    RuntimeLease closing = lease;
                    lease = null;
                    closing.close();
                }
            }
        }
    }

    interface ActivationRuntimeFactory {
        void validateBase(
                IrisEngine engine,
                GenerationHistory history,
                GenerationActivation activation,
                GenerationEpoch epoch,
                IrisEngine.GenerationRuntimeBinding binding
        ) throws IOException;

        IrisEngine.GenerationRuntimeBinding load(
                IrisEngine engine,
                GenerationHistory history,
                GenerationActivation activation,
                GenerationEpoch epoch
        ) throws IOException;
    }

    private static final class DefaultActivationRuntimeFactory implements ActivationRuntimeFactory {
        @Override
        public void validateBase(
                IrisEngine engine,
                GenerationHistory history,
                GenerationActivation activation,
                GenerationEpoch epoch,
                IrisEngine.GenerationRuntimeBinding binding
        ) throws IOException {
            requireWorldSeed(epoch, binding.target().getWorld().getRawWorldSeed());
            Path expectedPack = history.packRoot(activation.activationId());
            Path actualPack = binding.target().getData().getDataFolder().toPath().toAbsolutePath().normalize();
            if (!expectedPack.equals(actualPack)) {
                throw new IOException("Base Iris runtime does not use active activation "
                        + activation.activationId() + " pack " + expectedPack + ".");
            }
            binding.target().getData().bindGenerationRegistryContract(epoch.registryContract());
            requireDimensionContract(binding.target().getDimension(), epoch.dimensionContract(), actualPack);
        }

        @Override
        public IrisEngine.GenerationRuntimeBinding load(
                IrisEngine engine,
                GenerationHistory history,
                GenerationActivation activation,
                GenerationEpoch epoch
        ) throws IOException {
            requireWorldSeed(epoch, engine.getWorld().getRawWorldSeed());
            Path packRoot = history.packRoot(activation.activationId());
            IrisData data = IrisData.openRuntime(packRoot.toFile());
            try {
                data.bindGenerationRegistryContract(epoch.registryContract());
                IrisDimension dimension = data.getDimensionLoader().load(epoch.dimensionContract().dimensionKey());
                if (dimension == null) {
                    throw new IOException("Immutable generation pack does not contain dimension '"
                            + epoch.dimensionContract().dimensionKey() + "': " + packRoot);
                }
                requireDimensionContract(dimension, epoch.dimensionContract(), packRoot);
                TransitionGenerationPlan plan = activation.isInitial()
                        ? null
                        : history.transitionPlan(activation.activationId());
                EngineTarget target = new EngineTarget(engine.getWorld(), dimension, data);
                return engine.buildDetachedGenerationRuntime(
                        target,
                        history.paths().activationMantleRoot(activation.activationId()),
                        epoch.kernelVersion(),
                        plan
                );
            } catch (Throwable failure) {
                if (!data.isClosed()) {
                    data.unregisterEngine(engine);
                    data.close();
                }
                throw propagate(failure, "Unable to build generation runtime for activation "
                        + activation.activationId() + ".");
            }
        }

        private static void requireWorldSeed(GenerationEpoch epoch, long actualSeed) throws IOException {
            if (epoch.worldSeed() != actualSeed) {
                throw new IOException("Generation activation epoch requires world seed "
                        + epoch.worldSeed() + " but the Iris engine uses " + actualSeed + ".");
            }
        }

        private static void requireDimensionContract(
                IrisDimension dimension,
                GenerationEpoch.DimensionContract recorded,
                Path packRoot
        ) throws IOException {
            GenerationEpoch.DimensionContract loaded;
            try {
                loaded = GenerationEpochContractFactory.create(
                        dimension,
                        dimension.getLoadKey(),
                        recorded.dimensionTypeKey()
                );
            } catch (RuntimeException failure) {
                throw new IOException("Unable to validate immutable generation dimension at " + packRoot + ".", failure);
            }
            if (!recorded.equals(loaded)) {
                throw new IOException("Immutable generation pack no longer matches its dimension contract: "
                        + packRoot);
            }
        }
    }

    public static final class RuntimeRoute implements AutoCloseable {
        private final GenerationHistoryRuntimeRouter router;
        private final GenerationHistory.GenerationStage stage;
        private final RuntimeLease lease;
        private final IrisEngine.GenerationRuntimeBinding binding;
        private final RuntimeOwnership ownership;
        private int activeScopes;
        private boolean closed;

        private RuntimeRoute(
                GenerationHistoryRuntimeRouter router,
                GenerationHistory.GenerationStage stage,
                RuntimeLease lease
        ) {
            this.router = router;
            this.stage = stage;
            this.lease = lease;
            this.binding = lease.binding();
            this.ownership = new RuntimeOwnership(stage.activation().activationId(), binding);
        }

        public GenerationHistory.GenerationStage generationStage() {
            return stage;
        }

        public int chunkX() {
            return stage.chunkX();
        }

        public int chunkZ() {
            return stage.chunkZ();
        }

        public GenerationActivation activation() {
            return stage.activation();
        }

        public GenerationEpoch epoch() {
            return stage.epoch();
        }

        public TransitionGenerationPlan transitionPlan() {
            return binding.transitionPlan();
        }

        public boolean claimGeneratedSemantics() throws IOException {
            if (router.scopedRoute.get() != this) {
                throw new IllegalStateException(
                        "Generation semantics must be captured inside this route's runtime scope."
                );
            }
            ChunkGenerationSemantics semantics = GenerationSemanticCapture.capture(
                    router.engine,
                    stage
            );
            return router.history.claimGeneratedSemantics(stage, semantics);
        }

        public RuntimeScope openRuntimeScope() {
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("Generation-history runtime route is closed.");
                }
                activeScopes++;
            }
            router.enterRuntimeScope();
            RuntimeRoute previousRoute = router.installScopedRoute(this);
            try {
                IrisEngine.GenerationRuntimeScope opened = router.engine.openGenerationRuntimeScope(binding);
                return new RuntimeScope(this, opened, Thread.currentThread(), previousRoute);
            } catch (Throwable failure) {
                try {
                    router.restoreScopedRoute(this, previousRoute);
                } catch (Throwable closeFailure) {
                    appendFailure(failure, closeFailure);
                }
                try {
                    router.leaveRuntimeScope();
                } catch (Throwable closeFailure) {
                    appendFailure(failure, closeFailure);
                }
                try {
                    releaseRuntimeScope();
                } catch (Throwable closeFailure) {
                    appendFailure(failure, closeFailure);
                }
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            if (activeScopes > 0) {
                throw new IllegalStateException("Generation-history runtime route still has active scopes.");
            }
            closed = true;
            Throwable failure = null;
            try {
                stage.close();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                lease.close();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                router.leaveRouteOperation();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new IllegalStateException("Failed to close generation-history runtime route.", failure);
            }
        }

        private synchronized void releaseRuntimeScope() {
            if (activeScopes <= 0) {
                throw new IllegalStateException("Generation-history runtime route has no active scope.");
            }
            activeScopes--;
        }

        public static final class RuntimeScope implements AutoCloseable {
            private final RuntimeRoute route;
            private final IrisEngine.GenerationRuntimeScope runtimeScope;
            private final Thread owner;
            private final RuntimeRoute previousRoute;
            private boolean closed;

            private RuntimeScope(
                    RuntimeRoute route,
                    IrisEngine.GenerationRuntimeScope runtimeScope,
                    Thread owner,
                    RuntimeRoute previousRoute
            ) {
                this.route = route;
                this.runtimeScope = runtimeScope;
                this.owner = owner;
                this.previousRoute = previousRoute;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                if (Thread.currentThread() != owner) {
                    throw new IllegalStateException("Generation-history runtime scope closed from a different thread.");
                }
                closed = true;
                Throwable failure = null;
                try {
                    runtimeScope.close();
                } catch (Throwable closeFailure) {
                    failure = closeFailure;
                }
                try {
                    route.router.restoreScopedRoute(route, previousRoute);
                } catch (Throwable closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else if (failure != closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                try {
                    route.router.leaveRuntimeScope();
                } catch (Throwable closeFailure) {
                    failure = appendFailure(failure, closeFailure);
                }
                try {
                    route.releaseRuntimeScope();
                } catch (Throwable closeFailure) {
                    failure = appendFailure(failure, closeFailure);
                }
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                if (failure != null) {
                    throw new IllegalStateException("Failed to close generation-history runtime scope.", failure);
                }
            }
        }
    }

    public record RuntimeOwnership(
            long activationId,
            IrisEngine.GenerationRuntimeBinding binding
    ) {
        public RuntimeOwnership {
            if (activationId <= 0L) {
                throw new IllegalArgumentException("Generation activation ID must be positive.");
            }
            Objects.requireNonNull(binding, "generation runtime binding");
        }
    }

    public static final class RuntimeStage implements AutoCloseable {
        private final RuntimeRoute route;
        private final RuntimeRoute.RuntimeScope runtimeScope;
        private final Thread owner;
        private boolean closed;

        private RuntimeStage(
                RuntimeRoute route,
                RuntimeRoute.RuntimeScope runtimeScope,
                Thread owner
        ) {
            this.route = route;
            this.runtimeScope = runtimeScope;
            this.owner = owner;
        }

        public GenerationHistory.GenerationStage generationStage() {
            return route.generationStage();
        }

        public GenerationActivation activation() {
            return route.activation();
        }

        public GenerationEpoch epoch() {
            return route.epoch();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Generation-history stage closed from a different thread.");
            }
            Throwable failure = null;
            try {
                runtimeScope.close();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                route.close();
            } catch (Throwable closeFailure) {
                failure = appendFailure(failure, closeFailure);
            } finally {
                closed = true;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new IllegalStateException("Failed to close generation-history stage.", failure);
            }
        }
    }

    public static final class CoordinateScope implements AutoCloseable {
        private final int blockX;
        private final int blockZ;
        private final RuntimeRoute route;
        private final RuntimeStage stage;

        private CoordinateScope(int blockX, int blockZ, RuntimeRoute route, RuntimeStage stage) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.route = route;
            this.stage = stage;
        }

        public int blockX() {
            return blockX;
        }

        public int blockZ() {
            return blockZ;
        }

        public int chunkX() {
            return route == null
                    ? Math.floorDiv(blockX, GenerationBoundary.CHUNK_SIZE)
                    : route.chunkX();
        }

        public int chunkZ() {
            return route == null
                    ? Math.floorDiv(blockZ, GenerationBoundary.CHUNK_SIZE)
                    : route.chunkZ();
        }

        public GenerationActivation activation() {
            if (route == null) {
                throw new IllegalStateException("Borrowed runtime coordinate scope has no history route metadata.");
            }
            return route.activation();
        }

        public GenerationEpoch epoch() {
            if (route == null) {
                throw new IllegalStateException("Borrowed runtime coordinate scope has no history route metadata.");
            }
            return route.epoch();
        }

        public boolean claimGeneratedSemantics() throws IOException {
            return stage != null && route.claimGeneratedSemantics();
        }

        @Override
        public void close() {
            if (stage != null) {
                stage.close();
            }
        }
    }
}
