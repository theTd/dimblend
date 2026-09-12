package dimblend.mixin;

import com.simibubi.create.content.trains.graph.EdgeData;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.SignalPropagator;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the full-graph {@code walkSignals} when the graph holds no signals: the walk's only
 * necessary effect on a signal-less graph is assigning {@link EdgeData#passiveGroup} to the
 * new node's incident edges (preserving getGroupAtPosition -> graph.id). The unconditional
 * per-edge edgeDataChanged sync for idempotent no-op writes is pure overhead at corridor
 * pregen scale. Graphs with signals pass through unchanged.
 */
@Mixin(value = SignalPropagator.class, remap = false)
public abstract class SignalPropagatorMixin {

    @Inject(method = "notifySignalsOfNewNode", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dimblend$skipSignalWalkForSignallessGraph(TrackGraph graph, TrackNode node, CallbackInfo ci) {
        if (!graph.getPoints(EdgePointType.SIGNAL).isEmpty()) {
            return;
        }
        for (Map.Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node).entrySet()) {
            entry.getValue().getEdgeData().setSingleSignalGroup(graph, EdgeData.passiveGroup);
            TrackEdge incoming = graph.getConnectionsFrom(entry.getKey()).get(node);
            if (incoming != null) {
                incoming.getEdgeData().setSingleSignalGroup(graph, EdgeData.passiveGroup);
            }
        }
        ci.cancel();
    }
}
