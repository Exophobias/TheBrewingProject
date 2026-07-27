package dev.jsinco.brewery.bukkit.api.event.process;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

/**
 * An event that triggers whenever a brew distills. A distillery only processes while its inventory is open, or while a
 * hopper is pulling from one of its containers, meaning this event can trigger without a player being involved.
 * Several of these events can be called within the same tick, one for each brew being distilled.
 */
public class BrewDistillEvent extends BrewProcessEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final DistilleryAccess distillery;

    public BrewDistillEvent(DistilleryAccess distillery, Brew source, Brew result) {
        super(source, result);
        this.distillery = distillery;
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public DistilleryAccess getDistillery() {
        return this.distillery;
    }
}
