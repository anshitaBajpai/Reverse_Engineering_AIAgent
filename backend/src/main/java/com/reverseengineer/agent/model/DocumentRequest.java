package com.reverseengineer.agent.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request body for {@code POST /document}. */
public record DocumentRequest(

        @NotBlank(message = "project_name must not be blank")
        @Size(max = 120, message = "project_name must be at most 120 characters")
        String projectName,

        @Min(value = 5,  message = "k must be at least 5")
        @Max(value = 40, message = "k must be at most 40")
        int k,

        /** Scope the document to these project IDs. Empty / null = all ingested projects. */
        List<String> projectIds
) {
    public DocumentRequest {
        if (projectName == null || projectName.isBlank()) projectName = "Ingested Repository";
        if (k == 0) k = 25;
        if (projectIds == null) projectIds = List.of();
    }
}
