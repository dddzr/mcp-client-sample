package com.example.mcpclient;

import com.example.mcpclient.config.McpServerConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(McpServerConfig.class)
@EnableScheduling
public class McpclientApplication {

	public static void main(String[] args) {
		// JVM 인코딩을 UTF-8로 강제 설정 (Windows 환경에서 한글 깨짐 방지)
		System.setProperty("file.encoding", "UTF-8");
		System.setProperty("sun.jnu.encoding", "UTF-8");
		java.nio.charset.Charset.defaultCharset(); // 인코딩 설정 적용을 위한 호출
		
		SpringApplication.run(McpclientApplication.class, args);
	}

}
