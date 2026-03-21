# 크래시 복구 패턴 (Crash Recovery)

## 문제

앱이 비정상 종료되면 메모리의 `executionStates`가 사라진다. DB에는 RUNNING 상태로 남아 있지만 아무도 후속 Job을 dispatch하지 않아 실행이 영원히 멈춘다.

## 해결: @PostConstruct 복구

```java
@PostConstruct
public void recoverRunningExecutions() {
    List<PipelineExecution> running = executionMapper.findByStatus("RUNNING");
    for (var exec : running) {
        // 1. Job + 의존성 로드 → DagExecutionState 재구성
        // 2. RUNNING/WAITING_WEBHOOK job → FAILED (webhook 유실 가정)
        // 3. ready job 재dispatch or handleFailure
    }
}
```

## 핵심 결정: RUNNING job을 FAILED로 마킹하는 이유

크래시 시점에 Jenkins가 빌드를 완료했더라도 webhook 결과를 알 수 없다. 두 가지 선택지가 있었다.

- **(A) RUNNING 유지 + webhook 재수신 대기**: Jenkins가 webhook을 재발송하지 않으므로 영원히 멈춤.
- **(B) FAILED 전환 + 수동 재시작**: 보수적이지만 데이터 일관성 보장. 부분 재시작 API로 재개 가능.

(B)를 선택한 이유는 잘못된 SUCCESS 판정이 잘못된 FAILED 판정보다 위험하기 때문이다. FAILED는 재시작으로 복구할 수 있지만, 실제로 실패한 빌드를 SUCCESS로 간주하면 잘못된 아티팩트가 배포될 수 있다.

## DB가 진실의 원천

메모리 상태는 캐시일 뿐이다. 복구 시 DB의 job execution 상태를 기반으로 `DagExecutionState`를 재구성한다. 이 설계 덕분에 복구 로직이 `startExecution()`과 같은 코드를 재사용할 수 있다.
