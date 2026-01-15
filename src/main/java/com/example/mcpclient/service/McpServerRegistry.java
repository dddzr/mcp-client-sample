package com.example.mcpclient.service;

import com.example.mcpclient.config.McpServerConfig;
import com.example.mcpclient.model.McpRequest;
import com.example.mcpclient.model.McpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서버 등록/해제 관리
 * 등록된 서버 조회
 * 초기화 시 설정 파일에서 서버 정보 로드
 * 서버 연결 시 자동으로 도구 목록 가져오기
 */
@Service
public class McpServerRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(McpServerRegistry.class);
    private final McpServerConfig serverConfig;
    private final McpServerConnection serverConnection;
    private final Map<String, McpServerConfig.McpServerInfo> registeredServers = new HashMap<>();
    // 서버별 도구 목록 저장
    private final Map<String, List<Map<String, Object>>> serverTools = new HashMap<>();

    public McpServerRegistry(McpServerConfig serverConfig, McpServerConnection serverConnection) {
        this.serverConfig = serverConfig;
        this.serverConnection = serverConnection;
    }

    @PostConstruct
    public void initialize() {
        // 설정 파일에서 서버 정보 로드
        logger.info("Initializing MCP Server Registry...");
        logger.info("Loaded servers from config: {}", serverConfig.getServers());
        
        if (serverConfig.getServers() != null && !serverConfig.getServers().isEmpty()) {
            registeredServers.putAll(serverConfig.getServers());
            logger.info("Registered {} servers", registeredServers.size());
            
            // 등록된 서버 자동 연결 및 도구 목록 가져오기
            for (Map.Entry<String, McpServerConfig.McpServerInfo> entry : registeredServers.entrySet()) {
                try {
                    String serverName = entry.getKey();
                    serverConnection.connectServer(serverName, entry.getValue());
                    logger.info("Auto-connected MCP server: {}", serverName);
                    
                    // 서버 연결 후 도구 목록 자동으로 가져오기
                    fetchToolsFromServer(serverName);
                } catch (IOException e) {
                    logger.error("Failed to connect MCP server: {}", entry.getKey(), e);
                }
            }
        } else {
            logger.warn("No MCP servers found in configuration");
        }
    }

    @PreDestroy
    public void cleanup() {
        // 애플리케이션 종료 시 모든 서버 연결 해제
        for (String serverName : registeredServers.keySet()) {
            serverConnection.disconnectServer(serverName);
        }
    }

    /**
     * MCP 서버 등록 해제 및 연결 종료
     */
    public void unregisterServer(String serverName) {
        serverConnection.disconnectServer(serverName);
        registeredServers.remove(serverName);
    }
    
    /**
     * MCP 서버 연결 객체 반환
     */
    public McpServerConnection getServerConnection() {
        return serverConnection;
    }

    /**
     * 등록된 서버 정보 조회
     */
    public McpServerConfig.McpServerInfo getServer(String serverName) {
        return registeredServers.get(serverName);
    }

    /**
     * 등록된 모든 서버 이름 목록
     */
    public Set<String> getRegisteredServerNames() {
        return registeredServers.keySet();
    }

    /**
     * 서버가 등록되어 있는지 확인
     */
    public boolean isServerRegistered(String serverName) {
        return registeredServers.containsKey(serverName);
    }
    
    /**
     * MCP 서버로부터 도구 목록 가져오기
     */
    private void fetchToolsFromServer(String serverName) {
        try {
            McpRequest request = new McpRequest();
            request.setMethod("tools/list");
            request.setParams(new HashMap<>());
            request.setId("tools-list-" + System.currentTimeMillis());
            
            McpResponse response = serverConnection.sendRequest(serverName, request);
            
            if (response.getError() == null && response.getResult() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.getResult();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
                
                if (tools != null) {
                    serverTools.put(serverName, tools);
                    logger.info("Fetched {} tools from server {}", tools.size(), serverName);
                    for (Map<String, Object> tool : tools) {
                        logger.debug("Tool: {}", tool.get("name"));
                    }
                } else {
                    logger.warn("No tools found in response from server {}", serverName);
                }
            } else {
                logger.warn("Failed to fetch tools from server {}: {}", serverName, 
                    response.getError() != null ? response.getError().getMessage() : "Unknown error");
            }
        } catch (Exception e) {
            logger.error("Error fetching tools from server {}", serverName, e);
        }
    }
    
    /**
     * 서버의 도구 목록 조회
     */
    public List<Map<String, Object>> getServerTools(String serverName) {
        return serverTools.getOrDefault(serverName, List.of());
    }
    
    /**
     * 모든 서버의 도구 목록 조회
     */
    public Map<String, List<Map<String, Object>>> getAllServerTools() {
        return new HashMap<>(serverTools);
    }
    
    /**
     * MCP 서버 등록 및 연결 (도구 목록도 가져오기)
     */
    public void registerServer(String serverName, McpServerConfig.McpServerInfo serverInfo) throws IOException {
        registeredServers.put(serverName, serverInfo);
        serverConnection.connectServer(serverName, serverInfo);
        fetchToolsFromServer(serverName);
    }
}
