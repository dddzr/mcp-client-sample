package com.example.mcpclient.controller;

import com.example.mcpclient.config.McpServerConfig;
import com.example.mcpclient.service.McpServerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서버/도구 관리 컨트롤러
 * 운영/구성 요소 관리
 */
@RestController
@RequestMapping("/mcp/servers")
public class ServerController {
    
    private static final Logger logger = LoggerFactory.getLogger(ServerController.class);
    private final McpServerRegistry serverRegistry;
    
    public ServerController(McpServerRegistry serverRegistry) {
        this.serverRegistry = serverRegistry;
    }
    
    /**
     * 등록된 서버 목록 조회
     */
    @GetMapping
    public ResponseEntity<Set<String>> listServers() {
        return ResponseEntity.ok(serverRegistry.getRegisteredServerNames());
    }
    
    /**
     * MCP 서버 등록
     */
    @PostMapping("/{serverName}")
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
            logger.error("Error registering server: {}", serverName, e);
            return ResponseEntity.status(500).body(Map.of(
                "status", "error",
                "message", "Failed to register server: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 서버 등록 해제
     */
    @DeleteMapping("/{serverName}")
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
    @GetMapping("/{serverName}/tools")
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
