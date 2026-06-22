package com.reverseengineer.agent.model;

import java.util.List;

/** Response body for {@code POST /query}. */
public record QueryResponse(
        String answer,
        List<String> sources
) {}
