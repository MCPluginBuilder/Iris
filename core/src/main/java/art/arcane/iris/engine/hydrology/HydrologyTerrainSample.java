package art.arcane.iris.engine.hydrology;

import java.util.List;

public record HydrologyTerrainSample(
        int naturalHeight,
        double slope,
        boolean ocean,
        boolean caveAvailable,
        int caveFloorY,
        int caveFluidY,
        boolean transitAllowed,
        boolean outletAllowed,
        boolean surfaceSourceAllowed,
        boolean surfaceSourceRequired,
        boolean undergroundSourceAllowed,
        boolean undergroundSourceRequired,
        double routingCost,
        double surfaceSourceWeight,
        double undergroundSourceWeight,
        double widthMultiplier,
        double depthMultiplier,
        double incisionMultiplier,
        double routingMultiplier,
        double bankMultiplier,
        String parentBiomeKey,
        String surfaceBiomeKey,
        String mouthBiomeKey,
        String shoreBiomeKey,
        String bankBiomeKey,
        String floodedCaveBiomeKey,
        List<String> preferredProfileKeys
) {
    public HydrologyTerrainSample {
        requireFiniteNonNegative(slope, "slope");
        requireFiniteNonNegative(routingCost, "routingCost");
        requireFiniteNonNegative(surfaceSourceWeight, "surfaceSourceWeight");
        requireFiniteNonNegative(undergroundSourceWeight, "undergroundSourceWeight");
        requireFinitePositive(widthMultiplier, "widthMultiplier");
        requireFinitePositive(depthMultiplier, "depthMultiplier");
        requireFiniteNonNegative(incisionMultiplier, "incisionMultiplier");
        requireFiniteNonNegative(routingMultiplier, "routingMultiplier");
        requireFinitePositive(bankMultiplier, "bankMultiplier");
        if (caveAvailable && (caveFluidY <= caveFloorY || caveFluidY >= naturalHeight)) {
            throw new IllegalArgumentException("Available cave fluid must be above its floor and below natural terrain.");
        }
        parentBiomeKey = normalizeKey(parentBiomeKey, "parent");
        surfaceBiomeKey = normalizeKey(surfaceBiomeKey, parentBiomeKey);
        mouthBiomeKey = normalizeKey(mouthBiomeKey, surfaceBiomeKey);
        shoreBiomeKey = normalizeKey(shoreBiomeKey, parentBiomeKey);
        bankBiomeKey = normalizeKey(bankBiomeKey, parentBiomeKey);
        floodedCaveBiomeKey = normalizeKey(floodedCaveBiomeKey, surfaceBiomeKey);
        preferredProfileKeys = normalizeProfiles(preferredProfileKeys);
    }

    public static HydrologyTerrainSample openLand(int naturalHeight, double slope, String parentBiomeKey) {
        return new HydrologyTerrainSample(
                naturalHeight,
                slope,
                false,
                false,
                naturalHeight - 32,
                naturalHeight - 30,
                true,
                true,
                true,
                false,
                false,
                false,
                0D,
                1D,
                1D,
                1D,
                1D,
                1D,
                1D,
                1D,
                parentBiomeKey,
                parentBiomeKey,
                parentBiomeKey,
                parentBiomeKey,
                parentBiomeKey,
                parentBiomeKey,
                List.of("default")
        );
    }

    public static HydrologyTerrainSample ocean(int naturalHeight, String parentBiomeKey) {
        return new HydrologyTerrainSample(
                naturalHeight,
                0D,
                true,
                false,
                naturalHeight - 32,
                naturalHeight - 30,
                false,
                false,
                false,
                false,
                false,
                false,
                0D,
                0D,
                0D,
                1D,
                1D,
                1D,
                1D,
                1D,
                parentBiomeKey,
                parentBiomeKey,
                parentBiomeKey,
                parentBiomeKey,
                parentBiomeKey,
                parentBiomeKey,
                List.of("default")
        );
    }

    public HydrologyTerrainSample withSlope(double replacementSlope) {
        return new HydrologyTerrainSample(
                naturalHeight,
                replacementSlope,
                ocean,
                caveAvailable,
                caveFloorY,
                caveFluidY,
                transitAllowed,
                outletAllowed,
                surfaceSourceAllowed,
                surfaceSourceRequired,
                undergroundSourceAllowed,
                undergroundSourceRequired,
                routingCost,
                surfaceSourceWeight,
                undergroundSourceWeight,
                widthMultiplier,
                depthMultiplier,
                incisionMultiplier,
                routingMultiplier,
                bankMultiplier,
                parentBiomeKey,
                surfaceBiomeKey,
                mouthBiomeKey,
                shoreBiomeKey,
                bankBiomeKey,
                floodedCaveBiomeKey,
                preferredProfileKeys
        );
    }

    private static List<String> normalizeProfiles(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of("default");
        }
        if (profilesNormalized(values)) {
            return List.copyOf(values);
        }
        List<String> normalized = values.stream()
                .filter((String value) -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        return normalized.isEmpty() ? List.of("default") : normalized;
    }

    private static boolean profilesNormalized(List<String> values) {
        String previous = null;
        for (String value : values) {
            if (value == null
                    || value.isBlank()
                    || !value.equals(value.trim())
                    || previous != null && previous.compareTo(value) >= 0) {
                return false;
            }
            previous = value;
        }
        return true;
    }

    private static String normalizeKey(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0D) {
            throw new IllegalArgumentException(name + " must be finite and non-negative.");
        }
    }

    private static void requireFinitePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0D) {
            throw new IllegalArgumentException(name + " must be finite and positive.");
        }
    }
}
