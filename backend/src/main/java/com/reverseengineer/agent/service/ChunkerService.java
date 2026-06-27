package com.reverseengineer.agent.service;

import com.reverseengineer.agent.model.Chunk;
import com.reverseengineer.agent.model.CodeFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkerService {

    private static final Logger log = LoggerFactory.getLogger(ChunkerService.class);

    public static final int DEFAULT_CHUNK_SIZE = 1200;
    public static final int DEFAULT_OVERLAP    = 150;

    public List<Chunk> chunkCodeFiles(List<CodeFile> files) {
        return chunkCodeFiles(files, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public List<Chunk> chunkCodeFiles(List<CodeFile> files, int chunkSize, int overlap) {
        List<Chunk> all = files.parallelStream()
                .flatMap(file -> chunkFile(file, chunkSize, overlap).stream())
                .toList();
        log.info("Chunked {} files → {} chunks", files.size(), all.size());
        return all;
    }

    private static List<Chunk> chunkFile(CodeFile file, int chunkSize, int overlap) {
        List<String> splits = recursiveSplit(file.content(), chunkSize);
        int[] nlIndex = buildNewlineIndex(file.content());
        return mergeIntoChunks(splits, file.path(), chunkSize, overlap, file.content(), nlIndex);
    }

    /**
     * Prefix-sum array where {@code nlIndex[i]} = number of {@code '\n'} chars in {@code content[0..i)}.
     * Gives O(1) line-number lookups after an O(n) build.
     */
    private static int[] buildNewlineIndex(String content) {
        int len = content.length();
        int[] idx = new int[len + 1];
        for (int i = 0; i < len; i++) {
            idx[i + 1] = idx[i] + (content.charAt(i) == '\n' ? 1 : 0);
        }
        return idx;
    }

    private static List<String> recursiveSplit(String text, int maxSize) {
        if (text.length() <= maxSize) return List.of(text);

        String[] separators = {"\n\n", "\n", " ", ""};
        for (String sep : separators) {
            List<String> parts = splitBySeparator(text, sep);
            if (parts.size() > 1) {
                List<String> result = new ArrayList<>();
                for (String part : parts) {
                    if (part.length() > maxSize) {
                        result.addAll(recursiveSplit(part, maxSize));
                    } else if (!part.isBlank()) {
                        result.add(part);
                    }
                }
                return result;
            }
        }
        List<String> fallback = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxSize) {
            fallback.add(text.substring(i, Math.min(i + maxSize, text.length())));
        }
        return fallback;
    }

    private static List<String> splitBySeparator(String text, String sep) {
        if (sep.isEmpty()) return List.of(text);
        String[] raw = text.split(java.util.regex.Pattern.quote(sep), -1);
        if (raw.length <= 1) return List.of(text);
        List<String> result = new ArrayList<>();
        for (String part : raw) {
            if (!part.isBlank()) result.add(part);
        }
        return result;
    }

    private static List<Chunk> mergeIntoChunks(List<String> splits,
                                                String filePath,
                                                int chunkSize,
                                                int overlap,
                                                String originalContent,
                                                int[] nlIndex) {
        List<Chunk> chunks = new ArrayList<>();
        if (splits.isEmpty()) return chunks;

        int chunkIndex = 0;
        StringBuilder current = new StringBuilder();
        String overlapBuffer = "";
        int searchHint = 0;

        for (String split : splits) {
            if (!current.isEmpty() && current.length() + split.length() + 1 > chunkSize) {
                String text = current.toString();
                int pos = locateChunk(originalContent, text, searchHint, overlap);
                chunks.add(buildChunk(text, filePath, chunkIndex++, nlIndex, pos));
                if (pos >= 0) {
                    searchHint = Math.max(0, pos + text.length() - overlap);
                }
                current = new StringBuilder(overlapBuffer);
            }
            if (!current.isEmpty()) current.append('\n');
            current.append(split);

            String cur = current.toString();
            overlapBuffer = cur.length() > overlap
                    ? cur.substring(cur.length() - overlap)
                    : cur;
        }

        if (!current.isEmpty()) {
            String text = current.toString();
            int pos = locateChunk(originalContent, text, searchHint, overlap);
            chunks.add(buildChunk(text, filePath, chunkIndex, nlIndex, pos));
        }

        return chunks;
    }

    private static int locateChunk(String content, String text, int hint, int overlap) {
        return content.indexOf(text, Math.max(0, hint - overlap));
    }

    private static Chunk buildChunk(String text, String filePath, int chunkIndex,
                                    int[] nlIndex, int pos) {
        Integer startLine = null;
        Integer endLine   = null;
        if (pos >= 0) {
            startLine = nlIndex[pos] + 1;
            int endPos = Math.min(pos + text.length(), nlIndex.length - 1);
            endLine   = nlIndex[endPos] + 1;
        }
        return new Chunk(text, filePath, chunkIndex, startLine, endLine);
    }
}
