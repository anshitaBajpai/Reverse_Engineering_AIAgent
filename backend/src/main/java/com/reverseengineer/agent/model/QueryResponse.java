package com.reverseengineer.agent.model;

import java.util.List;
import java.util.Map;

/**
 * Response body for {@code POST /query}. {@code citations[i]} describes
 * {@code sources[i]}: {@code project_id}, {@code file_path}, optional
 * {@code start_line}/{@code end_line}, and {@code url} when the host is linkable.
 */
public record QueryResponse(
        String answer,
        List<String> sources,
        List<Map<String, Object>> citations
) {}
