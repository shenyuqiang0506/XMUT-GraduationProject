package org.shen.ai.m1;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("m1 & ai")
class M1AiConfiguration {
    @Bean
    ChatClient m1ChatClient(ChatClient.Builder builder, OpenAiChatProperties chat,
                            OpenAiCommonProperties connection) {
        // Spring AI 2.0.1 toOptions() does not copy the common request timeout.
        return builder.defaultOptions(chat.toOptions().mutate().timeout(connection.getTimeout())
                .maxRetries(connection.getMaxRetries())).build();
    }

    @Bean
    ToolCallingManager m1ToolCallingManager() {
        return DefaultToolCallingManager.builder().maxCallsPerTool(2).maxTotalToolCalls(2)
                .toolExecutionExceptionProcessor(DefaultToolExecutionExceptionProcessor.builder()
                        .alwaysThrow(true).build()).build();
    }
}
