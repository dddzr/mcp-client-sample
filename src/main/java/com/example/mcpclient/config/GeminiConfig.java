package com.example.mcpclient.config;

import com.google.genai.Client;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.support.RetryTemplate;
import io.micrometer.observation.ObservationRegistry;

@Configuration
public class GeminiConfig {
    
    @Value("${spring.ai.google.genai.api-key}")
    private String apiKey;
    
    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}")
    private String model;
    
    @Bean
    @Primary
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel googleGenAiChatModel() {
        // Google GenAI SDK Client 생성
        Client client = Client.builder()
                .apiKey(apiKey)
                .build();
        
        // ChatOptions 생성 (modelName 필수)
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(model)
                .build();
        
        // RetryTemplate 생성
        RetryTemplate retryTemplate = new RetryTemplate();
        
        // Spring AI 1.1.2 빌더 사용
        // 빌더에서는 toolCallingManager가 null이면 DEFAULT_TOOL_CALLING_MANAGER를 사용
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(options)
                .retryTemplate(retryTemplate)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }
}
