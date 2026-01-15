package com.example.mcpclient.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpResponse {
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";
    
    private Object result;
    private McpError error;
    private String id;

    public McpResponse() {
    }

    public McpResponse(Object result, String id) {
        this.result = result;
        this.id = id;
    }

    public McpResponse(McpError error, String id) {
        this.error = error;
        this.id = id;
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public McpError getError() {
        return error;
    }

    public void setError(McpError error) {
        this.error = error;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
