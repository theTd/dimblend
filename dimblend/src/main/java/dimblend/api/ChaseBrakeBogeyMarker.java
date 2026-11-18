package dimblend.api;

/**
 * Persistent marker interface mixed onto Simurail's {@code PhysicsBogeyBlockEntity} by
 * {@code dimblend-experience} and set by {@code dimblend-dragonchase} when assembling a Brake Car.
 *
 * <p>Marked bogeys bypass the player tune brake-signal cap ({@code trainBrakeSignalCap}) so redstone
 * power drives full signal-15 braking. Thread-safe {@code volatile} boolean read on physics/server
 * threads; persisted in block-entity NBT across chunk reloads and world restarts.
 */
public interface ChaseBrakeBogeyMarker {

    boolean dimblend$isChaseBrakeBogey();

    void dimblend$setChaseBrakeBogey(boolean marked);
}
