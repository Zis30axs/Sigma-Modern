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

import java.io.File;
import net.raphimc.viabedrock.ViaBedrockPlatformImpl;

/**
 * MODIFIED for porting: carries {@link ViaFabricPlusViaBedrockConfig} into ViaBedrock. ViaBedrockPlatformImpl's
 * constructor calls {@code this.init(new File(getDataFolder(), "viabedrock.yml"))}, and that default method is
 * what builds the stock config - so overriding it is the supported way to substitute one. The logger is already
 * assigned by the time the super constructor gets here, and this class holds no state of its own, so being
 * called from the super constructor is safe.
 */
public final class ViaFabricPlusViaBedrockPlatform extends ViaBedrockPlatformImpl {

    @Override
    public void init(final File configFile) {
        this.init(new ViaFabricPlusViaBedrockConfig(configFile, this.getLogger()));
    }

}
