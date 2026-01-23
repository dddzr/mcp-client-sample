package com.example.mcpclient.controller;

import com.example.mcpclient.service.McpChatService;
import com.example.mcpclient.service.McpServerRegistry;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            if (!serverRegistry.isServerRegistered(serverName)) {
                return ResponseEntity.notFound().build();
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) chatRequest.get("messages");
            if (messages == null || messages.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "messages required"));
            }
            
            // 디버깅: 서버에서 받은 메시지 내용 확인 (인코딩 문제 확인)
            logger.info("=== Received messages from client ===");
            for (int i = 0; i < messages.size(); i++) {
                Map<String, Object> msg = messages.get(i);
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                logger.info("[{}] {}: {}", i, role, content != null ? content : "null");
                if (content != null) {
                    logger.info("[{}] content bytes (UTF-8): {} bytes", i, content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                }
            }
            logger.info("=== End of received messages ===");
            
            // 세션 ID 추출 (chat history 관리용)
            String sessionId = extractSessionId(request, chatRequest);
            logger.debug("Using sessionId: {} (from cookie: {}, from body: {})", 
                sessionId,
                getCookieValue(request, "SESSIONID") != null,
                chatRequest.get("sessionId") != null);
            
            // access_token 추출 (서버 인증용)
            String access_token = extractAccessToken(request, chatRequest);
            logger.debug("Using access_token: {} (from header: {}, from body: {})", 
                access_token != null ? "present" : "null",
                request.getHeader("Authorization") != null,
                chatRequest.get("access_token") != null);
            
            // 전체 대화 히스토리를 Gemini에 전달 (이전 대화 내용 기억)
            // 세션별 히스토리 자동 관리 (TTL: 30분)
            // Gemini가 MCP 서버의 도구를 자동으로 선택하고 호출
            McpChatService.ChatResponse chatResponse = mcpChatService.chatWithServer(serverName, messages, sessionId, access_token);
            
            // 응답 쿠키에 세션 ID 설정 (클라이언트가 다음 요청에 쿠키로 전달)
            String responseSessionId = chatResponse.getSessionId();
            if (responseSessionId != null && !responseSessionId.trim().isEmpty()) {
                Cookie sessionCookie = new Cookie("SESSIONID", responseSessionId);
                sessionCookie.setPath("/");
                sessionCookie.setMaxAge(30 * 60); // 30분 (초 단위)
                sessionCookie.setHttpOnly(true); // XSS 방지
                response.addCookie(sessionCookie);
                logger.debug("Set SESSIONID cookie: {}", responseSessionId);
            }
            
            // 응답에 세션 ID 포함 (클라이언트 참고용, 실제로는 쿠키 사용)
            return ResponseEntity.ok(Map.of(
                "role", "assistant",
                "content", chatResponse.getContent(),
                "sessionId", responseSessionId
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
     * 세션 ID 추출 (chat history 관리용)
     * 쿠키 우선, body 다음, 없으면 null
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
     * access_token 추출 (서버 인증용)
     * Authorization 헤더 우선, body 다음, 없으면 null
     */
    private String extractAccessToken(HttpServletRequest request, Map<String, Object> chatRequest) {
        // 1. Authorization 헤더에서 Bearer 토큰 추출 (가장 우선)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        
        // 2. 요청 body에서 access_token 추출
        String bodyAccessToken = (String) chatRequest.get("access_token");
        if (bodyAccessToken != null && !bodyAccessToken.trim().isEmpty()) {
            return bodyAccessToken;
        }
        
        // 3. 없으면 null 반환
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
