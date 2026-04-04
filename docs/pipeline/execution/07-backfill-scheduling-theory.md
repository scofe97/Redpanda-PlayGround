# 백필 스케줄링 (Backfill Scheduling) 이론

## 문제 — 왜 자원이 놀고 있는가

HPC(High-Performance Computing) 클러스터에서 대형 병렬 작업은 많은 프로세서를 동시에 요구한다. 100개 프로세서 중 80개가 사용 중이면, 50개를 요구하는 대형 작업은 30개가 부족하여 대기한다. 이때 남은 20개 프로세서는 대형 작업을 위해 **예약(reservation)** 되어 아무도 쓰지 못한다.

```
프로세서 100개 클러스터:

  [사용 중: 80개]  [예약: 20개 — 아무도 못 씀]

  대형 작업 (50개 필요): "20개 더 비면 시작할게"
  소형 작업 (5개 필요):  "20개 비어있는데 왜 못 쓰지?"
```

이 상황이 지속되면 자원 활용률이 급격히 떨어진다. IBM Spectrum LSF 문서에서도 이 문제를 명확히 지적한다. "바쁜 클러스터에서 프로세서 예약은 대형 병렬 작업을 더 빨리 스케줄링하는 데 도움이 된다. 그러나 기본적으로 예약된 프로세서는 대형 작업이 시작될 때까지 유휴 상태로 남아 있다."

백필 스케줄링은 이 유휴 자원을 활용하는 알고리즘이다.

---

## 핵심 원리 — "빌려 쓰되, 방해하지 않는다"

백필의 규칙은 단 하나다. **예약된 슬롯을 다른 작업이 사용할 수 있되, 예약한 작업의 시작을 지연시키지 않아야 한다.**

이를 판단하려면 두 가지 정보가 필요하다:

1. **대형 작업의 예상 시작 시간** — 현재 실행 중인 작업들이 언제 끝나는지에서 역산
2. **소형 작업의 예상 실행 시간** — 소형 작업이 대형 작업 시작 전에 끝나는지 판단

IBM LSF는 이렇게 동작한다. "LSF는 작업이 실행 한계가 만료될 때까지 실행된다고 가정한다. 대형 작업의 시작 시간 전에 만료되는 실행 한계가 있는 작업만 예약된 슬롯을 사용할 수 있다."

```
시간축 →

T=현재           T=2시간 후 (대형 작업 시작 예정)
  |                    |
  ├── 소형 작업 A (1시간) ──┤  ← 2시간 전에 끝남 → 백필 OK
  ├── 소형 작업 B (3시간) ─────────────┤  ← 시작 시간 넘김 → 백필 거부
  |                    |
  └── 예약 슬롯 ──────────┘
```

---

## IBM LSF 시나리오 (공식 문서)

IBM Spectrum LSF 문서의 4-CPU 호스트 시나리오를 재구성한다.

```
시간    CPU-1       CPU-2       CPU-3       CPU-4       이벤트
─────  ─────       ─────       ─────       ─────       ─────
8:00   [job1]      [예약]      [예약]      [예약]      job1 시작 (순차, 2시간 한계)
                                                        job2 제출 (4CPU 필요) → 3CPU 예약

8:30   [job1]      [job3]      [job3]      [예약]      job3 제출 (2CPU, 1시간 한계)
                                                        job3은 9:30에 끝남 → job2 시작(10:00) 전
                                                        ★ 백필 허용!

9:30   [job1]      [빈 슬롯]   [빈 슬롯]   [예약]      job3 완료
                                                        예약 슬롯 복원

10:00  [job2]      [job2]      [job2]      [job2]      job1 완료 → job2 시작 (4CPU 확보)
```

만약 job3의 실행 한계가 2시간이었다면, job3은 10:30에 끝나게 되어 job2의 시작(10:00)을 지연시킨다. 이 경우 백필은 거부된다.

**핵심:** 백필 판단은 "실행 한계(runtime limit)"에 의존한다. 실행 한계가 없는 작업은 언제 끝날지 예측할 수 없으므로, LSF는 백필할 수 없다. "LSF는 실행 한계가 정의되지 않은 작업을 기다리는 대형 작업에 대해 백필할 수 없다."

---

## 두 가지 변종 — EASY vs Conservative

백필 알고리즘은 크게 두 가지로 나뉜다.

### EASY Backfill (Extensible Argonne Scheduling System)

1995년 David Lifka가 Argonne National Laboratory에서 제안했다. IBM SP2 슈퍼컴퓨터의 자원 활용률을 높이기 위해 개발되었으며, 이후 Slurm, PBS, LSF 등 주요 HPC 스케줄러에 채택되었다.

**규칙:** 큐의 **첫 번째 작업(가장 높은 우선순위)**만 보호한다. 나머지 작업의 시작 시간은 보장하지 않는다.

```
큐: [작업A(우선순위 1)] [작업B(우선순위 2)] [작업C(우선순위 3)]

EASY 규칙:
  - 작업A의 시작을 지연시키지 않는 한, 어떤 작업이든 빈 슬롯 사용 가능
  - 작업B, 작업C의 시작 시간은 보장하지 않음
  - 작업D(낮은 우선순위)가 작업B를 밀어내도 OK — 작업A만 안 밀면 됨
```

**장점:** 자원 활용률이 높다. 보호 대상이 1개뿐이므로 백필 기회가 많다.

**단점:** 작업B, C의 대기 시간이 예측 불가능하다. 계속 밀려날 수 있다(starvation 위험).

### Conservative Backfill

모든 큐 내 작업에 대해 예약(reservation)을 만든다. 백필은 **어떤 작업의 시작도 지연시키지 않을 때만** 허용된다.

```
큐: [작업A(10:00 시작)] [작업B(11:00 시작)] [작업C(12:00 시작)]

Conservative 규칙:
  - 작업A, B, C 모두의 예상 시작 시간을 보호
  - 백필 작업이 작업B의 시작(11:00)도 지연시키면 안 됨
  - 모든 작업의 대기 시간이 예측 가능
```

**장점:** 대기 시간을 사전에 예측할 수 있다. 사용자에게 "당신의 작업은 11:00에 시작됩니다"라고 알려줄 수 있다.

**단점:** 백필 기회가 EASY보다 적다. 자원 활용률이 약간 낮다.

### 비교

| 항목 | EASY | Conservative |
|------|------|-------------|
| 보호 대상 | 큐의 첫 번째 작업만 | 큐의 모든 작업 |
| 자원 활용률 | 높음 | EASY와 거의 동등 |
| 대기 시간 예측 | 불가능 (첫 번째 외) | 가능 (모든 작업) |
| Starvation 위험 | 있음 (2번째 이후 작업) | 없음 |
| 구현 복잡도 | 낮음 | 높음 (모든 작업에 reservation) |
| 채택 현황 | Slurm 기본, PBS, LSF | LSF 옵션, 일부 연구 스케줄러 |

연구 결과에 따르면 "Conservative 방식은 자원 활용률 면에서 EASY와 본질적으로 동일한 이점을 제공하면서, 대기 시간을 사전에 예측할 수 있다는 추가 장점이 있다."

---

## 백필의 전제 조건 — 실행 시간 추정

백필은 **"이 작업이 언제 끝나는가"**를 알아야만 동작한다. 추정이 없으면 백필 판단 자체가 불가능하다.

### HPC에서의 추정 방법

| 방법 | 원리 | 정확도 |
|------|------|--------|
| 사용자 명시 (`bsub -W 2:00`) | 사용자가 실행 시간 상한을 직접 지정 | 보수적 (과대 추정 경향) |
| 히스토리 기반 | 같은 종류 작업의 과거 실행 시간 평균 | 중간 |
| ML 예측 | 작업 특성(입력 크기, 알고리즘 등)으로 실행 시간 예측 | 높음 (학습 데이터 충분 시) |

사용자 명시 방식은 가장 일반적이지만 문제가 있다. 사용자는 안전하게 실행 시간을 과대 추정하는 경향이 있다. 실제 30분 걸리는 작업에 2시간을 지정하면, 그 2시간 동안 다른 작업이 백필하지 못한다. 이를 **"사용자 과대 추정 문제(user overestimation problem)"**라고 한다.

### CI/CD 파이프라인에서의 추정

CI/CD 파이프라인의 Job은 HPC 작업과 다른 특성을 갖는다:

- **실행 시간이 짧다** — 대부분 수초~수분 (HPC는 수시간~수일)
- **반복 실행이 많다** — 같은 빌드 Job이 매일 수십 번 실행
- **의존성이 DAG 형태** — HPC는 독립 작업이 많지만, CI/CD는 Job 간 의존성이 복잡

Playground에서는 실행 시간 추정 없이 백필을 구현했다. 대신 "DAG 의존성으로 인해 ready job이 없는 파이프라인을 건너뛰고, 다음 파이프라인의 ready job을 디스패치"하는 방식이다. 이는 EASY 백필의 변형으로, 시간 기반 판단 대신 **상태 기반 판단**을 사용한다.

```
HPC EASY Backfill:
  "이 작업이 대형 작업 시작 전에 끝나는가?" → 시간 기반

Playground Backfill:
  "이 파이프라인에 지금 실행 가능한 Job이 있는가?" → 상태 기반
```

---

## 고급 백필 변종

### Interruptible Backfill (중단 가능한 백필)

IBM LSF에서 지원하는 기능이다. 일반 백필은 "대형 작업 시작 전에 끝나는 작업만" 허용하지만, 중단 가능한 백필은 **끝나지 않아도 되는 대신, 대형 작업이 시작하면 즉시 중단(kill)된다.**

```
일반 백필:
  소형 작업 실행 시간 < 대형 작업 시작까지 남은 시간 → 허용

중단 가능한 백필:
  소형 작업 실행 시간 무관 → 허용
  대형 작업 시작 시 → 소형 작업 강제 종료
```

체크포인팅(checkpointing)을 지원하는 작업에 적합하다. 중단되어도 체크포인트에서 재시작할 수 있으므로 작업이 낭비되지 않는다.

### Fattened Backfill

EASY 백필에서 2번째 이후 작업의 starvation 문제를 완화하기 위한 변종이다. 첫 번째 작업뿐 아니라 **상위 K개 작업**에 대해 reservation을 만든다. EASY와 Conservative의 중간 지점이다.

```
EASY:          첫 번째 작업만 보호     → 백필 기회 최대, starvation 위험
Fattened(K=3): 상위 3개 작업 보호      → 백필 기회 중간, starvation 완화
Conservative:  모든 작업 보호           → 백필 기회 최소, starvation 없음
```

### ML 기반 백필 (2023~)

2023년 SC'23 워크숍에서 발표된 연구는 강화학습(Reinforcement Learning)으로 백필 전략을 학습한다. 기존 EASY 백필이 "첫 번째 작업만 보호"라는 고정 규칙을 사용하는 반면, RL 기반 백필은 클러스터 상태에 따라 **동적으로 백필 결정**을 내린다.

---

## 백필과 함께 쓰이는 스케줄링 전략

백필은 단독으로 쓰이기보다 다른 스케줄링 전략과 조합될 때 효과가 극대화된다. Samsung SDS의 ML/DL 클라우드 스케줄링 사례에서 이 조합을 잘 보여준다.

### Gang Scheduling — "전부 아니면 전무"

단일 작업에 필요한 모든 리소스(GPU, CPU, Memory)가 동시에 확보될 때만 작업을 시작한다. Kubernetes 기본 스케줄러는 작업을 분할하여 여러 노드에 배정하는데, 리소스가 부족하면 일부만 실행되어 실패할 수 있다. Gang Scheduler는 "전부 확보되면 시작, 아니면 대기"라는 원칙으로 작업 실패를 방지한다.

**백필과의 관계:** Gang Scheduling으로 대형 작업이 리소스 확보를 기다리는 동안 발생하는 유휴 자원을 백필이 채운다. 두 전략은 상호 보완적이다.

### Bin-Packing — "한 곳에 몰아서"

작업을 여러 노드에 분산하지 않고 가능한 한 하나의 노드에 집중 배치한다. 분산 학습에서 노드 간 데이터 전송 오버헤드를 줄이고, GPU 사용률을 높인다.

**백필과의 관계:** Bin-Packing이 작업을 한 노드에 몰면, 다른 노드에 빈 공간이 생긴다. 백필이 이 빈 공간을 소형 작업으로 채운다.

### Multi-Queue — "크기별 분류"

GPU 요청량에 따라 작업을 'High Queue'(대용량)와 'Low Queue'(소용량)로 분류한다. Samsung SDS 사례에서 FIFO 대비 **평균 대기시간이 32.3시간에서 5.77시간**으로 줄었고, **GPU 효율성이 20% 향상**되었다.

**백필과의 관계:** 각 큐 내에서 백필을 적용하면 큐 간 간섭 없이 자원 활용률을 높일 수 있다. Playground의 Purpose별 Jenkins 분리도 Multi-Queue의 변형으로 볼 수 있다.

### 예측 기반 백필 (Predicted Time Based Backfill)

Samsung SDS가 제시한 방식이다. 기존 백필이 **현재 시점의 유휴 자원만 보는 것**과 달리, 예측 기반 백필은 **선행 작업의 완료 시점을 미리 예측**하여 유휴 자원과 시간을 동시에 고려한다.

```
기존 백필:
  빈 슬롯 발견 → GPU 1개 요청 작업 즉시 배정
  → 소형 작업만 계속 실행, 대형 작업(Job1, Job2) 계속 연기

예측 기반 백필:
  Job3의 완료 시점 예측 (t=5) → t=5에 GPU 3개 확보 가능
  → Job1(GPU 3개 필요)을 t=5에 예약
  → t=0~5 사이 빈 슬롯에는 Job1을 방해하지 않는 작업만 배정
  → "사용자의 작업 순서를 보장하면서도 유휴 자원을 활용"
```

이 방식은 두 가지 예측 모델을 조합한다:
- **연산량 기반 모델** — 작업의 계산량(FLOPs 등)을 합산하여 시간 추정
- **데이터 기반 모델** — 과거 실행 이력에서 유사 작업의 실행 시간을 참조

**Playground 시사점:** 현재 Playground는 실행 시간 추정 없이 상태 기반으로 백필한다. 하지만 Jenkins 빌드 히스토리가 쌓이면, 과거 빌드 시간을 기반으로 예측 모델을 구축할 수 있다. 이를 통해 "이 파이프라인은 약 3분 후에 executor를 반환할 것"이라는 예측이 가능해지고, 보다 정밀한 백필 결정을 내릴 수 있다.

---

## Playground 적용 — HPC와의 차이

| 항목 | HPC 백필 | Playground 백필 |
|------|---------|----------------|
| 자원 단위 | 프로세서(CPU) 수 | Jenkins executor 수 |
| 작업 단위 | 독립 Job (의존성 없음) | DAG 내 Job (의존성 있음) |
| 실행 시간 추정 | 필수 (runtime limit) | 불필요 (상태 기반 판단) |
| 백필 트리거 | 예약 슬롯에 빈자리 | DAG 대기로 ready job 없음 |
| 백필 종료 조건 | 시간 만료 (예상 실행 시간 초과) | Job 완료 (webhook 도착) |
| 우선순위 기준 | 큐 순서 (FIFO 또는 가중치) | createdAt ASC (FIFO) |
| 보호 범위 | EASY: 첫 번째만, Conservative: 전체 | 전체 (createdAt 순서로 항상 선순위 우선) |

Playground의 백필은 **Conservative에 가깝다.** `dispatchByPriority()`가 항상 createdAt ASC 순서로 모든 파이프라인을 순회하기 때문에, 선순위 파이프라인에 ready job이 있으면 반드시 먼저 디스패치된다. 후순위 파이프라인이 선순위를 밀어내는 일은 구조적으로 불가능하다.

다만 HPC와 달리 "예상 시작 시간"을 보장하지는 않는다. 파이프라인의 총 실행 시간이 DAG 구조와 Jenkins 빌드 시간에 따라 동적으로 변하기 때문이다. 이는 CI/CD 워크로드의 특성상 자연스러운 트레이드오프다.

---

## 참조

- [Samsung SDS — 작업 스케줄링](https://www.samsungsds.com/kr/techreport/job-scheduling.html) — Gang, Bin-Packing, Multi-Queue, 예측 기반 백필 종합 해설
- [IBM Spectrum LSF — Backfill Scheduling](https://www.ibm.com/docs/en/spectrum-lsf/10.1.0?topic=jobs-backfill-scheduling) — IBM 공식 문서
- [Utilization and Predictability in Scheduling the IBM SP2 with Backfilling](https://ieeexplore.ieee.org/document/669970/) — Lifka 1995, EASY 백필 원본 논문
- [Evaluating the EASY-backfill job scheduling](https://www.researchgate.net/publication/4373991_Evaluating_the_EASY-backfill_job_scheduling_of_static_workloads_on_clusters) — EASY vs Conservative 비교 연구
- [A Reinforcement Learning Based Backfilling Strategy](https://arxiv.org/html/2404.09264v1) — ML 기반 백필 (2023 SC'23)
- [Tuning EASY-Backfilling Queues](https://hal.science/hal-01522459/document) — EASY 백필 튜닝 연구
- [priority-backfill-scheduler.md](./08-priority-backfill-scheduler.md) — Playground 설계 문서
- [priority-backfill-implementation.md](./09-priority-backfill-implementation.md) — Playground 구현 해설
