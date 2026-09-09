package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.structure.SinglePositionStructure;
import dev.jsinco.brewery.api.util.Logger;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.database.PersistenceException;
import org.jspecify.annotations.NonNull;

public class ListenerUtil {

    public static void removeActiveSinglePositionStructure(@NonNull SinglePositionStructure structure) {
        removeIfCurrent(structure);
    }

    static boolean removeIfCurrent(@NonNull SinglePositionStructure structure) {
        return removeIfCurrent(structure, null);
    }

    static boolean removeIfCurrent(@NonNull SinglePositionStructure structure,
                                   BukkitCauldron.ExtractionReservation extraction) {
        var registry = TheBrewingProject.getInstance().getBreweryRegistry();
        if (!isCurrent(structure) || !mayRemove(structure, extraction)) {
            return false;
        }
        if (extraction != null && structure instanceof BukkitCauldron cauldron) cauldron.destroyForExtraction(extraction);
        else structure.destroy();
        // Teardown can invoke another plugin's entity-removal callback. A replacement may now
        // own this coordinate; identity-aware registry removal alone cannot protect its SQL row.
        if (!isCurrent(structure) || !mayRemove(structure, extraction)) {
            return false;
        }
        if (structure instanceof BukkitCauldron cauldron) {
            try {
                var deletion = TheBrewingProject.getInstance().getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE)
                        .removeCauldron(cauldron);
                if (deletion.isCompletedExceptionally()) { deletion.exceptionally(Logger::logAndTrackErr); return false; }
                deletion.exceptionally(Logger::logAndTrackErr);
            } catch (PersistenceException | RuntimeException e) {
                Logger.logErr(e);
                return false;
            }
        }
        registry.removeActiveSinglePositionStructure(structure);
        return true;
    }

    private static boolean mayRemove(SinglePositionStructure structure, BukkitCauldron.ExtractionReservation extraction) {
        if (!(structure instanceof BukkitCauldron cauldron)) return extraction == null;
        return cauldron.persistenceAvailable()
                && (extraction == null ? !cauldron.isExtractionPending() : cauldron.ownsExtraction(extraction));
    }

    public static boolean isCurrent(@NonNull SinglePositionStructure structure) {
        return TheBrewingProject.getInstance().getBreweryRegistry()
                .getActiveSinglePositionStructure(structure.position())
                .filter(current -> current == structure).isPresent();
    }
}
