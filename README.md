# HR Roster Sync

**MoveInSync Backend Case Study 5: idempotent, out-of-order, self-healing.** Advanced Track.

## What this is

Corporate clients push employee changes (joiners, transfers, shift changes, exits) over an API
in near real time, and drop a full nightly export of up to 200,000 employees as a file. In
practice those updates **arrive twice, arrive out of order, contain bad data, and are sometimes
cut short.** This service keeps MoveInSync's employee records correct anyway, cancels the bookings
of people who leave, and refuses to deactivate half a company because a file got truncated.

## Run it

One command, including the database and the message broker:

```bash
docker compose up --build
```

| Service | URL |
|---|---|
| API | http://localhost:8081 |
| Health | http://localhost:8081/actuator/health |
| Metrics (Prometheus, operator token) | http://localhost:8081/actuator/prometheus |
| RabbitMQ management | http://localhost:15672 (`roster` / `rosterpass`) |
| MySQL | `localhost:3307` (`roster` / `rosterpass`) |

Host ports are shifted off the defaults (API 8081, MySQL 3307) to avoid collisions. The app waits
for MySQL and RabbitMQ to report healthy, not merely started.

**Seeded users, all with the password `Demo@1234`, for local use only:**

| Tenant code | Username | Role |
|---|---|---|
| `ACME` | `acme.employee` (is EMP-1002) | EMPLOYEE |
| `ACME` | `acme.admin` | ADMIN |
| `ACME` | `acme.operator` | OPERATOR |
| `GLOBEX` | `globex.employee` (is GLX-2001) | EMPLOYEE |
| `GLOBEX` | `globex.admin` | ADMIN |
| `GLOBEX` | `globex.operator` | OPERATOR |

To run the app on the host instead: `docker compose up -d mysql rabbitmq`, then `mvn spring-boot:run`.

## Demo script

Run these in order in one bash shell (Git Bash works on Windows), against a fresh
`docker compose up`. On a stack that has already run them, start with `docker compose down -v`.
Every output below is pasted unedited from one real run. Step 1 masks the token itself, and
`batchId` and the timestamps differ on each run.

**1. Set up and log in.** Every later step uses these. Tokens last one hour, and the tenant comes from the token and nothing else.

```bash
B=http://localhost:8081
login() { curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' -d "{\"tenantCode\":\"ACME\",\"username\":\"$1\",\"password\":\"Demo@1234\"}"; }
token() { login "$1" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'; }
ingest() { curl -s -w '\nHTTP %{http_code}\n' -X POST $B/api/v1/roster/events -H "Authorization: Bearer $OPERATOR" -H 'Content-Type: application/json' -d "$1"; }
OPERATOR=$(token acme.operator)
EMPLOYEE=$(token acme.employee)
login acme.operator | sed -E 's/"accessToken":"[^"]+"/"accessToken":"<token>"/'; echo
```

```text
{"accessToken":"<token>","tokenType":"Bearer","expiresInSeconds":3600,"role":"OPERATOR"}
```

**2. An ingest batch with one bad record.** Index 2 has a malformed phone number. The status is still 207, the other three are applied, and the error names the field and the rule, never the value.

```bash
BATCH='{"events":[
 {"eventId":"demo-1","employeeExternalId":"NEW-1","source":"HR","eventType":"CREATE","version":1,"payload":{"name":"Demo New One","email":"new1@acme.example","officeCode":"BLR-WHF","shiftCode":"S1"}},
 {"eventId":"demo-2","employeeExternalId":"NEW-2","source":"HR","eventType":"CREATE","version":1,"payload":{"name":"Demo New Two","email":"new2@acme.example","officeCode":"BLR-WHF","shiftCode":"S1"}},
 {"eventId":"demo-3","employeeExternalId":"BAD-1","source":"HR","eventType":"CREATE","version":1,"payload":{"name":"Demo Bad","email":"bad@acme.example","phone":"+9198765","officeCode":"BLR-WHF"}},
 {"eventId":"demo-4","employeeExternalId":"NEW-3","source":"HR","eventType":"CREATE","version":1,"payload":{"name":"Demo New Three","email":"new3@acme.example","officeCode":"BLR-ECO","shiftCode":"S2"}}
]}'
ingest "$BATCH"
```

```text
{"batchId":"fe6078d3-c8d5-4e80-a823-b74f09b0aff2","received":4,"applied":3,"ignoredStale":0,"duplicates":0,"ignoredNoOwnedFields":0,"rejected":1,"notProcessed":0,"results":[{"index":0,"eventId":"demo-1","employeeExternalId":"NEW-1","outcome":"APPLIED","errors":[],"warnings":[]},{"index":1,"eventId":"demo-2","employeeExternalId":"NEW-2","outcome":"APPLIED","errors":[],"warnings":[]},{"index":2,"eventId":"demo-3","employeeExternalId":"BAD-1","outcome":"REJECTED","errors":[{"code":"INVALID_PHONE","field":"phone"}],"warnings":[]},{"index":3,"eventId":"demo-4","employeeExternalId":"NEW-3","outcome":"APPLIED","errors":[],"warnings":[]}]}
HTTP 207
```

**3. The same batch replayed.** Nothing is applied twice. Every accepted event is a `DUPLICATE_NOOP` that reports what its first attempt did. The bad record is rejected again rather than reported as a duplicate, because a rejected record does not claim its key: a client that fixes the data can resend it with the same `eventId`.

```bash
ingest "$BATCH"
```

```text
{"batchId":"dd2c7888-8278-4e99-98aa-93837ceacd02","received":4,"applied":0,"ignoredStale":0,"duplicates":3,"ignoredNoOwnedFields":0,"rejected":1,"notProcessed":0,"results":[{"index":0,"eventId":"demo-1","employeeExternalId":"NEW-1","outcome":"DUPLICATE_NOOP","originalOutcome":"APPLIED","errors":[],"warnings":[]},{"index":1,"eventId":"demo-2","employeeExternalId":"NEW-2","outcome":"DUPLICATE_NOOP","originalOutcome":"APPLIED","errors":[],"warnings":[]},{"index":2,"eventId":"demo-3","employeeExternalId":"BAD-1","outcome":"REJECTED","errors":[{"code":"INVALID_PHONE","field":"phone"}],"warnings":[]},{"index":3,"eventId":"demo-4","employeeExternalId":"NEW-3","outcome":"DUPLICATE_NOOP","originalOutcome":"APPLIED","errors":[],"warnings":[]}]}
HTTP 207
```

**4. Version 7 arrives before version 6.** Example Use Case 1. An office transfer at version 7, then a shift change at version 6. The older event is ignored as stale, and the employee ends in the new office with the version 7 shift.

```bash
ingest '{"events":[{"eventId":"demo-v7","employeeExternalId":"EMP-1004","source":"HR","eventType":"UPDATE","version":7,"payload":{"officeCode":"BLR-ECO","shiftCode":"S3"}}]}'
ingest '{"events":[{"eventId":"demo-v6","employeeExternalId":"EMP-1004","source":"HR","eventType":"UPDATE","version":6,"payload":{"shiftCode":"S9"}}]}'
curl -s $B/api/v1/employees/EMP-1004 -H "Authorization: Bearer $OPERATOR"; echo
```

```text
{"batchId":"f05b526f-4b44-4bbf-92b5-bcfe35bac4a8","received":1,"applied":1,"ignoredStale":0,"duplicates":0,"ignoredNoOwnedFields":0,"rejected":0,"notProcessed":0,"results":[{"index":0,"eventId":"demo-v7","employeeExternalId":"EMP-1004","outcome":"APPLIED","errors":[],"warnings":[]}]}
HTTP 207
{"batchId":"c6a778bc-47ac-4384-9086-ee80b6ce9fc2","received":1,"applied":0,"ignoredStale":1,"duplicates":0,"ignoredNoOwnedFields":0,"rejected":0,"notProcessed":0,"results":[{"index":0,"eventId":"demo-v6","employeeExternalId":"EMP-1004","outcome":"IGNORED_STALE","errors":[],"warnings":[]}]}
HTTP 207
{"externalId":"EMP-1004","name":"Demo Employee Four","managerExternalId":"EMP-1005","officeCode":"BLR-ECO","shiftCode":"S3","status":"ACTIVE"}
```

**5. An HR payload carrying a pickup point.** The pickup point belongs to the transport admin. The shift change is applied, the pickup fields are dropped with a warning, and the record is not rejected. The employee's own view shows the pickup point is untouched.

```bash
ingest '{"events":[{"eventId":"demo-own","employeeExternalId":"EMP-1002","source":"HR","eventType":"UPDATE","version":6,"payload":{"shiftCode":"S2","pickupLat":1.0,"pickupLng":1.0,"pickupLabel":"Overwritten"}}]}'
curl -s $B/api/v1/employees/me -H "Authorization: Bearer $EMPLOYEE"; echo
```

```text
{"batchId":"1aebd5e3-46be-4094-beda-54add1d09453","received":1,"applied":1,"ignoredStale":0,"duplicates":0,"ignoredNoOwnedFields":0,"rejected":0,"notProcessed":0,"results":[{"index":0,"eventId":"demo-own","employeeExternalId":"EMP-1002","outcome":"APPLIED","errors":[],"warnings":[{"code":"FIELD_IGNORED_NOT_OWNED","field":"pickupLat"},{"code":"FIELD_IGNORED_NOT_OWNED","field":"pickupLng"},{"code":"FIELD_IGNORED_NOT_OWNED","field":"pickupLabel"}]}]}
HTTP 207
{"externalId":"EMP-1002","name":"Demo Employee Two","email":"emp1002@acme.example","phone":"+919000001002","managerExternalId":"EMP-1005","officeCode":"BLR-WHF","shiftCode":"S2","pickupLat":12.980000,"pickupLng":77.740000,"pickupLabel":"Demo Pickup Point B","specialAssistance":"Demo wheelchair access","status":"ACTIVE"}
```

**6. A DEACTIVATE cancels future bookings and frees seats.** Bookings have no API in this service, so this step reads them from MySQL. Booking 1 is in the past and stays `BOOKED`, because it is a billing record. Booking 8 was already cancelled, so it frees nothing. Trips 2 and 3 each lose exactly one seat, in the same transaction as the outbox rows.

```bash
SQL() { docker exec roster-mysql mysql -uroster -prosterpass -t roster -e "$1" 2>/dev/null; }
BOOKINGS="SELECT b.id AS booking, b.trip_id AS trip, IF(b.trip_date < UTC_DATE(), 'past', 'future') AS trip_is, b.status, b.cancel_reason, t.seats_taken FROM booking b JOIN trip t ON t.id = b.trip_id WHERE b.employee_id = 1 ORDER BY b.id"
SQL "$BOOKINGS"
ingest '{"events":[{"eventId":"demo-exit","employeeExternalId":"EMP-1001","source":"HR","eventType":"DEACTIVATE","version":6,"payload":{}}]}'
SQL "$BOOKINGS"
sleep 2
SQL "SELECT event_type, aggregate_type, aggregate_id, status FROM outbox WHERE event_type IN ('EMPLOYEE_DEACTIVATED', 'BOOKING_CANCELLED') ORDER BY id"
```

```text
+---------+------+---------+-----------+---------------------+-------------+
| booking | trip | trip_is | status    | cancel_reason       | seats_taken |
+---------+------+---------+-----------+---------------------+-------------+
|       1 |    1 | past    | BOOKED    | NULL                |           2 |
|       3 |    2 | future  | BOOKED    | NULL                |           3 |
|       6 |    3 | future  | BOOKED    | NULL                |           1 |
|       8 |    5 | future  | CANCELLED | DEMO_USER_CANCELLED |           0 |
+---------+------+---------+-----------+---------------------+-------------+
{"batchId":"c4012180-c64e-4dfe-9409-143c20251af5","received":1,"applied":1,"ignoredStale":0,"duplicates":0,"ignoredNoOwnedFields":0,"rejected":0,"notProcessed":0,"results":[{"index":0,"eventId":"demo-exit","employeeExternalId":"EMP-1001","outcome":"APPLIED","errors":[],"warnings":[]}]}
HTTP 207
+---------+------+---------+-----------+---------------------+-------------+
| booking | trip | trip_is | status    | cancel_reason       | seats_taken |
+---------+------+---------+-----------+---------------------+-------------+
|       1 |    1 | past    | BOOKED    | NULL                |           2 |
|       3 |    2 | future  | CANCELLED | EMPLOYEE_EXIT       |           2 |
|       6 |    3 | future  | CANCELLED | EMPLOYEE_EXIT       |           0 |
|       8 |    5 | future  | CANCELLED | DEMO_USER_CANCELLED |           0 |
+---------+------+---------+-----------+---------------------+-------------+
+----------------------+----------------+--------------+-----------+
| event_type           | aggregate_type | aggregate_id | status    |
+----------------------+----------------+--------------+-----------+
| EMPLOYEE_DEACTIVATED | EMPLOYEE       | EMP-1001     | PUBLISHED |
| BOOKING_CANCELLED    | BOOKING        | 3            | PUBLISHED |
| BOOKING_CANCELLED    | BOOKING        | 6            | PUBLISHED |
+----------------------+----------------+--------------+-----------+
```

**7. A cut-off export trips the safety brake.** Example Use Case 2 at small scale: this export names only 2 of the 7 active employees, as a file cut short would. The run stops at `AWAITING_APPROVAL`, nobody is deactivated, and `missingCount` tells the operator how many records the file appears to be missing. A second upload is refused while this one is open. `unchangedCount` is 4 for a 2-row file because it also counts the two inactive employees the file rightly leaves out, EMP-1001 and EMP-1006.

```bash
cat > roster-export-cut-off.csv <<'EOF'
externalId,name,email,phone,managerExternalId,officeCode,shiftCode
EMP-1003,Demo Employee Three,emp1003@acme.example,+919000001003,EMP-1005,BLR-ECO,S2
EMP-1005,Demo Manager Five,emp1005@acme.example,+919000001005,,BLR-WHF,S1
EOF
upload() { curl -s -w '\nHTTP %{http_code}\n' -X POST $B/api/v1/reconciliation/runs -H "Authorization: Bearer $OPERATOR" -F "file=@roster-export-cut-off.csv;type=text/csv" -F "generatedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; }
upload
upload
curl -s $B/api/v1/employees/EMP-1002 -H "Authorization: Bearer $OPERATOR"; echo
```

```text
{"id":1,"status":"AWAITING_APPROVAL","exportGeneratedAt":"2026-10-31T05:11:32Z","startedAt":"2026-10-31T05:11:32.798Z","finishedAt":null,"exportRowCount":2,"activeCountBefore":7,"missingCount":5,"missingPercent":71.42,"thresholdPercent":30.00,"createdCount":0,"updatedCount":0,"reactivatedCount":0,"deactivatedCount":0,"skippedCount":0,"unchangedCount":4,"malformedRowCount":0,"unknownOfficeCount":0,"abortReason":null,"approvedBy":null,"approvedAt":null}
HTTP 201
{"type":"about:blank","title":"Conflict","status":409,"detail":"The request conflicts with the current state of the resource.","instance":"/api/v1/reconciliation/runs","code":"CONFLICT"}
HTTP 409
{"externalId":"EMP-1002","name":"Demo Employee Two","managerExternalId":"EMP-1005","officeCode":"BLR-WHF","shiftCode":"S2","status":"ACTIVE"}
```

**8. The operator rejects the run.** Reject discards the parked deactivations and the run becomes `ABORTED`. Everyone the file left out is still active. To accept a run instead, the same URL ends in `/approve`, which deactivates them with the side effects shown in step 6.

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST $B/api/v1/reconciliation/runs/1/reject -H "Authorization: Bearer $OPERATOR"
curl -s $B/api/v1/employees/EMP-1002 -H "Authorization: Bearer $OPERATOR"; echo
curl -s $B/actuator/prometheus -H "Authorization: Bearer $OPERATOR" | grep -E '^roster_reconciliation_(safety_brake|missing)'
rm -f roster-export-cut-off.csv
```

```text
{"id":1,"status":"ABORTED","exportGeneratedAt":"2026-10-31T05:11:32Z","startedAt":"2026-10-31T05:11:32.798Z","finishedAt":"2026-10-31T05:11:33.769655933Z","exportRowCount":2,"activeCountBefore":7,"missingCount":5,"missingPercent":71.42,"thresholdPercent":30.00,"createdCount":0,"updatedCount":0,"reactivatedCount":0,"deactivatedCount":0,"skippedCount":0,"unchangedCount":4,"malformedRowCount":0,"unknownOfficeCount":0,"abortReason":"REJECTED_BY_OPERATOR","approvedBy":null,"approvedAt":null}
HTTP 200
{"externalId":"EMP-1002","name":"Demo Employee Two","managerExternalId":"EMP-1005","officeCode":"BLR-WHF","shiftCode":"S2","status":"ACTIVE"}
roster_reconciliation_missing_percent{application="roster-sync"} 71.42
roster_reconciliation_safety_brake_trips_total{application="roster-sync"} 1.0
```

## The guarantee table

Test classes are under `src/test/java/com/moveinsync/roster/`.

| Guarantee | Where it lives | How it is enforced | The test that proves it |
|---|---|---|---|
| Every record is validated on its own | `RecordValidator` | Returns all of a record's errors as closed codes and field names, never values | `RecordValidatorTest.reportsEveryProblemInOneRecord` |
| One bad record does not fail a batch | `IngestService`, `EventApplier` | One transaction per record, in a separate bean, and always a 207 | `IngestIT.oneBadRecordInABatchOf100Gives207With99AppliedAnd1Rejected` |
| Idempotency key | `EventApplier`, `roster_event` | Inserting the ledger row claims the key, under a unique constraint on tenant and `eventId` | `IngestIT.twoRequestsWithTheSameEventIdApplyExactlyOnce`, `IngestIT.replayingTheEntireFeedTwiceChangesNothing` |
| Version ordering | `VersionResolver` | Compares against the sender's own counter, under a row lock | `IngestIT.exampleUseCase1OfficeTransferV7ArrivesBeforeShiftChangeV6`, `IngestIT.manyThreadsIngestingOneNewEmployeeEndAtTheHighestVersion` |
| Field ownership | `FieldOwnership` | Drops fields the sender does not own before the write, with a warning | `IngestIT.hrEventCarryingAPickupPointLeavesItUnchangedAndWarns` |
| Outbox atomicity | `OutboxWriter`, `EventApplier` | `Propagation.MANDATORY`, so an outbox row cannot be written outside the change's transaction | `IngestAtomicityIT.aFailureAfterUpdatingAnEmployeeRollsBackTheChangeTheLedgerAndTheOutbox` |
| At-least-once delivery | `OutboxRelay`, `OutboxPublisher` | A row is settled only after the broker acknowledges it and did not return it | `OutboxRelayIT.crashBetweenTheDatabaseWriteAndTheEventPublishLosesNothingAndPublishesExactlyOnce`, `OutboxRelayIT.brokerDownGrowsTheBacklogBacksOffLosesNothingAndDrainsWhenItReturns` |
| Deactivation side effects | `EmployeeDeactivationService` | The only deactivation path: cancels future bookings, frees seats, writes events, all in one transaction | `EmployeeDeactivationIT.aDeactivateEventThroughTheApiProducesTheFullSideEffects`, `EmployeeDeactivationIT.twoThreadsDeactivatingTheSameEmployeeFreeEachSeatOnce` |
| Bounded-memory diff | `ExportPartitioner`, `BucketDiffer` | Streams to bucket files on disk, then holds one bucket and one page at a time | `ReconciliationBenchmarkIT.peakHeapDoesNotGrowMateriallyBetween20000And200000Rows` |
| The safety brake | `SafetyBrake`, `ReconciliationService` | Decided after the diff and before any write, and deactivations stay parked when it trips | `ReconciliationIT.exampleUseCase2AFileCutOffAt60PercentTripsTheBrakeAndDeactivatesNobody` |
| The brake's threshold is configurable | `ReconciliationProperties` | `roster.reconciliation.missing-threshold-percent`, default 30 | `ReconciliationThresholdIT.theSameCutOffExportPassesOnceTheThresholdIsRaisedTo50` |
| Tenant isolation | `JwtAuthFilter`, `TenantContext` | The tenant comes from the signed token only, and employee and run lookups filter on it | `EmployeeAccessIT.acmeTokenRequestingGlobexEmployeeIs404Not403`, `EmployeeAccessIT.aClientSuppliedTenantIdIsIgnored` |
| RBAC | `SecurityConfig`, `@PreAuthorize` | Role checks on each controller method, and `/actuator/**` is operator-only except health and info | `EmployeeAccessIT.employeeCallingAnotherEmployeeIs403`, `AuthenticationIT.onlyAnOperatorCanReadMetricsBecauseTheyCombineEveryTenant` |
| No personal data in logs or events | `ValidationError`, `OutboxWriter`, `toString` overrides | Errors have no value component, payloads carry ids only, and `toString` prints ids only | `IngestIT.personalDataIsNotLoggedEvenAtDebug`, `IngestIT.theOutboxPayloadCarriesIdsOnlyAndItsOwnRowId` |

## Architecture

An HR system sends event batches to the ingest API, where each record gets its own transaction:
validate, claim the idempotency key, lock the employee, compare versions, filter by field
ownership, write the change and write an outbox row. A relay publishes those rows to RabbitMQ
outside any transaction, and consumers deduplicate by message id. The nightly export takes a
second path: an operator uploads it, it is streamed to disk and diffed one bucket at a time, and a
safety brake decides whether its deactivations may run. Both paths deactivate through one method.

Diagrams and the failure-mode table are in [docs/DESIGN.md](docs/DESIGN.md). The stack is Java 21, Spring Boot 3.5.6, MySQL 8.4, RabbitMQ 3.13, Flyway, Caffeine and Micrometer.

- **Encapsulation:** `SpillWorkspace` keeps its temp directory, file layout and line format private. Callers only append to it, read from it and close it.
- **Inheritance:** `BaseEntity` gives all ten entities, such as `Employee` and `Booking`, their id and their proxy-safe `equals` and `hashCode`.
- **Polymorphism:** only at framework seams. `JwtAuthFilter` and `RequestTraceFilter` both override `OncePerRequestFilter.doFilterInternal` and are called through the servlet `Filter` interface. The project defines no interface of its own with several implementations. Its domain rules use closed enums and exhaustive switches, such as `FieldOwnership.ownerOf`, so a new field does not compile without an owner.

### Reconciliation API

```bash
curl -X POST http://localhost:8081/api/v1/reconciliation/runs -H "Authorization: Bearer $TOKEN" \
  -F file=@export.csv -F generatedAt=2026-10-30T00:00:00Z

curl http://localhost:8081/api/v1/reconciliation/runs/1 -H "Authorization: Bearer $TOKEN"
curl -X POST http://localhost:8081/api/v1/reconciliation/runs/1/approve -H "Authorization: Bearer $TOKEN"
curl -X POST http://localhost:8081/api/v1/reconciliation/runs/1/reject -H "Authorization: Bearer $TOKEN"
```

| In the export | In the store | Result |
|---|---|---|
| yes | absent | create |
| yes | active, same hash | no-op |
| yes | active, different hash | update |
| yes | inactive | reactivate (a rehire) |
| no | active | deactivation candidate |
| no | inactive | no-op |

### Monitoring

Metrics are at `/actuator/prometheus`, for an operator token only, because they combine every
tenant's numbers. The names below are Micrometer's. Prometheus shows them with underscores, and
with `_total` or `_seconds` on counters and timers.

| Metric | Type | What it tells someone at 3 AM |
|---|---|---|
| `roster.outbox.pending` | gauge | Queue depth. Rising means the relay is losing |
| `roster.outbox.lag.seconds` | gauge | Age of the oldest pending row. The real SLA signal, because a small queue that is not draining is worse than a large one that is |
| `roster.outbox.published`, `.failed` | counter | Error rate. Anything in `failed` is parked and needs a human |
| `roster.outbox.retries` | counter | The broker is unhappy |
| `roster.ingest.events` | counter, tag `outcome` | Applied, stale, duplicate, rejected, not processed |
| `roster.ingest.rejections` | counter, tag `code` | Rejections by reason |
| `roster.ingest.batch` | timer | p50 and p99 per batch |
| `roster.reconciliation.duration` | timer | Against the 60-second budget |
| `roster.reconciliation.missing.percent` | gauge | How close the last finished run came to the brake |
| `roster.reconciliation.safety_brake.trips` | counter | Page someone |
| `roster.deactivations` | counter, tag `trigger` | Live exits against reconciliation |

The gauges read the database on every scrape, so a restart cannot reset a backlog to zero. Metrics
are tagged only with closed enums, because an employee id, username, run id or batch id is
unbounded and every distinct value would become a new time series.

Log lines carry `[traceId] [tenantId] [runId]`. The trace id comes from a request's `X-Request-Id`
header when that is a safe value, is generated otherwise, and is returned in the response. Logs,
metrics and error responses never contain a name, email, phone or location.

### Caching

**What is cached:** office lookups, keyed by tenant and office code, because they are read on every
ingested record and change perhaps monthly. Caffeine, 10-minute TTL, 10,000 entries
(`roster.cache.*`). Writing an office evicts its entry.

**What is deliberately not cached:** employee records. They are write-heavy and correctness-critical,
and a stale employee read is exactly the bug this project exists to prevent. Reconciliation reads
offices directly too, so it is never stale.

**What can become stale, for how long, and how that is bounded:** an office created or deactivated by
another instance can be stale here for at most 10 minutes. The instance that wrote it evicts its
own entry immediately.

- A **stale miss**, for a newly created office, gives a per-record `UNKNOWN_OFFICE` rejection. The
  client sees it and can retry with the same `eventId`, because a rejected record does not claim its
  key. It is never a silent bad write.
- A **stale hit**, for an office just deactivated, means an event can still be accepted for that
  office for up to the same 10 minutes. The office is real and belongs to the tenant, so the record
  stays valid, and the next export reconciles any correction HR makes.

## Data model

**Two version counters, `hr_version` and `transport_version`.** HR and transport number their events
independently. With one counter, a transport v3 would look stale against an HR v9 and be silently
dropped. Each event is compared only against its sender's counter.

**`content_hash` covers HR-owned fields only.** It is a SHA-256 of name, email, phone, manager, office
code and shift code. The export comes from HR and knows nothing about pickup points, so including
them would make every employee with a pickup point look changed every night.

**`bucket` is computed by the application, not the database.** Java must produce the identical value
while streaming the export file, before any row exists to read a generated column from. It is
`floorMod(externalId.hashCode(), 256)`, and `String.hashCode` is specified by the language, so the
file and the column always agree. An index on tenant, bucket and status serves the diff.

**`hr_updated_at` holds the export's timestamp after a repair, never the current time.** That keeps the
rule "a live event newer than the snapshot wins" correct across repeated runs.

## Benchmarks

```
CPU     11th Gen Intel(R) Core(TM) i5-11400H @ 2.70GHz, 6 cores, 12 logical
RAM     15.7 GB (Docker VM: 12 CPUs, 7.6 GiB)
OS      Microsoft Windows 11 Home Single Language, build 26300
Docker  29.9.2, Docker Desktop, WSL2 kernel 6.18.54.1
JDK     OpenJDK 21.0.12.1 (Temurin-21.0.12.1+1-LTS)
Other   Maven 3.9.16, MySQL 8.4.13, RabbitMQ 3.13.7
```

Each figure is a single run on this laptop, not an average, so read them as an order of magnitude.

### Reconciliation at 200,000 rows

Run with `-Xmx512m`, 256 buckets, against MySQL through the Docker Desktop port proxy. The normal
profile is 1% changed, 0.5% new and 0.5% gone.

| Scenario | Total | Partition | Diff | Apply |
|---|---|---|---|---|
| Normal night (1,000 created, 1,990 updated, 1,000 deactivated) | **35.8 s** | 0.7 s | 6.8 s | 27.9 s |
| Cut-off file, 120,000 of 200,000 rows (brake trips) | **10.2 s** | 0.4 s | 9.0 s | 0.8 s |
| Worst case, every row changed (199,000 updated, 1,000 created, 1,000 deactivated) | **142.2 s** | 0.9 s | 5.9 s | 135.3 s |

The cut-off run reported `status=AWAITING_APPROVAL`, `missingCount=80000`, `missingPercent=40.00` and
`deactivatedCount=0`.

### Memory

| Export size | Growth in the live set during the run |
|---|---|
| 20,000 rows | 1,010 KB |
| 200,000 rows | 4,368 KB |

**The pair of numbers, not either one alone, is what proves memory is bounded.** One figure could
simply mean the heap was large enough. Ten times the rows adding 3.4 MB to the live set is what
shows the diff does not hold the file in memory. The live set was measured with a forced GC every
250 ms, and the test fails if the growth reaches 32 MB. The raw heap peak on the 200,000-row run
was 158 MB of a 512 MB maximum, most of it collectable garbage.

**Complexity of the diff.** With N export rows, M stored rows and B buckets, time is **O(N + M)**:
each side is read once, and each lookup is an average O(1) in that bucket's hash map. Space is
**O(N / B)**: only one bucket of the export is resident, about 780 rows at N = 200,000, plus one page of
the store. B is `roster.reconciliation.bucket-count`, default 256. Raising it lowers memory but raises
the number of store queries, at least one per bucket, and partitioning holds one write buffer per
bucket. B cannot simply be changed on a populated database: the `bucket` column is written with it and
nothing recomputes existing rows, so that column would have to be rebuilt first. The apply phase is
extra, at one write per changed row.

### Ingest

Measured from a Python client on the same laptop, through the Docker Desktop port proxy, against
the running `docker compose` stack, with 4 concurrent connections. Every event creates a new employee, so
each is a real write with its ledger and outbox rows. The first 10% of requests were a warm-up and are
not counted. Latency is per HTTP request, so a batch's figure covers all of its records.

| Request size | Run | Events per second | p50 | p99 | Max |
|---|---|---|---|---|---|
| 100 records (40,000 events) | 1 | **314** | 1,256 ms | 1,506 ms | 1,536 ms |
| 100 records (40,000 events) | 2 | **294** | 1,343 ms | 1,681 ms | 1,831 ms |
| 100 records (40,000 events) | 3 | **278** | 1,427 ms | 1,937 ms | 2,193 ms |
| 1 record (4,000 events) | six runs, range | **115 to 125** | 23 to 25 ms | 107 to 113 ms | 134 to 186 ms |

Every event in these runs was applied. One earlier 100-record run, before the retry wait described under
[Where it stops scaling](#where-it-stops-scaling-and-why), fell to 123 events per second with a p99 of
8,919 ms and no errors. I did not find out why and it did not recur in five other runs, so treat it as
what this laptop can do on a bad run.

## Where it stops scaling, and why

- **Reconciliation's apply phase is what grows, not the diff.** The diff takes 6 to 9 seconds on
  200,000 rows however much changed, because it compares hashes. Apply is real writes with real
  events: 27.9 s at 1% changed, 135.3 s when everything changed. That worst case of 142 s misses
  the 60-second budget. Such a night is unusual, and it costs time rather than correctness.
- **The relay has a ceiling.** It publishes at most `batch-size` rows per `poll-interval-ms`, which is
  400 rows per second at the defaults, less each cycle's own time. That ceiling is calculated, not
  measured at saturation. During the ingest load the relay kept pace at 314 events per second, with at
  most 20 rows pending when the load ended. At the ceiling, a worst-case night's 200,000 events would
  take at least 8 minutes to publish. Relays can run side by side safely (`SKIP LOCKED`), until MySQL
  write throughput becomes the limit.
- **Concurrent new employees can still deadlock.** Inserting adjacent external ids takes gap locks on
  the unique index under REPEATABLE READ. A deadlock is retried up to 5 times with a random wait of 10
  to 80 ms between attempts, because an immediate retry re-creates the collision. Six runs of 4,000
  single-record creates at 4 connections lost 10 of 24,000 records with immediate retries and **0 of
  24,000 with the wait**, which raised p99 from 63-98 ms to 107-113 ms. The zero is what this load
  measured, not a guarantee: the deadlock itself remains. A record that loses every retry is returned as
  `PROCESSING_FAILED`, nothing is half-applied and its key stays unclaimed, so resending with the same
  `eventId` works.
- **One reconciliation runner.** Each tenant has one open run, on the instance that received the
  upload, doing one bucket at a time. Nothing parallelises a run or moves it if that instance dies.

## Trade-offs

| Decision | Favours | What was given up |
|---|---|---|
| **At-least-once delivery** over exactly-once | Consistency and availability: no event is lost, and the API never waits on the broker | A crash after publishing sends the same id again, so every consumer must deduplicate |
| **Per-record transactions** over one batch transaction | Availability: one bad record fails alone, and locks are held briefly | Latency, with one commit per record, and a batch can partly apply. Callers read per-record results |
| **Brake holds only deactivations** rather than aborting the run | Availability of correct data: rows that arrived are valid HR data | Whole-file consistency: a broken file's rows are applied before a human looks at it |
| **Brake threshold of 30%** | Availability: ordinary exits and small files flow through without waking anyone | Consistency below the line: a file cut by less than 30% looks like real exits and does deactivate. The next full export reactivates those people, but not their cancelled bookings |
| **In-process cache** over Redis | Latency and availability: no network hop, and no extra system that can fail | Consistency across instances: an office can be stale for up to 10 minutes |
| **RabbitMQ** over Kafka | Simplicity and low latency per message. Ordering is carried by versions, so the broker need not provide it | Replay from history and partitioned throughput. A consumer that loses state cannot rewind |
| **Reconciliation does not advance `hr_version`** | Availability of the live stream: the next legitimate live event still applies | Consistency in one corner: a delayed live event with a version above the stored one, but older than the export, can overwrite a repair until the next export corrects it |

## What I deliberately left out

- **Frontend, real HR-system connectors:** this is the backend, and a demo drives it with curl.
- **Refresh tokens and rotation:** clients log in again after an hour. Disabling a user takes effect
  at login, and a token already issued stays valid until it expires.
- **Kafka and Redis:** see trade-offs.
- **Leader election for multi-instance reconciliation:** a unique index allows one open run per tenant.
- **A dead-letter consumer:** `FAILED` outbox rows are parked for a human, who requeues them.
- **Database sharding and read replicas.**
- **A distributed tracing backend:** the MDC trace id is what exists.
- **Soft delete and full audit history:** the `roster_event` ledger is the audit trail.
- **Background approval:** approving a run is synchronous. At about 28 ms per deactivation, 80,000
  employees would take **about 37 minutes inside one HTTP request**. That figure is derived from the
  measured per-deactivation cost, not measured end to end. A client that times out does not stop
  the work. Production would run approval as a background job with a status endpoint.
- **Per-tenant time zones:** see assumptions.
- **A dedicated scraper account:** Prometheus would have to log in as an operator and renew a one-hour
  token. Production would expose actuator on an internal management port instead.

## Assumptions

- **"Future" means by the UTC date.** A booking is future when its `trip_date` is today or later in
  UTC. `trip_date` has no time component, so a trip that departed earlier today still counts as
  future and is cancelled when its passenger leaves.
- **External ids are case-sensitive.** `EMP-1` and `emp-1` are different employees.
- **Usernames are unique per tenant,** so login takes a tenant code.
- **The export is CSV** with a header and exactly these columns in this order:
  `externalId,name,email,phone,managerExternalId,officeCode,shiftCode`. An empty cell is a null. `name`,
  `email` and `officeCode` are required. Cells may be quoted, and a line break inside a cell is not
  supported. A bad row fails only itself. A wrong header rejects the file with a 400, before a run
  opens.
- **One reconciliation run per tenant at a time.** A second upload gets a 409 until the first
  finishes or is resolved.

## Running the tests

No local Maven or JDK is needed. The named volume keeps the dependency cache between runs.

`mvn test` runs the fast tests (`*Test`), with no database and no Docker access:

```bash
# Linux or macOS
docker run --rm -v "$PWD":/app -w /app -v roster-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test
```

```powershell
# PowerShell
docker run --rm -v "${PWD}:/app" -w /app -v roster-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test
```

```bash
# Git Bash: stop it rewriting /app into a Windows path
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)":/app -w /app -v roster-m2:/root/.m2 maven:3.9-eclipse-temurin-21 mvn -B test
```

`mvn verify` also runs the integration tests (`*IT`) against a real MySQL 8.4 and RabbitMQ started by
Testcontainers. The Maven container then needs the Docker socket, which gives it control of the
Docker daemon, so use this only with this trusted image and project:

```powershell
# PowerShell, on Docker Desktop
docker run --rm -v "${PWD}:/app" -w /app -v roster-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-21 mvn -B verify
```

```bash
# Git Bash, on Docker Desktop
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)":/app -w /app -v roster-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-21 mvn -B verify
```

The benchmark is excluded from a normal run because it takes about six minutes. In PowerShell:

```powershell
docker run --rm -v "${PWD}:/app" -w /app -v roster-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-21 mvn -B verify '-Dtest=NoSuchTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=ReconciliationBenchmarkIT' '-Dtest.excluded.groups=' '-DargLine=-Xmx512m'
```

The security tests were themselves verified by deliberately breaking each rule and confirming a test caught it.
