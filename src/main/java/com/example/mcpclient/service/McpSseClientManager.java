package com.example.mcpclient.service;

import com.example.mcpclient.model.McpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 클라이언트 연결 관리
 * 각 서버별로 SSE 스트림을 읽고 응답을 처리
 */
@Component
public class McpSseClientManager {
    
    private static final Logger logger = LoggerFactory.getLogger(McpSseClientManager.class);
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    
    // 서버별 SSE 스트림 구독 관리
    private final Map<String, Flux<String>> sseStreams = new ConcurrentHashMap<>();
    // 요청 ID별 응답 Future 저장
    private final Map<String, CompletableFuture<McpResponse>> pendingRequests = new ConcurrentHashMap<>();
    
    public McpSseClientManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();
    }
    
    /**
     * SSE 연결 시작 및 스트림 구독
     */
    public void startSseConnection(String serverName, String baseUrl, String clientId) {
        String sseUrl = baseUrl + "/mcp/events?clientId=" + clientId;
        
        logger.info("Starting SSE connection for server {}: {}", serverName, sseUrl);
        
        Flux<String> sseStream = webClient.get()
                .uri(sseUrl)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofMinutes(30)) // 30분 타임아웃
                .doOnError(error -> {
                    logger.error("SSE stream error for server {}: {}", serverName, error.getMessage());
                    // 재연결 로직 추가 가능
                })
                .doOnComplete(() -> {
                    logger.info("SSE stream completed for server {}", serverName);
                    sseStreams.remove(serverName);
                });
        
        // SSE 이벤트 처리
        sseStream.subscribe(
                eventData -> handleSseEvent(serverName, eventData),
                error -> {
                    logger.error("Error in SSE stream for server {}", serverName, error);
                    sseStreams.remove(serverName);
                },
                () -> {
                    logger.info("SSE stream closed for server {}", serverName);
                    sseStreams.remove(serverName);
                }
        );
        
        sseStreams.put(serverName, sseStream);
        logger.info("SSE connection started for server {}", serverName);
    }
    
    /**
     * SSE 이벤트 처리
     */
    private void handleSseEvent(String serverName, String eventData) {
        try {
            // SSE 형식: "data: {...}" 또는 직접 JSON
            String jsonData = eventData;
            if (eventData.startsWith("data: ")) {
                jsonData = eventData.substring(6).trim();
            }
            
            JsonNode jsonNode = objectMapper.readTree(jsonData);
            String requestId = jsonNode.has("id") ? jsonNode.get("id").asText() : null;
            
            if (requestId == null) {
                logger.warn("Received SSE event without request ID from {}: {}", serverName, jsonData);
                return;
            }
            
            CompletableFuture<McpResponse> future = pendingRequests.remove(requestId);
            if (future != null) {
                McpResponse response = objectMapper.treeToValue(jsonNode, McpResponse.class);
                future.complete(response);
                logger.info("Completed future for request ID: {} from server {}", requestId, serverName);
            } else {
                logger.warn("No pending request found for ID: {} from server {}", requestId, serverName);
            }
        } catch (Exception e) {
            logger.error("Error parsing SSE event from server {}: {}", serverName, eventData, e);
        }
    }
    
    /**
     * 요청 등록 (응답 대기)
     */
    public CompletableFuture<McpResponse> registerRequest(String requestId) {
        CompletableFuture<McpResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        return future;
    }
    
    /**
     * SSE 연결 종료
     */
    public void stopSseConnection(String serverName) {
        Flux<String> stream = sseStreams.remove(serverName);
        if (stream != null) {
            // 스트림 구독 취소는 이미 doOnComplete에서 처리됨
            logger.info("Stopped SSE connection for server {}", serverName);
        }
    }
    
    /**
     * 모든 pending 요청 취소
     */
    public void cancelAllPendingRequests(String serverName) {
        // 서버별로 요청을 구분할 수 있도록 개선 가능
        pendingRequests.values().forEach(future -> future.cancel(true));
        pendingRequests.clear();
    }
}
