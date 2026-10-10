package com.mentalfrostbyte.jello.module.impl.combat.aimassist;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AimStyleModelTest {
    @TempDir Path directory;

    private static final AimStyleModel.Input STILL = new AimStyleModel.Input(0, 0, 0, 0, 0, 0);

    @Test
    void adaptationLearnsDemonstratedStyleOnUnseenInputs() {
        AimStyleModel model = new AimStyleModel();
        double before = heldOutError(model);
        Random random = new Random(42);
        for (int i = 0; i < 8_000; i++) {
            AimStyleModel.Input input = sample(random);
            model.observe(input, demonstration(input), i % 4);
        }
        double after = heldOutError(model);
        assertTrue(after < before * 0.25,
                "Held-out squared error must substantially improve: " + before + " -> " + after);
        assertTrue(model.isReady());
        assertTrue(model.residualDeviation().yaw() > 0);
        assertTrue(model.residualDeviation().yaw() <= 90);
        assertTrue(model.residualDeviation().pitch() <= 90);
    }

    @Test
    void readinessRequiresEveryScenarioAndEnoughTotalSamples() {
        AimStyleModel model = new AimStyleModel();
        for (int scenario = 0; scenario < 4; scenario++) {
            for (int i = 0; i < AimStyleModel.MIN_SAMPLES_PER_SCENARIO; i++) {
                model.observe(STILL, new AimStyleModel.Velocity(0, 0), scenario);
            }
        }
        assertFalse(model.isReady(), "Scenario coverage alone is insufficient");
        while (model.sampleCount() < AimStyleModel.MIN_SAMPLES) {
            model.observe(STILL, new AimStyleModel.Velocity(0, 0), 0);
        }
        assertTrue(model.isReady());
        model.reset();
        for (int i = 0; i < AimStyleModel.MIN_SAMPLES * 2; i++) {
            model.observe(STILL, new AimStyleModel.Velocity(0, 0), 0);
        }
        assertFalse(model.isReady(), "A single practiced scenario cannot unlock the model");
    }

    @Test
    void persistencePreservesPredictionsAndResetRestoresTheFrozenController() throws IOException {
        AimStyleModel model = new AimStyleModel();
        AimStyleModel.Input probe = sample(new Random(5));
        AimStyleModel.Velocity baseline = model.predict(probe);
        Random random = new Random(70);
        for (int i = 0; i < 650; i++) {
            AimStyleModel.Input input = sample(random);
            model.observe(input, demonstration(input), i % 4);
        }
        Path path = directory.resolve("profile.json");
        model.save(path);
        AimStyleModel loaded = AimStyleModel.load(path);
        assertEquals(model.predict(probe), loaded.predict(probe));
        assertEquals(model.residualDeviation(), loaded.residualDeviation());
        assertEquals(model.sampleCount(), loaded.sampleCount());
        assertEquals(model.isReady(), loaded.isReady());
        for (int i = 0; i < 4; i++) assertEquals(model.scenarioSamples(i), loaded.scenarioSamples(i));
        loaded.reset();
        assertEquals(baseline, loaded.predict(probe));
        assertEquals(0, loaded.sampleCount());
        assertEquals(new AimStyleModel.Velocity(0, 0), loaded.residualDeviation());
        loaded.save(path);
        assertEquals(0, AimStyleModel.load(path).sampleCount());
        try (var children = Files.list(directory)) {
            assertEquals(1, children.count(), "Atomic saves should leave only the destination");
        }
    }

    @Test
    void missingProfileStartsFreshButInvalidProfilesDoNotPretendToBeTrained() throws IOException {
        Path missing = directory.resolve("missing.json");
        assertFalse(AimStyleModel.load(missing).isReady());
        Path path = directory.resolve("invalid.json");
        Files.writeString(path, "{}");
        assertThrows(IOException.class, () -> AimStyleModel.load(path));
        Files.writeString(path, "[".repeat(20_000));
        assertThrows(IOException.class, () -> AimStyleModel.load(path));
        assertInvalidProfile(root -> root.addProperty("version", 9));
        assertInvalidProfile(root -> root.addProperty("samples", 1));
        assertInvalidProfile(root -> root.addProperty("samples", -1));
        assertInvalidProfile(root -> root.addProperty("samples", 1.5));
        assertInvalidProfile(root -> root.add("a", new JsonArray()));
        assertInvalidProfile(root -> root.getAsJsonArray("a").get(0).getAsJsonArray().set(0,
                JsonParser.parseString("1e999")));
        assertInvalidProfile(root -> root.getAsJsonArray("b").get(0).getAsJsonArray().set(0,
                JsonParser.parseString("99")));
        assertInvalidProfile(root -> root.getAsJsonArray("residualMeanSquare").set(0,
                JsonParser.parseString("-1")));
        assertInvalidProfile(root -> root.addProperty("unknown", "ignored state is forbidden"));
    }

    @Test
    void duplicateFieldsTrailingContentAndWrongTypesAreRejected() throws IOException {
        Path path = directory.resolve("profile.json");
        new AimStyleModel().save(path);
        String valid = Files.readString(path);
        for (String corrupted : new String[]{
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
                valid + " {}",
                valid.replace("\"samples\":0", "\"samples\":\"0\""),
                valid.replace("\"a\":[", "\"a\":null,\"ignored\":[")
        }) {
            Files.writeString(path, corrupted);
            assertThrows(IOException.class, () -> AimStyleModel.load(path));
        }
    }

    @Test
    void invalidSamplesFailBeforeChangingStateAndFiniteExtremesStayBounded() {
        AimStyleModel model = new AimStyleModel();
        assertThrows(IllegalArgumentException.class, () -> new AimStyleModel.Input(Double.NaN, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AimStyleModel.Velocity(0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> model.observe(STILL, new AimStyleModel.Velocity(1, 1), -1));
        assertThrows(IllegalArgumentException.class, () -> model.scenarioSamples(4));
        assertEquals(0, model.sampleCount());

        AimStyleModel.Input extreme = new AimStyleModel.Input(Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE);
        for (int i = 0; i < 1_000; i++) {
            model.observe(extreme, new AimStyleModel.Velocity(-Double.MAX_VALUE, Double.MAX_VALUE), i % 4);
        }
        AimStyleModel.Velocity prediction = model.predict(extreme);
        assertTrue(Double.isFinite(prediction.yaw()));
        assertTrue(Double.isFinite(prediction.pitch()));
        assertTrue(Math.abs(prediction.yaw()) <= AimStyleModel.MAX_VELOCITY);
        assertTrue(Math.abs(prediction.pitch()) <= AimStyleModel.MAX_VELOCITY);
    }

    private void assertInvalidProfile(Consumer<JsonObject> corrupt) throws IOException {
        Path path = directory.resolve("corrupt.json");
        new AimStyleModel().save(path);
        JsonObject profile = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        corrupt.accept(profile);
        Files.writeString(path, profile.toString());
        assertThrows(IOException.class, () -> AimStyleModel.load(path));
    }

    private static double heldOutError(AimStyleModel model) {
        Random random = new Random(903);
        double error = 0;
        for (int i = 0; i < 250; i++) {
            AimStyleModel.Input input = sample(random);
            AimStyleModel.Velocity actual = model.predict(input);
            AimStyleModel.Velocity wanted = demonstration(input);
            error += Math.pow(actual.yaw() - wanted.yaw(), 2) + Math.pow(actual.pitch() - wanted.pitch(), 2);
        }
        return error / 250.0;
    }

    private static AimStyleModel.Input sample(Random random) {
        return new AimStyleModel.Input(signed(random, 60), signed(random, 30),
                signed(random, 80), signed(random, 80), signed(random, 100), signed(random, 100));
    }

    private static AimStyleModel.Velocity demonstration(AimStyleModel.Input input) {
        return new AimStyleModel.Velocity(
                input.yawError() * 3.2 + input.targetYawVelocity() * 0.6 + input.previousYawVelocity() * 0.12,
                input.pitchError() * 4.0 + input.targetPitchVelocity() * 0.2 + input.previousPitchVelocity() * 0.4);
    }

    private static double signed(Random random, double limit) {
        return (random.nextDouble() * 2.0 - 1.0) * limit;
    }
}
