package com.reverseengineer.agent.controller;

import com.reverseengineer.agent.service.ResponseListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Sends a question's or document's progress to the browser as server-sent events.
 *
 * <p>Events, in order: {@code sources} ({@code {sources, citations}}), then for
 * documents {@code step} ({@code {name}}) per finished chain step, then
 * {@code delta} ({@code {text}}) per generated fragment, and finally either
 * {@code done} (the full response body, as the non-streaming endpoint returns it)
 * or {@code error} ({@code {message}}). The stream closes after the last event.</p>
 *
 * <p>If the browser goes away, the next send throws {@link ClientGoneException},
 * which aborts generation instead of paying for tokens nobody will read.</p>
 */
class SseResponseStream implements ResponseListener {

    private static final Logger log = LoggerFactory.getLogger(SseResponseStream.class);

    /** Thrown out of a listener callback when the client has disconnected. */
    static class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super("Client disconnected", cause);
        }
    }

    private final SseEmitter emitter;
    private volatile boolean closed;
    private volatile boolean sentText;

    SseResponseStream(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onTimeout(() -> closed = true);
        emitter.onError(e -> closed = true);
        emitter.onCompletion(() -> closed = true);
    }

    @Override
    public void onSources(List<String> sources, List<Map<String, Object>> citations) {
        send("sources", Map.of("sources", sources, "citations", citations));
    }

    @Override
    public void onStep(String name) {
        send("step", Map.of("name", name));
    }

    @Override
    public void onDelta(String text) {
        send("delta", Map.of("text", text));
        sentText = true;
    }

    /** Whether any generated text reached the client (used to decide quota refunds). */
    boolean hasSentText() {
        return sentText;
    }

    /** Sends the final response and closes the stream. */
    void done(Object body) {
        trySend("done", body);
        complete();
    }

    /** Sends an error the UI can show and closes the stream. */
    void fail(String message) {
        trySend("error", Map.of("message", message));
        complete();
    }

    boolean isClosed() {
        return closed;
    }

    private void send(String event, Object data) {
        if (closed) {
            throw new ClientGoneException(null);
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            closed = true;
            throw new ClientGoneException(e);
        }
    }

    private void trySend(String event, Object data) {
        try {
            send(event, data);
        } catch (ClientGoneException e) {
            log.debug("Could not send '{}' event; client already gone.", event);
        }
    }

    private void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("Stream already closed: {}", e.getMessage());
        }
        closed = true;
    }
}
