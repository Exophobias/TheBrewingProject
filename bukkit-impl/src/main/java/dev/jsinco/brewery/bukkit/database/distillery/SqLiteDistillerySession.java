package dev.jsinco.brewery.bukkit.database.distillery;

import com.google.gson.JsonParser;
import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.api.util.Logger;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.PersistenceSupplier;
import dev.jsinco.brewery.database.UncheckedPersistenceException;
import dev.jsinco.brewery.database.sql.SqlStatements;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public record SqLiteDistillerySession(Executor executor, PersistenceSupplier<Connection> connectionSupplier,
                                      CompletableFuture<ResolvedIngredientManager<ItemStack>> ingredientManagerFuture) implements DistillerySession {
    private static final SqlStatements BREW_DISTILLERY_STATEMENTS = new SqlStatements("/database/generic/distillery_brews");
    private static final SqlStatements DISTILLERY_STATEMENTS = new SqlStatements("/database/generic/distilleries");

    @Override
    public CompletableFuture<Void> insertBrew(BreweryLocation distilleryLocation, int inventoryPos, boolean distillateInventoryType, Brew brew) {
        return ingredientManagerFuture.thenAcceptAsync(ingredientManager -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BREW_DISTILLERY_STATEMENTS.get(SqlStatements.Type.INSERT))) {
                preparedStatement.setInt(1, distilleryLocation.x());
                preparedStatement.setInt(2, distilleryLocation.y());
                preparedStatement.setInt(3, distilleryLocation.z());
                preparedStatement.setBytes(4, DecoderEncoder.asBytes(distilleryLocation.worldUuid()));
                preparedStatement.setInt(5, inventoryPos);
                preparedStatement.setBoolean(6, distillateInventoryType);
                preparedStatement.setString(7, BrewImpl.SERIALIZER.serialize(brew, ingredientManager).toString());
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new UncheckedPersistenceException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeBrew(BreweryLocation distilleryLocation, int inventoryPos, boolean distillateInventoryType) {
        return execute(() -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BREW_DISTILLERY_STATEMENTS.get(SqlStatements.Type.DELETE))) {
                preparedStatement.setInt(1, distilleryLocation.x());
                preparedStatement.setInt(2, distilleryLocation.y());
                preparedStatement.setInt(3, distilleryLocation.z());
                preparedStatement.setBytes(4, DecoderEncoder.asBytes(distilleryLocation.worldUuid()));
                preparedStatement.setInt(5, inventoryPos);
                preparedStatement.setBoolean(6, distillateInventoryType);
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<BrewLookupResult>> findBrews(BreweryLocation distilleryLocation) {
        return ingredientManagerFuture.thenApplyAsync(ingredientManager -> {
            List<BrewLookupResult> output = new ArrayList<>();
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BREW_DISTILLERY_STATEMENTS.get(SqlStatements.Type.FIND))) {
                preparedStatement.setInt(1, distilleryLocation.x());
                preparedStatement.setInt(2, distilleryLocation.y());
                preparedStatement.setInt(3, distilleryLocation.z());
                preparedStatement.setBytes(4, DecoderEncoder.asBytes(distilleryLocation.worldUuid()));
                ResultSet resultSet = preparedStatement.executeQuery();
                while (resultSet.next()) {
                    int pos = resultSet.getInt("pos");
                    boolean isDistillate = resultSet.getBoolean("is_distillate");
                    Brew brew = BrewImpl.SERIALIZER.deserialize(JsonParser.parseString(resultSet.getString("brew")), ingredientManager);
                    output.add(new BrewLookupResult(brew, pos, isDistillate));
                }
            } catch (SQLException e) {
                throw new UncheckedPersistenceException(e);
            }
            return output;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateBrew(BreweryLocation distilleryLocation, int inventoryPos, boolean distillateInventoryType, Brew newBrew) {
        return ingredientManagerFuture.thenAcceptAsync(ingredientManager -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BREW_DISTILLERY_STATEMENTS.get(SqlStatements.Type.UPDATE))) {
                preparedStatement.setString(1, BrewImpl.SERIALIZER.serialize(newBrew, ingredientManager).toString());
                preparedStatement.setInt(2, distilleryLocation.x());
                preparedStatement.setInt(3, distilleryLocation.y());
                preparedStatement.setInt(4, distilleryLocation.z());
                preparedStatement.setBytes(5, DecoderEncoder.asBytes(distilleryLocation.worldUuid()));
                preparedStatement.setInt(6, inventoryPos);
                preparedStatement.setBoolean(7, distillateInventoryType);
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new UncheckedPersistenceException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> moveBrewsAtomically(BreweryLocation distilleryLocation,
                                                           List<DistilleryAccess.AtomicBrewMove> moves,
                                                           long committedStartTime) {
        List<DistilleryAccess.AtomicBrewMove> moveSnapshot = List.copyOf(moves);
        return ingredientManagerFuture.thenApplyAsync(
                ingredientManager -> executeAtomicMove(
                        distilleryLocation, moveSnapshot, committedStartTime, ingredientManager),
                executor
        );
    }

    @Override
    public CompletableFuture<Boolean> removeMixtureBrewsAtomically(
            BreweryLocation distilleryLocation, List<DistilleryAccess.AtomicBrewRemoval> removals) {
        List<DistilleryAccess.AtomicBrewRemoval> removalSnapshot = List.copyOf(removals);
        return ingredientManagerFuture.thenApplyAsync(
                ingredientManager -> executeAtomicRemoval(distilleryLocation, removalSnapshot, ingredientManager),
                executor
        );
    }

    @Override
    public CompletableFuture<Boolean> consumeDistilleryAtomically(BreweryLocation distilleryLocation) {
        return CompletableFuture.supplyAsync(
                () -> executeAtomicConsumption(distilleryLocation), executor
        );
    }

    private boolean executeAtomicConsumption(BreweryLocation location) {
        Connection connection;
        try {
            connection = connectionSupplier.getUnchecked();
        } catch (RuntimeException failure) {
            // No transaction was opened, so the old durable structure is known to be intact.
            throw new DistillerySession.AtomicMovePersistenceException(failure, true);
        }
        boolean committed = false;
        boolean commitAttempted = false;
        boolean transactionStarted = false;
        try {
            connection.setAutoCommit(false);
            transactionStarted = true;

            // Delete children explicitly even though production SQLite also has ON DELETE
            // CASCADE. Keeping both statements in this transaction makes the lifecycle contract
            // independent of connection pragma drift and gives failures between them a real
            // rollback boundary.
            deleteAllBrews(connection, location);
            int removedStructures = deleteDistillery(connection, location);
            if (removedStructures == 0) {
                connection.rollback();
                return false;
            }
            if (removedStructures != 1) {
                throw new SQLException("Atomic distillery consumption removed an unexpected number of structures: "
                        + removedStructures);
            }

            commitAttempted = true;
            connection.commit();
            committed = true;
            return true;
        } catch (SQLException | RuntimeException failure) {
            boolean rollbackConfirmed = !transactionStarted;
            if (!committed) {
                try {
                    connection.rollback();
                    // JDBC cannot establish that a throwing commit did not become durable.
                    rollbackConfirmed = !commitAttempted;
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw new DistillerySession.AtomicMovePersistenceException(failure, rollbackConfirmed);
        } finally {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                Logger.logErr(closeFailure);
            }
        }
    }

    private boolean executeAtomicRemoval(BreweryLocation location,
                                          List<DistilleryAccess.AtomicBrewRemoval> removals,
                                          ResolvedIngredientManager<ItemStack> ingredientManager) {
        if (removals.isEmpty() || hasDuplicateRemovalPositions(removals)) {
            return false;
        }

        Connection connection;
        try {
            connection = connectionSupplier.getUnchecked();
        } catch (RuntimeException failure) {
            // No transaction was opened, so the old durable state is known to be intact.
            throw new DistillerySession.AtomicMovePersistenceException(failure, true);
        }
        boolean committed = false;
        boolean commitAttempted = false;
        boolean transactionStarted = false;
        try {
            connection.setAutoCommit(false);
            transactionStarted = true;
            Map<InventorySlot, Brew> persistedBrews = findPersistedBrews(connection, location, ingredientManager);
            if (!matchesPersistedRemovalState(removals, persistedBrews)) {
                connection.rollback();
                return false;
            }

            for (DistilleryAccess.AtomicBrewRemoval removal : removals) {
                if (deleteBrew(connection, location, removal.mixturePosition(), false) != 1) {
                    throw new SQLException("Atomic distillery removal did not remove exactly one source brew");
                }
            }
            commitAttempted = true;
            connection.commit();
            committed = true;
            return true;
        } catch (SQLException | RuntimeException failure) {
            boolean rollbackConfirmed = !transactionStarted;
            if (!committed) {
                try {
                    connection.rollback();
                    // Once commit was attempted, JDBC cannot prove whether a thrown commit took
                    // effect. A later successful rollback does not make that outcome unambiguous.
                    rollbackConfirmed = !commitAttempted;
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw new DistillerySession.AtomicMovePersistenceException(failure, rollbackConfirmed);
        } finally {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                Logger.logErr(closeFailure);
            }
        }
    }

    private boolean executeAtomicMove(BreweryLocation location, List<DistilleryAccess.AtomicBrewMove> moves,
                                      long committedStartTime,
                                      ResolvedIngredientManager<ItemStack> ingredientManager) {
        if (moves.isEmpty() || hasOverlappingPositions(moves)) {
            return false;
        }

        Connection connection;
        try {
            connection = connectionSupplier.getUnchecked();
        } catch (RuntimeException failure) {
            // No transaction was opened, so the old durable state is known to be intact.
            throw new DistillerySession.AtomicMovePersistenceException(failure, true);
        }
        boolean committed = false;
        boolean commitAttempted = false;
        boolean transactionStarted = false;
        try {
            connection.setAutoCommit(false);
            transactionStarted = true;
            Map<InventorySlot, Brew> persistedBrews = findPersistedBrews(connection, location, ingredientManager);
            if (!matchesPersistedState(moves, persistedBrews)) {
                connection.rollback();
                return false;
            }

            for (DistilleryAccess.AtomicBrewMove move : moves) {
                if (deleteBrew(connection, location, move.mixturePosition(), false) != 1) {
                    throw new SQLException("Atomic distillery move did not remove exactly one source brew");
                }
                if (insertBrew(connection, location, move.distillatePosition(), true,
                        move.distillate(), ingredientManager) != 1) {
                    throw new SQLException("Atomic distillery move did not insert exactly one destination brew");
                }
            }
            if (updateDistilleryStartTime(connection, location, committedStartTime) != 1) {
                throw new SQLException("Atomic distillery move did not update exactly one distillery timer");
            }
            commitAttempted = true;
            connection.commit();
            committed = true;
            return true;
        } catch (SQLException | RuntimeException failure) {
            boolean rollbackConfirmed = !transactionStarted;
            if (!committed) {
                try {
                    connection.rollback();
                    // A commit exception is inherently ambiguous: rollback returning normally
                    // cannot prove that the commit did not already become durable.
                    rollbackConfirmed = !commitAttempted;
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw new DistillerySession.AtomicMovePersistenceException(failure, rollbackConfirmed);
        } finally {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                // A successful commit is already authoritative. Treating a subsequent connection
                // cleanup failure as a failed move would leave the live inventory on the old side
                // of a durable commit until restart.
                Logger.logErr(closeFailure);
            }
        }
    }

    private static boolean hasOverlappingPositions(List<DistilleryAccess.AtomicBrewMove> moves) {
        Set<Integer> mixturePositions = new HashSet<>();
        Set<Integer> distillatePositions = new HashSet<>();
        return moves.stream().anyMatch(move -> !mixturePositions.add(move.mixturePosition())
                || !distillatePositions.add(move.distillatePosition()));
    }

    private static boolean hasDuplicateRemovalPositions(List<DistilleryAccess.AtomicBrewRemoval> removals) {
        Set<Integer> mixturePositions = new HashSet<>();
        return removals.stream().anyMatch(removal -> !mixturePositions.add(removal.mixturePosition()));
    }

    private static boolean matchesPersistedState(List<DistilleryAccess.AtomicBrewMove> moves,
                                                  Map<InventorySlot, Brew> persistedBrews) {
        for (DistilleryAccess.AtomicBrewMove move : moves) {
            Brew persistedSource = persistedBrews.get(new InventorySlot(move.mixturePosition(), false));
            if (!sameBrew(persistedSource, move.expectedMixture())
                    || persistedBrews.containsKey(new InventorySlot(move.distillatePosition(), true))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesPersistedRemovalState(List<DistilleryAccess.AtomicBrewRemoval> removals,
                                                         Map<InventorySlot, Brew> persistedBrews) {
        for (DistilleryAccess.AtomicBrewRemoval removal : removals) {
            Brew persistedSource = persistedBrews.get(new InventorySlot(removal.mixturePosition(), false));
            if (!sameBrew(persistedSource, removal.expectedMixture())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBrew(Brew first, Brew second) {
        return first != null && second != null
                && Objects.equals(first.getSteps(), second.getSteps())
                && Objects.equals(first.meta(), second.meta());
    }

    private static Map<InventorySlot, Brew> findPersistedBrews(Connection connection, BreweryLocation location,
                                                               ResolvedIngredientManager<ItemStack> ingredientManager)
            throws SQLException {
        Map<InventorySlot, Brew> brews = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                BREW_DISTILLERY_STATEMENTS.get(SqlStatements.Type.FIND))) {
            setLocation(statement, location, 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    InventorySlot slot = new InventorySlot(
                            resultSet.getInt("pos"),
                            resultSet.getBoolean("is_distillate")
                    );
                    Brew brew = BrewImpl.SERIALIZER.deserialize(
                            JsonParser.parseString(resultSet.getString("brew")), ingredientManager
                    );
                    if (brews.putIfAbsent(slot, brew) != null) {
                        throw new SQLException("Duplicate persisted distillery brew slot: " + slot);
                    }
                }
            }
        }
        return brews;
    }

    private static int deleteBrew(Connection connection, BreweryLocation location, int position,
                                  boolean distillate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                BREW_DISTILLERY_STATEMENTS.get(SqlStatements.Type.DELETE))) {
            setLocation(statement, location, 1);
            statement.setInt(5, position);
            statement.setBoolean(6, distillate);
            return statement.executeUpdate();
        }
    }

    private static int deleteAllBrews(Connection connection, BreweryLocation location)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                BREW_DISTILLERY_STATEMENTS.get("delete_all"))) {
            setLocation(statement, location, 1);
            return statement.executeUpdate();
        }
    }

    private static int deleteDistillery(Connection connection, BreweryLocation location)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                DISTILLERY_STATEMENTS.get(SqlStatements.Type.DELETE))) {
            setLocation(statement, location, 1);
            return statement.executeUpdate();
        }
    }

    private static int updateDistilleryStartTime(Connection connection, BreweryLocation location,
                                                  long committedStartTime) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                DISTILLERY_STATEMENTS.get(SqlStatements.Type.UPDATE))) {
            statement.setLong(1, committedStartTime);
            setLocation(statement, location, 2);
            return statement.executeUpdate();
        }
    }

    private static int insertBrew(Connection connection, BreweryLocation location, int position,
                                  boolean distillate, Brew brew,
                                  ResolvedIngredientManager<ItemStack> ingredientManager) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                BREW_DISTILLERY_STATEMENTS.get(SqlStatements.Type.INSERT))) {
            setLocation(statement, location, 1);
            statement.setInt(5, position);
            statement.setBoolean(6, distillate);
            statement.setString(7, BrewImpl.SERIALIZER.serialize(brew, ingredientManager).toString());
            return statement.executeUpdate();
        }
    }

    private static void setLocation(PreparedStatement statement, BreweryLocation location, int firstParameter)
            throws SQLException {
        statement.setInt(firstParameter, location.x());
        statement.setInt(firstParameter + 1, location.y());
        statement.setInt(firstParameter + 2, location.z());
        statement.setBytes(firstParameter + 3, DecoderEncoder.asBytes(location.worldUuid()));
    }

    private record InventorySlot(int position, boolean distillate) {
    }

    @Override
    public CompletableFuture<Void> insertDistillery(BukkitDistillery distillery) {
        return execute(() -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(DISTILLERY_STATEMENTS.get(SqlStatements.Type.INSERT))) {
                PlacedBreweryStructure<BukkitDistillery> structure = distillery.getStructure();
                BreweryLocation origin = BukkitAdapter.toBreweryLocation(structure.getWorldOrigin());
                BreweryLocation unique = structure.getUnique();
                preparedStatement.setInt(1, origin.x());
                preparedStatement.setInt(2, origin.y());
                preparedStatement.setInt(3, origin.z());
                preparedStatement.setInt(4, unique.x());
                preparedStatement.setInt(5, unique.y());
                preparedStatement.setInt(6, unique.z());
                preparedStatement.setBytes(7, DecoderEncoder.asBytes(origin.worldUuid()));
                preparedStatement.setString(8, DecoderEncoder.serializeTransformation(structure.getTransformation()));
                preparedStatement.setString(9, structure.getStructure().getName());
                preparedStatement.setLong(10, distillery.getStartTime());
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> removeDistillery(BukkitDistillery distillery) {
        return execute(() -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(DISTILLERY_STATEMENTS.get(SqlStatements.Type.DELETE))) {
                BreweryLocation unique = distillery.getStructure().getUnique();
                preparedStatement.setInt(1, unique.x());
                preparedStatement.setInt(2, unique.y());
                preparedStatement.setInt(3, unique.z());
                preparedStatement.setBytes(4, DecoderEncoder.asBytes(unique.worldUuid()));
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> updateDistillery(BukkitDistillery newDistillery) {
        return execute(() -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(DISTILLERY_STATEMENTS.get(SqlStatements.Type.UPDATE))) {
                long startTime = newDistillery.getStartTime();
                BreweryLocation unique = newDistillery.getStructure().getUnique();
                preparedStatement.setLong(1, startTime);
                preparedStatement.setInt(2, unique.x());
                preparedStatement.setInt(3, unique.y());
                preparedStatement.setInt(4, unique.z());
                preparedStatement.setBytes(5, DecoderEncoder.asBytes(unique.worldUuid()));
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        });
    }
}
