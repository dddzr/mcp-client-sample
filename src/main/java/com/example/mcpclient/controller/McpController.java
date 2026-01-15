package com.example.mcpclient.controller;

import com.example.mcpclient.config.McpServerConfig;
import com.example.mcpclient.model.McpRequest;
import com.example.mcpclient.model.McpResponse;
import com.example.mcpclient.service.GeminiService;
import com.example.mcpclient.service.McpChatService;
import com.example.mcpclient.service.McpClientService;
import com.example.mcpclient.service.McpServerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/mcp")
public class McpController {
    
    private static final Logger logger = LoggerFactory.getLogger(McpController.class);
    private final McpClientService mcpClientService;
    private final McpServerRegistry serverRegistry;
    private final Environment environment;

    public McpController(McpClientService mcpClientService, McpServerRegistry serverRegistry, Environment environment, McpChatService mcpChatService) {
        this.mcpClientService = mcpClientService;
        this.serverRegistry = serverRegistry;
        this.environment = environment;
        this.mcpChatService = mcpChatService;
    }

    /**
     * MCP 서버로 요청 전달 (MCP 프로토콜)
     * 기본 서버로 요청을 전달하거나, 서버 이름을 지정할 수 있음
     */
    @PostMapping("/request")
    public ResponseEntity<?> processMcpRequest(
            @RequestBody McpRequest request,
            @RequestParam(required = false, defaultValue = "mcp-server-sample") String serverName) {
        try {
            logger.info("Received MCP request for server {}: {}", serverName, request);
            if (request == null) {
                logger.warn("Request body is null");
                return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
            }
            if (request.getMethod() == null || request.getMethod().isEmpty()) {
                logger.warn("Method is null or empty");
                return ResponseEntity.badRequest().body(Map.of("error", "Method is required"));
            }
            
            // MCP 서버로 요청 전달
            McpResponse response = serverRegistry.getServerConnection()
                    .sendRequest(serverName, request);
            logger.info("Response from server {}: {}", serverName, response);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            logger.error("Server not connected: {}", serverName, e);
            // 서버 연결 실패 시 Gemini로 에러 메시지를 사용자 친화적으로 변환
            return handleErrorWithGemini("MCP 서버에 연결할 수 없습니다: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error processing MCP request", e);
            // 에러 발생 시 Gemini로 에러 메시지를 사용자 친화적으로 변환
            return handleErrorWithGemini("요청 처리 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
    
    /**
     * 에러를 Gemini를 통해 사용자 친화적인 메시지로 변환
     */
    private ResponseEntity<?> handleErrorWithGemini(String errorMessage) {
        try {
            McpRequest geminiRequest = new McpRequest();
            geminiRequest.setMethod("chat/completion");
            geminiRequest.setParams(Map.of(
                "messages", List.of(Map.of(
                    "role", "user",
                    "content", "다음 에러 메시지를 사용자에게 친화적으로 설명해주세요: " + errorMessage
                ))
            ));
            geminiRequest.setId(String.valueOf(System.currentTimeMillis()));
            
            McpResponse response = mcpClientService.processRequest(geminiRequest);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error calling Gemini for error message", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Internal server error",
                "message", errorMessage
            ));
        }
    }
    
    /**
     * Gemini 직접 호출 (테스트용)
     * MCP 서버를 거치지 않고 Gemini에 직접 요청
     */
    @PostMapping("/gemini/chat")
    public ResponseEntity<?> chatWithGemini(@RequestBody Map<String, Object> chatRequest) {
        try {
            logger.info("Direct Gemini request: {}", chatRequest);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) chatRequest.get("messages");
            
            if (messages == null || messages.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Messages are required"));
            }
            
            // Gemini 직접 호출
            McpRequest mcpRequest = new McpRequest();
            mcpRequest.setMethod("chat/completion");
            mcpRequest.setParams(chatRequest);
            mcpRequest.setId(String.valueOf(System.currentTimeMillis()));
            
            McpResponse response = mcpClientService.processRequest(mcpRequest);
            logger.info("Gemini response: {}", response);
            
            // 에러가 있으면 에러 반환
            if (response.getError() != null) {
                return ResponseEntity.status(500).body(Map.of(
                    "error", "Gemini error",
                    "code", response.getError().getCode(),
                    "message", response.getError().getMessage()
                ));
            }
            
            // Gemini 응답 내용 추출
            String geminiResponse = extractGeminiResponse(response);
            
            // 원본 응답과 파싱된 응답 모두 반환
            return ResponseEntity.ok(Map.of(
                "geminiResponse", geminiResponse != null ? geminiResponse : "No response",
                "fullResponse", response
            ));
        } catch (Exception e) {
            logger.error("Error calling Gemini", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Error calling Gemini",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * McpResponse에서 Gemini 응답 텍스트 추출
     */
    private String extractGeminiResponse(McpResponse response) {
        try {
            if (response.getResult() == null) {
                return null;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> result = response.getResult() instanceof Map ? 
                (Map<String, Object>) response.getResult() : null;
            
            if (result == null) {
                return null;
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
            
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            
            Map<String, Object> firstChoice = choices.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
            
            if (message == null) {
                return null;
            }
            
            return (String) message.get("content");
        } catch (Exception e) {
            logger.error("Error extracting Gemini response", e);
            return null;
        }
    }
    
    @org.springframework.web.bind.annotation.ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonParseException(org.springframework.http.converter.HttpMessageNotReadableException e) {
        logger.error("JSON parse error: {}", e.getMessage(), e);
        String errorMsg = e.getMessage();
        if (errorMsg != null && errorMsg.contains("UTF-8")) {
            errorMsg = "UTF-8 인코딩 오류입니다. Git Bash에서는 한글 대신 영문을 사용하거나, 파일로 JSON을 전송하세요.";
        }
        return ResponseEntity.badRequest().body(Map.of(
            "error", "Invalid JSON format",
            "message", errorMsg != null ? errorMsg : "JSON 파싱 오류",
            "hint", "Git Bash에서 한글을 사용할 때는 UTF-8 인코딩 문제가 발생할 수 있습니다. 영문으로 테스트하거나 파일을 사용하세요."
        ));
    }

    private final McpChatService mcpChatService;

    /**
     * MCP 서버를 통한 채팅 요청
     * Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출
     */
    @PostMapping("/servers/{serverName}/chat")
    public ResponseEntity<Map<String, Object>> chatWithServer(
            @PathVariable String serverName,
            @RequestBody Map<String, Object> chatRequest) {
        try {
            if (!serverRegistry.isServerRegistered(serverName)) {
                return ResponseEntity.notFound().build();
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) chatRequest.get("messages");
            if (messages == null || messages.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "messages required"));
            }
            
            // 마지막 사용자 메시지 찾기
            String userMessage = null;
            for (int i = messages.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messages.get(i);
                if ("user".equalsIgnoreCase((String) msg.get("role"))) {
                    userMessage = (String) msg.get("content");
                    break;
                }
            }
            
            if (userMessage == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "user message not found"));
            }
            
            // Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출
            String response = mcpChatService.chatWithServer(serverName, userMessage);
            
            return ResponseEntity.ok(Map.of(
                "role", "assistant",
                "content", response
            ));
        } catch (Exception e) {
            logger.error("Error in chatWithServer for server: {}", serverName, e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Error processing chat request: " + e.getMessage()
            ));
        }
    }

    /**
     * 헬스 체크
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("MCP Client is running");
    }
    
    /**
     * Gemini 설정 확인
     */
    @GetMapping("/gemini/config")
    public ResponseEntity<?> checkGeminiConfig() {
        try {
            String apiKey = environment.getProperty("spring.ai.google.genai.api-key");
            String vertexProjectId = environment.getProperty("spring.ai.vertex.ai.gemini.project-id");
            String model = environment.getProperty("spring.ai.google.genai.chat.options.model", 
                           environment.getProperty("spring.ai.vertex.ai.gemini.chat.options.model", "gemini-pro"));
            
            boolean usingApiKey = apiKey != null && !apiKey.isEmpty();
            boolean usingVertex = vertexProjectId != null && !vertexProjectId.isEmpty();
            
            Map<String, Object> config = new HashMap<>();
            config.put("authenticationMethod", usingApiKey ? "API Key" : (usingVertex ? "Vertex AI" : "Not configured"));
            config.put("model", model);
            
            if (usingApiKey) {
                config.put("apiKeyConfigured", true);
                config.put("apiKeyPrefix", apiKey != null && apiKey.length() > 10 ? 
                    apiKey.substring(0, 10) + "..." : "not set");
                config.put("hint", "Using API Key authentication (no credentials.json needed)");
            } else if (usingVertex) {
                config.put("vertexAiConfigured", true);
                config.put("projectId", vertexProjectId);
                config.put("hint", "Using Vertex AI (credentials.json may be required)");
            } else {
                config.put("hint", "No authentication method configured. Please set spring.ai.google.genai.api-key or spring.ai.vertex.ai.gemini settings.");
            }
            
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            logger.error("Error checking config", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Error checking config",
                "message", e.getMessage()
            ));
        }
    }
    
    /**
     * Gemini 연결 테스트
     */
    @GetMapping("/gemini/test")
    public ResponseEntity<?> testGemini() {
        try {
            McpRequest testRequest = new McpRequest();
            testRequest.setMethod("chat/completion");
            testRequest.setParams(Map.of("messages", List.of(Map.of("role", "user", "content", "Hello, please respond with a simple greeting."))));
            testRequest.setId("test-1");
            
            McpResponse response = mcpClientService.processRequest(testRequest);
            logger.info("Gemini test response: {}", response);
            
            if (response.getError() != null) {
                return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "message", "Gemini 에러: " + response.getError().getMessage(),
                    "code", response.getError().getCode()
                ));
            }
            
            // Gemini 응답 내용 추출
            String geminiResponse = extractGeminiResponse(response);
            
            return ResponseEntity.ok(Map.of(
                "status", "connected",
                "message", "Gemini 연결 성공",
                "geminiResponse", geminiResponse != null ? geminiResponse : "No response from Gemini",
                "testQuestion", "Hello, please respond with a simple greeting."
            ));
        } catch (Exception e) {
            logger.error("Gemini connection test failed", e);
            return ResponseEntity.status(500).body(Map.of(
                "status", "error",
                "message", "Gemini 연결 실패: " + e.getMessage(),
                "exception", e.getClass().getSimpleName()
            ));
        }
    }

    /**
     * MCP 서버 등록
     */
    @PostMapping("/servers/{serverName}")
    public ResponseEntity<Map<String, Object>> registerServer(
            @PathVariable String serverName,
            @RequestBody McpServerConfig.McpServerInfo serverInfo) {
        try {
            serverRegistry.registerServer(serverName, serverInfo);
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Server registered: " + serverName
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "status", "error",
                "message", "Failed to register server: " + e.getMessage()
            ));
        }
    }

    /**
     * 등록된 서버 목록 조회
     */
    @GetMapping("/servers")
    public ResponseEntity<Set<String>> listServers() {
        return ResponseEntity.ok(serverRegistry.getRegisteredServerNames());
    }

    /**
     * 특정 서버 정보 조회
     */
    @GetMapping("/servers/{serverName}")
    public ResponseEntity<McpServerConfig.McpServerInfo> getServer(@PathVariable String serverName) {
        McpServerConfig.McpServerInfo server = serverRegistry.getServer(serverName);
        if (server == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(server);
    }

    /**
     * 서버 등록 해제
     */
    @DeleteMapping("/servers/{serverName}")
    public ResponseEntity<Map<String, Object>> unregisterServer(@PathVariable String serverName) {
        if (!serverRegistry.isServerRegistered(serverName)) {
            return ResponseEntity.notFound().build();
        }
        serverRegistry.unregisterServer(serverName);
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Server unregistered: " + serverName
        ));
    }

    /**
     * 서버의 도구 목록 조회
     */
    @GetMapping("/servers/{serverName}/tools")
    public ResponseEntity<List<Map<String, Object>>> getServerTools(@PathVariable String serverName) {
        if (!serverRegistry.isServerRegistered(serverName)) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> tools = serverRegistry.getServerTools(serverName);
        return ResponseEntity.ok(tools);
    }

    /**
     * 모든 서버의 도구 목록 조회
     */
    @GetMapping("/tools")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> getAllServerTools() {
        return ResponseEntity.ok(serverRegistry.getAllServerTools());
    }
}
