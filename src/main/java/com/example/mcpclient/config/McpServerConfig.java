package com.example.mcpclient.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP 서버 설정
 * application.yml에서 MCP 서버 정보 로드
 * 서버별 command, args, cwd, url, env, headers 설정을 지원
 */
@ConfigurationProperties(prefix = "mcp")
public class McpServerConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(McpServerConfig.class);
    
    private Map<String, McpServerInfo> servers = new HashMap<>();
    
    @PostConstruct
    public void logConfiguration() {
        logger.info("McpServerConfig initialized");
        logger.info("Servers map size: {}", servers.size());
        logger.info("Servers map: {}", servers);
        if (!servers.isEmpty()) {
            for (Map.Entry<String, McpServerInfo> entry : servers.entrySet()) {
                logger.info("Server: {} -> command: {}, args: {}, cwd: {}", 
                    entry.getKey(), 
                    entry.getValue().getCommand(),
                    entry.getValue().getArgs() != null ? String.join(",", entry.getValue().getArgs()) : "null",
                    entry.getValue().getCwd());
            }
        }
    }

    public Map<String, McpServerInfo> getServers() {
        return servers;
    }

    public void setServers(Map<String, McpServerInfo> servers) {
        this.servers = servers;
    }

    public static class McpServerInfo {
        private String command;
        private String[] args;
        private String cwd;
        private String url;
        private Map<String, String> env = new HashMap<>();
        private Map<String, String> headers = new HashMap<>();

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public String[] getArgs() {
            return args;
        }

        public void setArgs(String[] args) {
            this.args = args;
        }

        public String getCwd() {
            return cwd;
        }

        public void setCwd(String cwd) {
            this.cwd = cwd;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }
}
