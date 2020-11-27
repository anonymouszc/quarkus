package org.jboss.resteasy.reactive.server.handlers;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.ws.rs.HttpMethod;
import org.jboss.resteasy.reactive.common.http.ServerHttpRequest;
import org.jboss.resteasy.reactive.common.util.EmptyInputStream;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;

/**
 * Handler that reads data and sets up the input stream
 * <p>
 * By default this will attempt to buffer and use a fully in memory stream,
 * to allow the request to stay on the IO thread. If the request is too large
 * it will be delegated to an executor and a blocking stream used instead.
 * <p>
 * TODO: the stream implementation here could be a lot more efficent.
 */
public class InputHandler implements ServerRestHandler {

    final long maxBufferSize;
    private volatile Executor executor;
    private final Supplier<Executor> supplier;

    public InputHandler(long maxBufferSize, Supplier<Executor> supplier) {
        this.maxBufferSize = maxBufferSize;
        this.supplier = supplier;
    }

    @Override
    public void handle(ResteasyReactiveRequestContext context) throws Exception {
        // in some cases, with sub-resource locators or via request filters, 
        // it's possible we've already read the entity
        if (context.getInputStream() != EmptyInputStream.INSTANCE) {
            // let's not set it twice
            return;
        }
        if (context.serverRequest().getRequestMethod().equals(HttpMethod.GET) ||
                context.serverRequest().getRequestMethod().equals(HttpMethod.HEAD)) {
            return;
        }
        InputListener h = new InputListener(context);
        context.suspend();
        ServerHttpRequest req = context.serverRequest();
        if (!req.isRequestEnded()) {
            req.setReadListener(h);
            req.resumeRequestInput();
        } else {
            req.resumeRequestInput();
            h.done();
        }
    }

    class InputListener implements ServerHttpRequest.ReadCallback {
        final ResteasyReactiveRequestContext context;
        int dataCount;
        final List<ByteBuffer> data = new ArrayList<>();

        InputListener(ResteasyReactiveRequestContext context) {
            this.context = context;
        }

        @Override
        public void done() {
            //super inefficient
            //TODO: write a stream that just uses the existing vert.x buffers
            byte[] ar = new byte[dataCount];
            int count = 0;
            for (ByteBuffer i : data) {
                int remaining = i.remaining();
                i.get(ar, count, remaining);
                count += remaining;
            }
            context.setInputStream(new ByteArrayInputStream(ar));
            context.resume();
        }

        @Override
        public void data(ByteBuffer event) {

            dataCount += event.remaining();
            data.add(event);
            if (dataCount > maxBufferSize) {
                context.serverRequest().pauseRequestInput();
                if (executor == null) {
                    executor = supplier.get();
                }
                //super inefficient
                //TODO: write a stream that just uses the existing vert.x buffers
                int count = 0;
                byte[] ar = new byte[dataCount];
                for (ByteBuffer i : data) {
                    int remaining = i.remaining();
                    i.get(ar, count, remaining);
                    count += remaining;
                }
                //todo timeout
                context.setInputStream(context.serverRequest().createInputStream(ByteBuffer.wrap(ar)));
                context.resume(executor);
            }
        }
    }
}