package dev.jsinco.brewery.bukkit.database.barrel;

import com.google.gson.JsonParser;
import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.api.util.Pair;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.breweries.barrel.BukkitBarrel;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.PersistenceSupplier;
import dev.jsinco.brewery.database.UncheckedPersistenceException;
import dev.jsinco.brewery.database.sql.SqlStatements;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public record SqLiteBarrelSession(Executor executor, PersistenceSupplier<Connection> connectionSupplier,
                                  CompletableFuture<ResolvedIngredientManager<ItemStack>> ingredientManagerFuture) implements BarrelSession {

    private static final SqlStatements BARREL_STATEMENTS = new SqlStatements("/database/generic/barrels");
    private static final SqlStatements BARREL_BREW_STATEMENTS = new SqlStatements("/database/generic/barrel_brews");


    @Override
    public CompletableFuture<Void> insertBrew(BreweryLocation barrelLocation, int inventoryPos, Brew brew) {
        return ingredientManagerFuture.thenAcceptAsync(ingredientManager -> {
            {
                try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BARREL_BREW_STATEMENTS.get(SqlStatements.Type.INSERT))) {
                    preparedStatement.setInt(1, barrelLocation.x());
                    preparedStatement.setInt(2, barrelLocation.y());
                    preparedStatement.setInt(3, barrelLocation.z());
                    preparedStatement.setBytes(4, DecoderEncoder.asBytes(barrelLocation.worldUuid()));
                    preparedStatement.setInt(5, inventoryPos);
                    preparedStatement.setString(6, BrewImpl.SERIALIZER.serialize(brew, ingredientManager).toString());
                    preparedStatement.execute();
                } catch (SQLException e) {
                    throw new UncheckedPersistenceException(e);
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeBrew(BreweryLocation barrelLocation, int inventoryPos) {
        return execute(() -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BARREL_BREW_STATEMENTS.get(SqlStatements.Type.DELETE))) {
                preparedStatement.setInt(1, barrelLocation.x());
                preparedStatement.setInt(2, barrelLocation.y());
                preparedStatement.setInt(3, barrelLocation.z());
                preparedStatement.setBytes(4, DecoderEncoder.asBytes(barrelLocation.worldUuid()));
                preparedStatement.setInt(5, inventoryPos);
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<BrewLookupResult>> findBrews(BreweryLocation barrelLocation) {
        return ingredientManagerFuture.thenApplyAsync(ingredientManager -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BARREL_BREW_STATEMENTS.get(SqlStatements.Type.FIND))) {
                preparedStatement.setInt(1, barrelLocation.x());
                preparedStatement.setInt(2, barrelLocation.y());
                preparedStatement.setInt(3, barrelLocation.z());
                preparedStatement.setBytes(4, DecoderEncoder.asBytes(barrelLocation.worldUuid()));
                ResultSet resultSet = preparedStatement.executeQuery();
                List<BrewLookupResult> output = new ArrayList<>();
                while (resultSet.next()) {
                    final int pos = resultSet.getInt("pos");
                    output.add(new BrewLookupResult(brewFromResultSet(resultSet, ingredientManager), pos));
                }
                return output;
            } catch (SQLException e) {
                throw new UncheckedPersistenceException(e);
            }
        }, executor);
    }

    private Brew brewFromResultSet(ResultSet resultSet, ResolvedIngredientManager<ItemStack> resolvedIngredientManager) throws SQLException {
        return BrewImpl.SERIALIZER.deserialize(JsonParser.parseString(resultSet.getString("brew")), resolvedIngredientManager);
    }

    @Override
    public CompletableFuture<Void> updateBrew(BreweryLocation barrelLocation, int inventoryPos, Brew newBrew) {
        return ingredientManagerFuture.thenAcceptAsync(ingredientManager -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BARREL_BREW_STATEMENTS.get(SqlStatements.Type.UPDATE))) {
                preparedStatement.setString(1, BrewImpl.SERIALIZER.serialize(newBrew, ingredientManager).toString());
                preparedStatement.setInt(2, barrelLocation.x());
                preparedStatement.setInt(3, barrelLocation.y());
                preparedStatement.setInt(4, barrelLocation.z());
                preparedStatement.setBytes(5, DecoderEncoder.asBytes(barrelLocation.worldUuid()));
                preparedStatement.setInt(6, inventoryPos);
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new UncheckedPersistenceException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> insertBarrel(BukkitBarrel barrel) {
        PlacedBreweryStructure<BukkitBarrel> structure = barrel.getStructure();
        BreweryLocation origin = BukkitAdapter.toBreweryLocation(structure.getWorldOrigin());
        BreweryLocation unique = BukkitAdapter.toBreweryLocation(barrel.getUniqueLocation());
        String transformation = DecoderEncoder.serializeTransformation(structure.getTransformation());
        String format = structure.getStructure().getName();
        String type = barrel.getType().key().toString();
        int size = barrel.getSize();
        List<Pair<Brew, Integer>> brews = List.copyOf(barrel.getBrews());
        return ingredientManagerFuture.thenAcceptAsync(ingredients -> {
            try (Connection connection = connectionSupplier.getUnchecked()) {
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement statement = connection.prepareStatement(BARREL_STATEMENTS.get(SqlStatements.Type.INSERT))) {
                        statement.setInt(1, origin.x());
                        statement.setInt(2, origin.y());
                        statement.setInt(3, origin.z());
                        statement.setInt(4, unique.x());
                        statement.setInt(5, unique.y());
                        statement.setInt(6, unique.z());
                        statement.setBytes(7, DecoderEncoder.asBytes(unique.worldUuid()));
                        statement.setString(8, transformation);
                        statement.setString(9, format);
                        statement.setString(10, type);
                        statement.setInt(11, size);
                        statement.executeUpdate();
                    }
                    for (Pair<Brew, Integer> brew : brews) {
                        try (PreparedStatement statement = connection.prepareStatement(BARREL_BREW_STATEMENTS.get(SqlStatements.Type.INSERT))) {
                            statement.setInt(1, unique.x());
                            statement.setInt(2, unique.y());
                            statement.setInt(3, unique.z());
                            statement.setBytes(4, DecoderEncoder.asBytes(unique.worldUuid()));
                            statement.setInt(5, brew.second());
                            statement.setString(6, BrewImpl.SERIALIZER.serialize(brew.first(), ingredients).toString());
                            statement.executeUpdate();
                        }
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new UncheckedPersistenceException(failure);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeBarrel(BukkitBarrel barrel) {
        UUID worldUuid = barrel.getWorld().getUID();
        Location signLocation = barrel.getUniqueLocation();
        return execute(() -> {
            try (Connection connection = connectionSupplier.getUnchecked(); PreparedStatement preparedStatement = connection.prepareStatement(BARREL_STATEMENTS.get(SqlStatements.Type.DELETE))) {
                preparedStatement.setInt(1, signLocation.getBlockX());
                preparedStatement.setInt(2, signLocation.getBlockY());
                preparedStatement.setInt(3, signLocation.getBlockZ());
                preparedStatement.setBytes(4, DecoderEncoder.asBytes(worldUuid));
                preparedStatement.execute();
            } catch (SQLException e) {
                throw new PersistenceException(e);
            }
        });
    }


}
