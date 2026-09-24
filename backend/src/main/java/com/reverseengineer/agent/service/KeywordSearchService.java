package com.reverseengineer.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full-text search over the same {@code vector_store} rows the embeddings live
 * in. It catches what vector search misses: exact identifiers such as
 * {@code RateLimiterService} or {@code resolve_redirects}.
 *
 * <p>The index is a generated {@code tsvector} column. It is built from the
 * file path and the chunk text, with identifiers indexed both whole and split
 * at camelCase / snake_case boundaries, using the {@code simple} configuration
 * (lower-casing, no stemming or stop words, so code tokens survive intact).
 * The column is added at startup, after Spring AI has created the table; if
 * that fails, keyword search reports itself unavailable and callers fall back
 * to vector search only.</p>
 */
@Service
public class KeywordSearchService {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchService.class);

    private static final int MAX_QUERY_TERMS = 32;
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** Natural-language filler that would otherwise match nearly every chunk. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does", "for", "from",
            "how", "i", "if", "in", "into", "is", "it", "its", "me", "of", "on", "or", "show",
            "that", "the", "their", "there", "this", "to", "use", "used", "uses", "using", "was",
            "what", "when", "where", "which", "who", "why", "with", "work", "works", "you", "your",
            "explain", "tell", "about", "code", "file", "files", "happen", "happens", "get", "gets");

    // Identifiers are indexed both joined (regexp 1) and split at case changes
    // (regexp 2). Every function here is immutable, as a generated column requires.
    private static final String ADD_COLUMN_SQL = """
            ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS search_tsv tsvector
            GENERATED ALWAYS AS (
                setweight(to_tsvector('simple'::regconfig,
                    regexp_replace(coalesce(metadata->>'file_path', ''), '[^A-Za-z0-9]+', ' ', 'g')), 'A')
                || to_tsvector('simple'::regconfig,
                    regexp_replace(coalesce(content, ''), '[^A-Za-z0-9]+', ' ', 'g'))
                || to_tsvector('simple'::regconfig,
                    regexp_replace(
                        regexp_replace(coalesce(content, ''), '([a-z0-9])([A-Z])', '\\1 \\2', 'g'),
                        '[^A-Za-z0-9]+', ' ', 'g'))
            ) STORED""";

    private static final String ADD_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS vector_store_search_tsv_idx ON vector_store USING GIN (search_tsv)";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private volatile boolean available;

    /** {@code vectorStore} is injected only so the table exists before {@link #ensureIndex} runs. */
    public KeywordSearchService(JdbcTemplate jdbcTemplate, VectorStore vectorStore) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void ensureIndex() {
        try {
            jdbcTemplate.execute(ADD_COLUMN_SQL);
            jdbcTemplate.execute(ADD_INDEX_SQL);
            available = true;
        } catch (Exception e) {
            // e.g. another instance adding the column at the same moment: use it if it exists.
            available = columnExists();
            if (!available) {
                log.warn("Keyword search disabled; could not create the search index: {}", e.getMessage());
            }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Chunks matching any keyword of {@code query}, best first, restricted to
     * {@code ownerKey} and (when non-empty) {@code projectIds}. Returns an empty
     * list when the query has no usable terms or keyword search is unavailable.
     */
    public List<Document> search(String query, int limit, List<String> projectIds, String ownerKey) {
        List<String> terms = queryTerms(query);
        if (!available || terms.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(String.join(" | ", terms));
        args.add(ownerKey);
        StringBuilder sql = new StringBuilder("""
                SELECT id::text AS id, content, metadata::text AS metadata
                FROM vector_store, to_tsquery('simple', ?) query
                WHERE search_tsv @@ query
                  AND metadata->>'owner_id' = ?""");
        if (!projectIds.isEmpty()) {
            sql.append(" AND metadata->>'project_id' IN (")
                    .append(String.join(", ", Collections.nCopies(projectIds.size(), "?")))
                    .append(")");
            args.addAll(projectIds);
        }
        // Normalization 1 divides by 1 + log(length) so long chunks don't win on size alone.
        sql.append(" ORDER BY ts_rank_cd(search_tsv, query, 1) DESC LIMIT ?");
        args.add(limit);
        try {
            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new Document(
                    rs.getString("id"), rs.getString("content"), parseMetadata(rs.getString("metadata"))),
                    args.toArray());
        } catch (Exception e) {
            log.warn("Keyword search failed, continuing with vector results only: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Lower-cased search terms: each identifier in {@code query} whole, plus its
     * camelCase and snake_case parts, minus stop words. Terms contain only
     * {@code [a-z0-9]}, so they are safe to join into a {@code to_tsquery} string.
     */
    static List<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null) {
            return List.of();
        }
        Matcher words = WORD.matcher(query);
        while (words.find() && terms.size() < MAX_QUERY_TERMS) {
            String word = words.group();
            addTerm(terms, word.replace("_", ""));
            for (String part : word.split("_")) {
                for (String piece : CAMEL_BOUNDARY.split(part)) {
                    addTerm(terms, piece);
                }
            }
        }
        return terms.stream().limit(MAX_QUERY_TERMS).toList();
    }

    private static void addTerm(Set<String> terms, String raw) {
        String term = raw.toLowerCase(Locale.ROOT);
        if (term.length() >= 2 && !STOP_WORDS.contains(term)) {
            terms.add(term);
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return json != null ? mapper.readValue(json, MAP_TYPE) : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean columnExists() {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'vector_store' AND column_name = 'search_tsv'""", Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
