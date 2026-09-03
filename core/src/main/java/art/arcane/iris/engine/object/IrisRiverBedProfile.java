package art.arcane.iris.engine.object;

import art.arcane.iris.engine.object.annotations.Desc;

@Desc("The cross-section of the wet channel bed from the centerline out to the waterline.")
public enum IrisRiverBedProfile {
    @Desc("A level thalweg over thalwegFraction of the half-width that eases up to a one-block edge; a broad bowl.")
    BOWL,
    @Desc("Full depth across the whole width, so the channel edge drops straight to the bed.")
    FLAT,
    @Desc("A straight slope from full depth at the centerline to one block at the edge; thalwegFraction is ignored.")
    V,
    @Desc("A level thalweg over thalwegFraction of the half-width that stays deep almost to the edge, then rises steeply; a trough with steep sides.")
    U
}
