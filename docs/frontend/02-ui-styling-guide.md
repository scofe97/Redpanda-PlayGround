# Google Stitch UI 디자인 적용 가이드

## 개요

Redpanda Playground 프론트엔드(React 19 + Vite)의 UI를 Google Stitch로 디자인하고 적용하는 워크플로우.

## 현재 상태

| 항목 | 상태 |
|------|------|
| Stitch MCP 서버 | 설정 완료 (`@_davideast/stitch-mcp`) |
| Tailwind CSS v4 | 설치 완료 (`@tailwindcss/vite`) |
| 기존 vanilla CSS | `index.css`에 유지 (Tailwind과 공존) |
| 빌드 검증 | 통과 |

## 기술 스택

- React 19 + Vite 6 + TypeScript
- TanStack Query v5 (서버 상태)
- React Router v7
- Tailwind CSS v4 (신규)
- SSE (Server-Sent Events) for pipeline 실시간 업데이트

## 변경 불가 영역

다음 파일은 비즈니스 로직이므로 Stitch 적용 시 변경하지 않는다:

```
src/api/          # API 호출 (client.ts, ticketApi.ts, pipelineApi.ts, toolApi.ts)
src/hooks/        # 커스텀 훅 (useSSE.ts, usePipeline.ts, useTickets.ts, useTools.ts)
main.tsx          # React Router, QueryClient 설정
```

## 변경 대상

```
src/index.css           # vanilla CSS → Tailwind 클래스로 점진 교체
src/components/*.tsx     # 마크업 교체, Props 인터페이스 유지
src/pages/*.tsx          # 레이아웃 교체, 이벤트핸들러/훅 호출 유지
src/App.tsx              # 헤더/네비게이션 레이아웃 교체
```

## 주요 컴포넌트

| 컴포넌트 | 파일 | 역할 |
|----------|------|------|
| StatusBadge | `components/StatusBadge.tsx` | 상태별 색상 배지 |
| PipelineTimeline | `components/PipelineTimeline.tsx` | 파이프라인 단계 타임라인 |
| SourceSelector | `components/SourceSelector.tsx` | 배포 소스 선택 (GIT/NEXUS/HARBOR) |
| Sidebar | `components/Sidebar.tsx` | 사이드 네비게이션 |
| ErrorBoundary | `components/ErrorBoundary.tsx` | 에러 경계 처리 |

## 워크플로우

### 방법 A: Stitch MCP (추천)

```
1. stitch.withgoogle.com에서 디자인
2. Claude Code 새 세션에서:
   - get_screen_code 도구로 HTML 가져오기
   - HTML → React 컴포넌트 변환
   - 기존 hooks/api 연결
3. yarn build로 검증
```

### 방법 B: 수동 복사

```
1. stitch.withgoogle.com에서 디자인
2. 코드 에디터에서 HTML 복사
3. Claude Code에 붙여넣기 → 변환 요청
```

## 화면별 적용 순서

| # | 화면 | 파일 | 의존 컴포넌트 |
|---|------|------|--------------|
| 1 | Layout (Header + Nav) | `App.tsx` | - |
| 2 | StatusBadge | `components/StatusBadge.tsx` | - |
| 3 | Ticket 목록 | `pages/TicketListPage.tsx` | StatusBadge |
| 4 | Ticket 생성 | `pages/TicketCreatePage.tsx` | SourceSelector |
| 5 | Pipeline Timeline | `components/PipelineTimeline.tsx` | StatusBadge |
| 6 | Ticket 상세 | `pages/TicketDetailPage.tsx` | StatusBadge, PipelineTimeline |
| 7 | Tool 목록 | `pages/ToolListPage.tsx` | - |
| 8 | Tool 등록/수정 | `pages/ToolFormDrawer.tsx` | - |

> 순서 이유: 공통 컴포넌트(Layout, Badge) → 의존하는 페이지 순으로 적용해야 중복 변경을 방지.

## Stitch 디자인 프롬프트 (기능 중심)

아래 프롬프트를 stitch.withgoogle.com에서 사용한다. 각 화면을 **별도 프로젝트**로 생성하는 것을 추천.

### 공통 컨텍스트 (모든 프롬프트 앞에 붙이기)

```
This is a DevOps deployment management dashboard called "Redpanda Playground".
Tech: React 19, Tailwind CSS, SPA with client-side routing.
Style: Clean SaaS admin panel, professional, minimal.
```

---

### 1. Layout + Navigation

```
Create a SPA layout with top navigation bar.

Navigation:
- Logo "Redpanda Playground" on the left (links to /tickets)
- Two nav items on the right: "Tickets", "Tools"
- Current page nav item should be visually highlighted

Layout:
- Nav bar is always visible (not scrollable)
- Below nav: main content area where pages render
- Max-width 1200px, centered
```

### 2. Ticket List Page (`/tickets`)

```
Create a ticket list page.

Data: Array of tickets, each has { id, name, status, createdAt }

Features:
- Page title "Tickets" with "New Ticket" button that navigates to /tickets/new
- Table displaying all tickets
- "Name" column is a clickable link → navigates to /tickets/{id}
- "Status" column shows a colored badge pill:
  - DRAFT (gray), READY (blue), DEPLOYING (orange), DEPLOYED (green), FAILED (red)
- "Created" column shows formatted date
- Empty state when no tickets: "No tickets" centered message
- Loading state: "Loading..." text
- Error state: card with error message
```

### 3. Ticket Create Page (`/tickets/new`)

```
Create a ticket creation form.

Form fields:
1. Name (text input, required, max 200 chars)
2. Description (textarea, optional)
3. Sources section:
   - Three checkboxes: GIT, NEXUS, HARBOR
   - Each checkbox toggles a conditional sub-form:
     - GIT checked → show "Repository URL" (placeholder: https://gitlab.example.com/project.git) + "Branch" (placeholder: main)
     - NEXUS checked → show "Artifact Coordinate" (placeholder: com.example:app:1.0.0:war)
     - HARBOR checked → show "Image Name" (placeholder: registry/app:latest)
   - Multiple sources can be selected simultaneously
   - Each source sub-form appears as a nested card

Actions:
- "Create Ticket" button (disabled when name is empty or submitting)
- On submit: POST to API, then redirect to /tickets/{newId}
- On error: show alert with error message
- Button text changes to "Creating..." while submitting
```

### 4. Ticket Detail Page (`/tickets/:id`)

```
Create a ticket detail page with pipeline execution status.

Ticket info section:
- Header: ticket name (h2) on left, action buttons on right
- Action buttons:
  - "Start Pipeline" (blue, disabled when pipeline is running/pending)
  - "Delete" (red, shows confirm dialog before deleting)
- Card showing: status badge, description, sources list
- Sources: each shows sourceType + relevant detail (repoUrl+branch / artifactCoordinate / imageName)

Pipeline section (shown when pipeline exists):
- Separate card below ticket info
- Title "Pipeline" with pipeline status badge
- Vertical timeline showing each step:
  - Circle indicator on left (color by step status: PENDING=gray, RUNNING=blue, WAITING_WEBHOOK=yellow, SUCCESS=green, FAILED=red)
  - Step order number + step name + status badge
  - Log text below in smaller muted font (if exists)
  - Steps connected by a vertical line
- Error message at bottom if pipeline failed

Real-time behavior:
- When pipeline is running/pending/waiting_webhook, page auto-refreshes via SSE
- "Start Pipeline" shows "Starting..." while request is pending
- "Start Pipeline" shows "Running..." and stays disabled while pipeline is active

Invalid ID handling:
- Non-numeric ID → show "Invalid Ticket ID" with back button
- Ticket not found → show error with back button
```

### 5. Tool List Page (`/tools`)

```
Create a support tools management page.

Data: Array of tools, each has { id, name, toolType, url, username, active, createdAt }

Features:
- Page title "Support Tools" with "Add Tool" button → navigates to /tools/new
- Table with columns: Name, Type, URL, Active, Status, Actions
- Type: badge showing JENKINS / GITLAB / NEXUS / REGISTRY
- URL: shown in smaller muted text
- Active: checkmark if true, X if false
- Status column: connection test result per tool
  - Initial: "—" (not tested)
  - Testing: "Testing..." (orange)
  - Success: "Reachable" (green)
  - Failed: "Unreachable" (red)
- Actions column per row (3 buttons):
  - "Test" → triggers POST /tools/{id}/test, updates Status column
  - "Edit" → navigates to /tools/{id}/edit
  - "Delete" → shows confirm dialog, then deletes
- Empty state: "No tools configured"
```

### 6. Tool Form Drawer (`/tools/new`, `/tools/:id/edit`)

```
Create a tool registration/edit form. Same form for create and edit modes.

Form fields:
1. Tool Type (dropdown select: JENKINS, GITLAB, NEXUS, REGISTRY)
2. Name (text input, required, max 100 chars)
3. URL (text input, required, max 500 chars, placeholder: "http://localhost:8080")
4. Username (text input, optional, max 100 chars)
5. Credential (password input, max 500 chars)
   - In edit mode: show hint "leave empty to keep current"
6. Active (checkbox/toggle, default: true)

Behavior:
- Edit mode: pre-fills all fields from existing tool data (except credential)
- Page title: "Add Tool" for create, "Edit Tool" for edit
- Submit button: "Create" or "Update" depending on mode
- Cancel button → navigates back to /tools
- Submit disabled when Name or URL is empty, or while saving
- Button text: "Saving..." while request is pending
- On error: show alert with error message
- On success: redirect to /tools
```

## 변환 시 주의사항

1. **Tailwind 클래스 → 그대로 사용**: Stitch가 생성한 Tailwind 클래스를 React JSX의 `className`에 그대로 적용
2. **HTML → JSX 변환**: `class` → `className`, `for` → `htmlFor`, self-closing tags
3. **이벤트 핸들러 보존**: 기존 `onClick`, `onSubmit`, `onChange` 핸들러를 새 마크업에 연결
4. **조건부 렌더링 보존**: `isLoading`, `error`, empty state 등 기존 분기 로직 유지
5. **타입 안전성**: Props 인터페이스는 변경하지 않음
6. **기존 CSS 정리**: Tailwind 적용 완료된 컴포넌트의 vanilla CSS는 `index.css`에서 제거

## 검증

```bash
cd frontend
yarn build          # 빌드 통과
yarn dev            # localhost:5173에서 확인
```

- [ ] 헤더/네비게이션 동작
- [ ] Ticket CRUD 동작
- [ ] Pipeline SSE 실시간 업데이트
- [ ] Tool CRUD + 연결 테스트
- [ ] 반응형 레이아웃
