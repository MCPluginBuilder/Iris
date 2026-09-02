package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("Controls whether river routes may pass through a dimension, region or biome, and at what cost.")
public enum IrisRiverRoutingMode {
    @Desc("Rivers never route through here.")
    BLOCK,
    @Desc("Rivers route through here only when no cheaper path exists; transit carries a strong cost.")
    AVOID,
    @Desc("Rivers route through here at the normal cost.")
    ALLOW,
    @Desc("Rivers route through here at a reduced cost, so routes are drawn toward this area.")
    PREFER
}
