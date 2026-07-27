package dev.jsinco.brewery.api.structure;

import dev.jsinco.brewery.api.breweries.StructureHolder;
import dev.jsinco.brewery.api.vector.BreweryLocation;

import java.util.List;
import java.util.Optional;

public interface MultiblockStructure<H extends StructureHolder<H>> {

    /**
     * @return The block positions of this structure
     */
    List<BreweryLocation> positions();

    /**
     * @return A behavior holder
     */
    H getHolder();

    /**
     * @param holder A behavior holder
     */
    void setHolder(H holder);

    /**
     * @return A unique position to identify this structure
     */
    BreweryLocation getUnique();

    /**
     * @return The name of the structure definition this was placed from, if it has one
     */
    default Optional<String> getDefinitionName() {
        return Optional.empty();
    }
}
