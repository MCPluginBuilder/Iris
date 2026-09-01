package art.arcane.iris.engine.hydrology.policy;

import art.arcane.iris.engine.object.IrisRiverPlacementMode;
import art.arcane.iris.engine.object.IrisRiverRoutingMode;

import java.util.List;
import java.util.Objects;

public record EffectiveRiverPolicy(
        IrisRiverPlacementMode placement,
        IrisRiverRoutingMode routing,
        boolean outletAdmission,
        List<String> profiles,
        List<String> surfaceBiomes,
        List<String> mouthBiomes,
        List<String> shoreBiomes,
        List<String> bankBiomes,
        List<String> floodedCaveBiomes,
        double widthMultiplier,
        double depthMultiplier,
        double incisionMultiplier,
        double routingMultiplier,
        double bankMultiplier
) {
    public EffectiveRiverPolicy {
        placement = Objects.requireNonNull(placement);
        routing = Objects.requireNonNull(routing);
        profiles = List.copyOf(profiles);
        surfaceBiomes = List.copyOf(surfaceBiomes);
        mouthBiomes = List.copyOf(mouthBiomes);
        shoreBiomes = List.copyOf(shoreBiomes);
        bankBiomes = List.copyOf(bankBiomes);
        floodedCaveBiomes = List.copyOf(floodedCaveBiomes);
    }

    public boolean allowsSources() {
        return switch (placement) {
            case DISABLED, TRANSIT_ONLY -> false;
            case NATURAL, PREFERRED_HEADWATER, REQUIRED_HEADWATER -> true;
        };
    }

    public boolean allowsTransit() {
        return placement != IrisRiverPlacementMode.DISABLED;
    }

    public boolean prefersHeadwaters() {
        return placement == IrisRiverPlacementMode.PREFERRED_HEADWATER
                || placement == IrisRiverPlacementMode.REQUIRED_HEADWATER;
    }

    public boolean requiresHeadwaters() {
        return placement == IrisRiverPlacementMode.REQUIRED_HEADWATER;
    }

    public boolean allowsRouting() {
        return routing != IrisRiverRoutingMode.BLOCK;
    }
}
