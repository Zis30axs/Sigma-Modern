package com.mentalfrostbyte.jello.module.impl.combat.aimassist;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Small local imitation controller: a frozen analytic controller plus a rank-two
 * adapter on a frozen 8 x 10 feature layer. Only A and B learn from practice.
 * This is low-rank adaptation of a hand-built controller, not a pretrained AI model.
 * Prediction is deterministic; the live controller owns time-dependent noise and limits.
 */
public final class AimStyleModel {
    public static final int SCENARIO_COUNT = 4;
    public static final int MIN_SAMPLES_PER_SCENARIO = 120;
    public static final int MIN_SAMPLES = 600;
    public static final double MAX_VELOCITY = 720.0;

    private static final int VERSION = 1;
    private static final int FEATURES = 10;
    private static final int HIDDEN = 8;
    private static final int RANK = 2;
    private static final int MAX_PROFILE_BYTES = 16 * 1024;
    private static final int MAX_SAMPLES = 10_000_000;
    private static final double VELOCITY_SCALE = 360.0;
    private static final double WEIGHT_LIMIT = 4.0;
    private static final double LEARNING_RATE = 0.08;
    private static final double WEIGHT_DECAY = 0.00001;
    private static final double MAX_RESIDUAL_DEVIATION = 90.0;
    private static final double[][] FROZEN = frozenFeatures();

    private final double[][] a = new double[RANK][FEATURES];
    private final double[][] b = new double[HIDDEN][RANK];
    private final int[] scenarioSamples = new int[SCENARIO_COUNT];
    private final double[] residualMeanSquare = new double[2];
    private int samples;

    public record Input(double yawError, double pitchError,
                        double targetYawVelocity, double targetPitchVelocity,
                        double previousYawVelocity, double previousPitchVelocity) {
        public Input {
            finite(yawError, pitchError, targetYawVelocity, targetPitchVelocity,
                    previousYawVelocity, previousPitchVelocity);
        }
    }

    public record Velocity(double yaw, double pitch) {
        public Velocity {
            finite(yaw, pitch);
        }
    }

    public AimStyleModel() {
        reset();
    }

    public synchronized Velocity predict(Input input) {
        Objects.requireNonNull(input, "input");
        return forward(input, features(input)).velocity();
    }

    /** One supervised sample, pairing the pre-movement state with the user's next movement. */
    public synchronized void observe(Input input, Velocity demonstrated, int scenario) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(demonstrated, "demonstrated");
        checkScenario(scenario);
        double[] features = features(input);
        Forward forward = forward(input, features);
        double[] error = {
                clamp((forward.velocity.yaw - clamp(demonstrated.yaw, MAX_VELOCITY)) / VELOCITY_SCALE, 1.0),
                clamp((forward.velocity.pitch - clamp(demonstrated.pitch, MAX_VELOCITY)) / VELOCITY_SCALE, 1.0)
        };
        for (int axis = 0; axis < 2; axis++) {
            double residual = error[axis] * VELOCITY_SCALE;
            residualMeanSquare[axis] = Math.min(MAX_RESIDUAL_DEVIATION * MAX_RESIDUAL_DEVIATION,
                    residualMeanSquare[axis] * 0.98 + residual * residual * 0.02);
        }
        double[] latentGradient = new double[RANK];
        double[] hiddenGradient = new double[HIDDEN];
        for (int h = 0; h < HIDDEN; h++) {
            hiddenGradient[h] = error[h % 2] * 0.25 * (1.0 - forward.hidden[h] * forward.hidden[h]);
            for (int r = 0; r < RANK; r++) {
                latentGradient[r] += hiddenGradient[h] * b[h][r];
            }
        }

        // Compute all gradients against the old weights before changing either matrix.
        double featureNorm = 1.0;
        for (double value : features) featureNorm += value * value * 0.1;
        double step = LEARNING_RATE / featureNorm;
        for (int h = 0; h < HIDDEN; h++) {
            for (int r = 0; r < RANK; r++) {
                b[h][r] = update(b[h][r], hiddenGradient[h] * forward.latent[r], step);
            }
        }
        for (int r = 0; r < RANK; r++) {
            for (int f = 0; f < FEATURES; f++) {
                a[r][f] = update(a[r][f], latentGradient[r] * features[f], step);
            }
        }
        if (samples < MAX_SAMPLES) {
            samples++;
            scenarioSamples[scenario]++;
        }
    }

    public synchronized int sampleCount() {
        return samples;
    }

    /** Bounded RMS of recent prediction residuals, for the caller's smooth noise envelope. */
    public synchronized Velocity residualDeviation() {
        return new Velocity(Math.sqrt(residualMeanSquare[0]), Math.sqrt(residualMeanSquare[1]));
    }

    public synchronized int scenarioSamples(int scenario) {
        checkScenario(scenario);
        return scenarioSamples[scenario];
    }

    public synchronized boolean isReady() {
        return samples >= MIN_SAMPLES
                && Arrays.stream(scenarioSamples).allMatch(count -> count >= MIN_SAMPLES_PER_SCENARIO);
    }

    public synchronized void reset() {
        samples = 0;
        Arrays.fill(scenarioSamples, 0);
        Arrays.fill(residualMeanSquare, 0.0);
        for (double[] row : b) Arrays.fill(row, 0.0);
        for (int r = 0; r < RANK; r++) {
            for (int f = 0; f < FEATURES; f++) {
                a[r][f] = Math.sin((r + 1.0) * (f + 2.0)) * 0.08;
            }
            a[r][r] = 0.5;
        }
    }

    /** Saves to the caller's fixed local profile path; names never come from profile contents. */
    public synchronized void save(Path path) throws IOException {
        Path destination = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Path directory = destination.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "aim-style-", ".tmp");
        try {
            try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))) {
                writer.beginObject();
                writer.name("version").value(VERSION);
                writer.name("samples").value(samples);
                writer.name("scenarioSamples").beginArray();
                for (int count : scenarioSamples) writer.value(count);
                writer.endArray();
                writer.name("residualMeanSquare").beginArray();
                for (double value : residualMeanSquare) writer.value(value);
                writer.endArray();
                writeMatrix(writer, "a", a);
                writeMatrix(writer, "b", b);
                writer.endObject();
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Missing profiles start fresh. Malformed profiles throw without publishing partial state. */
    public static AimStyleModel load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (Files.notExists(path)) return new AimStyleModel();
        byte[] bytes;
        try (InputStream stream = Files.newInputStream(path)) {
            bytes = stream.readNBytes(MAX_PROFILE_BYTES + 1);
        }
        if (bytes.length > MAX_PROFILE_BYTES) throw new IOException("Aim style profile is too large");

        AimStyleModel model = new AimStyleModel();
        Set<String> fields = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(new String(bytes, StandardCharsets.UTF_8)))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.add(name)) throw new IOException("Duplicate aim style field: " + name);
                switch (name) {
                    case "version" -> {
                        if (readInteger(reader) != VERSION) throw new IOException("Unsupported aim style version");
                    }
                    case "samples" -> model.samples = readInteger(reader);
                    case "scenarioSamples" -> {
                        reader.beginArray();
                        for (int i = 0; i < SCENARIO_COUNT; i++) model.scenarioSamples[i] = readInteger(reader);
                        reader.endArray();
                    }
                    case "a" -> readMatrix(reader, model.a);
                    case "b" -> readMatrix(reader, model.b);
                    case "residualMeanSquare" -> {
                        reader.beginArray();
                        for (int axis = 0; axis < 2; axis++) {
                            if (reader.peek() != JsonToken.NUMBER) throw new IOException("Non-numeric aim residual");
                            double value = reader.nextDouble();
                            if (!Double.isFinite(value) || value < 0
                                    || value > MAX_RESIDUAL_DEVIATION * MAX_RESIDUAL_DEVIATION) {
                                throw new IOException("Aim residual is out of range");
                            }
                            model.residualMeanSquare[axis] = value;
                        }
                        reader.endArray();
                    }
                    default -> throw new IOException("Unknown aim style field: " + name);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || fields.size() != 6
                    || Arrays.stream(model.scenarioSamples).sum() != model.samples) {
                throw new IOException("Incomplete or inconsistent aim style profile");
            }
            return model;
        } catch (IllegalStateException | NumberFormatException exception) {
            throw new IOException("Invalid aim style profile", exception);
        }
    }

    private Forward forward(Input input, double[] features) {
        double[] latent = new double[RANK];
        double[] hidden = new double[HIDDEN];
        double[] residual = new double[2];
        for (int r = 0; r < RANK; r++) {
            for (int f = 0; f < FEATURES; f++) latent[r] += a[r][f] * features[f];
        }
        for (int h = 0; h < HIDDEN; h++) {
            double frozen = 0.0;
            for (int f = 0; f < FEATURES; f++) {
                frozen += FROZEN[h][f] * features[f];
            }
            double adapted = frozen;
            for (int r = 0; r < RANK; r++) adapted += b[h][r] * latent[r];
            hidden[h] = Math.tanh(adapted);
            residual[h % 2] += (hidden[h] - Math.tanh(frozen)) * 0.25;
        }
        double yaw = baseline(input.yawError, input.targetYawVelocity, input.previousYawVelocity);
        double pitch = baseline(input.pitchError, input.targetPitchVelocity, input.previousPitchVelocity);
        return new Forward(new Velocity(clamp(yaw + residual[0] * VELOCITY_SCALE, MAX_VELOCITY),
                clamp(pitch + residual[1] * VELOCITY_SCALE, MAX_VELOCITY)), latent, hidden);
    }

    private static double baseline(double error, double targetVelocity, double previousVelocity) {
        return clamp(5.0 * clamp(error, 180.0) + 0.35 * clamp(targetVelocity, MAX_VELOCITY)
                + 0.25 * clamp(previousVelocity, MAX_VELOCITY), MAX_VELOCITY);
    }

    private static double[] features(Input input) {
        double yaw = clamp(input.yawError / 90.0, 1.0);
        double pitch = clamp(input.pitchError / 90.0, 1.0);
        return new double[]{yaw, pitch,
                clamp(input.targetYawVelocity / VELOCITY_SCALE, 1.0),
                clamp(input.targetPitchVelocity / VELOCITY_SCALE, 1.0),
                clamp(input.previousYawVelocity / VELOCITY_SCALE, 1.0),
                clamp(input.previousPitchVelocity / VELOCITY_SCALE, 1.0),
                yaw * Math.abs(yaw), pitch * Math.abs(pitch), yaw * pitch, 1.0};
    }

    private static double update(double weight, double gradient, double step) {
        return clamp(weight - step * (gradient + WEIGHT_DECAY * weight), WEIGHT_LIMIT);
    }

    private static double[][] frozenFeatures() {
        double[][] weights = new double[HIDDEN][FEATURES];
        for (int h = 0; h < HIDDEN; h++) {
            for (int f = 0; f < FEATURES; f++) {
                weights[h][f] = Math.sin((h + 1.0) * (f + 1.0)) * 0.08;
            }
        }
        return weights;
    }

    private static void writeMatrix(JsonWriter writer, String name, double[][] matrix) throws IOException {
        writer.name(name).beginArray();
        for (double[] row : matrix) {
            writer.beginArray();
            for (double weight : row) writer.value(weight);
            writer.endArray();
        }
        writer.endArray();
    }

    private static void readMatrix(JsonReader reader, double[][] matrix) throws IOException {
        reader.beginArray();
        for (double[] row : matrix) {
            reader.beginArray();
            for (int f = 0; f < row.length; f++) {
                if (reader.peek() != JsonToken.NUMBER) throw new IOException("Non-numeric aim style weight");
                double weight = reader.nextDouble();
                if (!Double.isFinite(weight) || Math.abs(weight) > WEIGHT_LIMIT) {
                    throw new IOException("Aim style weight is out of range");
                }
                row[f] = weight;
            }
            reader.endArray();
        }
        reader.endArray();
    }

    private static int readInteger(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IOException("Non-numeric aim style counter");
        double value = reader.nextDouble();
        if (!Double.isFinite(value) || value < 0 || value > MAX_SAMPLES || value != Math.rint(value)) {
            throw new IOException("Aim style counter is out of range");
        }
        return (int) value;
    }

    private static void checkScenario(int scenario) {
        if (scenario < 0 || scenario >= SCENARIO_COUNT) throw new IllegalArgumentException("Unknown practice scenario");
    }

    private static void finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Aim samples must be finite");
        }
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private record Forward(Velocity velocity, double[] latent, double[] hidden) {}
}
