package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.collection.KSet;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Nullable river policy values inherited from dimension to region to biome.")
@Data
public class IrisRiverPolicy {
    @Desc("Controls source and transit admission in this area.")
    private IrisRiverPlacementMode placement = null;

    @Desc("Controls the routing preference or prohibition applied to this area.")
    private IrisRiverRoutingMode routing = null;

    @Desc("Whether this area may contain an accepted river outlet.")
    private Boolean outletAdmission = null;

    @ArrayType(type = String.class)
    @Desc("Preferred dimension-owned river profile identifiers. An empty list explicitly clears inherited profiles.")
    private KList<String> profiles = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Surface channel biome selection. An empty list explicitly clears the inherited selection.")
    private KList<String> surfaceBiomes = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("River mouth biome selection. An empty list explicitly clears the inherited selection.")
    private KList<String> mouthBiomes = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Narrow river shore biome selection. An empty list explicitly clears the inherited selection.")
    private KList<String> shoreBiomes = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Dry river footprint biome selection. An empty list explicitly clears the inherited selection.")
    private KList<String> dryBiomes = null;

    @RegistryListResource(IrisBiome.class)
    @ArrayType(type = String.class)
    @Desc("Flooded cave and grotto biome selection. An empty list explicitly clears the inherited selection.")
    private KList<String> floodedCaveBiomes = null;

    @MinNumber(0.0001)
    @MaxNumber(16)
    @Desc("Multiplier applied to accepted river width.")
    private Double widthMultiplier = null;

    @MinNumber(0.0001)
    @MaxNumber(16)
    @Desc("Multiplier applied to accepted river depth.")
    private Double depthMultiplier = null;

    @MinNumber(0)
    @MaxNumber(16)
    @Desc("Multiplier applied to maximum terrain incision.")
    private Double incisionMultiplier = null;

    @MinNumber(0)
    @MaxNumber(64)
    @Desc("Multiplier applied to terrain-guided routing cost.")
    private Double routingMultiplier = null;

    public KSet<String> getAllBiomeIds() {
        KSet<String> biomeIds = new KSet<>();
        addAll(biomeIds, surfaceBiomes);
        addAll(biomeIds, mouthBiomes);
        addAll(biomeIds, shoreBiomes);
        addAll(biomeIds, dryBiomes);
        addAll(biomeIds, floodedCaveBiomes);
        return biomeIds;
    }

    private static void addAll(KSet<String> destination, KList<String> source) {
        if (source != null) {
            destination.addAll(source);
        }
    }
}
