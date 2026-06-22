package com.reverseengineer.agent.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Request body for {@code POST /query}. */
public record QueryRequest(

        @NotBlank(message = "question must not be blank")
        String question,

        @Min(value = 1, message = "k must be at least 1")
        @Max(value = 20, message = "k must be at most 20")
        int k,

        /** Restrict search to these project IDs. Empty / null = search all ingested projects. */
        List<String> projectIds
) {
    public QueryRequest {
        if (k == 0) k = 5;
        if (projectIds == null) projectIds = List.of();
    }
}
