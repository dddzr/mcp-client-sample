# MCP (Model Context Protocol) 챗봇 데모

이 프로젝트는 MCP(Model Context Protocol)를 사용한 챗봇 시스템의 MCP Client 데모입니다.
사내 포털 등에 챗봇을 적용하기 위한 기반 구조를 제공합니다.

## 🎯 MCP Clinet 핵심 개념 - 전체 개념은 MCP Server 쪽에 기재함.

MCP 표준 메서드는 서버가 제공하고, 클라이언트는 그것을 호출하는 구조이며, 어떤 도구(tool)를 쓸지 선택하는 쪽은 클라이언트와 연결된 AI 모델(예: Gemini) 쪽이다.

즉, **사용자는 자연어로 요청, Gemini가 MCP 도구를 선택, 클라이언트가 호출합니다!**

## ✅ (사용자 - MCP client - MCP server ) 통합 시나리오
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


## 📋 사용자 요청 방법 (chat UI 없을 때 테스트)

### 방법 1: curl 명령어
```bash
curl -X POST "http://localhost:8080/mcp/chat/mcp-server-sample" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "Cookie: SESSIONID=796BAFB973B32658830B2CB822834C7B" \
  -d '{"messages":[{"role":"user","content":"Please show me the notice list"}]}'
```

### 방법 2: curl 명령어 + json (UTF-8 인코딩 문제로 파일 사용)
```bash
# 1. request-notice-list.json 파일 수정
# 2. 파일을 사용하여 요청
curl -X POST "http://localhost:8080/mcp/chat/mcp-server-sample" \
  -H "Content-Type: application/json; charset=UTF-8" \
  -H "Cookie: SESSIONID=959DB8AD2FC330C8D1AB45A453EB9372" \
  --max-time 40 \
  --data-binary @request-notice-list.json
```
