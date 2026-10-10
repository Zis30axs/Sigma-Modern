package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.joml.Matrix3x2f;
import org.junit.jupiter.api.Test;

class ModernHudBlurTest {
    @Test
    void worldSamplingTracksNestedGuiPoseAndFramebufferScale() {
        Matrix3x2f pose = new Matrix3x2f().translation(200, 80).scale(1.5F, 2);
        var element = ModernHudBlur.extract(pose, null, -3, 0, 90, 15, 3, 0.8F, 641, 361);
        assertNotNull(element);
        List<ModernHudBlur.Vertex> vertices = vertices(element, 2F / 1281, 2F / 721, 2);
        for (var vertex : vertices) {
            assertEquals(vertex.x() * 2F / 1281, vertex.u(), 0.000001);
            assertEquals(1 - vertex.y() * 2F / 721, vertex.v(), 0.000001);
        }
        assertEquals(263, vertices.getFirst().x(), 0.001);
        assertEquals(95, vertices.getFirst().y(), 0.001);
        // A ceil-rounded GUI width must not replace the precise physical framebuffer / GUI-scale ratio.
        assertNotEquals(vertices.getFirst().x() / 641, vertices.getFirst().u(), 0.000001);
    }

    @Test
    void extractedGeometryDoesNotChangeWhenPoseStackIsReused() {
        Matrix3x2f pose = new Matrix3x2f().translation(30, 40);
        var element = ModernHudBlur.extract(pose, null, 0, 0, 80, 20, 3, 1, 800, 600);
        assertNotNull(element);
        var before = vertices(element, 1F / 800, 1F / 600, 1);
        pose.identity().translate(500, 400);
        assertEquals(before, vertices(element, 1F / 800, 1F / 600, 1));
    }

    @Test
    void scissorIsIntersectedWithViewportAndOffscreenRequestsDoNoWork() {
        ScreenRectangle clip = new ScreenRectangle(40, 30, 20, 15);
        var element = ModernHudBlur.extract(new Matrix3x2f().translation(35, 25), clip, 0, 0, 100, 50, 4, 1, 800, 600);
        assertNotNull(element);
        assertEquals(clip, element.bounds());
        assertEquals(clip, element.scissorArea());
        assertNull(ModernHudBlur.extract(new Matrix3x2f().translation(900, 0), null, 0, 0, 80, 20, 4, 1, 800, 600));
        assertNull(ModernHudBlur.extract(new Matrix3x2f(), null, 0, 0, 80, 20, 4, 0, 800, 600));
    }

    @Test
    void roundedCornersHaveTransparentFringeWithoutSquareCorners() {
        var element = ModernHudBlur.extract(new Matrix3x2f(), null, 10, 20, 80, 30, 6, 0.4F, 800, 600);
        assertNotNull(element);
        var vertices = vertices(element, 1F / 800, 1F / 600, 2);
        assertTrue(vertices.stream().anyMatch(v -> v.alpha() == 0));
        assertTrue(vertices.stream().anyMatch(v -> v.alpha() == 0.4F));
        assertTrue(vertices.stream().allMatch(v -> v.alpha() >= 0 && v.alpha() <= 0.4F));
        assertTrue(vertices.stream().noneMatch(v -> v.x() == 10 && v.y() == 20));
        // Every arc includes both endpoints: the four connecting edges stay exactly horizontal or vertical.
        for (int end : new int[]{8, 17, 26, 35}) {
            var a = vertices.get(end * 8 + 5);
            var b = vertices.get(end * 8 + 6);
            assertTrue(Math.abs(a.x() - b.x()) < 0.0001 || Math.abs(a.y() - b.y()) < 0.0001);
        }
        for (int i = 0; i < vertices.size(); i += 4) {
            var a = vertices.get(i);
            var b = vertices.get(i + 1);
            var c = vertices.get(i + 2);
            double signedArea = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
            assertTrue(signedArea <= 0.0001, "Quad winding must match vanilla GUI blits");
        }
    }

    @Test
    void gpuSnapshotIsBoundedAndPreservesAspectAcrossResolutions() {
        assertEquals(new ModernHudBlur.Size(960, 540), ModernHudBlur.targetSize(1920, 1080));
        assertEquals(new ModernHudBlur.Size(1280, 720), ModernHudBlur.targetSize(3840, 2160));
        assertEquals(new ModernHudBlur.Size(360, 720), ModernHudBlur.targetSize(1080, 2160));
        assertEquals(new ModernHudBlur.Size(1, 1), ModernHudBlur.targetSize(1, 1));
        assertEquals(new ModernHudBlur.Size(0, 0), ModernHudBlur.targetSize(0, 600));
    }

    @Test
    void dedicatedEffectUsesTwoPositiveRadiusPassesIndependentOfMenuOptions() throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/minecraft/post_effect/sigma_hud_blur.json")) {
            assertNotNull(input);
            var effect = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            var passes = effect.getAsJsonArray("passes");
            assertEquals(2, passes.size());
            assertEquals("swap", passes.get(0).getAsJsonObject().get("output").getAsString());
            assertEquals("minecraft:main", passes.get(1).getAsJsonObject().get("output").getAsString());
            for (var pass : passes) {
                var uniforms = pass.getAsJsonObject().getAsJsonObject("uniforms").getAsJsonArray("BlurConfig");
                assertTrue(uniforms.get(1).getAsJsonObject().get("value").getAsFloat() >= 0.5F);
            }
        }
    }

    private static List<ModernHudBlur.Vertex> vertices(ModernHudBlur.Element element, float uScale, float vScale, int guiScale) {
        List<ModernHudBlur.Vertex> result = new ArrayList<>();
        ModernHudBlur.vertices(element.request(), uScale, vScale, guiScale,
                (x, y, u, v, alpha) -> result.add(new ModernHudBlur.Vertex(x, y, u, v, alpha)));
        return result;
    }
}
