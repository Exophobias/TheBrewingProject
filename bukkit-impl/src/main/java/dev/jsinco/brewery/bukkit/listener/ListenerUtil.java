package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.structure.SinglePositionStructure;
import dev.jsinco.brewery.api.util.Logger;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.database.PersistenceException;
import org.jspecify.annotations.NonNull;

public class ListenerUtil {

    /** Exact native teardown, returning the original ordered SQL acknowledgment rather than a boolean. */
    public static java.util.concurrent.CompletableFuture<Void> retireCauldron(
            TheBrewingProject provider, BukkitCauldron cauldron) throws PersistenceException {
        if (!org.bukkit.Bukkit.isPrimaryThread() || provider != TheBrewingProject.getInstance() || !provider.isEnabled()
                || org.bukkit.Bukkit.getServicesManager().load(dev.jsinco.brewery.bukkit.api.TheBrewingProjectApi.class) != provider)
            throw new IllegalStateException("Cauldron provider changed");
        var registry = provider.getBreweryRegistry();
        var database = provider.getDatabase();
        cauldron.persistenceOwner(provider.getCauldronPersistenceOrder());
        if (!isCurrent(cauldron) || !mayRemove(cauldron, null))
            throw new IllegalStateException("Cauldron holder is stale or unavailable");
        cauldron.destroy();
        // Display teardown invokes external callbacks. Revalidate every owner before any SQL mutation.
        if (provider != TheBrewingProject.getInstance() || !provider.isEnabled()
                || provider.getDatabase() != database
                || org.bukkit.Bukkit.getServicesManager().load(dev.jsinco.brewery.bukkit.api.TheBrewingProjectApi.class) != provider
                || !isCurrent(cauldron) || !mayRemove(cauldron, null))
            throw new IllegalStateException("Cauldron ownership changed during teardown");
        var original = database.startSession(SessionTypes.CAULDRON_SESSION_TYPE).removeCauldron(cauldron);
        if (!original.isCompletedExceptionally()) registry.removeActiveSinglePositionStructure(cauldron);
        return original;
    }

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
