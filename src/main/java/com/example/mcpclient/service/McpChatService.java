package com.example.mcpclient.service;

import com.example.mcpclient.model.McpRequest;
import com.example.mcpclient.model.McpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 서버를 통한 채팅 서비스
 * Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출하도록 처리
 */
@Service
public class McpChatService {
    
    private static final Logger logger = LoggerFactory.getLogger(McpChatService.class);
    private final McpServerRegistry serverRegistry;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final GeminiService geminiService;
    
    // 서버별 ChatClient 캐시
    private final Map<String, ChatClient> chatClientCache = new HashMap<>();
    
    // 세션별 대화 히스토리 저장 (세션ID -> 히스토리)
    private final Map<String, ConversationSession> conversationSessions = new ConcurrentHashMap<>();
    
    // 히스토리 TTL (기본 30분)
    private static final long HISTORY_TTL_MS = 30 * 60 * 1000; // 30분
    // 최대 히스토리 길이 (메모리 관리)
    private static final int MAX_HISTORY_SIZE = 50;
    
    // 현재 요청의 세션 ID를 ThreadLocal로 저장 (도구 호출 시 사용)
    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    
    /**
     * 대화 세션 정보
     */
    private static class ConversationSession {
        private final String sessionId;
        private final List<Map<String, Object>> history;
        private long lastAccessTime;
        
        public ConversationSession(String sessionId) {
            this.sessionId = sessionId;
            this.history = new ArrayList<>();
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        public void addMessage(Map<String, Object> message) {
            history.add(message);
            this.lastAccessTime = System.currentTimeMillis();
            
            // 최대 히스토리 길이 제한 (오래된 메시지 제거)
            if (history.size() > MAX_HISTORY_SIZE) {
                // 가장 오래된 메시지부터 제거 (최소 2개는 유지)
                int removeCount = history.size() - MAX_HISTORY_SIZE;
                for (int i = 0; i < removeCount && history.size() > 2; i++) {
                    history.remove(0);
                }
            }
        }
        
        public List<Map<String, Object>> getHistory() {
            this.lastAccessTime = System.currentTimeMillis();
            return new ArrayList<>(history);
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - lastAccessTime > HISTORY_TTL_MS;
        }
        
        public String getSessionId() {
            return sessionId;
        }
    }
    
    public McpChatService(
            McpServerRegistry serverRegistry,
            ChatModel chatModel,
            ObjectMapper objectMapper,
            GeminiService geminiService) {
        this.serverRegistry = serverRegistry;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.geminiService = geminiService;
    }
    
    /**
     * MCP 서버를 통한 채팅 요청 처리
     * Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출
     * 
     * @param serverName 서버 이름
     * @param messages 대화 히스토리 (role: user/assistant)
     * @param sessionId 세션 ID (선택사항, 없으면 자동 생성)
     * @return ChatResponse (응답과 세션 ID 포함)
     */
    public ChatResponse chatWithServer(String serverName, List<Map<String, Object>> messages, String sessionId) {
        logger.info("=== McpChatService.chatWithServer called for server: {} ===", serverName);
        try {
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("Messages cannot be null or empty");
            }
            
            // 세션 ID가 없으면 자동 생성
            if (sessionId == null || sessionId.trim().isEmpty()) {
                sessionId = UUID.randomUUID().toString();
                logger.debug("Auto-generated session ID: {}", sessionId);
            }
            
            // 현재 요청의 세션 ID를 ThreadLocal에 저장 (도구 호출 시 사용)
            currentSessionId.set(sessionId);
            
            try {
                // 세션별 히스토리 관리
                ConversationSession session = conversationSessions.computeIfAbsent(sessionId, ConversationSession::new);
                
                // 마지막 사용자 메시지 찾기
            Map<String, Object> lastUserMessage = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messages.get(i);
                if ("user".equalsIgnoreCase((String) msg.get("role"))) {
                    lastUserMessage = msg;
                    break;
                }
            }
            
            // 세션 히스토리에 새 메시지 추가
            if (lastUserMessage != null) {
                session.addMessage(lastUserMessage);
            }
            
            // 세션 히스토리와 새 메시지를 합쳐서 사용
            List<Map<String, Object>> fullHistory = new ArrayList<>(session.getHistory());
            // 새 메시지가 히스토리에 없으면 추가 (중복 방지)
            if (lastUserMessage != null && !fullHistory.contains(lastUserMessage)) {
                fullHistory.add(lastUserMessage);
            }
            
            logger.info("=== Starting chat request for server: {}, session: {} ===", serverName, sessionId);
            logger.info("Session history size: {}, New messages count: {}", session.getHistory().size(), messages.size());
            
            // 서버별 ChatClient 가져오기 또는 생성 (MCP 서버의 도구가 Function으로 등록됨)
            logger.debug("Getting or creating ChatClient for server: {}", serverName);
            ChatClient chatClient = getOrCreateChatClient(serverName);
            
            if (chatClient == null) {
                throw new IllegalStateException("Failed to create ChatClient for server: " + serverName);
            }
            
            // 대화 히스토리를 Spring AI Message 타입으로 변환 (세션 히스토리 사용)
            List<Message> springAiMessages = new ArrayList<>();
            for (Map<String, Object> msg : fullHistory) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                
                if (content == null) {
                    continue;
                }
                
                if ("user".equalsIgnoreCase(role)) {
                    springAiMessages.add(new UserMessage(content));
                } else if ("assistant".equalsIgnoreCase(role)) {
                    springAiMessages.add(new AssistantMessage(content));
                } else {
                    logger.warn("Unknown message role: {}, skipping", role);
                }
            }
            
            // Gemini에게 대화 히스토리와 함께 요청 전달
            // Gemini가 도구를 선택하면 자동으로 호출됨
            String response;
            if (springAiMessages.isEmpty()) {
                // 메시지가 없으면 에러
                throw new IllegalArgumentException("No valid messages found");
            } else {
                response = geminiService.generateResponseWithChatClient(chatClient, springAiMessages);
                
                // 할당량 초과 메시지는 세션 히스토리에 추가하지 않고 바로 반환
                if (response != null && response.startsWith("Quota exceeded")) {
                    return new ChatResponse(response, sessionId);
                }
            }
            logger.debug("Gemini response: {}", response);
            
            // Gemini 응답을 세션 히스토리에 추가
            Map<String, Object> assistantMessage = new HashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", response);
            session.addMessage(assistantMessage);
            
            return new ChatResponse(response, sessionId);
            } finally {
                // ThreadLocal 정리 (메모리 누수 방지)
                currentSessionId.remove();
            }
        } catch (Exception e) {
            // ThreadLocal 정리 (에러 발생 시에도)
            currentSessionId.remove();
            logger.error("=== Error in chatWithServer for server: {} ===", serverName, e);
            if (e.getCause() != null) {
                logger.error("Root cause: {}", e.getCause().getMessage(), e.getCause());
            }
            throw new RuntimeException("Failed to process chat request: " + e.getMessage(), e);
        }
    }
    
    /**
     * 서버별 ChatClient 가져오기 또는 생성
     * MCP 서버의 도구를 Function으로 등록
     */
    private ChatClient getOrCreateChatClient(String serverName) {
        if (chatClientCache.containsKey(serverName)) {
            return chatClientCache.get(serverName);
        }
        
        // MCP 서버의 도구 목록 가져오기
        List<Map<String, Object>> tools = serverRegistry.getServerTools(serverName);
        if (tools == null || tools.isEmpty()) {
            logger.warn("No tools found for server: {}", serverName);
            // 도구가 없어도 기본 ChatClient 반환
            ChatClient basicClient = ChatClient.builder(chatModel).build();
            chatClientCache.put(serverName, basicClient);
            return basicClient;
        }
        
        // MCP 서버의 도구를 Spring AI ToolCallback으로 변환
        List<ToolCallback> toolCallbacks = tools.stream()
                .map(tool -> createToolCallback(serverName, tool))
                .collect(Collectors.toList());
        
        // ChatClient 생성 (도구 등록)
        // ChatClient.Builder에는 defaultToolCallbacks() 메서드 사용
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbacks)
                .build();
        
        chatClientCache.put(serverName, chatClient);
        logger.info("Created ChatClient for server {} with {} tool callbacks", serverName, toolCallbacks.size());
        
        return chatClient;
    }
    
    /**
     * MCP tool → Spring AI ToolCallback 변환
     */
    @SuppressWarnings("unchecked")
    private ToolCallback createToolCallback(String serverName, Map<String, Object> tool) {
        String toolName = (String) tool.get("name");
        String description = (String) tool.getOrDefault("description", "");
        Map<String, Object> inputSchema = (Map<String, Object>) tool.getOrDefault("inputSchema", new HashMap<>());

        logger.info("Registering MCP tool → {}", toolName);

        // ToolCallback 직접 구현
        return new McpToolCallback(serverName, toolName, description, inputSchema);
    }
    
    /**
     * MCP 서버의 도구를 호출하는 ToolCallback 구현
     */
    private class McpToolCallback implements ToolCallback {
        private final String serverName;
        private final String toolName;
        private final String description;
        private final Map<String, Object> inputSchema;
        
        public McpToolCallback(String serverName, String toolName, String description, Map<String, Object> inputSchema) {
            this.serverName = serverName;
            this.toolName = toolName;
            this.description = description;
            this.inputSchema = inputSchema;
        }
        
        @Override
        public ToolDefinition getToolDefinition() {
            try {
                // inputSchema를 JSON 문자열로 변환
                String inputSchemaJson = objectMapper.writeValueAsString(inputSchema);
                return ToolDefinition.builder()
                        .name(toolName)
                        .description(description)
                        .inputSchema(inputSchemaJson)
                        .build();
            } catch (Exception e) {
                logger.error("Error creating ToolDefinition for tool {}", toolName, e);
                // 기본 ToolDefinition 반환
                return ToolDefinition.builder()
                        .name(toolName)
                        .description(description)
                        .build();
            }
        }
        
        @Override
        public String call(String toolInput) {
            long toolCallStart = System.currentTimeMillis();
            try {
                logger.info("=== ToolCallback.call() invoked for tool {} on server {} ===", toolName, serverName);
                logger.info("Tool input: {}", toolInput);
                
                // JSON 문자열을 Map으로 파싱
                long beforeParse = System.currentTimeMillis();
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = objectMapper.readValue(toolInput, Map.class);
                long afterParse = System.currentTimeMillis();
                logger.debug("Parsing tool input took {}ms", afterParse - beforeParse);
                
                // 쿠키에서 추출한 세션 ID가 있으면 arguments에 자동 추가
                // MCP 서버의 도구들이 session_id 파라미터를 필요로 할 수 있음
                String sessionIdFromCookie = currentSessionId.get();
                if (sessionIdFromCookie != null && !sessionIdFromCookie.trim().isEmpty()) {
                    // arguments에 session_id가 없으면 추가
                    if (!arguments.containsKey("session_id") && !arguments.containsKey("sessionId")) {
                        arguments.put("session_id", sessionIdFromCookie);
                        logger.info("Auto-added session_id to tool arguments: {}", sessionIdFromCookie);
                    } else {
                        logger.debug("session_id already present in arguments, skipping auto-add");
                    }
                } else {
                    logger.debug("No session_id available from cookie, skipping auto-add");
                }
                
                // MCP 서버로 도구 호출
                String result = callMcpTool(serverName, toolName, arguments);
                long toolCallEnd = System.currentTimeMillis();
                long toolCallElapsed = toolCallEnd - toolCallStart;
                logger.info("=== ToolCallback.call() completed in {}ms ===", toolCallElapsed);
                
                if (toolCallElapsed > 10000) {
                    logger.warn("⚠️ Tool call took {}ms (>10s), this is unusually slow!", toolCallElapsed);
                }
                
                return result;
            } catch (Exception e) {
                logger.error("Error calling tool {} on server {}", toolName, serverName, e);
                // 에러도 JSON 형식으로 반환 (Gemini가 파싱할 수 있도록)
                try {
                    Map<String, Object> errorResult = new HashMap<>();
                    errorResult.put("error", true);
                    errorResult.put("message", "Error parsing tool input: " + e.getMessage());
                    errorResult.put("exceptionType", e.getClass().getSimpleName());
                    return objectMapper.writeValueAsString(errorResult);
                } catch (Exception jsonError) {
                    logger.error("Failed to convert error to JSON", jsonError);
                    return "{\"error\":true,\"message\":\"Internal error: " + e.getMessage().replace("\"", "\\\"") + "\"}";
                }
            }
        }
    }
    
    /**
     * MCP 서버의 도구 호출
     */
    private String callMcpTool(String serverName, String toolName, Map<String, Object> arguments) {
        try {
            logger.info("=== Starting tool call: {} on server {} ===", toolName, serverName);
            logger.info("Tool arguments: {}", arguments);
            
            // MCP 서버로 tools/call 요청 전송
            McpRequest request = new McpRequest();
            request.setMethod("tools/call");
            Map<String, Object> params = new HashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments);
            request.setParams(params);
            request.setId("tool-call-" + System.currentTimeMillis());
            
            logger.info("Sending tools/call request to MCP server: {}", request.getId());
            long startTime = System.currentTimeMillis();
            
            McpServerConnectionInterface connection = serverRegistry.getServerConnection(serverName);
            if (connection == null) {
                throw new IllegalStateException("No connection found for server: " + serverName);
            }
            McpResponse response = connection.sendRequest(serverName, request);
            
            long elapsedTime = System.currentTimeMillis() - startTime;
            logger.info("Received response from MCP server after {}ms", elapsedTime);
            
            // MCP 서버 응답 전체를 로그로 출력
            logger.info("=== MCP Server Response for tool {} ===", toolName);
            try {
                String fullResponseJson = objectMapper.writeValueAsString(response);
                logger.info("Full MCP server response: {}", fullResponseJson);
            } catch (Exception e) {
                logger.warn("Failed to serialize full response to JSON: {}", e.getMessage());
                logger.info("Response ID: {}, JSON-RPC: {}", response.getId(), response.getJsonrpc());
                if (response.getError() != null) {
                    logger.info("Response error: code={}, message={}", 
                        response.getError().getCode(), response.getError().getMessage());
                } else {
                    logger.info("Response result type: {}", 
                        response.getResult() != null ? response.getResult().getClass().getSimpleName() : "null");
                }
            }
            logger.info("=== End of MCP Server Response ===");
            
            if (response.getError() != null) {
                // 에러도 JSON 형식으로 반환 (Gemini가 파싱할 수 있도록)
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", true);
                errorResult.put("message", "Tool call failed: " + response.getError().getMessage());
                errorResult.put("code", response.getError().getCode());
                String errorJson = objectMapper.writeValueAsString(errorResult);
                logger.error("MCP server returned error: {}", errorJson);
                return errorJson;
            }
            
            // 응답을 JSON 문자열로 변환하여 반환
            String result = objectMapper.writeValueAsString(response.getResult());
            logger.info("Tool {} completed successfully. Result length: {} chars", toolName, result.length());
            logger.info("Tool {} result (for Gemini): {}", toolName, result);
            return result;
        } catch (Exception e) {
            logger.error("=== Error calling tool {} on server {} ===", toolName, serverName, e);
            // 에러도 JSON 형식으로 반환 (Gemini가 파싱할 수 있도록)
            try {
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("error", true);
                errorResult.put("message", "Error calling tool: " + e.getMessage());
                if (e.getCause() != null) {
                    errorResult.put("cause", e.getCause().getMessage());
                }
                errorResult.put("exceptionType", e.getClass().getSimpleName());
                String errorJson = objectMapper.writeValueAsString(errorResult);
                logger.error("Returning error as JSON: {}", errorJson);
                return errorJson;
            } catch (Exception jsonError) {
                // JSON 변환도 실패하면 최소한의 JSON 반환
                logger.error("Failed to convert error to JSON", jsonError);
                return "{\"error\":true,\"message\":\"Internal error: " + e.getMessage().replace("\"", "\\\"") + "\"}";
            }
        }
    }
    
    /**
     * 세션별 대화 히스토리 조회
     */
    public List<Map<String, Object>> getSessionHistory(String sessionId) {
        ConversationSession session = conversationSessions.get(sessionId);
        if (session == null) {
            return Collections.emptyList();
        }
        return session.getHistory();
    }
    
    /**
     * 세션 종료 및 히스토리 삭제
     */
    public void clearSession(String sessionId) {
        ConversationSession removed = conversationSessions.remove(sessionId);
        if (removed != null) {
            logger.info("Session {} cleared, history size was: {}", sessionId, removed.getHistory().size());
        }
    }
    
    /**
     * 만료된 세션 정리 (30분마다 실행)
     */
    @Scheduled(fixedRate = 30 * 60 * 1000) // 30분마다
    public void cleanupExpiredSessions() {
        logger.debug("Starting cleanup of expired conversation sessions...");
        int initialSize = conversationSessions.size();
        
        List<String> expiredSessions = conversationSessions.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        for (String sessionId : expiredSessions) {
            conversationSessions.remove(sessionId);
            logger.debug("Removed expired session: {}", sessionId);
        }
        
        int removedCount = initialSize - conversationSessions.size();
        if (removedCount > 0) {
            logger.info("Cleaned up {} expired conversation sessions. Remaining: {}", 
                removedCount, conversationSessions.size());
        }
    }
    
    /**
     * 모든 세션 통계 조회
     */
    public Map<String, Object> getSessionStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSessions", conversationSessions.size());
        stats.put("maxHistorySize", MAX_HISTORY_SIZE);
        stats.put("historyTtlMinutes", HISTORY_TTL_MS / (60 * 1000));
        
        long totalMessages = conversationSessions.values().stream()
                .mapToInt(s -> s.getHistory().size())
                .sum();
        stats.put("totalMessages", totalMessages);
        
        return stats;
    }
    
    /**
     * 채팅 응답 결과 (응답과 세션 ID 포함)
     */
    public static class ChatResponse {
        private final String content;
        private final String sessionId;
        
        public ChatResponse(String content, String sessionId) {
            this.content = content;
            this.sessionId = sessionId;
        }
        
        public String getContent() {
            return content;
        }
        
        public String getSessionId() {
            return sessionId;
        }
    }
}
