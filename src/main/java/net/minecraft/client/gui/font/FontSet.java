package net.minecraft.client.gui.font;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.viaversion.viafabricplus.features.font.BuiltinEmptyGlyph1_12_2; // MODIFIED for porting: ViaFabricPlus
import com.viaversion.viafabricplus.features.font.RenderableGlyphDiff; // MODIFIED for porting: ViaFabricPlus
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator; // MODIFIED for porting: ViaFabricPlus
import com.viaversion.viafabricplus.settings.impl.DebugSettings; // MODIFIED for porting: ViaFabricPlus
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion; // MODIFIED for porting: ViaFabricPlus
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft; // MODIFIED for porting: ViaFabricPlus features/font MixinFontSet
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class FontSet implements AutoCloseable {
    private static final float LARGE_FORWARD_ADVANCE = 32.0F;
    private static final BakedGlyph INVISIBLE_MISSING_GLYPH = new BakedGlyph() {
        @Override
        public GlyphInfo info() {
            return SpecialGlyphs.MISSING;
        }

        @Override
        public TextRenderable.@Nullable Styled createGlyph(
            final float x, final float y, final int color, final int shadowColor, final Style style, final float boldOffset, final float shadowOffset
        ) {
            return null;
        }
    };
    private final GlyphStitcher stitcher;
    private final UnbakedGlyph.Stitcher wrappedStitcher = new UnbakedGlyph.Stitcher() {
        @Override
        public BakedGlyph stitch(final GlyphInfo glyphInfo, final GlyphBitmap glyphBitmap) {
            return Objects.requireNonNullElse(FontSet.this.stitcher.stitch(glyphInfo, glyphBitmap), FontSet.this.missingGlyph);
        }

        @Override
        public BakedGlyph getMissing() {
            return FontSet.this.missingGlyph;
        }
    };
    private List<GlyphProvider.Conditional> allProviders = List.of();
    private List<GlyphProvider> activeProviders = List.of();
    private final Int2ObjectMap<IntList> glyphsByWidth = new Int2ObjectOpenHashMap<>();
    public final CodepointMap<FontSet.SelectedGlyphs> glyphCache = new CodepointMap<>(FontSet.SelectedGlyphs[]::new, FontSet.SelectedGlyphs[][]::new);
    private final IntFunction<FontSet.SelectedGlyphs> glyphGetter = this::computeGlyphInfo;
    private BakedGlyph missingGlyph = INVISIBLE_MISSING_GLYPH;
    private final Supplier<BakedGlyph> missingGlyphGetter = () -> this.missingGlyph;
    private final FontSet.SelectedGlyphs missingSelectedGlyphs = new FontSet.SelectedGlyphs(this.missingGlyphGetter, this.missingGlyphGetter);
    private @Nullable EffectGlyph whiteGlyph;
    private final GlyphSource anyGlyphs = new FontSet.Source(false);
    private final GlyphSource nonFishyGlyphs = new FontSet.Source(true);
    // MODIFIED for porting: was VFP features/font MixinFontSet @Unique state (viaFabricPlus$blankBakedGlyph1_12_2,
    // viaFabricPlus$blankBakedGlyphPair1_12_2 and viaFabricPlus$obfuscatedLookup). <= 1.12.2 has no missing-glyph
    // box, so unknown codepoints are drawn with the 1.12.2 blank glyph instead; the flag suppresses the version
    // glyph filter while obfuscated text picks a random glyph.
    private BakedGlyph vfpBlankBakedGlyph1_12_2;
    private FontSet.SelectedGlyphs vfpBlankBakedGlyphPair1_12_2;
    private boolean vfpObfuscatedLookup;

    public FontSet(final GlyphStitcher stitcher) {
        this.stitcher = stitcher;
    }

    public void reload(final List<GlyphProvider.Conditional> providers, final Set<FontOption> options) {
        this.allProviders = providers;
        this.reload(options);
    }

    public void reload(final Set<FontOption> options) {
        this.activeProviders = List.of();
        this.resetTextures();
        this.activeProviders = this.selectProviders(this.allProviders, options);
    }

    private void resetTextures() {
        this.stitcher.reset();
        this.glyphCache.clear();
        this.glyphsByWidth.clear();
        // MODIFIED for porting: was VFP features/font MixinFontSet#bakeBlankGlyph1_12_2 (@Inject on the first
        // SpecialGlyphs#bake call in resetTextures). Ungated - only the gated lookups below read the baked pair.
        this.vfpBlankBakedGlyph1_12_2 = BuiltinEmptyGlyph1_12_2.INSTANCE.bake(this.stitcher);
        this.vfpBlankBakedGlyphPair1_12_2 = new FontSet.SelectedGlyphs(() -> this.vfpBlankBakedGlyph1_12_2, () -> this.vfpBlankBakedGlyph1_12_2);
        this.missingGlyph = Objects.requireNonNull(SpecialGlyphs.MISSING.bake(this.stitcher));
        this.whiteGlyph = SpecialGlyphs.WHITE.bake(this.stitcher);
    }

    private List<GlyphProvider> selectProviders(final List<GlyphProvider.Conditional> providers, final Set<FontOption> options) {
        IntSet supportedGlyphs = new IntOpenHashSet();
        List<GlyphProvider> selectedProviders = new ArrayList<>();

        for (GlyphProvider.Conditional conditionalProvider : providers) {
            if (conditionalProvider.filter().apply(options)) {
                selectedProviders.add(conditionalProvider.provider());
                supportedGlyphs.addAll(conditionalProvider.provider().getSupportedGlyphs());
            }
        }

        Set<GlyphProvider> usedProviders = Sets.newHashSet();
        supportedGlyphs.forEach((int codepoint) -> {
            for (GlyphProvider provider : selectedProviders) {
                UnbakedGlyph glyph = provider.getGlyph(codepoint);
                if (glyph != null) {
                    usedProviders.add(provider);
                    if (glyph.info() != SpecialGlyphs.MISSING) {
                        this.glyphsByWidth.computeIfAbsent(Mth.ceil(glyph.info().getAdvance(false)), w -> new IntArrayList()).add(codepoint);
                    }
                    break;
                }
            }
        });
        return selectedProviders.stream().filter(usedProviders::contains).toList();
    }

    @Override
    public void close() {
        this.stitcher.close();
    }

    private static boolean hasFishyAdvance(final GlyphInfo glyph) {
        float advance = glyph.getAdvance(false);
        if (!(advance < 0.0F) && !(advance > 32.0F)) {
            float boldAdvance = glyph.getAdvance(true);
            return boldAdvance < 0.0F || boldAdvance > 32.0F;
        } else {
            return true;
        }
    }

    private FontSet.SelectedGlyphs computeGlyphInfo(final int codepoint) {
        FontSet.DelayedBake firstGlyph = null;

        for (GlyphProvider provider : this.activeProviders) {
            UnbakedGlyph glyph = provider.getGlyph(codepoint);
            if (glyph != null) {
                if (firstGlyph == null) {
                    firstGlyph = new FontSet.DelayedBake(glyph);
                }

                if (!hasFishyAdvance(glyph.info())) {
                    if (firstGlyph.unbaked == glyph) {
                        return this.vfpFixBlankGlyph1_12_2(new FontSet.SelectedGlyphs(firstGlyph, firstGlyph));
                    }

                    return this.vfpFixBlankGlyph1_12_2(new FontSet.SelectedGlyphs(firstGlyph, new FontSet.DelayedBake(glyph)));
                }
            }
        }

        return this.vfpFixBlankGlyph1_12_2(
            firstGlyph != null ? new FontSet.SelectedGlyphs(firstGlyph, this.missingGlyphGetter) : this.missingSelectedGlyphs
        );
    }

    // MODIFIED for porting: was VFP features/font MixinFontSet#fixBlankGlyph1_12_2 (@Inject computeGlyphInfo RETURN,
    // cancellable). RETURN covers every exit path, so all three returns above are routed through this. <= 1.12.2
    // never drew a missing-glyph box, so an unknown codepoint becomes the 1.12.2 blank glyph there.
    private FontSet.SelectedGlyphs vfpFixBlankGlyph1_12_2(final FontSet.SelectedGlyphs glyphPair) {
        if (ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return glyphPair == this.missingSelectedGlyphs ? this.vfpBlankBakedGlyphPair1_12_2 : glyphPair;
        }

        return glyphPair;
    }

    private FontSet.SelectedGlyphs getGlyph(final int codepoint) {
        final FontSet.SelectedGlyphs glyphPair = this.glyphCache.computeIfAbsent(codepoint, this.glyphGetter);
        // MODIFIED for porting: was VFP features/font MixinFontSet#filterBakedGlyph (@Inject getGlyph RETURN,
        // cancellable). A codepoint the target version cannot render is blank on <= 1.12.2 and the missing-glyph
        // box on everything newer. Upstream cancels here, which also skips resumeCharacterFiltering below - that
        // is harmless, because vfpShouldBeInvisible() can only be true while vfpObfuscatedLookup is already false.
        if (this.vfpShouldBeInvisible(codepoint)) {
            return ProtocolTranslator.getTargetVersion().olderThanOrEqualTo(ProtocolVersion.v1_12_2)
                ? this.vfpBlankBakedGlyphPair1_12_2
                : this.missingSelectedGlyphs;
        }

        // MODIFIED for porting: was VFP features/font MixinFontSet#resumeCharacterFiltering (@Inject getGlyph RETURN)
        this.vfpObfuscatedLookup = false;
        return glyphPair;
    }

    // MODIFIED for porting: was VFP features/font MixinFontSet#viaFabricPlus$shouldBeInvisible (@Unique helper).
    // Only the default font is filtered; RenderableGlyphDiff knows which codepoints the target version can draw.
    private boolean vfpShouldBeInvisible(final int codePoint) {
        if (!this.vfpObfuscatedLookup && DebugSettings.INSTANCE.filterNonExistingGlyphs.getValue()) {
            return this.stitcher.texturePrefix.equals(Minecraft.DEFAULT_FONT) && !RenderableGlyphDiff.isGlyphRenderable(codePoint);
        } else {
            return false;
        }
    }

    public BakedGlyph getRandomGlyph(final RandomSource random, final int width) {
        // MODIFIED for porting: was VFP features/font MixinFontSet#pauseCharacterFiltering (@Inject getRandomGlyph
        // HEAD). Obfuscated text uses every codepoint, even ones the target version has no glyph for, so the filter
        // is paused for this lookup; the getGlyph call below clears the flag again.
        this.vfpObfuscatedLookup = true;
        IntList chars = this.glyphsByWidth.get(width);
        return chars != null && !chars.isEmpty() ? this.getGlyph(chars.getInt(random.nextInt(chars.size()))).nonFishy().get() : this.missingGlyph;
    }

    public EffectGlyph whiteGlyph() {
        return Objects.requireNonNull(this.whiteGlyph);
    }

    public GlyphSource source(final boolean nonFishyOnly) {
        return nonFishyOnly ? this.nonFishyGlyphs : this.anyGlyphs;
    }

    @OnlyIn(Dist.CLIENT)
    private class DelayedBake implements Supplier<BakedGlyph> {
        private final UnbakedGlyph unbaked;
        private @Nullable BakedGlyph baked;

        private DelayedBake(final UnbakedGlyph unbaked) {
            this.unbaked = unbaked;
        }

        public BakedGlyph get() {
            if (this.baked == null) {
                this.baked = this.unbaked.bake(FontSet.this.wrappedStitcher);
            }

            return this.baked;
        }
    }

    @OnlyIn(Dist.CLIENT)
    public record SelectedGlyphs(Supplier<BakedGlyph> any, Supplier<BakedGlyph> nonFishy) {
        private Supplier<BakedGlyph> select(final boolean filterFishy) {
            return filterFishy ? this.nonFishy : this.any;
        }
    }

    @OnlyIn(Dist.CLIENT)
    public class Source implements GlyphSource {
        private final boolean filterFishyGlyphs;

        public Source(final boolean filterFishyGlyphs) {
            this.filterFishyGlyphs = filterFishyGlyphs;
        }

        @Override
        public BakedGlyph getGlyph(final int codepoint) {
            return FontSet.this.getGlyph(codepoint).select(this.filterFishyGlyphs).get();
        }

        @Override
        public BakedGlyph getRandomGlyph(final RandomSource random, final int width) {
            return FontSet.this.getRandomGlyph(random, width);
        }
    }
}
