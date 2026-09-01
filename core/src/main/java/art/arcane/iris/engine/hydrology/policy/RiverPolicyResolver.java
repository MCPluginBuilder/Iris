package art.arcane.iris.engine.hydrology.policy;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverPlacementMode;
import art.arcane.iris.engine.object.IrisRiverPolicy;
import art.arcane.iris.engine.object.IrisRiverRoutingMode;

import java.util.List;

public final class RiverPolicyResolver {
    private RiverPolicyResolver() {
    }

    public static EffectiveRiverPolicy resolve(IrisDimension dimension, IrisRegion region, IrisBiome biome) {
        return resolve(
                dimension == null ? null : dimension.getRiverPolicy(),
                region == null ? null : region.getRiverPolicy(),
                biome == null ? null : biome.getRiverPolicy()
        );
    }

    public static EffectiveRiverPolicy resolve(
            IrisRiverPolicy dimensionPolicy,
            IrisRiverPolicy regionPolicy,
            IrisRiverPolicy biomePolicy
    ) {
        State state = new State();
        state.apply(dimensionPolicy);
        state.apply(regionPolicy);
        state.apply(biomePolicy);
        return state.build();
    }

    private static final class State {
        private IrisRiverPlacementMode placement = IrisRiverPlacementMode.NATURAL;
        private IrisRiverRoutingMode routing = IrisRiverRoutingMode.ALLOW;
        private boolean outletAdmission = true;
        private List<String> profiles = List.of();
        private List<String> surfaceBiomes = List.of();
        private List<String> mouthBiomes = List.of();
        private List<String> shoreBiomes = List.of();
        private List<String> bankBiomes = List.of();
        private List<String> floodedCaveBiomes = List.of();
        private double widthMultiplier = 1D;
        private double depthMultiplier = 1D;
        private double incisionMultiplier = 1D;
        private double routingMultiplier = 1D;
        private double bankMultiplier = 1D;

        private void apply(IrisRiverPolicy policy) {
            if (policy == null) {
                return;
            }
            if (policy.getPlacement() != null) {
                placement = policy.getPlacement();
            }
            if (policy.getRouting() != null) {
                routing = policy.getRouting();
            }
            if (policy.getOutletAdmission() != null) {
                outletAdmission = policy.getOutletAdmission();
            }
            if (policy.getProfiles() != null) {
                profiles = List.copyOf(policy.getProfiles());
            }
            if (policy.getSurfaceBiomes() != null) {
                surfaceBiomes = List.copyOf(policy.getSurfaceBiomes());
            }
            if (policy.getMouthBiomes() != null) {
                mouthBiomes = List.copyOf(policy.getMouthBiomes());
            }
            if (policy.getShoreBiomes() != null) {
                shoreBiomes = List.copyOf(policy.getShoreBiomes());
            }
            if (policy.getBankBiomes() != null) {
                bankBiomes = List.copyOf(policy.getBankBiomes());
            }
            if (policy.getFloodedCaveBiomes() != null) {
                floodedCaveBiomes = List.copyOf(policy.getFloodedCaveBiomes());
            }
            if (policy.getWidthMultiplier() != null) {
                widthMultiplier = policy.getWidthMultiplier();
            }
            if (policy.getDepthMultiplier() != null) {
                depthMultiplier = policy.getDepthMultiplier();
            }
            if (policy.getIncisionMultiplier() != null) {
                incisionMultiplier = policy.getIncisionMultiplier();
            }
            if (policy.getRoutingMultiplier() != null) {
                routingMultiplier = policy.getRoutingMultiplier();
            }
            if (policy.getBankMultiplier() != null) {
                bankMultiplier = policy.getBankMultiplier();
            }
        }

        private EffectiveRiverPolicy build() {
            return new EffectiveRiverPolicy(
                    placement,
                    routing,
                    outletAdmission,
                    profiles,
                    surfaceBiomes,
                    mouthBiomes,
                    shoreBiomes,
                    bankBiomes,
                    floodedCaveBiomes,
                    widthMultiplier,
                    depthMultiplier,
                    incisionMultiplier,
                    routingMultiplier,
                    bankMultiplier
            );
        }
    }
}
