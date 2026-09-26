package com.mentalfrostbyte.jello.selfcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mentalfrostbyte.jello.selfcheck.api.SelfCheckEngineFactory;
import com.mentalfrostbyte.jello.selfcheck.engine.probe.IsolationProbe;
import com.mentalfrostbyte.jello.selfcheck.host.EngineLoader;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineLoaderTest {

    private static final String SERVICE = "META-INF/services/" + SelfCheckEngineFactory.class.getName();

    @TempDir
    Path tmp;

    private final List<String> problems = new ArrayList<>();
    private final ClassLoader parent = EngineLoaderTest.class.getClassLoader();

    @Test
    void eachConnectionGetsItsOwnCopyOfABuiltInEngine() throws Exception {
        List<EngineLoader.Loaded> first = EngineLoader.builtIn(List.of(IsolationProbe.class.getName()), this.parent, this.problems::add);
        List<EngineLoader.Loaded> second = EngineLoader.builtIn(List.of(IsolationProbe.class.getName()), this.parent, this.problems::add);

        assertTrue(this.problems.isEmpty(), this.problems.toString());
        Class<?> a = first.get(0).factory().getClass();
        Class<?> b = second.get(0).factory().getClass();
        assertNotSame(a, b);
        assertNotSame(IsolationProbe.class, a, "not the client's own copy either");
        assertEquals(1, a.getField("instances").getInt(null), "statics do not carry over between connections");
        assertEquals(1, b.getField("instances").getInt(null));
        assertEquals("Probe1", second.get(0).factory().id());
    }

    @Test
    void theEngineApiItselfIsShared() {
        EngineLoader.Loaded loaded = EngineLoader.builtIn(List.of(IsolationProbe.class.getName()), this.parent, this.problems::add).get(0);
        assertInstanceOf(SelfCheckEngineFactory.class, loaded.factory(), "the factory implements the client's interface, not a copy");
        ClassLoader loader = loaded.loader();
        assertTrue(loader instanceof EngineLoader.Isolating);
        assertDoesNotIsolate(loader, SelfCheckEngineFactory.class);
        assertDoesNotIsolate(loader, ByteBuf.class);
    }

    private static void assertDoesNotIsolate(final ClassLoader loader, final Class<?> shared) {
        try {
            assertSame(shared, Class.forName(shared.getName(), false, loader));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aMissingBuiltInIsReportedNotFatal() {
        List<EngineLoader.Loaded> loaded = EngineLoader.builtIn(List.of("com.mentalfrostbyte.jello.selfcheck.engine.DoesNotExist"), this.parent, this.problems::add);
        assertTrue(loaded.isEmpty());
        assertEquals(1, this.problems.size());
    }

    @Test
    void aPluginJarIsLoadedFromItsOwnServiceFile() throws Exception {
        Path jar = this.pluginJar("plugin.TestPlugin", """
                package plugin;
                import com.mentalfrostbyte.jello.selfcheck.api.*;
                public final class TestPlugin implements SelfCheckEngineFactory {
                    public String id() { return "TestPlugin"; }
                    public boolean supports(int protocol) { return protocol == 47; }
                    public SelfCheckEngine create(EngineContext context) {
                        return new SelfCheckEngine() {
                            public void onClientbound(WirePacket packet) { }
                            public void onServerbound(WirePacket packet) { }
                        };
                    }
                }
                """);

        List<EngineLoader.Loaded> loaded = EngineLoader.plugins(List.of(jar), this.parent, this.problems::add);

        assertTrue(this.problems.isEmpty(), this.problems.toString());
        assertEquals(1, loaded.size());
        SelfCheckEngineFactory factory = loaded.get(0).factory();
        assertEquals("TestPlugin", factory.id());
        assertTrue(factory.supports(47));
        assertSame(loaded.get(0).loader(), factory.getClass().getClassLoader());
        assertInstanceOf(URLClassLoader.class, loaded.get(0).loader());
        loaded.forEach(EngineLoader.Loaded::close);
    }

    @Test
    void aPluginCannotSmuggleInAClassFromTheClient() throws IOException {
        Path jar = this.tmp.resolve("smuggler.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(SERVICE));
            out.write((IsolationProbe.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
        }

        List<EngineLoader.Loaded> loaded = EngineLoader.plugins(List.of(jar), this.parent, this.problems::add);

        assertTrue(loaded.isEmpty());
        assertTrue(this.problems.get(0).contains("outside its own loader"), this.problems.toString());
    }

    @Test
    void aJarWithoutAServiceFileIsReported() throws IOException {
        Path jar = this.tmp.resolve("empty.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("readme.txt"));
        }

        assertTrue(EngineLoader.plugins(List.of(jar), this.parent, this.problems::add).isEmpty());
        assertTrue(this.problems.get(0).contains("lists no engine"), this.problems.toString());
    }

    /** Compiles {@code source} against the engine API and packs it with a service file naming {@code className}. */
    private Path pluginJar(final String className, final String source) throws IOException, URISyntaxException {
        Path src = this.tmp.resolve("src").resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = this.tmp.resolve("classes");
        Files.createDirectories(classes);
        String classPath = location(SelfCheckEngineFactory.class) + java.io.File.pathSeparator + location(ByteBuf.class);

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int status = javac.run(null, null, null, "-d", classes.toString(), "-cp", classPath, src.toString());
        assertEquals(0, status, "the test plugin must compile");

        Path jar = this.tmp.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, (OutputStream) out);
            }
            out.putNextEntry(new JarEntry(SERVICE));
            out.write(("# test plugin\n" + className + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static String location(final Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
