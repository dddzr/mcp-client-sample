package com.example.mcpclient.service;

import com.example.mcpclient.config.McpServerConfig;
import com.example.mcpclient.model.McpRequest;
import com.example.mcpclient.model.McpResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 서버와의 stdio 통신 관리
 */
@Service
public class McpServerConnection {
    
    private static final Logger logger = LoggerFactory.getLogger(McpServerConnection.class);
    private final ObjectMapper objectMapper;
    private final Map<String, Process> serverProcesses = new ConcurrentHashMap<>();
    private final Map<String, BufferedReader> serverReaders = new ConcurrentHashMap<>();
    private final Map<String, PrintWriter> serverWriters = new ConcurrentHashMap<>();

    public McpServerConnection(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * MCP 서버 프로세스 시작 및 연결
     */
    public void connectServer(String serverName, McpServerConfig.McpServerInfo serverInfo) throws IOException {
        if (serverProcesses.containsKey(serverName)) {
            logger.warn("Server {} is already connected", serverName);
            return;
        }

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(serverInfo.getCommand());
        if (serverInfo.getArgs() != null) {
            for (String arg : serverInfo.getArgs()) {
                processBuilder.command().add(arg);
            }
        }
        
        if (serverInfo.getCwd() != null) {
            processBuilder.directory(new File(serverInfo.getCwd()));
        }
        
        if (serverInfo.getEnv() != null && !serverInfo.getEnv().isEmpty()) {
            processBuilder.environment().putAll(serverInfo.getEnv());
        }

        Process process = processBuilder.start();
        serverProcesses.put(serverName, process);

        // stdin/stdout 스트림 설정
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"), true);
        
        serverReaders.put(serverName, reader);
        serverWriters.put(serverName, writer);

        // 서버 초기화 메시지 읽기 및 무시 (일반 텍스트 메시지)
        // MCP 서버는 초기화 시 일반 텍스트 메시지를 보낼 수 있음
        try {
            // 초기화 메시지가 있으면 읽고 무시 (최대 5줄까지)
            for (int i = 0; i < 5; i++) {
                if (!reader.ready()) {
                    // 데이터가 없으면 잠시 대기
                    Thread.sleep(100);
                    if (!reader.ready()) {
                        break;
                    }
                }
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                // JSON 형식인지 확인 (시작이 '{' 또는 '[')
                if (line.trim().startsWith("{") || line.trim().startsWith("[")) {
                    // JSON 형식이면 버퍼에 다시 넣을 수 없으므로 별도 처리 필요
                    // 일단 로그만 남기고 넘어감
                    logger.debug("Received JSON during initialization: {}", line);
                    break;
                } else {
                    // 일반 텍스트 초기화 메시지
                    logger.debug("Ignoring initialization message from {}: {}", serverName, line);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while reading initialization messages from {}", serverName);
        }

        logger.info("MCP server {} connected", serverName);
    }

    /**
     * JSON 형식인지 확인
     */
    private boolean isJsonFormat(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * MCP 서버에 요청 전송
     */
    public McpResponse sendRequest(String serverName, McpRequest request) throws IOException {
        if (!serverProcesses.containsKey(serverName)) {
            throw new IllegalStateException("Server " + serverName + " is not connected");
        }

        PrintWriter writer = serverWriters.get(serverName);
        BufferedReader reader = serverReaders.get(serverName);

        // JSON-RPC 요청 전송
        String requestJson = objectMapper.writeValueAsString(request);
        logger.debug("Sending to {}: {}", serverName, requestJson);
        writer.println(requestJson);
        writer.flush();

        // 응답 읽기 (JSON 형식이 아닌 메시지는 건너뛰기)
        String responseLine = null;
        int maxAttempts = 10; // 최대 10줄까지 읽기 시도
        int attempts = 0;
        long startTime = System.currentTimeMillis();
        long timeout = 25000; // 25초 타임아웃
        
        logger.info("Waiting for response from {} for request: {}", serverName, request.getMethod());
        
        while (attempts < maxAttempts) {
            // 타임아웃 체크
            if (System.currentTimeMillis() - startTime > timeout) {
                logger.error("Timeout waiting for response from {} after {}ms", serverName, timeout);
                throw new IOException("Timeout waiting for response from server " + serverName + " after " + timeout + "ms");
            }
            
            // 데이터가 준비되었는지 확인 (논블로킹 체크)
            if (!reader.ready()) {
                try {
                    Thread.sleep(100); // 100ms 대기 후 다시 시도
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for response from " + serverName);
                }
                continue;
            }
            
            String line = reader.readLine();
            if (line == null) {
                logger.warn("Received null line from {} (attempt {})", serverName, attempts + 1);
                throw new IOException("No response from server " + serverName + " after " + attempts + " attempts");
            }
            
            logger.info("Received from {} (attempt {}): {}", serverName, attempts + 1, line);
            
            // JSON 형식인지 확인
            if (isJsonFormat(line)) {
                responseLine = line;
                logger.info("Found valid JSON response from {} after {}ms", serverName, System.currentTimeMillis() - startTime);
                break;
            } else {
                // JSON 형식이 아닌 메시지는 로그만 남기고 건너뛰기
                logger.debug("Skipping non-JSON message from {}: {}", serverName, line);
                attempts++;
            }
        }
        
        if (responseLine == null) {
            logger.error("No valid JSON response from {} after {} attempts and {}ms", serverName, maxAttempts, System.currentTimeMillis() - startTime);
            throw new IOException("No valid JSON response from server " + serverName + " after " + maxAttempts + " attempts");
        }

        // MCP 서버 원본 응답 로그 출력
        logger.info("=== MCP Server Raw Response from {} ===", serverName);
        logger.info("Raw response line: {}", responseLine);
        
        // MCP 서버가 배열을 반환할 수 있으므로 처리
        McpResponse parsedResponse;
        try {
            // 먼저 JsonNode로 파싱하여 배열인지 확인
            JsonNode jsonNode = objectMapper.readTree(responseLine);
            
            if (jsonNode.isArray()) {
                // 배열인 경우 첫 번째 요소를 사용
                logger.info("Response is an array (size: {}), using first element", jsonNode.size());
                if (jsonNode.size() == 0) {
                    throw new IOException("Empty array response from server " + serverName);
                }
                parsedResponse = objectMapper.treeToValue(jsonNode.get(0), McpResponse.class);
            } else {
                // 단일 객체인 경우 그대로 파싱
                logger.debug("Response is a single object");
                parsedResponse = objectMapper.treeToValue(jsonNode, McpResponse.class);
            }
            
            // 파싱된 응답 로그 출력
            logger.info("=== MCP Server Parsed Response from {} ===", serverName);
            logger.info("Parsed response - ID: {}, JSON-RPC: {}", parsedResponse.getId(), parsedResponse.getJsonrpc());
            if (parsedResponse.getError() != null) {
                logger.info("Parsed response - Error: code={}, message={}", 
                    parsedResponse.getError().getCode(), parsedResponse.getError().getMessage());
            } else {
                logger.info("Parsed response - Result: {}", objectMapper.writeValueAsString(parsedResponse.getResult()));
            }
            logger.info("=== End of MCP Server Response ===");
            
            return parsedResponse;
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse JSON response from {}: {}", serverName, responseLine, e);
            throw new IOException("Failed to parse JSON response from server " + serverName + ": " + e.getMessage(), e);
        }
    }

    /**
     * 서버 연결 종료
     */
    public void disconnectServer(String serverName) {
        Process process = serverProcesses.remove(serverName);
        if (process != null) {
            process.destroy();
            logger.info("MCP server {} disconnected", serverName);
        }
        
        BufferedReader reader = serverReaders.remove(serverName);
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                logger.error("Error closing reader for {}", serverName, e);
            }
        }
        
        PrintWriter writer = serverWriters.remove(serverName);
        if (writer != null) {
            writer.close();
        }
    }

    /**
     * 서버가 연결되어 있는지 확인
     */
    public boolean isConnected(String serverName) {
        Process process = serverProcesses.get(serverName);
        return process != null && process.isAlive();
    }
}
