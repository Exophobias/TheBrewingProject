package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.BrewPersistenceSnapshot;
import dev.jsinco.brewery.database.PersistenceSupplier;
import dev.jsinco.brewery.database.UncheckedPersistenceException;
import dev.jsinco.brewery.database.sql.SqlStatements;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Every session shares one database-lifetime owner order; no mutable holder reaches the SQL queue. */
public record SqLiteCauldronSession(Executor executor, PersistenceSupplier<Connection> connectionSupplier,
        CompletableFuture<ResolvedIngredientManager<ItemStack>> ingredientManagerFuture,
        CauldronPersistenceOrder order) implements CauldronSession {
    private static final SqlStatements STATEMENTS = new SqlStatements("/database/generic/cauldrons");

    @Override public CompletableFuture<Void> insertCauldron(BukkitCauldron cauldron) {
        return write(cauldron, CauldronPersistenceOrder.Write.INSERT);
    }
    @Override public CompletableFuture<Void> updateCauldron(BukkitCauldron cauldron) {
        return write(cauldron, CauldronPersistenceOrder.Write.UPDATE);
    }
    @Override public CompletableFuture<Void> removeCauldron(BukkitCauldron cauldron) {
        return write(cauldron, CauldronPersistenceOrder.Write.DELETE);
    }

    private CompletableFuture<Void> write(BukkitCauldron cauldron, CauldronPersistenceOrder.Write kind) {
        return order.admit(cauldron.persistenceOwner(order), kind, predecessor -> {
            BreweryLocation key = cauldron.position();
            String brew = kind == CauldronPersistenceOrder.Write.DELETE ? null
                    : BrewPersistenceSnapshot.captureNow(cauldron.getBrew());
            String type = kind == CauldronPersistenceOrder.Write.DELETE ? null
                    : cauldron.getCauldronType().key().minimalized();
            // Capture above is synchronous, before either dependency can publish SQL work.
            CompletableFuture<Void> ready = kind == CauldronPersistenceOrder.Write.DELETE ? predecessor
                    : predecessor.thenCombine(ingredientManagerFuture, (ignored, ingredients) -> null);
            return ready.thenRunAsync(() -> {
                SqlStatements.Type statement = switch (kind) {
                    case INSERT -> SqlStatements.Type.INSERT;
                    case UPDATE -> SqlStatements.Type.UPDATE;
                    case DELETE -> SqlStatements.Type.DELETE;
                };
                try (Connection connection = connectionSupplier.getUnchecked();
                     PreparedStatement sql = connection.prepareStatement(STATEMENTS.get(statement))) {
                    int offset = kind == CauldronPersistenceOrder.Write.UPDATE ? 2 : 0;
                    sql.setInt(offset + 1, key.x()); sql.setInt(offset + 2, key.y()); sql.setInt(offset + 3, key.z());
                    sql.setBytes(offset + 4, DecoderEncoder.asBytes(key.worldUuid()));
                    if (kind != CauldronPersistenceOrder.Write.DELETE) {
                        sql.setString(kind == CauldronPersistenceOrder.Write.INSERT ? 5 : 1, brew);
                        sql.setString(kind == CauldronPersistenceOrder.Write.INSERT ? 6 : 2, type);
                    }
                    int changed = sql.executeUpdate();
                    if (changed != 1) throw new SQLException("Exact cauldron owner write affected " + changed + " rows");
                } catch (SQLException failure) { throw new UncheckedPersistenceException(failure); }
            }, executor);
        });
    }
}
