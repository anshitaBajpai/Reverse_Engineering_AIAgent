package com.reverseengineer.agent.config;

import com.reverseengineer.agent.mcp.AgentMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class McpConfig {

    /**
     * @Lazy breaks the startup cycle:
     *   ragService → llmService → chatClientBuilder → openAiChatModel
     *   → toolCallingManager → toolCallbackResolver → agentToolCallbackProvider
     *   → (lazy proxy) agentMcpTools → ragService  ← cycle broken here
     */
    @Bean
    public ToolCallbackProvider agentToolCallbackProvider(@Lazy AgentMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
