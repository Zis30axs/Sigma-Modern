package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.commands.GrimVersion;

public class UpdateChecker implements StartableInitable {
    @Override
    public void start() {
        // MODIFIED for porting: the embedded Grim is updated with the client, not by itself, so it never contacts
        // api.grim.ac - whatever a copied-in config.yml says about check-for-updates.
    }
}
