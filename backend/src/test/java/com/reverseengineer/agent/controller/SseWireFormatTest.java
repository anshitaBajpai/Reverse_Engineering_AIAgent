package com.reverseengineer.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs {@link SseResponseStream} through Spring MVC's real SSE handling to pin
 * the bytes the frontend's parser (apiClient.createSseParser) must understand.
 */
class SseWireFormatTest {

    @RestController
    static class StreamingController {
        @PostMapping("/stream")
        SseEmitter stream() {
            SseEmitter emitter = new SseEmitter(5_000L);
            SseResponseStream events = new SseResponseStream(emitter);
            CompletableFuture.runAsync(() -> {
                events.onSources(List.of("### File: a.py:1-2\nx = 1"),
                        List.of(Map.of("file_path", "a.py", "start_line", 1)));
                events.onStep("architecture_extraction");
                events.onDelta("Hi \"there\"\nnext line");
                events.done(Map.of("answer", "Hi"));
            });
            return emitter;
        }
    }

    @Test
    void eventsAreNamedJsonOnSingleDataLines() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StreamingController()).build();

        MvcResult started = mvc.perform(post("/stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/event-stream"))
                .andReturn().getResponse().getContentAsString();

        // Every event is "event:<name>\ndata:<one line of JSON>\n\n" — newlines inside
        // values stay escaped, so the frontend never sees a multi-line data field.
        String[] events = body.split("\n\n", -1);
        assertEquals(5, events.length, body);
        assertEquals("", events[4]);
        List<String> names = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String[] lines = events[i].split("\n");
            assertEquals(2, lines.length, events[i]);
            assertTrue(lines[0].startsWith("event:") && lines[1].startsWith("data:"), events[i]);
            names.add(lines[0].substring("event:".length()));
            data.add(JSON.readValue(lines[1].substring("data:".length()), Object.class));
        }

        assertEquals(List.of("sources", "step", "delta", "done"), names);
        assertEquals(Map.of("sources", List.of("### File: a.py:1-2\nx = 1"),
                "citations", List.of(Map.of("file_path", "a.py", "start_line", 1))), data.get(0));
        assertEquals(Map.of("name", "architecture_extraction"), data.get(1));
        assertEquals(Map.of("text", "Hi \"there\"\nnext line"), data.get(2));
        assertEquals(Map.of("answer", "Hi"), data.get(3));
    }

    private static final ObjectMapper JSON = new ObjectMapper();
}
