// Copyright 2014, Kevin Ko <kevin@faveset.com>

package com.faveset.khttp;

import java.nio.ByteBuffer;
import java.util.Map;

class MessageBodyHandler implements StateHandler {
    public boolean handleState(NonBlockingConnection conn, ByteBuffer buf, HandlerState state) throws InvalidRequestException {
        HttpRequest req = state.getRequestBuilder();

        // Trim the data buff depending on request's headers.
        switch (req.getMethod()) {
            case GET:
                return handleGet(buf, state, req);

            case POST:
            case PUT:
            case HEAD:
            default:
                return handleRaw(buf, state, req);
        }
    }

    /**
     * @return true if the state is done processing.
     */
    private boolean handleGet(ByteBuffer buf, HandlerState state, HttpRequest req) throws InvalidRequestException {
        // TODO: handle conditionals
        // TODO: handle partials

        HandlerState.OnRequestCallback handler = state.getOnRequestCallback();

        ByteBuffer getBuf = buf.duplicate();
        // Get methods have no payload.
        getBuf.clear();
        return handler.onRequest(req, getBuf, state.getResponseWriter());
    }

    private boolean handleRaw(ByteBuffer buf, HandlerState state, HttpRequest req) throws InvalidRequestException {
        HandlerState.OnRequestCallback handler = state.getOnRequestCallback();
        return handler.onRequest(req, buf, state.getResponseWriter());
    }
}
