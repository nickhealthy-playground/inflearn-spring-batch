# 인프런 강의

해당 저장소의 `README.md`는 인프런 Spring Batch 강의(Spring Boot 2.x 기반)를 듣고 공부한 내용을 가볍게 정리한 것입니다. 문제가 될 시 삭제하겠습니다.



## 해당 프로젝트에서 배우는 내용

* 섹션 3 | 스프링 배치 시작
* 섹션 4 | 스프링 배치 도메인 이해
* 섹션 5 | 스프링 배치 실행 - Job



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



# 섹션 4 | 스프링 배치 도메인 이해

<details>
<summary><b>핵심 정리 펼쳐보기</b></summary>

<br>

> 배치를 **구성하는 객체**(`Job`·`Step`)와, 실행할 때마다 만들어져 메타데이터 테이블에 저장되는 **실행 객체**(`JobInstance`·`JobExecution`·`StepExecution`·`ExecutionContext`)를 나눠서 본다.
> `JobLauncher` 가 Job을 실행하고 `JobRepository` 가 실행 상태를 기록하며, 그 기록으로 완료된 작업의 재실행을 막고 실패한 Step부터 재시작한다.
> (사용 버전: Spring Batch 4.3.3)

### 한눈에 보기

| 구분 | 객체 | 핵심 | 테이블 |
|---|---|---|---|
| 구성 | `Job` | 배치 작업의 최상위 단위. `SimpleJob`(Step 순차 실행), `FlowJob`(조건 분기) | - |
| 구성 | `Step` | Job의 한 단계. `TaskletStep`·`PartitionStep`·`JobStep`·`FlowStep` | - |
| 실행 | `JobInstance` | Job 이름 + identifying 파라미터로 구분되는 논리적 실행 단위 | `BATCH_JOB_INSTANCE` |
| 실행 | `JobParameters` | JobInstance를 구분하고 Step에 값을 전달. String·Date·Long·Double 4종 | `BATCH_JOB_EXECUTION_PARAMS` |
| 실행 | `JobExecution` | JobInstance의 실행 시도 1번. 실패 후 다시 실행하면 같은 인스턴스에 새 JobExecution | `BATCH_JOB_EXECUTION` |
| 실행 | `StepExecution` | Step의 실행 시도 1번. 읽기·쓰기·커밋·롤백 카운트를 가짐 | `BATCH_STEP_EXECUTION` |
| 실행 | `StepContribution` | 청크(트랜잭션) 하나의 변경분. 커밋할 때 `StepExecution` 에 합산 | - |
| 실행 | `ExecutionContext` | Job·Step 범위의 key-value 저장소. 직렬화해서 저장하고 재시작 시 복원 | `BATCH_*_EXECUTION_CONTEXT` |
| 인프라 | `JobRepository` | 실행 객체의 저장·조회·갱신 | `BATCH_` 테이블 전체 |
| 인프라 | `JobLauncher` | `run(job, jobParameters)` 로 Job 실행. 구현체 `SimpleJobLauncher` | - |

### JobInstance가 만들어지는 조건 (`SimpleJobRepository#createJobExecution`)

| 같은 Job 이름 + 같은 identifying 파라미터로 실행할 때 | 결과 |
|---|---|
| 처음 실행 | JobInstance + JobExecution 생성 |
| 이전 실행이 `COMPLETED` (또는 `ABANDONED`) | `JobInstanceAlreadyCompleteException` |
| 이전 실행이 `FAILED`·`STOPPED` | 같은 JobInstance에 새 JobExecution (재시작), 이전 Job `ExecutionContext` 를 이어받음 |
| 이전 실행이 아직 실행 중 | `JobExecutionAlreadyRunningException` |
| identifying 파라미터가 하나도 없음 | 완료된 인스턴스여도 예외 없이 같은 인스턴스에 실행이 추가된다 |

### BatchStatus vs ExitStatus

| 구분 | `BatchStatus` | `ExitStatus` |
|---|---|---|
| 의미 | 프레임워크가 관리하는 실행 상태 | 실행 결과 코드. Flow 조건 분기에 쓰고 직접 정의할 수 있음 |
| 값 | `STARTING`, `STARTED`, `STOPPING`, `STOPPED`, `FAILED`, `COMPLETED`, `ABANDONED`, `UNKNOWN` | `UNKNOWN`, `EXECUTING`, `COMPLETED`, `NOOP`, `FAILED`, `STOPPED` |
| 컬럼 | `STATUS` | `EXIT_CODE`, `EXIT_MESSAGE` |
| Job 결과 | `SimpleJob` 은 마지막으로 실행한 Step의 상태를 Job의 상태로 올린다 | 마지막 Step의 종료 코드를 Job의 종료 코드로 쓴다 |

### JobParameters 전달과 조회

| 방법 | 예시 | 비고 |
|---|---|---|
| 코드 | `new JobParametersBuilder().addString("name", "user2").addLong("seq", 1L).toJobParameters()` | `ApplicationRunner` 에서 `jobLauncher.run(job, params)` (`JobRunner`) |
| 명령행 | `name=user1 'seq(long)=1' 'date(date)=2026/11/12' 'age(double)=16.5'` | `DefaultJobParametersConverter` 의 키 접미사 규칙. SpEL이 아니다. 앞에 `-` 를 붙이면 non-identifying |

```java
// StepExecution → JobExecution → JobParameters (타입별 getter)
JobParameters jobParameters = contribution.getStepExecution().getJobExecution().getJobParameters();
String name = jobParameters.getString("name");

// ChunkContext → StepContext → Map (값만 꺼냄)
Map<String, Object> parametersMap = chunkContext.getStepContext().getJobParameters();
```

### ExecutionContext 범위

| 범위 | 공유 | 꺼내는 방법 |
|---|---|---|
| Job | 같은 JobExecution의 모든 Step이 공유 | `stepExecution.getJobExecution().getExecutionContext()` |
| Step | 해당 Step 안에서만 | `stepExecution.getExecutionContext()` |

```java
// ExecutionContextTasklet3: 첫 실행은 Job 컨텍스트에 값을 남기고 실패
if (jobExecutionContext.get("name") == null) {
    jobExecutionContext.put("name", "user1");
    throw new RuntimeException("step3 was failed");
}
// 같은 파라미터로 재시작 → step1·2는 COMPLETED라 건너뛰고, step3은 복원된 name을 보고 통과
```

### JobRepository 설정 (`CustomBatchConfigurer`)

| 설정 | 기본값 | 내용 |
|---|---|---|
| `setIsolationLevelForCreate()` | `ISOLATION_SERIALIZABLE` | `create*` 메서드의 격리 수준. 같은 Job을 동시에 실행해도 JobInstance가 중복 생성되지 않게 한다 |
| `setTablePrefix()` | `BATCH_` | 조회·저장에 쓰는 테이블 이름 접두어 |
| `setMaxVarCharLength()` | 2500 | `EXIT_MESSAGE` 같은 긴 문자열 컬럼에 저장할 최대 길이 |

부트 2.5에서는 `BasicBatchConfigurer` 를 상속해 `createJobRepository()` 를 재정의한다. `JobRepository` 의 트랜잭션은 `create*` 가 `REQUIRES_NEW` + 위 격리 수준, 나머지는 `REQUIRED` 다.

### JobLauncher 동기 / 비동기

| 구분 | `TaskExecutor` | `run()` 반환 시점 |
|---|---|---|
| 동기 (기본) | `SyncTaskExecutor` | Job이 끝난 뒤. 최종 상태가 담긴 `JobExecution` |
| 비동기 | `SimpleAsyncTaskExecutor` 등 | 바로 반환. 아직 실행 중인 `JobExecution` (HTTP 요청에서 Job을 띄울 때) |

### 동작 원리

| 동작 | 실제로 일어나는 일 |
|---|---|
| 실행 요청 | `SimpleJobLauncher.run()` → 마지막 실행 조회(재시작 가능 여부·실행 중 여부) → 파라미터 검증 → `jobRepository.createJobExecution()` |
| 인스턴스·실행 생성 | `createJobExecution()` 호출 하나에서 JobInstance(없을 때)·JobExecution·Job `ExecutionContext` 를 저장. `REQUIRES_NEW` 트랜잭션이라 함께 커밋된다 |
| Step 실행 준비 | `SimpleStepHandler` 가 StepExecution을 만든다. 이전 실행이 `COMPLETED` 면 건너뛰고(`allowStartIfComplete=false`), 실패였으면 이전 Step `ExecutionContext` 를 복원 |
| 청크 커밋 | `TaskletStep` 이 트랜잭션마다 `stepExecution.apply(contribution)` 으로 카운트를 합산하고 Step `ExecutionContext`·`StepExecution` 을 저장 |
| Step 종료 후 | Job `ExecutionContext` 저장 (`jobRepository.updateExecutionContext(execution)`). Step이 실패해도 저장되므로 재시작 때 값이 남아 있다 |
| 주입받는 객체 | `@EnableBatchProcessing` 이 등록한 `JobLauncher`·`JobRepository` 는 지연 프록시라 `SimpleJobLauncher` 로 캐스팅할 수 없다 → `BasicBatchConfigurer#getJobLauncher()` 로 실제 객체를 꺼낸다 (`JobLauncherController`) |

### 주의사항

| 주의 | 내용 |
|---|---|
| 없는 키 조회 | `jobParameters.getLong("없는키")` 는 예외 없이 `null` 을 반환한다. 키 오타가 조용히 넘어간다 |
| 명령행 날짜 형식 | `(date)` 는 `yyyy/MM/dd` 고정. `2026-11-12` 처럼 쓰면 `Date format is invalid` 로 기동이 실패한다 |
| zsh에서 `( )` | zsh는 `seq(long)=1` 을 glob 패턴으로 보고 `no matches found` 로 명령을 실행하지 않는다 → 따옴표로 감싼다. IntelliJ Program arguments는 쉘을 거치지 않아 괜찮다 |
| 명령행 인자 구분 | `--` 로 시작하면 스프링 프로퍼티, 나머지 `key=value` 만 JobParameters. `=` 가 없는 인자는 조용히 버려진다 |
| 파라미터 없는 Job | identifying 파라미터가 없으면 완료 후 다시 실행해도 막히지 않는다. 재실행을 막고 싶으면 날짜 같은 파라미터를 넣는다 |
| `tablePrefix` | 접두어를 바꾸면 그 이름의 테이블을 조회할 뿐 테이블을 만들지 않는다. `SYSTEM_JOB_EXECUTION` 같은 테이블이 실제로 있어야 한다 |
| `ExecutionContext` 값 | 직렬화해서 DB에 저장된다(4.3.3 기본 `Jackson2ExecutionContextStringSerializer`). 큰 객체나 민감 정보는 넣지 않는다 |
| 공유 `JobLauncher` 변경 | 실습처럼 `SimpleJobLauncher` 의 `TaskExecutor` 를 요청마다 바꾸면 같은 빈을 쓰는 모든 실행이 비동기가 된다. 비동기 전용 `JobLauncher` 빈을 따로 둔다 |
| in-memory 저장소 | `MapJobRepositoryFactoryBean` 은 4.3에서 deprecated. 테스트도 H2 같은 DB와 `JobRepositoryFactoryBean` 을 쓴다 |

</details>



# 섹션 5 | 스프링 배치 실행 - Job

<details>
<summary><b>핵심 정리 펼쳐보기</b></summary>

<br>

> 스프링 부트가 기동할 때 Job을 실행하는 방식(`JobLauncherApplicationRunner`)과, `JobBuilderFactory` → `SimpleJobBuilder` 로 Job을 만드는 API를 다룬다.
> `SimpleJob` 은 Step을 순서대로 실행하고, `validator()`·`preventRestart()`·`incrementer()`·`listener()` 로 실행 전후 동작을 붙인다.
> (사용 버전: Spring Boot 2.5.2, Spring Batch 4.3.3)

### 한눈에 보기

| 구분 | API | 핵심 |
|---|---|---|
| 기동 시 실행 | `spring.batch.job.enabled` | 기본 `true`. `false` 면 runner 빈이 등록되지 않음 |
| 기동 시 실행 | `spring.batch.job.names` | 실행할 Job 이름(쉼표 구분, 패턴 가능). 비어 있으면 컨텍스트의 모든 Job 실행 |
| 빌더 | `jobBuilderFactory.get(name)` | `new JobBuilder(name).repository(jobRepository)` |
| 빌더 | `start(Step)` / `start(Flow)` | `SimpleJobBuilder` → `SimpleJob` / `JobFlowBuilder` → `.end()` → `FlowJob` |
| SimpleJob | `next(Step)` | 다음 Step. 앞 Step이 `COMPLETED` 일 때만 실행 |
| SimpleJob | `validator()` | JobParameters 검증 |
| SimpleJob | `preventRestart()` | 재시작 금지 (`restartable = false`) |
| SimpleJob | `incrementer()` | 다음 실행에 쓸 JobParameters 생성 |
| SimpleJob | `listener()` | `JobExecutionListener` 의 `beforeJob` / `afterJob` |

### 배치 초기화 설정 (`JobLauncherApplicationRunner`)

| 항목 | 동작 |
|---|---|
| 실행 시점 | `ApplicationRunner` 라서 컨텍스트 refresh가 끝난 뒤 실행 |
| 실행 대상 | `spring.batch.job.names` 와 일치하는 Job만. 일치하지 않는 Job은 DEBUG 로그(`Skipped job`)만 남기고 건너뜀 |
| JobParameters | `--` 가 없는 인자(`getNonOptionArgs()`)만 `=` 기준으로 나눠 `DefaultJobParametersConverter` 로 변환 |
| incrementer 적용 | Job에 incrementer가 있으면 마지막 인스턴스의 파라미터로 `getNext()` 를 호출하고, 명령행 값으로 덮어써서 실행 |
| 실패한 인스턴스 재실행 | 같은 파라미터의 인스턴스가 `FAILED`·`STOPPED` 이고 재시작 가능하면 이전 identifying 파라미터를 이어받아 재시작 |

### validator()

| 설정 (`DefaultJobParametersValidator`) | 검증 |
|---|---|
| `requiredKeys` | 하나라도 없으면 `JobParametersInvalidException` |
| `optionalKeys` 가 비어 있지 않음 | `requiredKeys` ∪ `optionalKeys` 에 없는 키가 있으면 예외 |
| `optionalKeys` 가 비어 있음 | `requiredKeys` 외의 키는 검사하지 않음 |

```java
return this.jobBuilderFactory.get("ValidatorConfiguration")
        // .validator(new CustomJobParametersValidator())   // JobParametersValidator 직접 구현
        .validator(new DefaultJobParametersValidator(new String[]{"name", "date"}, new String[]{"year"}))
        .start(step1())
        .next(step2())
        .next(step3())
        .build();
```

검증은 두 번 호출된다. `SimpleJobLauncher.run()` 에서 실행을 만들기 전에 한 번, `AbstractJob.execute()` 시작할 때 한 번.

### preventRestart()

| 같은 파라미터로 다시 실행할 때 | 기본 (`restartable = true`) | `preventRestart()` |
|---|---|---|
| 이전 실행 실패 | 같은 인스턴스로 재시작 | `JobRestartException: JobInstance already exists and is not restartable` |
| 이전 실행 완료 | `JobInstanceAlreadyCompleteException` | `JobRestartException` (런처가 저장소보다 먼저 검사) |
| 새 파라미터 | 새 인스턴스 | 새 인스턴스 |

실습(`PreventRestartConfiguration`)은 step3이 항상 실패하므로, 같은 파라미터로 다시 실행하면 재시작 대신 `JobRestartException` 이 난다.

### incrementer()

| 구현 | 동작 |
|---|---|
| `RunIdIncrementer` | 이전 파라미터의 `run.id` + 1 (없으면 1). 다른 파라미터도 그대로 이어받는다 |
| `CustomJobParametersIncrementer` (실습) | `run.id` 에 현재 시각 문자열(`yyyy-MM-dd HH:mm:ss`) |

```java
public JobParameters getNext(@Nullable JobParameters parameters) {
    String id = dateFormat.format(new Date());
    return new JobParametersBuilder().addString("run.id", id).toJobParameters();
}
```

### JobBuilderFactory 구조

| 클래스 | 역할 |
|---|---|
| `JobBuilderFactory` | `get(name)` 으로 `JobBuilder` 를 만들고 `JobRepository` 를 넣어 준다 |
| `JobBuilderHelper` | 공통 설정: `validator`, `incrementer`, `repository`, `listener`, `preventRestart` |
| `JobBuilder` | `start(Step)` → `SimpleJobBuilder`, `start(Flow)`·`flow(Step)` → `JobFlowBuilder` |
| `SimpleJobBuilder` | `next(Step)` 로 Step 추가, `build()` → `SimpleJob` |
| `JobFlowBuilder` → `FlowJobBuilder` | Flow 정의 후 `.end()` → `build()` → `FlowJob` (`JobBuilderConfiguration#batchJob2`) |

### 동작 원리 (SimpleJob 아키텍처)

| 단계 | 실제로 일어나는 일 |
|---|---|
| 1. 실행 요청 | `SimpleJobLauncher.run()`: 마지막 실행 조회 → `preventRestart` 검사 → `validator` 검증 → `createJobExecution()` |
| 2. Job 시작 | `AbstractJob.execute()`: `validator` 재검증 → 시작 시각 기록, 상태 `STARTED` → `listener.beforeJob()` |
| 3. Step 실행 | `SimpleJob.doExecute()` 가 Step마다 `SimpleStepHandler.handleStep()` 호출 → StepExecution 생성·저장 → `step.execute()` |
| 4. 중단 | Step 상태가 `COMPLETED` 가 아니면 남은 Step을 실행하지 않는다 |
| 5. 결과 반영 | 마지막 Step의 `BatchStatus`·`ExitStatus` 를 JobExecution에 반영 |
| 6. 종료 | 종료 시각 기록 → `listener.afterJob()` → `jobRepository.update(execution)` |

### 버전 차이

| 항목 | Batch 4.3.3 | Batch 6.0.6 |
|---|---|---|
| Job 생성 | `jobBuilderFactory.get(name)` | `new JobBuilder(name, jobRepository)`. 팩토리는 6.0에서 제거 |
| 기동 시 실행할 Job | `spring.batch.job.names` (여러 개, 패턴) | `spring.batch.job.name` (하나). Job이 여러 개면 지정 필수 |
| 명령행 파라미터 표기 | `seq(long)=1` | `seq=1,java.lang.Long` |
| 실행 API | `JobLauncher` | `JobOperator` (`JobLauncher` deprecated) |
| 검증 실패 시 | 인스턴스를 만들기 전에 검증 → 아무것도 남지 않음 | 인스턴스를 먼저 커밋하고 검증 → **실행 없는 인스턴스**가 남고, 같은 파라미터로 다시 실행하면 `IllegalStateException` (H2로 두 버전 실행해 확인) |

### 주의사항

| 주의 | 내용 |
|---|---|
| Job 여러 개 | 부트 2.5는 `names` 가 없으면 컨텍스트의 Job을 **모두** 같은 파라미터로 실행한다. 실습 Configuration을 하나만 켜는 이유 |
| `names` 오타 | 일치하는 Job이 없으면 에러 없이 DEBUG 로그만 남는다 |
| incrementer는 런처가 쓰지 않음 | `jobLauncher.run(job, params)` 를 직접 부르면 incrementer가 적용되지 않는다. 부트 runner처럼 `getNextJobParameters()` 를 거치는 경로에서만 동작 |
| `optionalKeys` + incrementer | `optionalKeys` 를 지정했다면 `run.id` 도 `requiredKeys`·`optionalKeys` 에 넣어야 한다. 아니면 검증에서 막힌다 |
| 무거운 검증 | validator는 실행 한 번에 두 번 호출된다. 외부 자원을 조회하는 검증은 비용도 두 번 든다 |
| 시각 기반 `run.id` | 실습 incrementer는 초 단위라 1초 안에 두 번 실행하면 같은 `run.id` 가 되어 두 번째가 `JobInstanceAlreadyCompleteException` 으로 막힌다. `static SimpleDateFormat` 은 thread-safe하지 않다 |
| `afterJob` 예외 | `afterJob` 에서 던진 예외는 로그만 남고 Job 상태에 반영되지 않는다 |
| `preventRestart` 이후 | 실패한 인스턴스를 다시 돌릴 수 없으므로 파라미터를 바꿔 새 인스턴스로 실행해야 한다 |

</details>
