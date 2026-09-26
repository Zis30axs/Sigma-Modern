package com.mentalfrostbyte.jello.selfcheck.host;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * The self-check's "server directory", laid out the way a server's is so that a server's plugin folder can be
 * copied straight in:
 *
 * <pre>
 * plugins/&lt;engine&gt;/      an engine's data folder (plugins/GrimAC/config.yml, punishments.yml, ...)
 * plugins/*.jar             engines added by the player
 * logs/latest.log           the console: every flag, alert, would-be punishment and engine failure
 * recordings/               wire recordings for replay tests
 * </pre>
 *
 * <p>Like a server, the previous {@code latest.log} is packed into {@code logs/yyyy-MM-dd-N.log.gz} the first time
 * anything is written in a new game session.</p>
 */
public final class ServerRoot implements AutoCloseable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private final Path root;
    private @Nullable BufferedWriter console;
    private boolean consoleFailed;

    public ServerRoot(final Path root) {
        this.root = root;
    }

    public Path root() {
        return this.root;
    }

    public Path plugins() {
        return this.root.resolve("plugins");
    }

    /** {@code plugins/<id>/}, created if missing. Characters that cannot appear in a folder name become {@code _}. */
    public Path pluginFolder(final String id) throws IOException {
        Path folder = this.plugins().resolve(folderName(id));
        Files.createDirectories(folder);
        return folder;
    }

    /** The jars in {@code plugins/}, by name. */
    public List<Path> pluginJars() throws IOException {
        List<Path> jars = new ArrayList<>();
        if (!Files.isDirectory(this.plugins())) {
            return jars;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.plugins(), "*.jar")) {
            for (Path jar : stream) {
                if (Files.isRegularFile(jar)) {
                    jars.add(jar);
                }
            }
        }
        jars.sort(null);
        return jars;
    }

    /** A new file under {@code recordings/} named after the time and {@code label}. */
    public Path newRecording(final String label) throws IOException {
        Path folder = this.root.resolve("recordings");
        Files.createDirectories(folder);
        return folder.resolve(LocalDateTime.now().format(STAMP) + "_" + folderName(label) + ".sgsc");
    }

    /**
     * Appends {@code line} to {@code logs/latest.log} with a time stamp. Logging is never allowed to fail the
     * caller: if the file cannot be written, the console goes quiet for the rest of the game session.
     */
    public synchronized void log(final String line) {
        if (this.consoleFailed) {
            return;
        }
        try {
            if (this.console == null) {
                this.console = this.openConsole();
            }
            this.console.write("[" + LocalDateTime.now().format(TIME) + "] " + line);
            this.console.newLine();
            this.console.flush();
        } catch (IOException e) {
            this.consoleFailed = true;
        }
    }

    @Override
    public synchronized void close() {
        if (this.console != null) {
            try {
                this.console.close();
            } catch (IOException ignored) {
                // nothing left to tell anyone
            }
            this.console = null;
        }
    }

    private BufferedWriter openConsole() throws IOException {
        Path logs = this.root.resolve("logs");
        Files.createDirectories(logs);
        Path latest = logs.resolve("latest.log");
        if (Files.isRegularFile(latest) && Files.size(latest) > 0) {
            archive(logs, latest);
        }
        return Files.newBufferedWriter(latest, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Packs {@code latest} into {@code yyyy-MM-dd-N.log.gz}, dated by when it was last written. */
    private static void archive(final Path logs, final Path latest) throws IOException {
        FileTime modified = Files.getLastModifiedTime(latest);
        String day = LocalDate.ofInstant(modified.toInstant(), ZoneId.systemDefault()).toString();
        Path target;
        int n = 1;
        do {
            target = logs.resolve(day + "-" + n++ + ".log.gz");
        } while (Files.exists(target));

        try (InputStream in = Files.newInputStream(latest);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            in.transferTo(out);
        }
    }

    static String folderName(final String id) {
        String cleaned = id.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.') ? "_" : cleaned;
    }
}
