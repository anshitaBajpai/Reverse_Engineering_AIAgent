package com.reverseengineer.agent.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReciprocalRankFusionTest {

    @Test
    void documentFoundByBothSearchesRanksFirst() {
        List<Document> vector  = List.of(doc("a"), doc("b"), doc("c"));
        List<Document> keyword = List.of(doc("c"), doc("d"));

        assertEquals(List.of("c", "a", "b", "d"), ids(RagService.reciprocalRankFusion(List.of(vector, keyword))));
    }

    @Test
    void tiesKeepVectorOrderFirst() {
        List<Document> vector  = List.of(doc("a"), doc("b"));
        List<Document> keyword = List.of(doc("x"), doc("y"));

        assertEquals(List.of("a", "x", "b", "y"), ids(RagService.reciprocalRankFusion(List.of(vector, keyword))));
    }

    @Test
    void emptyKeywordResultsLeaveVectorOrderUnchanged() {
        List<Document> vector = List.of(doc("a"), doc("b"), doc("c"));

        assertEquals(List.of("a", "b", "c"), ids(RagService.reciprocalRankFusion(List.of(vector, List.of()))));
    }

    @Test
    void keepsTheVectorResultInstanceWhenBothReturnTheSameRow() {
        Document fromVector = new Document("a", "text", Map.of("distance", 0.1));
        Document fromKeyword = new Document("a", "text", Map.of());

        Document fused = RagService.reciprocalRankFusion(List.of(List.of(fromVector), List.of(fromKeyword))).get(0);

        assertSame(fromVector, fused);
    }

    private static Document doc(String id) {
        return new Document(id, "text " + id, Map.of());
    }

    private static List<String> ids(List<Document> documents) {
        return documents.stream().map(Document::getId).toList();
    }
}
