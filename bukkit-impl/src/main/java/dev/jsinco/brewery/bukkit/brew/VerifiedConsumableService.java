package dev.jsinco.brewery.bukkit.brew;

import com.google.gson.*;
import dev.jsinco.brewery.api.brew.*;
import dev.jsinco.brewery.api.recipe.Recipe;
import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.database.BrewPersistenceSnapshot;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Authenticated completion facts captured from the full brew, including sealed outputs. */
public final class VerifiedConsumableService {
    private static final NamespacedKey PROOF = TheBrewingProject.key("verified_consumable");
    private static final int MAX_PROOF_BYTES = 4096;
    private final byte[] signingKey;
    public VerifiedConsumableService(byte[] signingKey) {
        if (signingKey == null || signingKey.length != 32) throw new IllegalArgumentException("Signing identity must be 32 bytes");
        this.signingKey = signingKey.clone();
    }
    /** Output templates and renderers without full completion evidence cannot retain an old proof. */
    public static void invalidate(ItemStack item) { item.editPersistentDataContainer(pdc -> pdc.remove(PROOF)); }
    /** Always remove inherited proof. Poor, unfinished, overridden and mismatched renders receive none. */
    public void refresh(ItemStack item, Brew brew, Recipe<ItemStack> recipe, BrewScore score, boolean sealed) {
        invalidate(item);
        if (recipe == null || !score.completed() || !Double.isFinite(score.score()) || score.score() < .6 || score.score() > 1
                || BrewQuality.quality(score.score()).orElse(null) != score.brewQuality()) return;
        var data = item.getPersistentDataContainer();
        String tag = data.get(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING);
        Double renderedScore = data.get(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE);
        String canonical = BreweryKey.parse(recipe.getRecipeName()).toString();
        if (!canonical.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return;
        if (tag == null || !canonical.equals(BreweryKey.parse(tag).toString()) || renderedScore == null
                || Double.compare(renderedScore, score.score()) != 0) return;
        // A plugin-supplied output cannot smuggle a different brew history into a valid proof.
        var reconstructed = BrewAdapterAccess.fromItem(item);
        String history = historyRevision(brew);
        if (sealed ? reconstructed.isPresent() : reconstructed.isEmpty() || !history.equals(historyRevision(reconstructed.get()))) return;
        var descriptor = new VerifiedBrewConsumable(canonical, recipeRevision(recipe), 1, true,
                score.score(), BrewQuality.quality(score.score()).orElseThrow(), sealed);
        byte[] payload = encode(descriptor, item.getType().getKey().toString(), history);
        byte[] signature = sign(payload);
        byte[] proof = Arrays.copyOf(payload, payload.length + signature.length);
        System.arraycopy(signature, 0, proof, payload.length, signature.length);
        item.editPersistentDataContainer(pdc -> pdc.set(PROOF, PersistentDataType.BYTE_ARRAY, proof));
    }
    /** Empty is deliberately generic: this boundary never identifies an unrecognized product. */
    public Optional<VerifiedBrewConsumable> inspect(ItemStack item) {
        if (item == null || item.isEmpty()) return Optional.empty();
        try {
            var data = item.getPersistentDataContainer();
            byte[] proof = data.get(PROOF, PersistentDataType.BYTE_ARRAY);
            if (proof == null || proof.length <= 32 || proof.length > MAX_PROOF_BYTES) return Optional.empty();
            byte[] payload = Arrays.copyOf(proof, proof.length - 32);
            if (!MessageDigest.isEqual(sign(payload), Arrays.copyOfRange(proof, proof.length - 32, proof.length))) return Optional.empty();
            var decoded = decode(payload);
            String tag = data.get(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING);
            Double score = data.get(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE);
            if (!decoded.material.equals(item.getType().getKey().toString()) || tag == null
                    || !decoded.descriptor.canonicalRecipeId().equals(BreweryKey.parse(tag).toString()) || score == null
                    || Double.compare(score, decoded.descriptor.score()) != 0) return Optional.empty();
            var brew = BrewAdapterAccess.fromItem(item);
            if (decoded.descriptor.sealed()) {
                if (brew.isPresent()) return Optional.empty();
            } else if (brew.isEmpty() || !decoded.history.equals(historyRevision(brew.get()))) return Optional.empty();
            return Optional.of(decoded.descriptor);
        } catch (RuntimeException | IOException malformed) { return Optional.empty(); }
    }
    private byte[] sign(byte[] payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(signingKey, "HmacSHA256")); return mac.doFinal(payload);
        } catch (GeneralSecurityException unavailable) { throw new IllegalStateException("Consumable authentication unavailable", unavailable); }
    }
    private static byte[] encode(VerifiedBrewConsumable descriptor, String material, String history) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            output.writeInt(descriptor.proofVersion()); output.writeUTF(descriptor.canonicalRecipeId()); output.writeUTF(descriptor.recipeRevision());
            output.writeBoolean(descriptor.completed()); output.writeDouble(descriptor.score()); output.writeUTF(descriptor.quality().name());
            output.writeBoolean(descriptor.sealed()); output.writeUTF(material); output.writeUTF(history); output.flush();
            byte[] result = bytes.toByteArray();
            if (result.length + 32 > MAX_PROOF_BYTES) throw new IllegalArgumentException("Consumable proof exceeds its bound");
            return result;
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    private static Decoded decode(byte[] payload) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(payload));
        int version = input.readInt(); String recipe = input.readUTF(); String revision = input.readUTF();
        boolean completed = input.readBoolean(); double score = input.readDouble(); BrewQuality quality = BrewQuality.valueOf(input.readUTF());
        boolean sealed = input.readBoolean(); String material = input.readUTF(); String history = input.readUTF();
        if (input.available() != 0 || !history.matches("[a-f0-9]{64}")) throw new IOException("Malformed consumable proof");
        return new Decoded(new VerifiedBrewConsumable(recipe, revision, version, completed, score, quality, sealed), material, history);
    }
    private record Decoded(VerifiedBrewConsumable descriptor, String material, String history) { }
    public static String recipeRevision(Recipe<?> recipe) {
        return hash(BreweryKey.parse(recipe.getRecipeName()) + "\n" + Double.toHexString(recipe.getBrewDifficulty()) + "\n"
                + canonicalJson(JsonParser.parseString(BrewPersistenceSnapshot.captureNow(new BrewImpl(recipe.getSteps())))));
    }
    private static String historyRevision(Brew brew) { return hash(canonicalJson(JsonParser.parseString(BrewPersistenceSnapshot.captureNow(brew)))); }
    /** Sort object keys, retaining semantic step/array order, so round trips and map order do not alter identity. */
    private static String canonicalJson(JsonElement element) {
        if (element.isJsonObject()) {
            var object = new JsonObject(); element.getAsJsonObject().keySet().stream().sorted()
                    .forEach(key -> object.add(key, JsonParser.parseString(canonicalJson(element.getAsJsonObject().get(key)))));
            return object.toString();
        }
        if (element.isJsonArray()) {
            var array = new JsonArray(); element.getAsJsonArray().forEach(value -> array.add(JsonParser.parseString(canonicalJson(value)))); return array.toString();
        }
        return element.toString();
    }
    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
