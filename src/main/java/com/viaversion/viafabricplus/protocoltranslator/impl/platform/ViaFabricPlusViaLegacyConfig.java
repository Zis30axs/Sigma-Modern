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

import com.viaversion.viafabricplus.settings.impl.GeneralSettings;
import java.io.File;
import java.util.logging.Logger;
import net.raphimc.vialegacy.ViaLegacyConfig;

/**
 * MODIFIED for porting: replaces the ViaFabricPlus mixin on {@link ViaLegacyConfig}, which is a ViaLegacy jar
 * class. {@code ViaLegacyPlatform#init(ViaLegacyConfig)} takes a caller-supplied config, so overriding the two
 * getters in a subclass and handing it in from {@link ViaFabricPlusViaLegacyPlatform} is equivalent - the same
 * pattern ViaVersion itself gets through {@code ViaFabricPlusConfig extends AbstractViaConfig}.
 */
public final class ViaFabricPlusViaLegacyConfig extends ViaLegacyConfig {

    public ViaFabricPlusViaLegacyConfig(final File configFile, final Logger logger) {
        super(configFile, logger);
    }

    // was VFP core/integration MixinViaLegacyConfig#replaceWithVFPSetting
    // (@Inject method = {"isLegacySkullLoading", "isLegacySkinLoading"} HEAD, cancellable, setReturnValue).
    // All versions: both are forced to the GUI setting instead of vialegacy.yml, which also flips the default
    // from false to true (GeneralSettings#loadSkinsAndSkullsInLegacyVersions), so <= 1.7.2 skulls and
    // <= 1.6.4 skins load. The getters are only ever read through the ViaLegacyConfig interface, so an
    // override reaches every caller the @Inject did.
    @Override
    public boolean isLegacySkullLoading() {
        return GeneralSettings.INSTANCE.loadSkinsAndSkullsInLegacyVersions.getValue();
    }

    @Override
    public boolean isLegacySkinLoading() {
        return GeneralSettings.INSTANCE.loadSkinsAndSkullsInLegacyVersions.getValue();
    }

}
