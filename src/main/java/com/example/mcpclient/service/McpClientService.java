package com.example.mcpclient.service;

import com.example.mcpclient.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class McpClientService {
    
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper;

    public McpClientService(GeminiService geminiService, ObjectMapper objectMapper) {
        this.geminiService = geminiService;
        this.objectMapper = objectMapper;
    }

    public McpResponse processRequest(McpRequest request) {
        try {
            String method = request.getMethod();
            Map<String, Object> params = request.getParams() != null ? request.getParams() : new HashMap<>();
            String id = request.getId();

            switch (method) {
                case "chat/completion":
                    return handleChatCompletion(params, id);
                case "tools/list":
                    return handleToolsList(id);
                case "tools/call":
                    return handleToolsCall(params, id);
                default:
                    return createErrorResponse(-32601, "Method not found", id);
            }
        } catch (Exception e) {
            return createErrorResponse(-32603, "Internal error: " + e.getMessage(), request.getId());
        }
    }

    private McpResponse handleChatCompletion(Map<String, Object> params, String id) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) params.get("messages");
            
            if (messages == null || messages.isEmpty()) {
                return createErrorResponse(-32602, "Invalid params: messages required", id);
            }

            List<ChatMessage> chatMessages = new ArrayList<>();
            for (Map<String, Object> msg : messages) {
                String role = (String) msg.get("role");
                String content = (String) msg.get("content");
                chatMessages.add(new ChatMessage(role, content));
            }

            String response = geminiService.generateResponse(chatMessages);

            Map<String, Object> result = new HashMap<>();
            Map<String, Object> choice = new HashMap<>();
            Map<String, Object> message = new HashMap<>();
            message.put("role", "assistant");
            message.put("content", response);
            choice.put("message", message);
            choice.put("finish_reason", "stop");
            result.put("choices", Collections.singletonList(choice));
            result.put("model", "gemini-pro");

            return new McpResponse(result, id);
        } catch (RuntimeException e) {
            // GeminiService에서 발생한 예외는 원본 메시지 포함
            String errorMessage = e.getMessage();
            if (e.getCause() != null) {
                errorMessage += " (Cause: " + e.getCause().getMessage() + ")";
            }
            return createErrorResponse(-32603, "Error processing chat completion: " + errorMessage, id);
        } catch (Exception e) {
            return createErrorResponse(-32603, "Error processing chat completion: " + e.getMessage(), id);
        }
    }

    private McpResponse handleToolsList(String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("tools", Collections.emptyList());
        return new McpResponse(result, id);
    }

    private McpResponse handleToolsCall(Map<String, Object> params, String id) {
        // Tools 호출 처리 (필요시 구현)
        Map<String, Object> result = new HashMap<>();
        result.put("content", Collections.emptyList());
        return new McpResponse(result, id);
    }

    private McpResponse createErrorResponse(int code, String message, String id) {
        McpError error = new McpError(code, message);
        return new McpResponse(error, id);
    }
}
