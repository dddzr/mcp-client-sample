package com.example.mcpclient.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 */
public class McpClientDemo {

    // MCP Client 엔드포인트 URL (필요하면 포트/서버명만 수정)
    private static final String MCP_CHAT_URL = "http://localhost:8081/mcp/chat/mcp-server-sample";

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("=== MCP Client 콘솔 데모 ===");
        System.out.println("서버 URL: " + MCP_CHAT_URL);
        System.out.println("종료하려면 빈 줄에서 Enter 또는 Ctrl+C");
        System.out.println();

        // 세션 ID를 간단히 메모리에 유지 (원하면 응답에서 sessionId를 읽어 재사용하도록 확장 가능)
        String sessionId = null;

        while (true) {
            System.out.print("사용자 요청: ");//자연어 요청 입력
            String natural = scanner.nextLine();

            if (natural == null || natural.trim().isEmpty()) {
                System.out.println("종료합니다.");
                break;
            }

            // 요청 JSON 구성 (request-notice-list.json 과 동일한 구조)
            ObjectNode root = mapper.createObjectNode();

            if (sessionId != null && !sessionId.isBlank()) {
                // 이전 응답에서 받은 세션이 있다면 함께 전송 (선택 사항)
                root.put("sessionId", sessionId);
            }

            ArrayNode messages = mapper.createArrayNode();
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", "user");
            msg.put("content", natural);
            messages.add(msg);

            root.set("messages", messages);

            String json = mapper.writeValueAsString(root);

            System.out.println("\n=== 요청 JSON ===");
            System.out.println(json);

            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(MCP_CHAT_URL))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                System.out.println("\n=== 서버 응답 (원본 JSON) ===");
                System.out.println(response.body());

                // 응답에서 content / sessionId 를 간단히 파싱해서 보여주기 (가능하면)
                try {
                    ObjectNode resp = (ObjectNode) mapper.readTree(response.body());

                    if (resp.has("content")) {
                        System.out.println("\n=== 어시스턴트 응답 (content) ===");
                        System.out.println(resp.get("content").asText());
                    }

                    if (resp.has("sessionId")) {
                        sessionId = resp.get("sessionId").asText();
                        System.out.println("\n[info] 세션 ID 업데이트됨: " + sessionId);
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
}

