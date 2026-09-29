package com.reverseengineer.agent.service;

import java.util.List;
import java.util.Map;

/**
 * Progress callbacks for a question or document while it is being generated,
 * used to stream results to the client. Every method may be called from any
 * thread; document chain steps finish in parallel.
 *
 * <p>{@link #NONE} means "no one is listening": the LLM is then called without
 * streaming, exactly as before.</p>
 */
public interface ResponseListener {

    ResponseListener NONE = new ResponseListener() {};

    /**
     * Retrieval finished: the formatted sources the response will be grounded in,
     * and one citation map per source (same order) for linking to the code.
     */
    default void onSources(List<String> sources, List<Map<String, Object>> citations) {}

    /** A document-chain step finished ({@code architecture_extraction}, ...). */
    default void onStep(String name) {}

    /** The next piece of the answer or final document text. */
    default void onDelta(String text) {}
}
