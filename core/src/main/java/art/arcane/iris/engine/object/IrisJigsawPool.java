/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2022 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.engine.object;

import art.arcane.iris.core.compat.CompatAction;
import art.arcane.iris.core.compat.CompatFinding;
import art.arcane.iris.core.compat.CompatRegistry;
import art.arcane.iris.core.compat.CompatStatus;
import art.arcane.iris.core.compat.ContentGate;
import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.core.loader.IrisRegistrant;
import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.volmlib.util.collection.KList;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
@Desc("A jigsaw pool. A pool is a weighted set of pieces the assembler chooses from when a connector targets this pool.")
@Data
@EqualsAndHashCode(callSuper = false)
public class IrisJigsawPool extends IrisRegistrant {
    @ArrayType(type = IrisJigsawPieceEntry.class, min = 1)
    @Desc("The weighted pieces in this pool.")
    private KList<IrisJigsawPieceEntry> pieces = new KList<>();

    @RegistryListResource(IrisJigsawPool.class)
    @Desc("The direct fallback pool tried after this pool, or used alone at maximum depth. The fallback pool's own fallback is not part of the same selection. Leave empty to stop expanding at maximum depth.")
    private String fallback = "";

    @Desc("Whether failure to place from this pool must be resolved through its direct fallback even when the structure does not require every branch to be capped.")
    private boolean mandatoryFallback = false;

    public boolean requiresFallback(boolean structureRequiresCaps) {
        return structureRequiresCaps || mandatoryFallback;
    }

    /**
     * Excluded pieces drop out of the pool; a pool with nothing left to place is excluded in turn so every connector
     * that targets it terminates instead of failing assembly.
     */
    @Override
    public CompatStatus evaluateCompat(ContentGate gate) {
        CompatStatus base = super.evaluateCompat(gate);

        if (base.excluded() || gate == null || !gate.ready()) {
            return base;
        }

        IrisData data = getLoader();

        if (data == null || pieces == null || pieces.isEmpty()) {
            return base;
        }

        KList<CompatFinding> reasons = new KList<>(base.reasons());
        CompatFinding cause = null;
        int usable = 0;

        for (int i = 0; i < pieces.size(); i++) {
            IrisJigsawPieceEntry entry = pieces.get(i);

            if (entry == null) {
                continue;
            }

            String key = entry.getPiece() == null ? "" : entry.getPiece().trim();

            // An empty membership terminates a branch on purpose, and a piece key that does not resolve at all is a
            // pack error the loader already reports - neither is a version-content problem.
            if (entry.isEmpty() || key.isEmpty()) {
                usable++;
                continue;
            }

            IrisJigsawPiece piece = data.load(IrisJigsawPiece.class, key, false);

            if (piece == null || !piece.isCompatExcluded()) {
                usable++;
                continue;
            }

            CompatFinding reason = piece.getCompat().reasons().isEmpty() ? null : piece.getCompat().reasons().getFirst();
            CompatFinding dropped = new CompatFinding(
                    reason == null ? CompatRegistry.BLOCK : reason.registry(),
                    reason == null ? key : reason.key(),
                    CompatAction.DROPPED, "jigsaw pool", getLoadKey(), "pieces[" + i + "] " + key);
            gate.report().record(dropped);
            reasons.add(dropped);

            if (cause == null) {
                cause = dropped;
            }
        }

        if (usable > 0 || cause == null) {
            return new CompatStatus(false, reasons);
        }

        CompatFinding excluded = new CompatFinding(cause.registry(), cause.key(), CompatAction.EXCLUDED,
                "jigsaw pool", getLoadKey(), "no pieces remain");
        gate.report().record(excluded);
        reasons.add(excluded);
        return CompatStatus.excludedBy(reasons);
    }

    @Override
    public String getFolderName() {
        return "jigsaw-pools";
    }

    @Override
    public String getTypeName() {
        return "Jigsaw Pool";
    }
}
