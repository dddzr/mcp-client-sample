package com.example.mcpclient.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;

/**
 * 전역 예외 처리 핸들러
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleJsonParseException(HttpMessageNotReadableException e) {
        logger.error("JSON parse error: {}", e.getMessage(), e);
        String errorMsg = e.getMessage();
        if (errorMsg != null && errorMsg.contains("UTF-8")) {
            errorMsg = "UTF-8 인코딩 오류입니다. Git Bash에서는 한글 대신 영문을 사용하거나, 파일로 JSON을 전송하세요.";
        }
        return ResponseEntity.badRequest().body(Map.of(
            "error", "Invalid JSON format",
            "message", errorMsg != null ? errorMsg : "JSON 파싱 오류",
            "hint", "Git Bash에서 한글을 사용할 때는 UTF-8 인코딩 문제가 발생할 수 있습니다. 영문으로 테스트하거나 파일을 사용하세요."
        ));
    }
}
