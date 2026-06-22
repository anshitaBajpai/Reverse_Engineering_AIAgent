package com.reverseengineer.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;


public record IngestRequest(

        @NotBlank(message = "repo_url must not be blank")
        @Pattern(
                regexp = "https://.*",
                message = "Only HTTPS repository URLs are allowed"
        )
        String repoUrl
) {}
