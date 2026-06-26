package com.reverseengineer.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    public enum Endpoint {
        INGEST(3, 30_000L),
        QUERY(30, 60_000L),
        DOCUMENT(5, 60_000L);

        final int maxRequests;
        final long windowMs;

        Endpoint(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }
    }

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public boolean isAllowed(String ip, Endpoint endpoint) {
        String key = endpoint.name() + ":" + ip;
        Deque<Long> timestamps = windows.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            long now = System.currentTimeMillis();
            long cutoff = now - endpoint.windowMs;

            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= endpoint.maxRequests) {
                log.warn("Rate limit exceeded: endpoint={} ip={}", endpoint, ip);
                return false;
            }

            timestamps.addLast(now);
            return true;
        }
    }
}
