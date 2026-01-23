package com.example.mcpclient.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Scanner;

/**
 * 간단한 MCP Client 데모용 콘솔 프로그램.
 *
 * - 콘솔에서 자연어 질문을 입력받아
 * - MCP Client의 /mcp/chat/mcp-server-sample 엔드포인트로 HTTP POST 요청을 보내고
 * - 응답(JSON) 전체를 그대로 출력합니다.
 *
 * curl 없이 동작 시연.
 * - 전체 프로젝트 Spring Boot 실행
 * - 터미널에서 인코딩 설정: chcp 65001
 * - java로 별도 실행 (파일오픈해서 ▶ 눌리면 실행됨.)
 * 
 * SSL 인증서 오류 발생 시:
 * - JVM 옵션 추가: -Djdk.internal.httpclient.disableHostnameVerification=true
 * - 또는 실행 시: java -Djdk.internal.httpclient.disableHostnameVerification=true McpClientDemo
 */
public class McpClientDemo {

    // MCP Client 엔드포인트 URL (필요하면 포트/서버명만 수정)
    private static final String MCP_CHAT_URL = "http://localhost:8081/mcp/chat/mcp-server-sample";
    // 포털(8083) 로그인 URL - access_token(JWT) 발급
    private static final String PORTAL_LOGIN_URL = "https://localhost:8083/jwt/login.json";

    private static String access_token = null;
    private static String refreshToken = null;
    // 쿠키 자동 관리 (sessionId를 쿠키로 관리)
    private static final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    
    /**
     * 개발 환경용 SSL 컨텍스트 생성 (모든 인증서 신뢰 + 호스트명 검증 비활성화)
     * 주의: 프로덕션 환경에서는 절대 사용하지 마세요!
     */
    private static SSLContext createTrustAllSSLContext() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL context", e);
        }
    }
    
    /**
     * 개발 환경용: SSL 호스트명 검증 비활성화
     * 주의: 프로덕션 환경에서는 절대 사용하지 마세요!
     */
    private static void disableSSLHostnameVerification() {
        try {
            // HttpsURLConnection의 기본 호스트명 검증기 설정
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true; // 모든 호스트명 허용
                }
            });
            
            // JVM 시스템 속성 설정 (Java 11+ HttpClient용)
            // 이 속성은 내부 API이지만 호스트명 검증을 비활성화하는 데 도움이 될 수 있음
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
            
            // 추가 시스템 속성 (일부 JVM에서 작동)
            System.setProperty("com.sun.net.ssl.checkRevocation", "false");
        } catch (Exception e) {
            System.err.println("[warn] Failed to disable SSL hostname verification: " + e.getMessage());
        }
    }
    
    /**
     * 개발 환경용 HttpClient 생성 (SSL 검증 완전 비활성화)
     * 포스트맨처럼 SSL 검증을 완전히 비활성화
     */
    private static HttpClient createHttpClient() {
        // 포스트맨처럼 SSL 검증 완전 비활성화를 위한 시스템 속성 설정
        // 이 속성들은 HttpClient 생성 전에 설정되어야 함
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        System.setProperty("com.sun.net.ssl.checkRevocation", "false");
        
        // SSL 검증 완전 비활성화를 위한 추가 설정
        try {
            // Java 11+ HttpClient의 내부 호스트명 검증 비활성화 시도
            java.lang.reflect.Field field = Class.forName("jdk.internal.net.http.HttpClientImpl")
                    .getDeclaredField("DISABLE_HOSTNAME_VERIFICATION");
            field.setAccessible(true);
            // 리플렉션으로는 접근이 제한될 수 있으므로 시스템 속성에 의존
        } catch (Exception e) {
            // 리플렉션 실패는 무시 (시스템 속성으로 대체)
        }
        
        SSLContext sslContext = createTrustAllSSLContext();
        
        return HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .sslContext(sslContext)
                .build();
    }
    
    // HttpClient를 지연 초기화하여 시스템 속성이 먼저 설정되도록 함
    private static HttpClient HTTP_CLIENT;
    
    private static HttpClient getHttpClient() {
        if (HTTP_CLIENT == null) {
            HTTP_CLIENT = createHttpClient();
        }
        return HTTP_CLIENT;
    }

    public static void main(String[] args) throws Exception {
        // 개발 환경용: SSL 호스트명 검증 비활성화 (localhost 인증서 문제 해결)
        // 포스트맨처럼 SSL 검증 완전 비활성화
        // 주의: 프로덕션 환경에서는 절대 사용하지 마세요!
        disableSSLHostnameVerification();
        
        // HttpClient 초기화 (시스템 속성 설정 후)
        getHttpClient();
        
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("=== MCP Client 콘솔 데모 ===");
        System.out.println("서버 URL: " + MCP_CHAT_URL);
        System.out.println("종료하려면 빈 줄에서 Enter 또는 Ctrl+C");
        System.out.println();

        while (true) {
            System.out.print("사용자 요청: ");//자연어 요청 입력
            String natural = scanner.nextLine();

            if (natural == null || natural.trim().isEmpty()) {
                System.out.println("종료합니다.");
                break;
            }

                // JWT(access_token)가 없으면 먼저 로그인 수행
                if (access_token == null || access_token.isBlank()) {
                    access_token = performLogin(scanner, mapper);
                    if (access_token == null || access_token.isBlank()) {
                        System.out.println("[warn] 로그인에 실패했습니다. 요청을 건너뜁니다.");
                        System.out.println("\n----------------------------------------\n");
                        continue;
                    }
                }

                // 요청 JSON 구성 (request-notice-list.json 과 동일한 구조)
            ObjectNode root = mapper.createObjectNode();

            // access_token(JWT)를 함께 전송 (서버가 Authorization 헤더 대신 body로 받는 경우 대비)
            if (access_token != null && !access_token.isBlank()) {
                root.put("access_token", access_token);
            }

            ArrayNode messages = mapper.createArrayNode();
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", "user");
            msg.put("content", natural);
            messages.add(msg);

            root.set("messages", messages);

            String json = mapper.writeValueAsString(root);
            
            // 인코딩 확인: 한글이 제대로 인코딩되었는지 확인
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            String decodedJson = new String(jsonBytes, StandardCharsets.UTF_8);
            System.out.println("\n=== 요청 JSON (인코딩 확인) ===");
            System.out.println("원본 JSON 길이: " + json.length() + " chars");
            System.out.println("UTF-8 바이트 길이: " + jsonBytes.length + " bytes");
            System.out.println("디코딩 검증: " + (json.equals(decodedJson) ? "OK" : "FAILED"));
            System.out.println("JSON 내용:");
            System.out.println(json);
            
            // 한글 포함 여부 확인
            if (natural.matches(".*[\\uAC00-\\uD7A3].*")) {
                System.out.println("\n[DEBUG] 한글 포함 감지됨");
                System.out.println("[DEBUG] 원본 입력: " + natural);
                System.out.println("[DEBUG] JSON의 content 필드: " + ((ObjectNode) root.get("messages").get(0)).get("content").asText());
            }

                try {
                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(MCP_CHAT_URL))
                            .header("Content-Type", "application/json; charset=UTF-8");

                    // Authorization 헤더로도 전달 (서버가 헤더 기반 인증을 요구할 수 있음)
                    if (access_token != null && !access_token.isBlank()) {
                        requestBuilder.header("Authorization", "Bearer " + access_token);
                    } //TODO: 헤더 필요없음.

                    // UTF-8로 명시적으로 인코딩된 바이트 배열로 전송 (위에서 이미 생성된 jsonBytes 재사용)
                    HttpRequest request = requestBuilder
                            .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBytes))
                            .build();

                    HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    
                    // 쿠키 디버깅: 쿠키가 제대로 저장/전송되는지 확인
                    System.out.println("\n=== 쿠키 상태 확인 ===");
                    System.out.println("저장된 쿠키 수: " + cookieManager.getCookieStore().getCookies().size());
                    cookieManager.getCookieStore().getCookies().forEach(cookie -> {
                        System.out.println("쿠키: " + cookie.getName() + " = " + cookie.getValue() + " (도메인: " + cookie.getDomain() + ", 경로: " + cookie.getPath() + ")");
                    });

                System.out.println("\n=== 서버 응답 (원본 JSON) ===");
                System.out.println(response.body());

                // 응답에서 content를 파싱해서 보여주기
                try {
                    ObjectNode resp = (ObjectNode) mapper.readTree(response.body());

                    if (resp.has("content")) {
                        System.out.println("\n=== 어시스턴트 응답 (content) ===");
                        System.out.println(resp.get("content").asText());
                    }

                } catch (Exception parseEx) {
                    System.out.println("\n[warn] 응답 JSON 파싱 중 오류 (원본은 위에 출력됨): " + parseEx.getMessage());
                }
            } catch (Exception e) {
                System.out.println("\n[error] MCP Client 호출 중 오류: " + e.getMessage());
                e.printStackTrace(System.out);
            }

            System.out.println("\n----------------------------------------\n");
        }
    }

    /**
     * 포털(8083) 로그인 호출 후 access_token/refreshToken을 메모리에 저장
     * 
     * API 스펙:
     * POST /jwt/login.json
     * 요청: { "user_id": "testuser", "pswd": "password123", "system_code": "MCP" }
     * 성공 (200): { "access_token": "...", "refresh_token": "...", "user_id": "...", "message": "success" }
     * 실패 (401): { "message": "Login failed: failed" }
     */
    private static String performLogin(Scanner scanner, ObjectMapper mapper) {
        try {
            System.out.println("[login] JWT(access_token)가 필요합니다. 포털 계정으로 로그인하세요.");
            System.out.print("포털 ID: ");
            String userId = scanner.nextLine().trim();
            System.out.print("비밀번호: ");
            String pswd = scanner.nextLine().trim();

            // API 스펙에 맞게 요청 구성
            ObjectNode loginReq = mapper.createObjectNode();
            loginReq.put("user_id", userId);
            loginReq.put("pswd", pswd);
            loginReq.put("system_code", "MCP"); // 선택사항이지만 명시적으로 설정

            String loginJson = mapper.writeValueAsString(loginReq);
            System.out.println("\n=== 로그인 요청 JSON ===");
            System.out.println(loginJson);

            HttpRequest loginRequest = HttpRequest.newBuilder()
                    .uri(URI.create(PORTAL_LOGIN_URL))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(loginJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> loginResponse = getHttpClient().send(loginRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            System.out.println("\n=== 로그인 응답 (원본 JSON) ===");
            System.out.println(loginResponse.body());

            int status = loginResponse.statusCode();
            
            // 응답 파싱
            ObjectNode resp = (ObjectNode) mapper.readTree(loginResponse.body());
            
            // 실패 처리 (401 또는 에러 메시지)
            if (status == 401 || (resp.has("message") && resp.get("message").asText().contains("failed"))) {
                String errorMsg = resp.has("message") ? resp.get("message").asText() : "Login failed";
                System.out.println("[error] 로그인 실패 - HTTP " + status + ": " + errorMsg);
                return null;
            }
            
            // 성공 처리
            if (status >= 200 && status < 300) {
                // API 스펙에 맞게 응답 필드 읽기 (access_token, refresh_token)
                if (resp.has("access_token")) {
                    access_token = resp.get("access_token").asText();
                }
                if (resp.has("refresh_token")) {
                    refreshToken = resp.get("refresh_token").asText();
                }

                if (access_token == null || access_token.isBlank()) {
                    System.out.println("[error] 로그인 응답에 access_token이 없습니다.");
                    return null;
                }

                System.out.println("[info] 로그인 성공. accessToken을 저장했습니다.");
                if (refreshToken != null && !refreshToken.isBlank()) {
                    System.out.println("[info] refreshToken도 함께 저장했습니다.");
                }
                return access_token;
            }
            
            // 기타 상태 코드
            System.out.println("[error] 로그인 실패 - HTTP " + status);
            return null;
        } catch (Exception e) {
            System.out.println("[error] 로그인 중 예외: " + e.getMessage());
            e.printStackTrace(System.out);
            return null;
        }
    }
}

