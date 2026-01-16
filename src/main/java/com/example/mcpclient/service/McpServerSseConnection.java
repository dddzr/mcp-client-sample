package com.example.mcpclient.service;

import com.example.mcpclient.config.McpServerConfig;
import com.example.mcpclient.model.McpRequest;
import com.example.mcpclient.model.McpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP 서버와의 SSE 통신 관리
 * SSE 엔드포인트를 통해 MCP 서버와 통신
 */
@Service
public class McpServerSseConnection implements McpServerConnectionInterface {
    
    private static final Logger logger = LoggerFactory.getLogger(McpServerSseConnection.class);
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final McpSseClientManager sseClientManager;
    
    // 서버별 클라이언트 ID 저장
    private final Map<String, String> serverClientIds = new ConcurrentHashMap<>();
    // 서버별 서버 정보 저장
    private final Map<String, McpServerConfig.McpServerInfo> serverInfos = new ConcurrentHashMap<>();
    // 서버별 SSE 연결 상태 저장
    private final Map<String, Boolean> serverConnectionStatus = new ConcurrentHashMap<>();
    
    public McpServerSseConnection(ObjectMapper objectMapper, McpSseClientManager sseClientManager) {
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        this.sseClientManager = sseClientManager;
    }
    
    @Override
    public void connectServer(String serverName, McpServerConfig.McpServerInfo serverInfo) throws IOException {
        if (serverClientIds.containsKey(serverName)) {
            logger.warn("Server {} is already connected", serverName);
            return;
        }
        
        String url = serverInfo.getUrl();
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("SSE connection requires 'url' in server configuration for server: " + serverName);
        }
        
        // 클라이언트 ID 생성
        String clientId = UUID.randomUUID().toString();
        serverClientIds.put(serverName, clientId);
        serverInfos.put(serverName, serverInfo);
        serverConnectionStatus.put(serverName, true);
        
        // SSE 연결 시작
        sseClientManager.startSseConnection(serverName, url, clientId);
        
        logger.info("MCP server {} connected via SSE with clientId: {}", serverName, clientId);
    }
    
    @Override
    public McpResponse sendRequest(String serverName, McpRequest request) throws IOException {
        String clientId = serverClientIds.get(serverName);
        if (clientId == null) {
            throw new IllegalStateException("Server " + serverName + " is not connected");
        }
        
        McpServerConfig.McpServerInfo serverInfo = getServerInfo(serverName);
        if (serverInfo == null) {
            throw new IllegalStateException("Server info not found for: " + serverName);
        }
        
        String baseUrl = serverInfo.getUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalStateException("Server URL not configured for: " + serverName);
        }
        
        // 요청 ID를 사용하여 응답 매핑
        String requestId = request.getId();
        CompletableFuture<McpResponse> responseFuture = sseClientManager.registerRequest(requestId);
        
        try {
            // 요청 전송 URL
            String requestUrl = baseUrl + "/mcp/request?clientId=" + clientId;
            
            // 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (serverInfo.getHeaders() != null) {
                serverInfo.getHeaders().forEach(headers::set);
            }
            
            // 요청 본문
            String requestJson = objectMapper.writeValueAsString(request);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            
            logger.info("Sending SSE request to {}: {}", serverName, requestJson);
            
            // 요청 전송 (응답은 SSE 스트림으로 받음)
            try {
                ResponseEntity<String> response = restTemplate.postForEntity(requestUrl, entity, String.class);
                logger.debug("Request sent successfully, status: {}", response.getStatusCode());
            } catch (Exception e) {
                logger.error("Error sending request to server {}", serverName, e);
                sseClientManager.cancelAllPendingRequests(serverName);
                throw new IOException("Error sending request to server " + serverName + ": " + e.getMessage(), e);
            }
            
            // SSE 스트림에서 응답 대기 (타임아웃 25초)
            McpResponse response = responseFuture.get(25, TimeUnit.SECONDS);
            logger.info("Received response from {} for request: {}", serverName, requestId);
            return response;
            
        } catch (TimeoutException e) {
            logger.error("Timeout waiting for response from {} after 25s", serverName);
            throw new IOException("Timeout waiting for response from server " + serverName, e);
        } catch (Exception e) {
            logger.error("Error waiting for response from server {}", serverName, e);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Error waiting for response from server " + serverName + ": " + e.getMessage(), e);
        }
    }
    
    @Override
    public void disconnectServer(String serverName) {
        sseClientManager.stopSseConnection(serverName);
        sseClientManager.cancelAllPendingRequests(serverName);
        serverClientIds.remove(serverName);
        serverInfos.remove(serverName);
        serverConnectionStatus.remove(serverName);
        logger.info("MCP server {} disconnected", serverName);
    }
    
    @Override
    public boolean isConnected(String serverName) {
        return serverClientIds.containsKey(serverName) && 
               serverConnectionStatus.getOrDefault(serverName, false);
    }
    
    /**
     * 서버 정보 가져오기
     */
    private McpServerConfig.McpServerInfo getServerInfo(String serverName) {
        return serverInfos.get(serverName);
    }
}
