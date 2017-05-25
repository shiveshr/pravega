package io.pravega.controller.server.eventProcessor;

import io.pravega.shared.controller.event.AutoScaleEvent;
import io.pravega.shared.controller.event.ControllerEvent;
import lombok.Data;

import java.util.concurrent.CompletableFuture;

@Data
public class RequestHandlerMultiplexer implements RequestHandler<ControllerEvent> {

    private final AutoScaleRequestHandler autoScaleRequestHandler;
    private final ScaleOperationRequestHandler scaleOperationRequestHandler;

    @Override
    public CompletableFuture<Void> process(ControllerEvent controllerEvent) {
        if (controllerEvent instanceof AutoScaleEvent) {
            return autoScaleRequestHandler.process((AutoScaleEvent) controllerEvent);
        }
        if (controllerEvent instanceof ScaleOpEvent) {
            return scaleOperationRequestHandler.process((ScaleOpEvent) controllerEvent);
        }

        // TODO: shivesh
        return null;
    }
}
