package com.example.mcpclient;

import com.example.mcpclient.config.McpServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(McpServerConfig.class)
public class McpclientApplication {

	public static void main(String[] args) {
		SpringApplication.run(McpclientApplication.class, args);
	}

}
