package com.reverseengineer.agent.model;

import java.util.List;
import java.util.Map;


public record DocumentResponse(
        String document,
        List<Map<String, String>> chainSteps,
        List<String> sources
) {}
