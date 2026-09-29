package com.reverseengineer.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SseResponseStreamTest {

    /** Records sent events as "name data" strings instead of writing to a response. */
    static class RecordingEmitter extends SseEmitter {
        final List<String> events = new ArrayList<>();
        boolean completed;
        boolean failOnSend;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failOnSend) {
                throw new IOException("Broken pipe");
            }
            events.add(builder.build().stream()
                    .map(ResponseBodyEmitter.DataWithMediaType::getData)
                    .map(String::valueOf)
                    .collect(Collectors.joining())
                    .replace("\n", " ").strip());
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    @Test
    void sendsEventsInOrderAndCompletes() {
        RecordingEmitter emitter = new RecordingEmitter();
        SseResponseStream stream = new SseResponseStream(emitter);

        stream.onSources(List.of("### File: a.py"), List.of(Map.of("file_path", "a.py")));
        stream.onStep("architecture_extraction");
        stream.onDelta("Hel");
        stream.onDelta("lo");
        stream.done(Map.of("answer", "Hello"));

        assertEquals(5, emitter.events.size());
        assertTrue(emitter.events.get(0).startsWith("event:sources data:"), emitter.events.get(0));
        assertTrue(emitter.events.get(1).startsWith("event:step data:"));
        assertTrue(emitter.events.get(2).contains("text=Hel"));
        assertTrue(emitter.events.get(4).startsWith("event:done data:"));
        assertTrue(emitter.completed);
        assertTrue(stream.hasSentText());
    }

    @Test
    void failSendsErrorEventAndCompletes() {
        RecordingEmitter emitter = new RecordingEmitter();
        SseResponseStream stream = new SseResponseStream(emitter);

        stream.fail("Query failed.");

        assertEquals(List.of("event:error data:{message=Query failed.}"), emitter.events);
        assertTrue(emitter.completed);
        assertFalse(stream.hasSentText());
    }

    @Test
    void disconnectedClientAbortsGeneration() {
        RecordingEmitter emitter = new RecordingEmitter();
        SseResponseStream stream = new SseResponseStream(emitter);
        emitter.failOnSend = true;

        assertThrows(SseResponseStream.ClientGoneException.class, () -> stream.onDelta("x"));
        assertTrue(stream.isClosed());
        assertFalse(stream.hasSentText());
        // Later callbacks keep failing fast; done/fail never throw.
        assertThrows(SseResponseStream.ClientGoneException.class, () -> stream.onStep("risk_extraction"));
        assertDoesNotThrow(() -> stream.done(Map.of()));
    }
}
