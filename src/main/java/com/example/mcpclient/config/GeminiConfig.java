package com.example.mcpclient.config;

import com.google.genai.Client;
import com.google.genai.errors.ClientException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import io.micrometer.observation.ObservationRegistry;

@Configuration
public class GeminiConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(GeminiConfig.class);
    
    @Value("${spring.ai.google.genai.api-key}")
    private String apiKey;
    
    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.5-flash}")
    private String model;
    
    @Bean
    @Primary
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel googleGenAiChatModel() {
        // Google GenAI SDK Client 생성
        Client client = Client.builder()
                .apiKey(apiKey)
                .build();
        
        // ChatOptions 생성 (modelName 필수)
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(model)
                .build();
        
        // RetryTemplate 생성 - 할당량 초과(429) 에러는 재시도하지 않음
        RetryTemplate retryTemplate = createRetryTemplate();
        
        // Spring AI 1.1.2 빌더 사용
        // 빌더에서는 toolCallingManager가 null이면 DEFAULT_TOOL_CALLING_MANAGER를 사용
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(options)
                .retryTemplate(retryTemplate)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }
    
    /**
     * RetryTemplate 생성 (재시도 정책 설정)
     */
    private RetryTemplate createRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3) {
            @Override
            public boolean canRetry(org.springframework.retry.RetryContext context) {
                Throwable lastThrowable = context.getLastThrowable();
                if (lastThrowable != null && isQuotaExceededError(lastThrowable)) {
                    // 할당량 초과 에러는 재시도하지 않음
                    return false;
                }
                return super.canRetry(context);
            }
        };
        
        retryTemplate.setRetryPolicy(retryPolicy);
        
        // 재시도 이벤트 로깅을 위한 RetryListener 추가 (디버깅용)
        /* 동작 흐름
                시나리오 1: 할당량 초과 에러 (재시도 없음)
                        최초 요청 → open() → 첫 시도 실패 → onError() (attempts=0) 
                        → canRetry() = false → 재시도 없음 → close()
                
                시나리오 2: 일반 에러 (최대 3회 재시도)
                        최초 요청 → open() → 첫 시도 실패 → onError() (attempts=0) 
                        → canRetry() = true → 두 번째 시도 → onError() (attempts=1) 
                        → canRetry() = true → 세 번째 시도 → onError() (attempts=2) 
                        → canRetry() = true → 재시도 종료 → close()
        */
        retryTemplate.registerListener(new RetryListener() {

            // 에러 감지
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, 
                                                         RetryCallback<T, E> callback, 
                                                         Throwable throwable) {
                int attempts = context.getRetryCount(); // 시도 횟수
                logger.warn("🔄 Retry attempt #{} failed: {} - {}", 
                    attempts,
                    throwable.getClass().getSimpleName(), 
                    throwable.getMessage());
            }

            // 재시도 종료
            @Override
            public <T, E extends Throwable> void close(RetryContext context, 
                                                        RetryCallback<T, E> callback, 
                                                        Throwable throwable) {
                int attempts = context.getRetryCount();
                if (throwable != null) {
                    // 할당량 초과(429) 에러는 즉시 실패하도록 설정 (재시도 없음)
                    if (isQuotaExceededError(throwable)) {
                        logger.warn("❌ Final failure after {} attempts - Quota exceeded (no retry)", attempts);
                    } else {
                        logger.error("❌ Final failure after {} attempts", attempts, throwable);
                    }
                } else {
                    logger.info("✅ Success after {} attempt(s)", attempts + 1);
                }
            }
            
            @Override
            public <T, E extends Throwable> boolean open(RetryContext context, 
                                                         RetryCallback<T, E> callback) {
                return true;
            }
        });
        
        return retryTemplate;
    }
    
    /**
     * 할당량 초과 에러인지 확인
     */
    private boolean isQuotaExceededError(Throwable throwable) {
        // ClientException이고 메시지에 429 관련 내용이 있으면 할당량 초과로 판단
        if (throwable instanceof ClientException) {
            String message = throwable.getMessage();
            if (message != null && (message.contains("429") || 
                                    message.toLowerCase().contains("quota") ||
                                    message.toLowerCase().contains("exceeded"))) {
                return true;
            }
        }
        
        // 원인 예외도 확인
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isQuotaExceededError(cause);
        }
        
        return false;
    }
}
