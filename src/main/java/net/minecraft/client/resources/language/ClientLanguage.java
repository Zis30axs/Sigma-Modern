package net.minecraft.client.resources.language;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.locale.DeprecatedTranslationsInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@OnlyIn(Dist.CLIENT)
public class ClientLanguage extends Language {
    private static final Logger LOGGER = LogUtils.getLogger();
    public final Map<String, String> storage;
    private final boolean defaultRightToLeft;

    private ClientLanguage(final Map<String, String> storage, final boolean defaultRightToLeft) {
        this.storage = storage;
        this.defaultRightToLeft = defaultRightToLeft;
    }

    /**
     * MODIFIED for porting: iris MixinClientLanguage @Unique field. It lets shader packs supply extra language entries without
     * a resource-manager reload; the entries are "sideloaded" through an override system because reloading the resource
     * manager on every shader pack change is very slow.
     * <p>
     * Upstream's own TODO notes this should not be static. It is kept as-is.
     */
    private static final List<String> iris$languageCodes = new ArrayList<>();

    public static ClientLanguage loadFrom(final ResourceManager resourceManager, final List<String> languageStack, final boolean defaultRightToLeft) {
        // MODIFIED for porting: was iris's MixinClientLanguage#check (@Inject HEAD) - make sure the codes do not carry over,
        // then record them in reverse order (vanilla lists en_us first and the primary language after it).
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            iris$languageCodes.clear();
            new java.util.LinkedList<>(languageStack).descendingIterator().forEachRemaining(iris$languageCodes::add);
        }

        Map<String, String> translations = new HashMap<>();

        for (String languageCode : languageStack) {
            String path = String.format(Locale.ROOT, "lang/%s.json", languageCode);

            for (String namespace : resourceManager.getNamespaces()) {
                try {
                    Identifier location = Identifier.fromNamespaceAndPath(namespace, path);
                    appendFrom(languageCode, resourceManager.getResourceStack(location), translations);
                } catch (Exception e) {
                    LOGGER.warn("Skipped language file: {}:{} ({})", namespace, path, e.toString());
                }
            }
        }

        DeprecatedTranslationsInfo.loadFromDefaultResource().applyToMap(translations);
        return new ClientLanguage(Map.copyOf(translations), defaultRightToLeft);
    }

    private static void appendFrom(final String languageCode, final List<Resource> resources, final Map<String, String> translations) {
        // MODIFIED for porting: was iris's MixinClientLanguage#injectFrom (@Inject HEAD) - iris's own language files ship in
        // its jar rather than in a resource pack.
        if (net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            String irisJson = String.format(Locale.ROOT, "lang/%s.json", languageCode);
            if (net.irisshaders.iris.Iris.class.getResource("/assets/iris/" + irisJson) != null) {
                Language.loadFromJson(net.irisshaders.iris.Iris.class.getResourceAsStream("/assets/iris/" + irisJson), translations::put);
            }
        }

        for (Resource resource : resources) {
            try (InputStream inputStream = resource.open()) {
                Language.loadFromJson(inputStream, translations::put);
            } catch (IOException e) {
                LOGGER.warn("Failed to load translations for {} from pack {}", languageCode, resource.sourcePackId(), e);
            }
        }
    }

    @Override
    public String getOrDefault(final String key, final String defaultValue) {
        // MODIFIED for porting: was iris's MixinClientLanguage#iris$addLanguageEntries (@Inject HEAD, cancellable)
        String irisOverride = this.iris$lookupOverriddenEntry(key);
        if (irisOverride != null) {
            return irisOverride;
        }

        return this.storage.getOrDefault(key, defaultValue);
    }

    @Override
    public boolean has(final String key) {
        // MODIFIED for porting: was iris's MixinClientLanguage#iris$addLanguageEntriesToTranslationChecks
        // (@Inject HEAD, cancellable)
        if (this.iris$lookupOverriddenEntry(key) != null) {
            return true;
        }

        return this.storage.containsKey(key);
    }

    // MODIFIED for porting: was iris's MixinClientLanguage#iris$lookupOverriddenEntry (@Unique)
    private @Nullable String iris$lookupOverriddenEntry(final String key) {
        if (!net.irisshaders.iris.mixin.IrisMixinPlugin.isEnabled()) {
            return null;
        }

        net.irisshaders.iris.shaderpack.ShaderPack pack = net.irisshaders.iris.Iris.getCurrentPack().orElse(null);

        if (pack == null) {
            // If no shaderpack is loaded, do not try to process language overrides.
            //
            // This prevents a cryptic NullPointerException when shaderpack loading fails for some reason.
            return null;
        }

        // Minecraft loads the "en_us" language code by default, and any other code will be right after it.
        //
        // So we also check if the user is loading a special language, and if the shaderpack has support for that
        // language. If they do, we load that, but if they do not, we load "en_us" instead.
        net.irisshaders.iris.shaderpack.LanguageMap languageMap = pack.getLanguageMap();

        if (this.storage.containsKey(key)) {
            // TODO: Should we allow shader packs to override existing MC translations?
            return null;
        }

        for (String code : iris$languageCodes) {
            Map<String, String> translations = languageMap.getTranslations(code);

            if (translations != null) {
                String translation = translations.get(key);

                if (translation != null) {
                    return translation;
                }
            }
        }

        return null;
    }

    @Override
    public boolean isDefaultRightToLeft() {
        return this.defaultRightToLeft;
    }

    @Override
    public FormattedCharSequence getVisualOrder(final FormattedText logicalOrderText) {
        return FormattedBidiReorder.reorder(logicalOrderText, this.defaultRightToLeft);
    }
}