/*
 * Iris is a World Generator for Minecraft Bukkit Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
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

import art.arcane.iris.engine.object.annotations.ArrayType;
import art.arcane.iris.engine.object.annotations.Desc;
import art.arcane.iris.engine.object.annotations.MaxNumber;
import art.arcane.iris.engine.object.annotations.MinNumber;
import art.arcane.iris.engine.object.annotations.RegistryListResource;
import art.arcane.iris.engine.object.annotations.Required;
import art.arcane.volmlib.util.collection.KList;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@NoArgsConstructor
@Desc("Stacks dimension terrain upright in declared top-to-bottom order")
@Data
public class IrisDimensionStack {
    @Required
    @ArrayType(min = 2, type = String.class)
    @RegistryListResource(IrisDimension.class)
    @Desc("Dimension load keys ordered from the highest layer to the lowest layer. The final key must be this dimension.")
    private KList<String> dimensions = new KList<>();

    @MinNumber(0)
    @MaxNumber(256)
    @Desc("Nominal air gap in blocks between adjacent dimension layers")
    private int spacer = 32;

    @Desc("Optional noise that blends adjacent layer boundaries. Omit this object for a constant configured gap.")
    private IrisDimensionStackBlend blend = null;
}
