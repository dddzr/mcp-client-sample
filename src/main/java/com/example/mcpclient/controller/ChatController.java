package com.example.mcpclient.controller;

import com.example.mcpclient.service.McpChatService;
import com.example.mcpclient.service.McpServerRegistry;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 대화 도메인 컨트롤러
 * 유저 facing API
 */
@RestController
@RequestMapping("/mcp/chat")
public class ChatController {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);
    private final McpChatService mcpChatService;
    private final McpServerRegistry serverRegistry;
    
    public ChatController(McpChatService mcpChatService, McpServerRegistry serverRegistry) {
        this.mcpChatService = mcpChatService;
        this.serverRegistry = serverRegistry;
    }
    
    /**
     * MCP 서버를 통한 채팅 요청
     * Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출
     */
    @PostMapping("/{serverName}")
    public ResponseEntity<Map<String, Object>> chatWithServer(
            @PathVariable String serverName,
            @RequestBody Map<String, Object> chatRequest,
            HttpServletRequest request) {
        try {
            if (!serverRegistry.isServerRegistered(serverName)) {
                return ResponseEntity.notFound().build();
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) chatRequest.get("messages");
            if (messages == null || messages.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "messages required"));
            }
            
            // 세션 ID 추출
            String sessionId = extractSessionId(request, chatRequest);
            logger.debug("Using sessionId: {} (from cookie: {}, from body: {})", 
                sessionId,
                getCookieValue(request, "SESSIONID") != null,
                chatRequest.get("sessionId") != null);
            
            // 전체 대화 히스토리를 Gemini에 전달 (이전 대화 내용 기억)
            // 세션별 히스토리 자동 관리 (TTL: 30분)
            // Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출
            McpChatService.ChatResponse chatResponse = mcpChatService.chatWithServer(serverName, messages, sessionId);
            
            // 응답에 세션 ID 포함 (클라이언트가 다음 요청에 사용)
            return ResponseEntity.ok(Map.of(
                "role", "assistant",
                "content", chatResponse.getContent(),
                "sessionId", chatResponse.getSessionId()
            ));
        } catch (Exception e) {
            logger.error("Error in chatWithServer for server: {}", serverName, e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Error processing chat request: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 세션별 대화 히스토리 조회
     */
    @GetMapping("/sessions/{sessionId}/history")
    public ResponseEntity<?> getSessionHistory(@PathVariable String sessionId) {
        try {
            List<Map<String, Object>> history = mcpChatService.getSessionHistory(sessionId);
            if (history.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "history", history,
                "count", history.size()
            ));
        } catch (Exception e) {
            logger.error("Error getting session history: {}", sessionId, e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Error getting session history: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 세션 종료 및 히스토리 삭제
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        try {
            mcpChatService.clearSession(sessionId);
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Session cleared: " + sessionId
            ));
        } catch (Exception e) {
            logger.error("Error clearing session: {}", sessionId, e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Error clearing session: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 세션 통계 조회
     */
    @GetMapping("/sessions/stats")
    public ResponseEntity<Map<String, Object>> getSessionStats() {
        try {
            Map<String, Object> stats = mcpChatService.getSessionStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("Error getting session stats", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Error getting session stats: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 세션 ID 추출 (쿠키 우선, body 다음, 없으면 null)
     */
    private String extractSessionId(HttpServletRequest request, Map<String, Object> chatRequest) {
        // 1. 쿠키에서 SESSIONID 추출 (가장 우선)
        String cookieSessionId = getCookieValue(request, "SESSIONID");
        if (cookieSessionId != null && !cookieSessionId.trim().isEmpty()) {
            return cookieSessionId;
        }
        
        // 2. 요청 body에서 sessionId 추출
        String bodySessionId = (String) chatRequest.get("sessionId");
        if (bodySessionId != null && !bodySessionId.trim().isEmpty()) {
            return bodySessionId;
        }
        
        // 3. 없으면 null 반환 (McpChatService에서 자동 생성)
        return null;
    }
    
    /**
     * 쿠키에서 값 추출
     */
    private String getCookieValue(HttpServletRequest request, String cookieName) {
        if (request.getCookies() == null) {
            return null;
        }
        
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
