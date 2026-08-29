package com.mentalfrostbyte.jello.util.game;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import net.minecraft.client.Minecraft;

/**
 * Who gets to name the game window.
 *
 * <p>The game rebuilds its own title whenever it feels the need to - joining a world, changing dimension -
 * so a client that simply sets the title once loses it again shortly after. Instead, whoever wants the
 * title registers here, and the game asks this class while building the title. Nobody fights over it.</p>
 *
 * <p>With no provider registered this returns null and the game's own title stands.</p>
 */
public final class WindowTitle {

    private static volatile @Nullable Supplier<String> provider;

    private WindowTitle() {
    }

    /** Takes over the window title. Call {@link #refresh()} to make it show immediately. */
    public static void provide(final Supplier<String> titleSupplier) {
        provider = titleSupplier;
    }

    /** Hands the title back to the game. */
    public static void clear() {
        provider = null;
    }

    /** The title the client wants, or null when it does not want one. */
    public static @Nullable String get() {
        Supplier<String> current = provider;
        return current == null ? null : current.get();
    }

    /** Rebuilds and applies the window title now. */
    public static void refresh() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.updateTitle();
        }
    }
}
