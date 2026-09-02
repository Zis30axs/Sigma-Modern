/*
 * This file is part of ViaFabricPlus - https://github.com/ViaVersion/ViaFabricPlus
 * Copyright (C) 2021-2026 the original authors
 *                         - Florian Reuth <git@florianreuth.de>
 *                         - RK_01/RaphiMC
 * Copyright (C) 2023-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.viaversion.viafabricplus.protocoltranslator.impl.platform;

import com.viaversion.viafabricplus.settings.impl.BedrockSettings;
import java.io.File;
import java.util.logging.Logger;
import net.raphimc.viabedrock.ViaBedrockConfig;

/**
 * MODIFIED for porting: replaces the ViaFabricPlus mixin on {@link ViaBedrockConfig}, which is a ViaBedrock jar
 * class. {@code ViaBedrockPlatform#init(ViaBedrockConfig)} takes a caller-supplied config, so overriding the
 * getter in a subclass and handing it in from {@link ViaFabricPlusViaBedrockPlatform} is equivalent.
 */
public final class ViaFabricPlusViaBedrockConfig extends ViaBedrockConfig {

    public ViaFabricPlusViaBedrockConfig(final File configFile, final Logger logger) {
        super(configFile, logger);
    }

    // was VFP core/integration MixinViaBedrockConfig#shouldEnableExperimentalFeatures (@Overwrite).
    // All versions, Bedrock target only: the setting moves into the GUI and its default flips from the
    // viabedrock.yml enable-experimental-features: false to BedrockSettings#experimentalFeatures (true).
    // ViaBedrock only ever reads this through the ViaBedrockConfig interface (including the warning
    // ViaBedrock#init logs), so an override reaches every caller the @Overwrite did.
    @Override
    public boolean shouldEnableExperimentalFeatures() {
        return BedrockSettings.INSTANCE.experimentalFeatures.getValue();
    }

}
