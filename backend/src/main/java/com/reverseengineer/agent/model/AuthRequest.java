package com.reverseengineer.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /auth/register} and {@code POST /auth/login}. */
public record AuthRequest(

        @NotBlank(message = "username must not be blank")
        @Size(min = 3, max = 64, message = "username must be 3-64 characters")
        @Pattern(regexp = "[A-Za-z0-9._-]+",
                message = "username may only contain letters, digits, dot, underscore, or hyphen")
        String username,

        @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 200, message = "password must be 8-200 characters")
        String password,

        /** Only checked by {@code /auth/register}, and only when {@code SIGNUP_CODE} is configured. */
        String signupCode
) {}
