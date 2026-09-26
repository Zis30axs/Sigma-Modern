package ac.grim.grimac.manager.init.start;

public class ViaBackwardsManager implements StartableInitable {
    @Override
    public void start() {
        // MODIFIED for porting: upstream sets the JVM-wide property com.viaversion.handlePingsAsInvAcknowledgements
        // for the server's ViaBackwards. In the client that property would change how ViaFabricPlus translates the
        // player's own connection, so it is left alone; there is no Via between SelfDetection's Grim and the wire.
    }
}
