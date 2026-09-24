package com.reverseengineer.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeywordSearchServiceTest {

    @Test
    void identifiersAreKeptWholeAndSplitIntoParts() {
        assertEquals(List.of("ratelimiterservice", "rate", "limiter", "service", "defined"),
                KeywordSearchService.queryTerms("Where is RateLimiterService defined?"));
    }

    @Test
    void snakeCaseAndAcronymsAreSplit() {
        List<String> terms = KeywordSearchService.queryTerms("resolve_redirects in HTTPAdapter");

        assertTrue(terms.containsAll(List.of("resolveredirects", "resolve", "redirects",
                "httpadapter", "http", "adapter")), terms.toString());
    }

    @Test
    void stopWordsAndSingleCharactersAreDropped() {
        assertEquals(List.of("authentication"),
                KeywordSearchService.queryTerms("How does a authentication work?"));
        assertTrue(KeywordSearchService.queryTerms("how is it?").isEmpty());
        assertTrue(KeywordSearchService.queryTerms(null).isEmpty());
    }

    @Test
    void termsAreSafeForTsquery() {
        List<String> terms = KeywordSearchService.queryTerms("x' | !(drop) & table:* -- ok");

        assertFalse(terms.isEmpty());
        assertTrue(terms.stream().allMatch(t -> t.matches("[a-z0-9]+")), terms.toString());
    }

    @Test
    void termCountIsCapped() {
        String many = String.join(" ", java.util.stream.IntStream.range(0, 100).mapToObj(i -> "word" + i).toList());

        assertEquals(32, KeywordSearchService.queryTerms(many).size());
    }
}
