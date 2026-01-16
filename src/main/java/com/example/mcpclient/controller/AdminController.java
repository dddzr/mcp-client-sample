package com.example.mcpclient.controller;

import com.example.mcpclient.model.ChatMessage;
import com.example.mcpclient.model.McpRequest;
import com.example.mcpclient.model.McpResponse;
import com.example.mcpclient.service.GeminiService;
import com.example.mcpclient.service.McpServerConnectionInterface;
import com.example.mcpclient.service.McpServerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 특수/디버깅/백도어 컨트롤러
 */
@RestController
@RequestMapping("/mcp/admin")
public class AdminController {
    
    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    private final GeminiService geminiService;
    private final McpServerRegistry serverRegistry;
    private final Environment environment;
    
    public AdminController(
            GeminiService geminiService,
            McpServerRegistry serverRegistry,
            Environment environment) {
        this.geminiService = geminiService;
        this.serverRegistry = serverRegistry;
        this.environment = environment;
    }
    
    /**
     * MCP 프로토콜 직접 요청
     */
    @PostMapping("/protocol")
    public ResponseEntity<?> processMcpRequest(
            @RequestBody McpRequest request,
            @RequestParam(required = false, defaultValue = "mcp-server-sample") String serverName) {
        try {
            logger.info("Received MCP protocol request for server {}: {}", serverName, request);
            if (request == null) {
                logger.warn("Request body is null");
                return ResponseEntity.badRequest().body(Map.of("error", "Request body is required"));
            }
            if (request.getMethod() == null || request.getMethod().isEmpty()) {
                logger.warn("Method is null or empty");
                return ResponseEntity.badRequest().body(Map.of("error", "Method is required"));
            }
            
            // MCP 서버로 요청 전달
            McpServerConnectionInterface connection = serverRegistry.getServerConnection(serverName);
            if (connection == null) {
                return ResponseEntity.status(500).body(Map.of(
                    "error", "Server not connected",
                    "message", "No connection found for server: " + serverName
                ));
            }
            McpResponse response = connection.sendRequest(serverName, request);
            logger.info("Response from server {}: {}", serverName, response);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            logger.error("Server not connected: {}", serverName, e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Server not connected",
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Error processing MCP protocol request", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Error processing request",
                "message", e.getMessage()
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
            
            // ChatMessage 리스트로 변환
            List<ChatMessage> chatMessages = new java.util.ArrayList<>();
            for (Map<String, Object> msg : messages) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                chatMessages.add(new ChatMessage(role, content));
            }
            
            // Gemini 직접 호출
            String response = geminiService.generateResponse(chatMessages);
            logger.info("Gemini response: {}", response);
            
            return ResponseEntity.ok(Map.of(
                "role", "assistant",
                "content", response != null ? response : "No response"
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
     * 헬스 체크
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("MCP Client is running");
    }
}
