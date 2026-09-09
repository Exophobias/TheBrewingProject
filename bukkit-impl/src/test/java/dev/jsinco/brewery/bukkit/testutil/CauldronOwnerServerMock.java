package dev.jsinco.brewery.bukkit.testutil;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Runs only explicitly drained owner publications; no world ticks or native physics are simulated. */
public final class CauldronOwnerServerMock extends TBPServerMock {
    private final ConcurrentLinkedQueue<Runnable> global = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Runnable> region = new ConcurrentLinkedQueue<>();
    public RuntimeException regionRejection;

    @Override public GlobalRegionScheduler getGlobalRegionScheduler() {
        return (GlobalRegionScheduler) Proxy.newProxyInstance(GlobalRegionScheduler.class.getClassLoader(),
                new Class<?>[]{GlobalRegionScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("execute")) global.add((Runnable) args[1]);
                    return null;
                });
    }
    @Override public RegionScheduler getRegionScheduler() {
        return (RegionScheduler) Proxy.newProxyInstance(RegionScheduler.class.getClassLoader(),
                new Class<?>[]{RegionScheduler.class}, (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        if (regionRejection != null) throw regionRejection;
                        @SuppressWarnings("unchecked") Consumer<ScheduledTask> task = (Consumer<ScheduledTask>) args[args.length - 1];
                        region.add(() -> task.accept(null));
                    }
                    return null;
                });
    }
    public void publishGlobal() { Runnable next; while ((next = global.poll()) != null) next.run(); }
    public void publishRegion() { Runnable next; while ((next = region.poll()) != null) next.run(); }
}
