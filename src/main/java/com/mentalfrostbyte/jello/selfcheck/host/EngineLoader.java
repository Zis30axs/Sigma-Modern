package com.mentalfrostbyte.jello.selfcheck.host;

import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds the engines for one connection and gives each a class loader of its own.
 *
 * <p>Grim decides a lot from the server's version once, in {@code static final} fields, and keeps its state in
 * singletons; PacketEvents does the same. A connection to a 1.8 server after a 26.2 one would inherit the wrong
 * answers. So the built-in engines are loaded afresh for every connection by an {@link Isolating} loader that
 * defines its own copy of those libraries from the client's class path, and dropped when the connection ends.
 * Everything else - the engine API, netty, the JDK, and the SQLite driver whose native library can only be
 * loaded once per process - comes from the client's loader and is shared.</p>
 *
 * <p>Jars in {@code plugins/} get one {@link URLClassLoader} each per connection. Only the factories a jar
 * lists itself are used, so the client's own built-in engine can never be picked up through a plugin's loader.</p>
 */
public final class EngineLoader {

    /** Engines that ship with the client, by factory class name. They live under {@code selfcheck.engine}. */
    public static final String GRIM = "com.mentalfrostbyte.jello.selfcheck.engine.grim.GrimEngineFactory";
    /** A diagnostic engine that only measures the transaction channel; switched on with a debug flag. */
    public static final String DIAGNOSTICS = "com.mentalfrostbyte.jello.selfcheck.engine.diag.DiagnosticsEngineFactory";

    /** Packages a built-in engine gets its own copy of. */
    static final List<String> ISOLATED = List.of(
            "ac.grim.grimac.",
            "com.github.retrooper.packetevents.",
            "io.github.retrooper.packetevents.",
            "net.kyori.",
            "github.scarsz.configuralize.",
            "org.incendo.cloud.",
            "com.mentalfrostbyte.jello.selfcheck.engine."
    );

    private static final String SERVICE = "META-INF/services/" + SelfCheckEngineFactory.class.getName();

    private EngineLoader() {
    }

    /** A factory and the loader it came from, which is closed with it. */
    public record Loaded(SelfCheckEngineFactory factory, ClassLoader loader, String origin) implements AutoCloseable {

        @Override
        public void close() {
            if (this.loader instanceof URLClassLoader closeable) {
                try {
                    closeable.close();
                } catch (IOException ignored) {
                    // the jar handle is released when the loader is collected anyway
                }
            }
        }
    }

    /**
     * The built-in engines named in {@code classNames}, all in one fresh isolating loader. A name that is not on the
     * class path is reported to {@code problems} and skipped.
     */
    public static List<Loaded> builtIn(final List<String> classNames, final ClassLoader parent, final Consumer<String> problems) {
        Isolating loader = new Isolating(parent);
        List<Loaded> loaded = new ArrayList<>();
        for (String name : classNames) {
            try {
                loaded.add(new Loaded(instantiate(loader, name), loader, "built-in"));
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                problems.accept("built-in engine " + name + " could not be loaded: " + e);
            }
        }
        return loaded;
    }

    /** The engines listed by each jar in {@code jars}, each jar in its own loader. */
    public static List<Loaded> plugins(final List<Path> jars, final ClassLoader parent, final Consumer<String> problems) {
        List<Loaded> loaded = new ArrayList<>();
        for (Path jar : jars) {
            List<String> names;
            try {
                names = listedFactories(jar);
            } catch (IOException e) {
                problems.accept(jar.getFileName() + " could not be read: " + e.getMessage());
                continue;
            }
            if (names.isEmpty()) {
                problems.accept(jar.getFileName() + " lists no engine in " + SERVICE);
                continue;
            }

            URLClassLoader loader;
            try {
                loader = new URLClassLoader("selfcheck-plugin-" + jar.getFileName(), new URL[]{jar.toUri().toURL()}, parent);
            } catch (IOException e) {
                problems.accept(jar.getFileName() + " could not be opened: " + e.getMessage());
                continue;
            }
            int before = loaded.size();
            for (String name : names) {
                try {
                    loaded.add(new Loaded(instantiate(loader, name), loader, jar.getFileName().toString()));
                } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                    problems.accept(jar.getFileName() + ": " + name + " could not be loaded: " + e);
                }
            }
            if (loaded.size() == before) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                    // nothing was loaded from it
                }
            }
        }
        return loaded;
    }

    private static SelfCheckEngineFactory instantiate(final ClassLoader loader, final String name) throws ReflectiveOperationException {
        Class<?> type = Class.forName(name, true, loader);
        if (type.getClassLoader() != loader) {
            throw new ClassNotFoundException(name + " resolved outside its own loader (" + type.getClassLoader() + ")");
        }
        return type.asSubclass(SelfCheckEngineFactory.class).getDeclaredConstructor().newInstance();
    }

    /** The class names in the jar's own service file, without comments or blank lines. */
    static List<String> listedFactories(final Path jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(SERVICE);
            if (entry == null) {
                return names;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int comment = line.indexOf('#');
                    String name = (comment >= 0 ? line.substring(0, comment) : line).trim();
                    if (!name.isEmpty() && !names.contains(name)) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    /**
     * Defines its own copy of every class under {@link #ISOLATED}, reading the bytes from the parent's class path;
     * everything else is the parent's. Resources are the parent's too - they are the same files either way.
     */
    public static final class Isolating extends ClassLoader {

        static {
            registerAsParallelCapable();
        }

        public Isolating(final ClassLoader parent) {
            super("selfcheck-engine", parent);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (!isolated(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (this.getClassLoadingLock(name)) {
                Class<?> type = this.findLoadedClass(name);
                if (type == null) {
                    type = this.defineFromParent(name);
                }
                if (resolve) {
                    this.resolveClass(type);
                }
                return type;
            }
        }

        private Class<?> defineFromParent(final String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = this.getParent().getResourceAsStream(path)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return this.defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        @Override
        public URL getResource(final String name) {
            return this.getParent().getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(final String name) throws IOException {
            return this.getParent().getResources(name);
        }

        @Override
        public InputStream getResourceAsStream(final String name) {
            return this.getParent().getResourceAsStream(name);
        }

        static boolean isolated(final String name) {
            for (String prefix : ISOLATED) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
