package com.example.mcpclient.service;

import com.example.mcpclient.model.McpRequest;
import com.example.mcpclient.model.McpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 서버를 통한 채팅 서비스
 * Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출하도록 처리
 */
@Service
public class McpChatService {
    
    private static final Logger logger = LoggerFactory.getLogger(McpChatService.class);
    private final McpServerRegistry serverRegistry;
    private final McpServerConnection serverConnection;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    
    // 서버별 ChatClient 캐시
    private final Map<String, ChatClient> chatClientCache = new HashMap<>();
    
    public McpChatService(
            McpServerRegistry serverRegistry,
            McpServerConnection serverConnection,
            ChatModel chatModel,
            ObjectMapper objectMapper) {
        this.serverRegistry = serverRegistry;
        this.serverConnection = serverConnection;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }
    
    /**
     * MCP 서버를 통한 채팅 요청 처리
     * Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출
     */
    public String chatWithServer(String serverName, String userMessage) {
        try {
            logger.info("=== Starting chat request for server: {} ===", serverName);
            logger.info("User message: {}", userMessage);
            
            // 서버별 ChatClient 가져오기 또는 생성 (MCP 서버의 도구가 Function으로 등록됨)
            logger.debug("Getting or creating ChatClient for server: {}", serverName);
            ChatClient chatClient = getOrCreateChatClient(serverName);
            
            if (chatClient == null) {
                throw new IllegalStateException("Failed to create ChatClient for server: " + serverName);
            }
            
            logger.info("ChatClient ready. Sending request to Gemini...");
            long startTime = System.currentTimeMillis();
            
            // Gemini에게 사용자 메시지 전달
            // Gemini가 도구를 선택하면 자동으로 호출됨
            String response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
            
            long elapsedTime = System.currentTimeMillis() - startTime;
            logger.info("=== Chat request completed in {}ms ===", elapsedTime);
            logger.debug("Gemini response: {}", response);
            
            return response;
        } catch (Exception e) {
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
            try {
                logger.info("Calling tool {} on server {} with input: {}", toolName, serverName, toolInput);
                
                // JSON 문자열을 Map으로 파싱
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = objectMapper.readValue(toolInput, Map.class);
                
                // MCP 서버로 도구 호출
                return callMcpTool(serverName, toolName, arguments);
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
            
            McpResponse response = serverConnection.sendRequest(serverName, request);
            
            long elapsedTime = System.currentTimeMillis() - startTime;
            logger.info("Received response from MCP server after {}ms", elapsedTime);
            
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
            logger.debug("Tool {} returned: {}", toolName, result);
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
}
