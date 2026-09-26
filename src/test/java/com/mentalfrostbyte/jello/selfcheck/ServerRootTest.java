package com.mentalfrostbyte.jello.selfcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.selfcheck.host.ServerRoot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerRootTest {

    @TempDir
    Path tmp;

    @Test
    void pluginFoldersAreLaidOutLikeAServers() throws IOException {
        ServerRoot root = new ServerRoot(this.tmp);
        Path grim = root.pluginFolder("GrimAC");
        assertEquals(this.tmp.resolve("plugins").resolve("GrimAC"), grim);
        assertTrue(Files.isDirectory(grim));
    }

    @Test
    void anEngineNameCannotEscapeThePluginsFolder() throws IOException {
        ServerRoot root = new ServerRoot(this.tmp);
        assertEquals(root.plugins().resolve(".._.._evil"), root.pluginFolder("../../evil"));
        assertEquals(root.plugins().resolve("_"), root.pluginFolder(".."));
        assertEquals(root.plugins().resolve("_"), root.pluginFolder(""));
    }

    @Test
    void onlyJarsInThePluginsFolderAreEngines() throws IOException {
        ServerRoot root = new ServerRoot(this.tmp);
        assertTrue(root.pluginJars().isEmpty(), "no plugins folder yet");
        Files.createDirectories(root.plugins().resolve("GrimAC"));
        Files.writeString(root.plugins().resolve("b.jar"), "");
        Files.writeString(root.plugins().resolve("a.jar"), "");
        Files.writeString(root.plugins().resolve("notes.txt"), "");

        assertEquals(List.of(root.plugins().resolve("a.jar"), root.plugins().resolve("b.jar")), root.pluginJars());
    }

    @Test
    void thePreviousConsoleIsPackedAwayLikeAServerLog() throws IOException {
        Path logs = this.tmp.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("latest.log"), "last session\n");

        try (ServerRoot root = new ServerRoot(this.tmp)) {
            root.log("new session");
        }

        String latest = Files.readString(logs.resolve("latest.log"));
        assertTrue(latest.endsWith("] new session" + System.lineSeparator()), latest);
        List<Path> archives;
        try (Stream<Path> files = Files.list(logs)) {
            archives = files.filter(p -> p.getFileName().toString().endsWith(".log.gz")).toList();
        }
        assertEquals(1, archives.size());
        assertTrue(archives.get(0).getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}-1\\.log\\.gz"));
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archives.get(0)))) {
            assertEquals("last session\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void recordingsGetTheirOwnFolder() throws IOException {
        ServerRoot root = new ServerRoot(this.tmp);
        Path file = root.newRecording("mc.example.net:25565");
        assertEquals(this.tmp.resolve("recordings"), file.getParent());
        assertTrue(file.getFileName().toString().endsWith("_mc.example.net_25565.sgsc"));
    }
}
