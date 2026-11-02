# MCP Client

- 이 프로젝트는 MCP(Model Context Protocol)를 사용한 챗봇 시스템의 MCP Client 데모입니다.
사내 포털 등에 챗봇을 적용하기 위한 기반 구조를 제공합니다.
- Spring AI + Gemini 기반으로 구현되었습니다.

## ✅ (사용자 - MCP client - MCP server ) 통합 시나리오
**MCP 표준 메서드는 서버가 제공하고, 클라이언트는 그것을 호출, 어떤 도구(tool)를 쓸지는 클라이언트와 연결된 AI 모델(예: Gemini)이 결정.**
```
1. Mcp Client실행  → tools/list 요청
   ↓
2. MCP Server → 도구 목록 반환

사용자 요청

3. MCP Client → Gemini에 요청 전달
   ↓
4. Gemini → 사용자 메시지 분석 및 도구 선택
   ＂공지사항 알려줘“ → " get_notice_list" 선택  및 콜백
   ↓    
5. MCP client → tools/call 요청
   ↓
6. MCP Server → 도구 실행(포털 API 호출) 및 결과 반환
   ↓
7. Mcp client 에게 (tools/call에 대한)응답

```

## 📁 프로젝트 구조
```
mcp-client-sample/
src/main/java/com/example/mcpclient/
├── McpclientApplication.java             # Spring Boot 애플리케이션
├── config/                               # Spring Bean 설정
│   ├── GeminiConfig.java                 # Gemini API 설정 및 RetryTemplate 구성
│   ├── McpServerConfig.java             # MCP 서버 설정 (application.yml에서 로드)
│   └── WebConfig.java                    # HTTP 메시지 컨버터 설정
├── controller/                           # HTTP Controller
│   ├── ChatController.java               # 대화 도메인 API (유저 facing)
│   ├── ServerController.java             # 서버/도구 관리 API
│   └── AdminController.java              # 특수/디버깅/백도어 API
├── exception/                            # 예외 처리
│   └── GlobalExceptionHandler.java       # 전역 예외 핸들러
├── model/                                # 데이터 모델
│   ├── ChatMessage.java                  # 채팅 메시지 모델
│   ├── McpRequest.java                   # MCP 요청 메시지
│   ├── McpResponse.java                  # MCP 응답 메시지
│   └── McpError.java                     # MCP 오류 정보
└── service/                              # 비즈니스 로직
    ├── GeminiService.java                # Gemini API 직접 호출 서비스
    ├── McpChatService.java               # MCP 서버를 통한 채팅 서비스 (세션 관리 포함)
    ├── McpServerRegistry.java            # MCP 서버 등록/관리 (도구 목록 캐싱, stdio/SSE 공통)
    ├── McpServerConnectionInterface.java # 통신 방식 추상화 인터페이스 (stdio/SSE 공통)
    ├── McpServerStdioConnection.java     # stdio 방식 MCP 서버 통신 (stdio 전용)
    ├── McpServerSseConnection.java       # SSE 방식 MCP 서버 통신 (SSE 전용)
    └── McpSseClientManager.java          # SSE 클라이언트 연결 관리 (SSE 전용)
```
## TODO: 구조 개선 (2026-11-02 수정)

### 1. MCP 프로토콜 처리를 공식 SDK로 교체
- [ ] `model/McpRequest`·`McpResponse`·`McpError` → 공식 MCP Java SDK(`io.modelcontextprotocol.sdk:mcp-core`) 객체로 교체
- [ ] `McpServerStdioConnection`·`McpServerSseConnection`·`McpSseClientManager`·`McpServerConnectionInterface`
      → SDK `McpClient.sync(transport)` + `StdioClientTransport` / `HttpClientStreamableHttpTransport`로 교체
- [ ] SSE 전송은 MCP 사양에서 deprecated → Streamable HTTP 지원 추가 (SSE는 구버전 서버용으로만 유지 여부 결정) **2025-03-26 이후 권장 사양 변경됨.**
- 대안: Spring AI MCP Client 스타터 사용. 단 질문마다 다른 인증 헤더로 연결해야 하면 자동 설정이 맞지 않음

### 2. LLM 연동 분리
- [ ] `GeminiService` → `LlmClient` 인터페이스 + `GeminiLlmClient` 구현으로 분리
- [ ] 테스트용 `StubLlmClient` 추가 (LLM 없이 Tool 호출 흐름 테스트)
- [ ] Tool 호출 모델을 공급자 중립으로: `LlmMessage`, `LlmResponse(text, toolCalls)`, `LlmToolCall(id, name, arguments)`, `ToolSpec(name, description, inputSchema)`

### 3. 보안
- [ ] `AdminController`(백도어 API) 제거 또는 개발 프로필에서만 활성화
- [ ] 사용자마다 Tool 목록이 다르므로 `McpServerRegistry`의 도구 목록 캐시를 사용자 단위로 바꾸거나 제거 **(LLM이 권한 없는 도구를 고르지 않게)**

### 4. 사용자 경험 (선택)
- [ ] 채팅 응답을 SSE 스트리밍으로: `delta`(답변 조각), `tool_start`/`tool_end`(진행 표시), `done`, `error`
- [ ] Tool 실행 루프를 직접 돌려 Tool 사이에 이벤트·로그를 넣을 수 있게 (확인 필요: 현재 Spring AI 자동 Tool 호출 사용 여부)

### 5. 패키지 구성 (선택)
- [ ] 계층별(`controller`/`service`/`model`) → 역할별(`chat`/`llm`/`mcp`/`config`)로 재배치 검토
      — 기능이 늘어날 때만. 지금 규모면 계층별 유지도 무방

### 유지할 것 (범용 데모이므로)
- 여러 MCP 서버 등록(`McpServerRegistry`, `ServerController`)
- stdio 전송 지원


## 🔄 MCP Clinet 동작 흐름
0. **도구 등록**: 프로젝트 실행 시 (Spring Boot 시작)
   - **MCP Client > McpServerRegistry.initialize()**: 설정 파일에서 서버 정보 로드
   - **MCP Client > McpServerRegistry.fetchToolsFromServer()**: Server에서 도구 리스트 받아옴
1. **사용자 요청**: "공지사항 목록을 보여줘" (자연어) - HTTP POST 요청으로 `/mcp/servers/{serverName}/chat` MCP Client 엔드포인트에 전송
2. **MCP Client > McpController.chatWithServer()**: `/mcp/servers/{serverName}/chat` 엔드포인트로 HTTP 요청 수신 및 파라미터 추출
3. **MCP Client > McpChatService.chatWithServer()**: Gemini에 요청 전달
   - **MCP Client > McpChatService.getOrCreateChatClient()**: ChatClient 생성/가져오기
     - **MCP Client > McpChatService.createToolCallback()**: MCP 서버의 도구를 Spring AI ToolCallback으로 변환
   - `ChatClient`는 이미 MCP 서버의 모든 도구를 `defaultToolCallbacks()`로 등록해둠
4. **Gemini**: 사용자 요청을 분석, 적절한 도구(`mcp_portal-mcp_get_notice_list`)를 자동으로 선택
5. **Gemini → ToolCallback**: 선택한 도구를 호출
   - **MCP Client > McpToolCallback.call()**: ToolCallback의 call 메서드 호출
6. **MCP Client > McpChatService.callMcpTool()**: MCP 서버로 `tools/call` 요청 전송
7. **MCP Server**: 도구 실행 후 결과 반환
8. **Gemini**: 도구 실행 결과를 받아서 사용자 친화적인 응답 생성
9. **MCP Client > McpController.chatWithServer()**: Gemini 응답을 HTTP 응답으로 반환
10. **사용자**: 최종 응답 수신


## 🚀 실행 방법
```bash
mvn spring-boot:run 
```

## ✅ 참고 사항
### MCP 서버 연결 확인
- `application.properties`에 등록 확인
```
mcp:
  servers:
    mcp-server-sample:
      command: java
      args:
        - -jar
        - C:\Users\User\Documents\projects\mcp-server-sample\target\mcp-server-sample-0.0.1-SNAPSHOT.jar
        - --mcp-stdio
      cwd: C:\Users\User\Documents\projects\mcp-server-sample
```
- `GET /mcp/servers`로 등록 확인

### gemini 가이드
- Gemini API : `application.properties`에서 API 키 확인
- https://ai.google.dev/gemini-api/docs/api-key?hl=ko



## 🛜 MCP Server 통신 방식 설정
- 통신 방식은 `application.yml`의 서버 설정에서 `type` 필드로 결정됩니다.
- **stdio 모드**: `application.yml`에서 `type: stdio` 설정 (기본값)
- **SSE 모드**: `application.yml`에서 `type: sse` 및 `url` 설정 필요
  ```yaml
  mcp:
    servers:
      mcp-server-sample-sse:
        type: sse
        url: http://localhost:8080  # MCP 서버의 기본 URL
  ```


## 📋 사용자 요청 방법 (chat UI 없을 때 테스트)

### 방법 1: curl 명령어
```bash
curl -X POST "http://localhost:8081/mcp/chat/mcp-server-sample" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "Cookie: SESSIONID=796BAFB973B32658830B2CB822834C7B" \
  -d '{"messages":[{"role":"user","content":"Please show me the notice list"}]}'
```

### 방법 2: curl 명령어 + json (UTF-8 인코딩 문제로 파일 사용)
```bash
# 1. request-notice-list.json 파일 수정
# 2. 파일을 사용하여 요청
curl -X POST "http://localhost:8081/mcp/chat/mcp-server-sample" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "Cookie: SESSIONID=959891C59F9E2B75626FD34975C30E30" \
  --max-time 40 \
  --data-binary @request-notice-list.json
```
### 방법 3: Demo Class 이용
McpClientDemo.java 파일오픈해서 ▶ 눌리면 실행됨.
자연어로 요청.
