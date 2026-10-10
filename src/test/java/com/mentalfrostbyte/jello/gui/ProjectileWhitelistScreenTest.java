package com.mentalfrostbyte.jello.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProjectileWhitelistScreenTest {
    @Test void narrowGuiKeepsBothDropTargetsAndDoneButtonInsideTheViewport() {
        for (int width : new int[]{240, 320, 400}) {
            ProjectileWhitelistScreen.Layout layout = ProjectileWhitelistScreen.layout(width, 240);
            assertEquals(layout.catalogue().x(), layout.selected().x());
            assertTrue(layout.catalogue().y() + layout.catalogue().h() < layout.selected().y());
            for (ProjectileWhitelistScreen.Box box : new ProjectileWhitelistScreen.Box[]{layout.catalogue(), layout.selected(), layout.done()}) {
                assertTrue(box.x() >= 0 && box.y() >= 0 && box.x() + box.w() <= width && box.y() + box.h() <= 240);
            }
            assertTrue(layout.catalogue().viewHeight() >= 30);
            assertTrue(layout.selected().viewHeight() >= 30);
        }
    }

    @Test void wideGuiKeepsListsBesideOneAnotherAndSeparatesTheirHitRegions() {
        ProjectileWhitelistScreen.Layout layout = ProjectileWhitelistScreen.layout(854, 480);
        assertEquals(layout.catalogue().y(), layout.selected().y());
        assertTrue(layout.catalogue().x() + layout.catalogue().w() < layout.selected().x());
        assertFalse(layout.catalogue().contains(layout.selected().x(), layout.selected().y()));
        assertFalse(layout.selected().contains(layout.catalogue().x(), layout.catalogue().y()));
        assertTrue(layout.selected().contains(layout.selected().x() + 1, layout.selected().viewTop() + 1));
    }
}
