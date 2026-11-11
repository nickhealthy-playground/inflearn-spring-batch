# 인프런 강의

해당 저장소의 `README.md`는 인프런 Spring Batch 강의(Spring Boot 2.x 기반)를 듣고 공부한 내용을 가볍게 정리한 것입니다. 문제가 될 시 삭제하겠습니다.



## 해당 프로젝트에서 배우는 내용

* 섹션 3 | 스프링 배치 시작



## 실행 환경

<details>
<summary><b>브랜치 구성과 실행 방법 펼쳐보기</b></summary>

<br>

### 브랜치

| 브랜치 | 스택 | 내용 |
|---|---|---|
| `main` | Java 17 · Spring Boot 2.5.2 · Spring Batch 4.3.3 | 강의 코드 그대로 |
| `boot4` | Java 17 · Spring Boot 4.1.2 · Spring Batch 6.0.6 | `main` 실습을 최신 버전으로 옮긴 것. 파일 경로·클래스명이 같아서 `git diff main boot4 -- <path>` 가 버전 간 마이그레이션 diff가 된다 |

- 메타데이터 DB는 브랜치마다 따로 쓴다 (`main` = `batch4`, `boot4` = `batch6`, `application.properties` 의 `app.database-name`).
- 1회차(2024) 실습 코드는 `2024/*` 태그에 남아 있다.

### MySQL

```bash
read -rs MYSQL_PW   # application.yml 의 mysql 프로파일 password 값
docker run -d --name spring-batch-mysql -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD="$MYSQL_PW" \
  -v spring-batch-mysql-data:/var/lib/mysql \
  mysql:8.4.11
```

### 로컬 설정 파일

`src/main/resources/application.yml` 은 DB 자격증명이 들어 있어 git에서 제외했다. 아래 구조로 만든다.

```yaml
spring:
  profiles:
    active: mysql

---
spring:
  config:
    activate:
      on-profile: local          # MySQL 없이 H2로 실행
  datasource:
    hikari:
      jdbc-url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
      username: sa
      password:
      driver-class-name: org.h2.Driver
  batch:
    jdbc:
      initialize-schema: embedded

---
spring:
  config:
    activate:
      on-profile: mysql
  datasource:
    hikari:
      jdbc-url: jdbc:mysql://localhost:3306/${app.database-name}?createDatabaseIfNotExist=true
      username: root
      password: <MySQL root 비밀번호>
      driver-class-name: com.mysql.cj.jdbc.Driver
  batch:
    jdbc:
      initialize-schema: always
    job:
      enabled: false             # 기동 시 자동 실행 방지. 실행할 때 명령행으로 켬
```

### 실행

실습 Configuration은 한 번에 하나만 `@Configuration` 주석을 푼다. 여러 실습이 `step1`, `step2` 같은 빈 이름을 같이 쓰고, 부트 2.5는 컨텍스트에 있는 Job을 모두 실행하기 때문이다.

```bash
./mvnw clean package -DskipTests
java -jar target/hello-world-0.0.1-SNAPSHOT.jar \
  --spring.batch.job.enabled=true \
  --spring.batch.job.names=SimpleJobArchitectureConfiguration \
  'name=user1' 'seq(long)=1' 'date(date)=2026/11/17'

SPRING_PROFILES_ACTIVE=local ./mvnw clean test   # MySQL 없이 테스트
```

</details>



# 섹션 3 | 스프링 배치 시작

<details>
<summary><b>핵심 정리 펼쳐보기</b></summary>

<br>

> 스프링 배치는 대량 데이터를 처리하는 작업을 **Job → Step → Tasklet** 구조로 실행하고, 실행 이력을 메타데이터 테이블에 기록한다. 이 기록으로 같은 작업의 중복 실행을 막고 실패한 지점부터 재시작한다.
> 스프링 부트 2.x에서는 `@EnableBatchProcessing` 을 붙여야 배치 인프라 빈과 자동 설정이 켜진다.
> (사용 버전: Spring Boot 2.5.2 → Spring Batch 4.3.3, MySQL 8.4 / H2 1.4.200)

### 한눈에 보기

| 구분 | API | 핵심 |
|---|---|---|
| 시작 설정 | `@EnableBatchProcessing` | `JobRepository`·`JobLauncher`·`JobExplorer`·`JobRegistry`·트랜잭션 매니저와 `JobBuilderFactory`·`StepBuilderFactory` 를 빈으로 등록 |
| Job | `jobBuilderFactory.get("이름")` | 배치 작업 하나. `start(step).next(step)` 로 Step을 순서대로 실행 |
| Step | `stepBuilderFactory.get("이름")` | Job의 한 단계. `tasklet()` 을 주면 `TaskletStep` 이 된다 |
| Tasklet | `execute(contribution, chunkContext)` | Step에서 실행할 로직. `RepeatStatus.FINISHED` 면 한 번 실행하고 끝, `CONTINUABLE` 이면 반복 |
| 기동 시 실행 | `JobLauncherApplicationRunner` | 부트가 기동 직후 컨텍스트의 Job을 실행 (섹션 5에서 자세히) |
| 스키마 생성 | `spring.batch.jdbc.initialize-schema` | 메타데이터 테이블 생성 스크립트(`schema-mysql.sql` 등) 실행 여부 |

### 메타데이터 테이블

| 테이블 | 저장 내용 | 관계 |
|---|---|---|
| `BATCH_JOB_INSTANCE` | Job 이름 + identifying 파라미터의 MD5 해시(`JOB_KEY`) | `(JOB_NAME, JOB_KEY)` 유니크 → 같은 Job·같은 파라미터는 같은 인스턴스 |
| `BATCH_JOB_EXECUTION` | 실행 시도 1번 = 1행. `STATUS`, `EXIT_CODE`, 시작·종료 시각 | 인스턴스 1 : 실행 N |
| `BATCH_JOB_EXECUTION_PARAMS` | 실행에 쓴 JobParameters. 타입별 컬럼(`STRING_VAL`·`DATE_VAL`·`LONG_VAL`·`DOUBLE_VAL`)과 `IDENTIFYING` | 실행 1 : 파라미터 N. PK 없음 |
| `BATCH_JOB_EXECUTION_CONTEXT` | Job 범위 `ExecutionContext` 직렬화 값 | 실행 1 : 1 |
| `BATCH_STEP_EXECUTION` | Step 실행 1번 = 1행. 상태, READ·WRITE·SKIP·COMMIT·ROLLBACK 카운트 | Job 실행 1 : Step 실행 N |
| `BATCH_STEP_EXECUTION_CONTEXT` | Step 범위 `ExecutionContext` 직렬화 값 | Step 실행 1 : 1 |
| `BATCH_JOB_SEQ` 외 2개 | ID 채번. MySQL은 시퀀스가 없어 테이블로 흉내 낸다 | - |

### 간단한 사용법

```java
@SpringBootApplication
@EnableBatchProcessing   // 부트 2.x: 배치 인프라 빈 + BatchAutoConfiguration 활성화
public class SpringBatchApplication { ... }
```

```java
@Bean
public Job Job() {
    return jobBuilderFactory.get("DBJobConfiguration")
            .start(step1())
            .next(step2())
            .build();
}

@Bean
public Step step1() {
    return stepBuilderFactory.get("step1")
            .tasklet((contribution, chunkContext) -> {
                System.out.println("STEP1 WAS EXECUTED");
                return RepeatStatus.FINISHED;   // 한 번 실행하고 종료
            }).build();
}
```

```yaml
spring:
  batch:
    jdbc:
      initialize-schema: always   # embedded(기본) | always | never
```

| `initialize-schema` | 동작 |
|---|---|
| `embedded` (기본값) | H2 같은 임베디드 DB일 때만 스크립트 실행 |
| `always` | DB 종류와 상관없이 실행. 로컬 MySQL에서 테이블을 자동으로 만들 때 |
| `never` | 실행하지 않음. 운영에서는 DDL을 직접 관리 |

### 동작 원리

| 동작 | 실제로 일어나는 일 |
|---|---|
| `@EnableBatchProcessing` | `SimpleBatchConfiguration` 이 `JobRepository`·`JobLauncher` 등을 **지연 프록시**로 등록하고, 실제 객체는 `BatchConfigurer`(부트는 `BasicBatchConfigurer`)가 만든다 |
| 부트 자동 설정 | `BatchAutoConfiguration` 은 `@ConditionalOnBean(JobLauncher.class)` 라서 `JobLauncher` 빈이 있어야 동작한다. 스키마 초기화와 `JobLauncherApplicationRunner` 등록을 맡는다 |
| 기동 시 실행 | `JobLauncherApplicationRunner`(`ApplicationRunner`)가 컨텍스트 refresh 뒤 `JobLauncher` 로 Job을 실행 |
| 실행 기록 | 실행할 때마다 `JOB_EXECUTION`·`STEP_EXECUTION`·`*_CONTEXT` 행이 쌓이고, 새 파라미터면 `JOB_INSTANCE` 도 생긴다 |

### 버전 차이

| 항목 | Batch 4.3.3 (부트 2.5) | Batch 6.0.6 (부트 4.1) |
|---|---|---|
| `@EnableBatchProcessing` | 붙여야 자동 설정이 켜짐 | 붙이면 부트 배치 자동 설정이 **꺼짐** |
| 기본 `JobRepository` | 항상 DB 사용 | 기본은 저장하지 않는 resourceless. 테이블이 필요하면 `spring-boot-starter-batch-jdbc` |
| 스키마 | `BATCH_JOB_EXECUTION_PARAMS` 에 타입별 값 컬럼 | `PARAMETER_NAME`·`PARAMETER_TYPE`·`PARAMETER_VALUE` 로 재설계(5.0), `BATCH_JOB_SEQ` → `BATCH_JOB_INSTANCE_SEQ`(6.0) |

### 주의사항

| 주의 | 내용 |
|---|---|
| `@EnableBatchProcessing` 누락 | 부트 2.x에서 빼면 `JobLauncher` 빈이 없어 `BatchAutoConfiguration` 전체가 빠지고, `JobBuilderFactory` 빈도 등록되지 않는다 |
| 자동 실행이 조용히 빠짐 | `spring.batch.job.enabled=false` 면 runner 빈 자체가 만들어지지 않아 에러 없이 아무 Job도 실행되지 않는다. `--debug` 조건 평가 리포트의 `BatchAutoConfiguration#jobLauncherApplicationRunner` 항목으로 확인한다 |
| 메타데이터 DB 공유 금지 | Batch 4와 5 이상은 스키마가 달라 한 DB를 같이 쓰면 깨진다. 이 저장소는 `batch4`/`batch6` 으로 나눴다 |
| 1:N 관계는 DDL이 아니라 프레임워크가 보장 | DDL에는 "인스턴스에 실행이 최소 하나" 같은 제약이 없다. 4.3.3은 인스턴스와 첫 실행을 한 트랜잭션에서 함께 만들어서 실행 없는 인스턴스가 생기지 않는다 |

</details>
