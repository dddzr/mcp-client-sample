package com.example.mcpclient.service;

import com.example.mcpclient.config.McpServerConfig;
import com.example.mcpclient.model.McpRequest;
import com.example.mcpclient.model.McpResponse;

import java.io.IOException;

/**
 * MCP 서버 통신 인터페이스
 * stdio와 SSE 방식 모두 지원
 */
public interface McpServerConnectionInterface {
    
    /**
     * MCP 서버 연결
     */
    void connectServer(String serverName, McpServerConfig.McpServerInfo serverInfo) throws IOException;
    
    /**
     * MCP 서버에 요청 전송
     */
    McpResponse sendRequest(String serverName, McpRequest request) throws IOException;
    
    /**
     * 서버 연결 종료
     */
    void disconnectServer(String serverName);
    
    /**
     * 서버가 연결되어 있는지 확인
     */
    boolean isConnected(String serverName);
}
