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
        var registry = TheBrewingProject.getInstance().getBreweryRegistry();
        if (!isCurrent(structure)) {
            return false;
        }
        structure.destroy();
        // Teardown can invoke another plugin's entity-removal callback. A replacement may now
        // own this coordinate; identity-aware registry removal alone cannot protect its SQL row.
        if (!isCurrent(structure)) {
            return false;
        }
        if (structure instanceof BukkitCauldron cauldron) {
            try {
                TheBrewingProject.getInstance().getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE)
                        .removeCauldron(cauldron)
                        .exceptionally(Logger::logAndTrackErr);
            } catch (PersistenceException e) {
                Logger.logErr(e);
            }
        }
        registry.removeActiveSinglePositionStructure(structure);
        return true;
    }

    public static boolean isCurrent(@NonNull SinglePositionStructure structure) {
        return TheBrewingProject.getInstance().getBreweryRegistry()
                .getActiveSinglePositionStructure(structure.position())
                .filter(current -> current == structure).isPresent();
    }
}
