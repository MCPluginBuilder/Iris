package art.arcane.iris.util.project.stream;

import java.util.function.Supplier;

final class ProceduralStreamCacheBypass {
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private ProceduralStreamCacheBypass() {
    }

    static boolean isActive() {
        return DEPTH.get() != null;
    }

    static <T> T supply(Supplier<T> operation) {
        Integer previousDepth = DEPTH.get();
        DEPTH.set(previousDepth == null ? 1 : previousDepth + 1);
        try {
            return operation.get();
        } finally {
            if (previousDepth == null) {
                DEPTH.remove();
            } else {
                DEPTH.set(previousDepth);
            }
        }
    }
}
