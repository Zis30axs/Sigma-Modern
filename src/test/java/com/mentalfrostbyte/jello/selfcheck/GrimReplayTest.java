package com.mentalfrostbyte.jello.selfcheck;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;
import com.mentalfrostbyte.jello.selfcheck.api.Verdicts;
import com.mentalfrostbyte.jello.selfcheck.host.EngineLoader;
import com.mentalfrostbyte.jello.selfcheck.host.Replay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The embedded Grim, fed a real connection: a recording made on a real server (run/sigma5/selfcheck/recordings/, or
 * {@code -Dsigma.selfcheck.recording=<file>}). Skipped when there is none.
 *
 * <p>What this can prove is that Grim is actually at work - it tracks the player and predicts their movement. That
 * it stays quiet is no evidence of anything unless it was predicting; an engine that never saw the player would be
 * just as quiet.</p>
 */
class GrimReplayTest {

    @TempDir
    Path tmp;

    private static Path recording() throws IOException {
        String configured = System.getProperty("sigma.selfcheck.recording");
        if (configured != null) {
            return Path.of(configured);
        }
        Path folder = Path.of("run", "sigma5", "selfcheck", "recordings");
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (Stream<Path> files = Files.list(folder)) {
            // the newest: names start with the time, and only recent ones carry per-packet markers (format 2)
            return files.filter(p -> p.getFileName().toString().endsWith(".sgsc")).max(java.util.Comparator.naturalOrder()).orElse(null);
        }
    }

    @Test
    void grimTracksTheRecordedPlayerAndPredictsTheirMovement() throws Exception {
        Path recording = recording();
        assumeTrue(recording != null && Files.isRegularFile(recording), "no recording to replay");

        List<String> problems = new ArrayList<>();
        List<EngineLoader.Loaded> loaded = EngineLoader.builtIn(List.of(EngineLoader.GRIM), getClass().getClassLoader(), problems::add);
        assertTrue(problems.isEmpty(), problems.toString());
        SelfCheckEngineFactory factory = loaded.get(0).factory();

        List<String> log = Collections.synchronizedList(new ArrayList<>());
        List<String> verdicts = Collections.synchronizedList(new ArrayList<>());
        Verdicts sink = new Verdicts() {
            @Override
            public void flag(final String check, final double violations, final String verbose) {
                verdicts.add("flag " + check + " x" + violations + " " + verbose);
            }

            @Override
            public void message(final String componentJson) {
                verdicts.add("message " + componentJson);
            }

            @Override
            public void punishment(final String command) {
                verdicts.add("punishment " + command);
            }

            @Override
            public void setback(final String reason) {
                verdicts.add("setback " + reason);
            }

            @Override
            public void disconnect(final String reason) {
                verdicts.add("disconnect " + reason);
            }
        };

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loaded.get(0).loader());
        Replay.Result result;
        long began = System.nanoTime();
        try {
            // in real time: Grim's timer checks read the clock, and a replay at full speed looks like a timer cheat
            result = Replay.run(recording, factory, this.tmp.resolve("GrimAC"), sink, log::add, true);
        } finally {
            thread.setContextClassLoader(previous);
        }
        long millis = (System.nanoTime() - began) / 1_000_000;

        System.out.println("[GrimReplayTest] " + recording.getFileName() + ": " + result + " in " + millis + " ms");
        log.forEach(line -> System.out.println("[GrimReplayTest] log: " + line));
        java.util.Map<String, Long> byKind = new java.util.TreeMap<>();
        for (String verdict : verdicts) {
            String[] words = verdict.split(" ");
            byKind.merge(words[0].equals("flag") ? "flag " + words[1] : words[0], 1L, Long::sum);
        }
        byKind.forEach((kind, count) -> System.out.println("[GrimReplayTest] " + count + " x " + kind));
        System.out.println("[GrimReplayTest] " + verdicts.size() + " verdicts in total");

        assertTrue(log.stream().anyMatch(line -> line.startsWith("checking ")), "Grim never started tracking the player: " + log);
        String summary = log.stream().filter(line -> line.startsWith("GrimAC stopped: ")).findFirst().orElseThrow();
        Matcher predicted = Pattern.compile("(\\d+) movements predicted").matcher(summary);
        assertTrue(predicted.find(), summary);
        // a standing 1.8 player reports its position about once a second, which is what Grim predicts
        assertTrue(Long.parseLong(predicted.group(1)) > 10, "Grim hardly predicted anything: " + summary);
    }
}
