package net.minecraft.client;

import com.mentalfrostbyte.jello.module.Modules;
import com.mentalfrostbyte.jello.module.impl.misc.ClientSpoof;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientBrandRetriever {
    public static final String VANILLA_NAME = "vanilla";

    public static String getClientModName() {
        // Sigma hook: ClientSpoof replaces the brand sent to the server on login.
        // Modules.enabled is null-safe before the client has started.
        ClientSpoof spoof = Modules.enabled(ClientSpoof.class);
        return spoof == null ? "vanilla" : spoof.getSpoofedBrand("vanilla");
    }
}
