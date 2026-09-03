package art.arcane.iris.engine.hydrology.policy;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisDimension;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverPlacementMode;
import art.arcane.iris.engine.object.IrisRiverPolicy;
import art.arcane.iris.engine.object.IrisRiverRoutingMode;
import art.arcane.volmlib.util.collection.KList;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RiverPolicyResolverTest {
    @Test
    public void suppliesCanonicalDefaultsWithoutConfiguredPolicies() {
        EffectiveRiverPolicy policy = RiverPolicyResolver.resolve(
                (IrisRiverPolicy) null,
                null,
                null
        );

        assertEquals(IrisRiverPlacementMode.NATURAL, policy.placement());
        assertEquals(IrisRiverRoutingMode.ALLOW, policy.routing());
        assertTrue(policy.outletAdmission());
        assertTrue(policy.profiles().isEmpty());
        assertTrue(policy.bankBiomes().isEmpty());
        assertEquals(1D, policy.widthMultiplier(), 0D);
        assertEquals(1D, policy.depthMultiplier(), 0D);
        assertEquals(1D, policy.incisionMultiplier(), 0D);
        assertEquals(1D, policy.routingMultiplier(), 0D);
        assertEquals(1D, policy.bankMultiplier(), 0D);
        assertTrue(policy.allowsSources());
        assertTrue(policy.allowsTransit());
        assertTrue(policy.allowsRouting());
        assertFalse(policy.prefersHeadwaters());
        assertFalse(policy.requiresHeadwaters());
    }

    @Test
    public void overlaysDimensionThenRegionThenBiomeIncludingExplicitEmptyLists() {
        IrisRiverPolicy dimension = new IrisRiverPolicy()
                .setPlacement(IrisRiverPlacementMode.NATURAL)
                .setRouting(IrisRiverRoutingMode.AVOID)
                .setOutletAdmission(false)
                .setProfiles(new KList<>("water"))
                .setSurfaceBiomes(new KList<>("river/dimension"))
                .setMouthBiomes(new KList<>("river/mouth"))
                .setBankBiomes(new KList<>("river/bank-dimension"))
                .setWidthMultiplier(1.5D)
                .setDepthMultiplier(1.25D)
                .setBankMultiplier(2D);
        IrisRiverPolicy region = new IrisRiverPolicy()
                .setRouting(IrisRiverRoutingMode.PREFER)
                .setProfiles(new KList<>())
                .setSurfaceBiomes(new KList<>("river/region"))
                .setShoreBiomes(new KList<>("river/shore"))
                .setBankBiomes(new KList<>())
                .setWidthMultiplier(0.8D)
                .setRoutingMultiplier(2D);
        IrisRiverPolicy biome = new IrisRiverPolicy()
                .setPlacement(IrisRiverPlacementMode.REQUIRED_HEADWATER)
                .setRouting(IrisRiverRoutingMode.BLOCK)
                .setOutletAdmission(true)
                .setSurfaceBiomes(new KList<>())
                .setFloodedCaveBiomes(new KList<>("river/flooded"))
                .setDepthMultiplier(0.75D)
                .setIncisionMultiplier(0.5D)
                .setBankMultiplier(0.5D);

        EffectiveRiverPolicy policy = RiverPolicyResolver.resolve(dimension, region, biome);

        assertEquals(IrisRiverPlacementMode.REQUIRED_HEADWATER, policy.placement());
        assertEquals(IrisRiverRoutingMode.BLOCK, policy.routing());
        assertTrue(policy.outletAdmission());
        assertTrue(policy.profiles().isEmpty());
        assertTrue(policy.surfaceBiomes().isEmpty());
        assertTrue(policy.bankBiomes().isEmpty());
        assertEquals(List.of("river/mouth"), policy.mouthBiomes());
        assertEquals(List.of("river/shore"), policy.shoreBiomes());
        assertEquals(List.of("river/flooded"), policy.floodedCaveBiomes());
        assertEquals(0.8D, policy.widthMultiplier(), 0D);
        assertEquals(0.75D, policy.depthMultiplier(), 0D);
        assertEquals(0.5D, policy.incisionMultiplier(), 0D);
        assertEquals(2D, policy.routingMultiplier(), 0D);
        assertEquals(0.5D, policy.bankMultiplier(), 0D);
        assertTrue(policy.allowsSources());
        assertTrue(policy.allowsTransit());
        assertFalse(policy.allowsRouting());
        assertTrue(policy.prefersHeadwaters());
        assertTrue(policy.requiresHeadwaters());
    }

    @Test
    public void bankBiomesInheritUntilOverridden() {
        IrisRiverPolicy dimension = new IrisRiverPolicy().setBankBiomes(new KList<>("river/bank"));
        IrisRiverPolicy region = new IrisRiverPolicy().setRouting(IrisRiverRoutingMode.PREFER);

        EffectiveRiverPolicy policy = RiverPolicyResolver.resolve(dimension, region, null);

        assertEquals(List.of("river/bank"), policy.bankBiomes());
        assertEquals(1D, policy.bankMultiplier(), 0D);
    }

    @Test
    public void resolvesOwnerPoliciesAndSnapshotsMutableLists() {
        KList<String> profiles = new KList<>("water");
        IrisDimension dimension = new IrisDimension().setRiverPolicy(new IrisRiverPolicy()
                .setProfiles(profiles)
                .setPlacement(IrisRiverPlacementMode.PREFERRED_HEADWATER));
        IrisRegion region = new IrisRegion().setRiverPolicy(new IrisRiverPolicy()
                .setRouting(IrisRiverRoutingMode.PREFER));
        IrisBiome biome = new IrisBiome().setRiverPolicy(new IrisRiverPolicy()
                .setMouthBiomes(new KList<>("river/mouth")));

        EffectiveRiverPolicy policy = RiverPolicyResolver.resolve(dimension, region, biome);
        profiles.add("lava");

        assertEquals(List.of("water"), policy.profiles());
        assertEquals(List.of("river/mouth"), policy.mouthBiomes());
        assertEquals(IrisRiverPlacementMode.PREFERRED_HEADWATER, policy.placement());
        assertEquals(IrisRiverRoutingMode.PREFER, policy.routing());
        assertThrows(UnsupportedOperationException.class, () -> policy.profiles().add("other"));
    }

    @Test
    public void placementModesExposeDistinctAdmissionSemantics() {
        EffectiveRiverPolicy disabled = resolvePlacement(IrisRiverPlacementMode.DISABLED);
        EffectiveRiverPolicy transitOnly = resolvePlacement(IrisRiverPlacementMode.TRANSIT_ONLY);
        EffectiveRiverPolicy natural = resolvePlacement(IrisRiverPlacementMode.NATURAL);
        EffectiveRiverPolicy preferred = resolvePlacement(IrisRiverPlacementMode.PREFERRED_HEADWATER);
        EffectiveRiverPolicy required = resolvePlacement(IrisRiverPlacementMode.REQUIRED_HEADWATER);

        assertFalse(disabled.allowsSources());
        assertFalse(disabled.allowsTransit());
        assertFalse(transitOnly.allowsSources());
        assertTrue(transitOnly.allowsTransit());
        assertTrue(natural.allowsSources());
        assertFalse(natural.prefersHeadwaters());
        assertTrue(preferred.prefersHeadwaters());
        assertFalse(preferred.requiresHeadwaters());
        assertTrue(required.requiresHeadwaters());
    }

    private EffectiveRiverPolicy resolvePlacement(IrisRiverPlacementMode placement) {
        return RiverPolicyResolver.resolve(new IrisRiverPolicy().setPlacement(placement), null, null);
    }
    @Test
    public void shoreBiomeWidthInheritsFromDimensionThroughRegionToBiome() {
        IrisRiverPolicy region = new IrisRiverPolicy().setShoreBiomeWidth(6D);
        IrisRiverPolicy biome = new IrisRiverPolicy().setShoreBiomeWidth(0D);

        assertNull(RiverPolicyResolver.resolve((IrisRiverPolicy) null, null, null).shoreBiomeWidth());
        assertEquals(6D, RiverPolicyResolver.resolve(null, region, null).shoreBiomeWidth(), 0D);
        assertEquals(6D, RiverPolicyResolver.resolve(null, region, new IrisRiverPolicy()).shoreBiomeWidth(), 0D);
        assertEquals(0D, RiverPolicyResolver.resolve(null, region, biome).shoreBiomeWidth(), 0D);
    }

    @Test
    public void confinementScopeIsTheLevelThatConfinedTheRiver() {
        IrisRiverPolicy confined = new IrisRiverPolicy().setConfined(true);
        IrisRiverPolicy released = new IrisRiverPolicy().setConfined(false);

        assertEquals(RiverConfinement.NONE, RiverPolicyResolver.resolve((IrisRiverPolicy) null, null, null).confinement());
        assertEquals(RiverConfinement.REGION, RiverPolicyResolver.resolve(confined, null, null).confinement());
        assertEquals(RiverConfinement.REGION, RiverPolicyResolver.resolve(null, confined, null).confinement());
        assertEquals(RiverConfinement.REGION, RiverPolicyResolver.resolve(null, confined, new IrisRiverPolicy()).confinement());
        assertEquals(RiverConfinement.BIOME, RiverPolicyResolver.resolve(null, null, confined).confinement());
        assertEquals(RiverConfinement.BIOME, RiverPolicyResolver.resolve(null, confined, confined).confinement());
        assertEquals(RiverConfinement.NONE, RiverPolicyResolver.resolve(null, confined, released).confinement());
        assertEquals(RiverConfinement.NONE, RiverPolicyResolver.resolve(confined, released, null).confinement());
    }


    @Test
    public void shoreWidthInheritsFromDimensionThroughRegionToBiome() {
        IrisRiverPolicy dimension = new IrisRiverPolicy().setShoreWidth(2D);
        IrisRiverPolicy biome = new IrisRiverPolicy().setShoreWidth(0D);

        assertNull(RiverPolicyResolver.resolve((IrisRiverPolicy) null, null, null).shoreWidth());
        assertNull(RiverPolicyResolver.resolve(new IrisRiverPolicy(), new IrisRiverPolicy(), new IrisRiverPolicy()).shoreWidth());
        assertEquals(2D, RiverPolicyResolver.resolve(dimension, null, null).shoreWidth(), 0D);
        assertEquals(2D, RiverPolicyResolver.resolve(dimension, new IrisRiverPolicy(), null).shoreWidth(), 0D);
        assertEquals(2D, RiverPolicyResolver.resolve(dimension, new IrisRiverPolicy(), new IrisRiverPolicy()).shoreWidth(), 0D);
        assertEquals(0D, RiverPolicyResolver.resolve(dimension, new IrisRiverPolicy(), biome).shoreWidth(), 0D);
        assertEquals(0D, RiverPolicyResolver.resolve(dimension, biome, null).shoreWidth(), 0D);
    }

    @Test
    public void erosionInheritsUntilAnAreaDecidesIt() {
        IrisRiverPolicy eroding = new IrisRiverPolicy().setErosion(true);
        IrisRiverPolicy bare = new IrisRiverPolicy().setErosion(false);

        assertNull(RiverPolicyResolver.resolve((IrisRiverPolicy) null, null, null).erosion());
        assertNull(RiverPolicyResolver.resolve(new IrisRiverPolicy(), new IrisRiverPolicy(), new IrisRiverPolicy()).erosion());
        assertEquals(Boolean.TRUE, RiverPolicyResolver.resolve(eroding, null, null).erosion());
        assertEquals(Boolean.TRUE, RiverPolicyResolver.resolve(eroding, new IrisRiverPolicy(), new IrisRiverPolicy()).erosion());
        assertEquals(Boolean.FALSE, RiverPolicyResolver.resolve(eroding, new IrisRiverPolicy(), bare).erosion());
        assertEquals(Boolean.FALSE, RiverPolicyResolver.resolve(null, bare, new IrisRiverPolicy()).erosion());
        assertEquals(Boolean.TRUE, RiverPolicyResolver.resolve(null, bare, eroding).erosion());
    }

    @Test
    public void effectivePolicyRejectsANegativeOrInfiniteShoreWidth() {
        EffectiveRiverPolicy base = RiverPolicyResolver.resolve((IrisRiverPolicy) null, null, null);

        assertEquals(0D, withShoreWidth(base, 0D).shoreWidth(), 0D);
        assertNull(withShoreWidth(base, null).shoreWidth());
        assertThrows(IllegalArgumentException.class, () -> withShoreWidth(base, -1D));
        assertThrows(IllegalArgumentException.class, () -> withShoreWidth(base, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> withShoreWidth(base, Double.NaN));
    }

    private static EffectiveRiverPolicy withShoreWidth(EffectiveRiverPolicy policy, Double shoreWidth) {
        return new EffectiveRiverPolicy(
                policy.placement(),
                policy.routing(),
                policy.outletAdmission(),
                policy.profiles(),
                policy.surfaceBiomes(),
                policy.mouthBiomes(),
                policy.shoreBiomes(),
                policy.bankBiomes(),
                policy.floodedCaveBiomes(),
                policy.surfacePools(),
                policy.widthMultiplier(),
                policy.depthMultiplier(),
                policy.incisionMultiplier(),
                policy.routingMultiplier(),
                policy.bankMultiplier(),
                policy.shoreBiomeWidth(),
                policy.confinement(),
                shoreWidth,
                policy.erosion()
        );
    }
}
