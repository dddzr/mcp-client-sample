package com.example.mcpclient.service;

import com.example.mcpclient.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Gemini AI 모델 직접 호출 서비스
 * Spring AI ChatClient를 사용하여 Gemini에 직접 요청을 전송하고 응답을 반환
 * 단일 메시지 또는 메시지 리스트를 처리
 */
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
            return handleGeminiException(e, "Failed to generate response for messages");
        }
    }
    
    /**
     * ChatClient를 사용하여 메시지 리스트로 응답 생성 (도구 등록 가능)
     * McpChatService에서 도구가 등록된 ChatClient를 사용할 때 호출
     */
    public String generateResponseWithChatClient(ChatClient chatClient, List<Message> messages) {
        if (chatClient == null) {
            throw new IllegalArgumentException("ChatClient cannot be null");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be null or empty");
        }
        
        long startTime = System.currentTimeMillis();
        try {
            logger.info("=== Starting Gemini API call ===");
            
            // 시스템 프롬프트 추가: 모든 질문에 답변하도록 지시
            // TODO: AI 모델 변경 테스트,
            // call() 후 체이닝으로 content() 호출 (Spring AI는 체이닝 방식 사용)
            String content = null;
            content = chatClient.prompt()
                    .system("사용자가 여러 질문을 한 번에 할 수 있습니다. 도구 호출 후 반드시 남은 사용자 질문에 대해 텍스트로 이어서 답변할 것. 도구 호출만 하고 대화를 중단하지 말 것.")
                    .messages(messages)
                    .call()
                    .content();

            // content가 비어있거나 null인 경우 처리
            if (content == null || content.isBlank()) {
                logger.warn("Content is empty, trying to get chatResponse");
                // chatResponse에서 전체 응답 구조 가져오기
                ChatResponse raw = chatClient.prompt()
                        .system("""
                                사용자는 한 메시지에 여러 질문을 할 수 있습니다.
                                모든 질문에 대해 빠짐없이, 순서대로, 텍스트로 답변해야 합니다.
                                도구가 필요할 경우 도구를 호출하고, 도구가 필요 없는 질문에는 즉시 답변하십시오.
                                도구 호출 후에도 남은 질문이 있으면 반드시 이어서 답변하십시오.
                                절대로 질문을 누락하거나 대화를 수정하거나 우회하지 마십시오.
                                """)
                        .messages(messages)
                        .call()
                        .chatResponse();
                logger.info("TEST@ RAW RESPONSE = {}", raw);
                // ChatResponse에서 텍스트 추출 시도
                if (raw != null && raw.getResult() != null && raw.getResult().getOutput() != null) {
                    content = raw.getResult().getOutput().getText();
                    logger.info("TEST@ CONTENT = {}", content);
                    List<ToolCall> toolCalls = raw.getResult().getOutput().getToolCalls();
                    logger.info("TEST@ TOOL CALLS = {}", toolCalls);
                }
                
                if (content == null || content.isBlank()) {
                    return raw != null ? raw.toString() : "Empty response";
                }
            }
            
            /*
            ChatResponse resp = chatClient.prompt().messages(messages).call().chatResponse();

            for (Generation gen : resp.getResults()) {
                AssistantMessage output = (AssistantMessage) gen.getOutput();
                if (output.getToolCalls() != null) {
                    // 1) tool 호출
                    ToolCall tc = output.getToolCalls().get(0);
                    Object toolResult = callTool(tc);
                    
                    // 2) tool 결과를 모델에게 다시 보냄
                    resp = chatClient.prompt()
                            .messages(messages)
                            .messages(new ToolResponseMessage(tc.getId(), toolResult))
                            .call().chatResponse();
                }
            }

            return resp.getContent();

            */
            long afterCall = System.currentTimeMillis();
            long elapsed = afterCall - startTime;
            logger.info("=== Gemini API call completed in {}ms ===", elapsed);
            // logger.debug("Generated response length: {} chars", response != null ? response.length() : 0);

            return content;
        } catch (Exception e) {
            return handleGeminiException(e, "Failed to generate response with custom ChatClient");
        }
    }
    
    /**
     * Gemini API 예외 처리 공통 메서드
     */
    private String handleGeminiException(Exception e, String errorContext) {
        // Gemini API 할당량 초과 에러(429) 처리
        if (isQuotaExceededError(e)) {
            String message = extractQuotaErrorMessage(e);
            // 할당량 초과는 정상적인 비즈니스 로직이므로 스택 트레이스 로깅하지 않음
            logger.warn("Gemini API quota exceeded - {}", message);
            return message;
        }
        
        // 할당량 초과가 아닌 에러는 상세 로깅
        logger.error(errorContext, e);
        throw new RuntimeException("Failed to generate content: " + e.getMessage(), e);
    }
    
    /**
     * 할당량 초과 예외 정보에서 핵심 정보 추출
     * Free tier은 그냥 하루에 20번 제한임.
     */
    private String extractQuotaErrorMessage(Exception e) {
        // Throwable cause = e.getCause();
        // if (cause == null) {
        //     return "Quota exceeded";
        // }
        // String message = cause.getMessage();

        // // "Please retry in X.XXs." 패턴 찾기
        // java.util.regex.Pattern retryPattern = java.util.regex.Pattern.compile(
        //     "retry\\s+in\\s+(\\d+(?:\\.\\d+)?)\\s*s\\.?", 
        //     java.util.regex.Pattern.CASE_INSENSITIVE
        // );
        // java.util.regex.Matcher matcher = retryPattern.matcher(message);
        // if (matcher.find()) {
        //     try {
        //         double retrySeconds = Double.parseDouble(matcher.group(1));
        //         return String.format("Quota exceeded, retry in %.1fs", retrySeconds);
        //     } catch (NumberFormatException ex) {
        //         return "Quota exceeded";
        //     }
        // }
        
        // // "limit: X" 패턴 찾기
        // java.util.regex.Pattern limitPattern = java.util.regex.Pattern.compile(
        //     "limit\\s*:\\s*(\\d+)", 
        //     java.util.regex.Pattern.CASE_INSENSITIVE
        // );
        // matcher = limitPattern.matcher(message);
        // if (matcher.find()) {
        //     return String.format("Quota exceeded (limit: %s)", matcher.group(1));
        // }
        
        return "Quota exceeded";
    }
    
    /**
     * 할당량 초과 에러인지 확인
     */
    public boolean isQuotaExceededError(Exception e) {
        // 예외 메시지에서 할당량 관련 키워드 확인
        String errorMessage = e.getMessage();
        if (errorMessage != null) {
            String lowerMessage = errorMessage.toLowerCase();
            if (lowerMessage.contains("quota") || 
                lowerMessage.contains("429") || 
                lowerMessage.contains("rate limit") ||
                lowerMessage.contains("exceeded") ||
                lowerMessage.contains("free_tier_requests")) {
                return true;
            }
        }
        // 예외 클래스 이름 확인
        String exceptionClassName = e.getClass().getName();
        if (exceptionClassName.contains("ClientException") || exceptionClassName.contains("429")) {
            // ClientException이고 메시지에 429 관련 내용이 있으면 할당량 초과로 판단
            if (errorMessage != null && errorMessage.contains("429")) {
                return true;
            }
        }
        
        // 원인 예외도 확인
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null) {
                String lowerCauseMessage = causeMessage.toLowerCase();
                if (lowerCauseMessage.contains("quota") || 
                    lowerCauseMessage.contains("429") || 
                    lowerCauseMessage.contains("rate limit") ||
                    lowerCauseMessage.contains("exceeded") ||
                    lowerCauseMessage.contains("free_tier_requests")) {
                    return true;
                }
            }
            
            // 원인 예외 클래스 이름 확인
            String causeClassName = cause.getClass().getName();
            if (causeClassName.contains("ClientException")) {
                if (causeMessage != null && causeMessage.contains("429")) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
}
