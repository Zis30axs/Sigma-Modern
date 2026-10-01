package com.mentalfrostbyte.jello.gui.legacy;

import net.minecraft.resources.Identifier;

/**
 * The old client's bitmap artwork, with the pixel size each file has.
 *
 * <p>26.2 blits by texture id and source rectangle, so the size travels with the id. Every file under
 * {@code textures/gui/sigma/legacy} has a {@code .mcmeta} asking for linear filtering, which is how the old
 * client always drew them - the backgrounds are 3840 px wide and are shown much smaller.</p>
 */
public enum LegacyTexture {
    // Jello main menu
    JELLO_BACKGROUND("jello/background/background.png", 3840, 1080),
    JELLO_MIDDLE("jello/background/middle.png", 3840, 1080),
    JELLO_FOREGROUND("jello/background/foreground.png", 3840, 1080),
    JELLO_PANORAMA("jello/background/panorama5.png", 3840, 1080),
    JELLO_LOGO("jello/logo_large.png", 336, 178),
    JELLO_SHADOW("jello/shadow.png", 330, 411),
    ICON_SINGLEPLAYER("jello/icons/singleplayer.png", 256, 256),
    ICON_MULTIPLAYER("jello/icons/multiplayer.png", 256, 256),
    ICON_REALMS("jello/icons/shop.png", 256, 256),
    ICON_OPTIONS("jello/icons/options.png", 256, 256),
    ICON_ALT("jello/icons/alt.png", 256, 256),

    // Soft panel edges (Jello's floating cards)
    FLOATING_CORNER("jello/floating_corner.png", 36, 36),
    FLOATING_BORDER("jello/floating_border.png", 36, 36),
    SHADOW_TOP("jello/shadow_top.png", 1, 20),
    SHADOW_BOTTOM("jello/shadow_bottom.png", 1, 20),
    SHADOW_LEFT("jello/shadow_left.png", 20, 1),
    SHADOW_RIGHT("jello/shadow_right.png", 20, 1),
    SHADOW_CORNER_1("jello/shadow_corner.png", 20, 20),
    SHADOW_CORNER_2("jello/shadow_corner_2.png", 20, 20),
    SHADOW_CORNER_3("jello/shadow_corner_3.png", 20, 20),
    SHADOW_CORNER_4("jello/shadow_corner_4.png", 20, 20),
    LOADING_INDICATOR("jello/loading_indicator.png", 60, 60),
    JELLO_ACCOUNT("jello/account.png", 60, 60),
    JELLO_SEARCH("jello/search.png", 44, 44),
    JELLO_OPTIONS("jello/options.png", 110, 82),
    DVD("jello/dvd.png", 201, 90),

    // Jello's music panel
    MUSIC_PLAY("music/play.png", 38, 38),
    MUSIC_PAUSE("music/pause.png", 38, 38),
    MUSIC_NEXT("music/forwards.png", 46, 46),
    MUSIC_PREVIOUS("music/backwards.png", 46, 46),
    MUSIC_BAR("music/bg.png", 653, 77),

    // The bird game
    BIRD_BACKGROUND("games/bg.png", 288, 512),
    BIRD_GROUND("games/fg.png", 336, 112),
    BIRD_PIPE_TOP("games/pipe.png", 52, 320),
    BIRD_PIPE_BOTTOM("games/pipe2.png", 52, 320),
    BIRD("games/bird.png", 102, 24),

    // In-game HUD
    /** The old {@code jello_watermark@2x.png}: shown at half this size, 170x104 framebuffer pixels. */
    JELLO_WATERMARK("jello/watermark.png", 340, 208),

    // Alt manager
    ALT_ACTIVE("alt/active.png", 17, 13),
    ALT_RING("alt/cercle.png", 100, 100),
    ALT_ERROR("alt/errors.png", 17, 17),
    ALT_WELCOME("alt/img.png", 920, 684),
    ALT_SELECT("alt/select.png", 18, 47),
    ALT_SKIN("alt/skin.png", 75, 75),

    // Components
    CHECK("component/check.png", 60, 60),
    TRASHCAN("component/trashcan.png", 22, 26),
    WAYPOINT("component/waypoint.png", 32, 46),
    SCROLLBAR_TOP("component/verticalscrollbartop.png", 11, 5),
    SCROLLBAR_BOTTOM("component/verticalscrollbarbottom.png", 11, 5),

    // Classic main menu
    CLASSIC_BACKGROUND("classic/mainmenubackground.png", 1920, 1080),
    CLASSIC_BIG("classic/big.png", 600, 195),
    CLASSIC_SINGLEPLAYER("classic/singleplayer.png", 200, 200),
    CLASSIC_MULTIPLAYER("classic/multiplayer.png", 200, 200),
    CLASSIC_OPTIONS("classic/options.png", 200, 200),
    CLASSIC_LANGUAGE("classic/language.png", 200, 200),
    CLASSIC_ACCOUNTS("classic/accounts.png", 200, 200),
    CLASSIC_SWITCH("classic/switch.png", 200, 200),
    CLASSIC_EXIT("classic/exit.png", 200, 200),

    // Classic ClickGUI
    GUI_CHECKBOX("classic/gui_checkbox.png", 800, 18),
    GUI_COMBAT("classic/gui_combat.png", 128, 128),
    GUI_COMBAT_HOVER("classic/gui_combat2.png", 128, 128),
    GUI_MOVEMENT("classic/gui_movement.png", 128, 128),
    GUI_MOVEMENT_HOVER("classic/gui_movement2.png", 128, 128),
    GUI_WORLD("classic/gui_world.png", 128, 128),
    GUI_WORLD_HOVER("classic/gui_world2.png", 128, 128),
    GUI_PLAYER("classic/gui_player.png", 128, 128),
    GUI_PLAYER_HOVER("classic/gui_player2.png", 128, 128),
    GUI_VISUALS("classic/gui_visuals.png", 128, 128),
    GUI_VISUALS_HOVER("classic/gui_visuals2.png", 128, 128),
    GUI_OTHERS("classic/gui_others.png", 128, 128),
    GUI_OTHERS_HOVER("classic/gui_others2.png", 128, 128),
    GUI_XMARK("classic/gui_xmark.png", 128, 128),
    GUI_XMARK_HOVER("classic/gui_xmark2.png", 128, 128),
    GUI_GEAR("classic/gui_gear.png", 128, 128),
    GUI_GEAR_HOVER("classic/gui_gear2.png", 128, 128),
    GUI_UPARROW("classic/gui_uparrow.png", 128, 128),
    GUI_DOWNARROW("classic/gui_downarrow.png", 128, 128);

    public final Identifier id;
    public final int width;
    public final int height;

    LegacyTexture(final String path, final int width, final int height) {
        this.id = Identifier.withDefaultNamespace("textures/gui/sigma/legacy/" + path);
        this.width = width;
        this.height = height;
    }
}
