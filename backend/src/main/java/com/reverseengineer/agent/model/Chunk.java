package com.reverseengineer.agent.model;

public record Chunk(
        String text,
        String filePath,
        int chunkIndex,
        Integer startLine,
        Integer endLine
) {}
