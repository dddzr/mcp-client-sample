package com.example.mcpclient.service;

import com.example.mcpclient.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GeminiService {
    
    private static final Logger logger = LoggerFactory.getLogger(GeminiService.class);
    private final ChatClient chatClient;

    public GeminiService(ChatModel chatModel) {
        try {
            if (chatModel != null) {
                this.chatClient = ChatClient.builder(chatModel).build();
                logger.info("ChatClient initialized successfully from ChatModel");
            } else {
                throw new IllegalStateException("ChatModel is not available. Please check Spring AI configuration.");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize ChatClient from ChatModel", e);
            throw new RuntimeException("Failed to initialize ChatClient: " + e.getMessage(), e);
        }
    }

    public String generateResponse(String userMessage) {
        try {
            logger.debug("Generating response for message: {}", userMessage);
            String response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
            logger.debug("Generated response: {}", response);
            return response;
        } catch (Exception e) {
            logger.error("Failed to generate response for message: {}", userMessage, e);
            throw new RuntimeException("Failed to generate content: " + e.getMessage(), e);
        }
    }

    public String generateResponse(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            logger.warn("Empty messages list provided");
            return "";
        }
        
        try {
            logger.debug("Generating response for {} messages", messages.size());
            
            // 마지막 사용자 메시지를 찾아서 처리
            ChatMessage lastUserMessage = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessage msg = messages.get(i);
                if ("user".equalsIgnoreCase(msg.getRole())) {
                    lastUserMessage = msg;
                    break;
                }
            }
            
            if (lastUserMessage == null) {
                // 사용자 메시지가 없으면 첫 번째 메시지 사용
                lastUserMessage = messages.get(0);
                logger.warn("No user message found, using first message");
            }
            
            var prompt = chatClient.prompt();
            
            // 시스템 메시지가 있으면 추가
            for (ChatMessage msg : messages) {
                if ("system".equalsIgnoreCase(msg.getRole())) {
                    prompt = prompt.system(msg.getContent());
                    logger.debug("Added system message");
                }
            }
            
            // 사용자 메시지 처리
            String response = prompt.user(lastUserMessage.getContent())
                    .call()
                    .content();
            logger.debug("Generated response: {}", response);
            return response;
        } catch (Exception e) {
            logger.error("Failed to generate response for messages", e);
            // 더 자세한 에러 정보 로깅
            Throwable cause = e;
            int depth = 0;
            while (cause != null && depth < 5) {
                logger.error("Error at depth {}: {} - {}", depth, cause.getClass().getSimpleName(), cause.getMessage());
                if (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                    depth++;
                } else {
                    break;
                }
            }
            throw new RuntimeException("Failed to generate content: " + e.getMessage(), e);
        }
    }
}
