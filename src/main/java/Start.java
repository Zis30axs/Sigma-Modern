import net.minecraft.client.main.Main;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
            throw new IllegalStateException(
                    "No Minecraft asset index found in: " + indexesDirectory.getAbsolutePath()
            );
        }

        File newest = Arrays.stream(indexes)
                .max(Comparator.comparingLong(File::lastModified))
                .orElseThrow();

        String name = newest.getName();
        return name.substring(0, name.length() - ".json".length());
    }
}
