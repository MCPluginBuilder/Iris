package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Controls whether river sources may start inside a dimension, region or biome, and how strongly they are drawn there.")
public enum IrisRiverPlacementMode {
    @Desc("No river source may start here. Rivers may still pass through when routing allows it.")
    DISABLED,
    @Desc("Rivers may pass through, but no source starts here.")
    TRANSIT_ONLY,
    @Desc("Sources start here on their own merits, ranked like everywhere else.")
    NATURAL,
    @Desc("Eligible source sites here rank ahead of sites elsewhere in the tile.")
    PREFERRED_HEADWATER,
    @Desc("At least one source starts here whenever a site can reach a legal outlet; sources.minimumPerTile can raise that quota.")
    REQUIRED_HEADWATER
}
