# ADR-005: 반입(IMPORT) 모드를 PoC에서 제외

## 상태

Accepted

## 맥락

TPS에는 배포 방식을 선택하는 개념이 있다. 빌드(BUILD) 모드는 소스를 직접 빌드하여 아티팩트를 생성한 뒤 배포한다. 반입(IMPORT) 모드는 이미 빌드된 아티팩트를 외부에서 가져와 배포한다. 예를 들어 다른 팀이 이미 빌드한 JAR 파일을 Nexus에서 다운로드하여 특정 서버에 배포하는 경우가 반입 모드에 해당한다.

PoC 설계 시, 반입 모드를 위한 별도 IMPORT Job 타입을 만들 것인지 검토했다. IMPORT Job은 "아티팩트 저장소에서 특정 버전을 가져오는" 기능이 BUILD Job과 다르기 때문이다.

## 결정

IMPORT Job 타입을 별도로 만들지 않는다. 대신 DEPLOY Job의 `config`에 `artifactRef` 필드를 추가하여, 외부에서 가져온 아티팩트를 직접 지정하는 방식으로 대체한다.

반입 시나리오는 다음 구조로 표현한다.

```
파이프라인 구성 (반입 배포):
  - DEPLOY Job만 포함
  - config.artifactRef = {
      repositoryType: "NEXUS",
      groupId: "com.example",
      artifactId: "my-service",
      version: "1.2.3"
    }
```

BUILD Job이 포함된 파이프라인의 경우 `artifactRef`를 비워두면, 빌드 결과물을 자동으로 참조한다.

## 근거

IMPORT Job 타입을 별도로 만들지 않는 이유는 두 가지다.

첫째, DEPLOY Job으로 충분히 표현 가능하다. 반입의 핵심은 "빌드 단계 없이 기존 아티팩트를 배포한다"는 것이다. DEPLOY Job에 `artifactRef`를 추가하면 "어떤 아티팩트를 배포할지"를 명시할 수 있고, BUILD Job 없이 DEPLOY Job만으로 파이프라인을 구성하면 반입 시나리오와 동일한 결과를 얻는다.

둘째, IMPORT Job은 불필요한 간접 계층이다. IMPORT Job을 만들면 "아티팩트를 가져온 후 DEPLOY Job에 전달하는" 두 단계의 Job 연계가 필요하다. `artifactRef` 필드를 DEPLOY Job에 직접 두면 한 단계로 해결된다. 간접 계층이 추가될수록 디버깅이 어려워지고 파이프라인 설정이 복잡해진다.

## 대안

**IMPORT Job 타입 추가**: BUILD/DEPLOY/IMPORT를 동등한 세 가지 타입으로 구성하는 방법이다. TPS 원본 구조에 더 가깝지만, IMPORT Job이 하는 일이 "아티팩트 참조 설정"에 불과하여 독립 엔티티로 만들 가치가 낮다. DEPLOY Job에 `artifactRef`를 추가하는 것으로 동일한 기능을 더 단순하게 표현할 수 있어 폐기했다.

**반입 전용 파이프라인 템플릿**: IMPORT 시나리오를 위한 파이프라인 프리셋을 만드는 방법이다. 이 역시 `artifactRef`가 있는 DEPLOY 전용 파이프라인과 실질적으로 같으므로 별도로 만들 필요가 없다.

## 영향

- `pipeline_job`의 `jobType` 열거형에 `IMPORT`를 추가하지 않는다
- DEPLOY Job `config` 스키마에 `artifactRef` 필드가 추가된다 (`nullable`, 없으면 BUILD Job 산출물 자동 참조)
- 반입 시나리오 테스트는 DEPLOY Job만 있는 파이프라인 + `artifactRef` 설정으로 작성한다
- Phase 4에서 아티팩트 어댑터가 구현되면 `artifactRef`의 실제 아티팩트 조회 로직을 연결한다
