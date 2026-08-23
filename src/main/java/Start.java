import net.minecraft.client.main.Main;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Start {

    private static final String VERSION = "26.2";

    private Start() {
    }

    public static void main(String[] args) {
        File rootDirectory = new File(System.getProperty("user.dir"));
        File runDirectory = new File(rootDirectory, "run");

        if (!runDirectory.exists() && !runDirectory.mkdirs()) {
            throw new IllegalStateException(
                    "Could not create run directory: " + runDirectory.getAbsolutePath()
            );
        }

        File assetsDirectory = resolveAssetsDirectory(runDirectory);
        String assetIndex = findAssetIndex(assetsDirectory);
        ensureVanillaAssets(assetsDirectory, assetIndex);

        System.out.println("Minecraft " + VERSION);
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("Game directory: " + runDirectory.getAbsolutePath());
        System.out.println("Assets directory: " + assetsDirectory.getAbsolutePath());
        System.out.println("Asset index: " + assetIndex);

        if (Runtime.version().feature() != 25) {
            System.err.println(
                    "WARNING: Minecraft 26.2 development environment "
                            + "is expected to use JDK 25, but current Java is "
                            + Runtime.version()
            );
        }

        List<String> launchArgs = new ArrayList<>();
        launchArgs.add("--gameDir");
        launchArgs.add(runDirectory.getAbsolutePath());
        launchArgs.add("--version");
        launchArgs.add(VERSION);
        launchArgs.add("--assetsDir");
        launchArgs.add(assetsDirectory.getAbsolutePath());
        launchArgs.add("--assetIndex");
        launchArgs.add(assetIndex);
        launchArgs.add("--accessToken");
        launchArgs.add("0");
        launchArgs.addAll(Arrays.asList(args));

        Main.main(launchArgs.toArray(String[]::new));
    }

    private static File resolveAssetsDirectory(File runDirectory) {
        List<File> candidates = new ArrayList<>();
        candidates.add(new File(runDirectory, "assets"));

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            candidates.add(new File(new File(appData, ".minecraft"), "assets"));
        }

        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isEmpty()) {
            candidates.add(new File(new File(userHome, ".minecraft"), "assets"));
            candidates.add(new File(new File(new File(userHome, "AppData"), "Roaming\\.minecraft"), "assets"));
        }

        for (File candidate : candidates) {
            if (new File(candidate, "indexes").isDirectory()) {
                return candidate;
            }
        }

        StringBuilder checked = new StringBuilder();
        for (File candidate : candidates) {
            if (checked.length() > 0) {
                checked.append(System.lineSeparator());
            }
            checked.append(" - ").append(candidate.getAbsolutePath());
        }

        throw new IllegalStateException(
                "Minecraft assets directory was not found. Checked:" +
                        System.lineSeparator() + checked
        );
    }

    private static String findAssetIndex(File assetsDirectory) {
        File indexesDirectory = new File(assetsDirectory, "indexes");
        File[] indexes = indexesDirectory.listFiles(
                (directory, name) -> name.endsWith(".json")
        );

        if (indexes == null || indexes.length == 0) {
            // MODIFIED for porting: no local index - bootstrap the one matching this Minecraft version
            System.out.println("No asset index found locally; downloading asset index " + FALLBACK_INDEX_ID + " ...");
            final File indexesDir = new File(assetsDirectory, "indexes");
            if (!indexesDir.isDirectory() && !indexesDir.mkdirs()) {
                throw new IllegalStateException("Could not create: " + indexesDir.getAbsolutePath());
            }
            downloadToFile(FALLBACK_INDEX_URL, new File(indexesDir, FALLBACK_INDEX_ID + ".json"));
            return FALLBACK_INDEX_ID;
        }

        File newest = Arrays.stream(indexes)
                .max(Comparator.comparingLong(File::lastModified))
                .orElseThrow();

        String name = newest.getName();
        return name.substring(0, name.length() - ".json".length());
    }
    // MODIFIED for porting: bootstrap constants - keep in sync when the target Minecraft version changes
    private static final String FALLBACK_INDEX_ID = "32";
    private static final String FALLBACK_INDEX_URL =
            "https://piston-meta.mojang.com/v1/packages/773791767c043b4f9493b50c54257619cecb08a4/32.json";

    /**
     * MODIFIED for porting: makes a fresh checkout self-contained by prefetching every object referenced by the asset
     * index (fonts, translations, sounds, programmer art) before handing control to the game.
     */
    private static void ensureVanillaAssets(File assetsDirectory, String assetIndexName) {
        final File indexFile = new File(new File(assetsDirectory, "indexes"), assetIndexName + ".json");
        if (!indexFile.isFile()) {
            throw new IllegalStateException("Asset index not found: " + indexFile.getAbsolutePath());
        }

        final String json;
        try {
            json = Files.readString(indexFile.toPath());
        } catch (final java.io.IOException e) {
            throw new UncheckedIOException(e);
        }

        final Matcher matcher = Pattern.compile("\"hash\":\\s*\"([0-9a-f]{40})\",\\s*\"size\":\\s*(\\d+)").matcher(json);
        int total = 0;
        long totalBytes = 0;
        final List<String> missing = new ArrayList<>();
        while (matcher.find()) {
            total++;
            final String hash = matcher.group(1);
            final long size = Long.parseLong(matcher.group(2));
            totalBytes += size;
            final File file = objectFile(assetsDirectory, hash);
            if (!file.isFile() || file.length() != size) {
                missing.add(hash);
            }
        }

        if (missing.isEmpty()) {
            System.out.println("Vanilla assets complete (" + total + " objects)");
            return;
        }

        System.out.println("Downloading " + missing.size() + "/" + total + " vanilla assets ("
                + totalBytes / (1024L * 1024L) + " MB) ...");

        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (final String hash : missing) {
                pool.execute(() -> downloadToFile(
                        "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash,
                        objectFile(assetsDirectory, hash)));
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(30, TimeUnit.MINUTES);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("Vanilla assets ready.");
    }

    private static File objectFile(final File assetsDirectory, final String hash) {
        return new File(new File(assetsDirectory, "objects"), hash.substring(0, 2) + "/" + hash);
    }

    private static void downloadToFile(final String url, final File target) {
        try {
            target.getParentFile().mkdirs();
            final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
