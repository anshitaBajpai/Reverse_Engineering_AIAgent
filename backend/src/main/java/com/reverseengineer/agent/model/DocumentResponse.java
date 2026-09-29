package com.reverseengineer.agent.model;

import java.util.List;
import java.util.Map;

/** Response body for {@code POST /document}; {@code citations} as in {@link QueryResponse}. */
public record DocumentResponse(
        String document,
        List<Map<String, String>> chainSteps,
        List<String> sources,
        List<Map<String, Object>> citations
) {}
