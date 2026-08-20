```text
                                                       █──▄────▄▄▄▄▄▄▄────▄───
                                                       █─▀▀▄─▄█████████▄─▄▀▀──
                                                       █─────██─▀███▀─██──────
                                                       █───▄─▀████▀████▀─▄────
                                                       █─▀█────██▀█▀██────█▀──
        ▄████▄   ▒█████   ███▄    █  ██ ██░██████ ▄▄▄  █
       ▒██▀ ▀█  ▒██▒  ██▒ ██ ▀█   █  ██ █░ ▓█   ▀▒████▄█
       ▒▓█    ▄ ▒██░  ██▒ ██  ▀█ █▒  ████░ ▒███  ▒██   █▄
       ▒▓▓▄ ▄██ ▒██   ██░ ██▒  ▐▌█▒  ██ █▄ ▒▓█  ▄░████████
       ▒ ▓███▀ ░░ ████▓▒  ██░   ▓█░  █▒ ██▄░▒████▒▓█  █▒
       ░ ░▒ ▒  ░░ ▒░▒░▒░ ░ ▒░   ▒ ▒  ▒▒ ▓▒░░ ▒░ ░▒▒   ▓▒█░
         ░  ▒     ░ ▒ ▒░ ░ ░░   ░ ▒  ░▒ ▒░ ░ ░  ░ ▒   ▒▒ ░
       ░        ░ ░ ░ ▒     ░   ░ ░ ░ ░░ ░    ░    ░   ▒
       ░ ░          ░ ░           ░ ░  ░      ░  ░     ░  ░
       ░
       ▄▄▄▄▄▄▄▄ ▄▄▄▄▄▄▄▄ ▄▄▄▄▄▄▄▄ ▄▄▄▄▄▄▄▄ ▄▄▄▄▄▄▄▄ ▄▄▄▄▄▄▄▄
      █ ▄▄▄ █ ▀▀ ▄▀ ▀▄▀ █ ▄▄▄ █ ▄▀ ▀▄▀ █ ▄▄▄ █ ▄▄▄ █ ▀▀ ▄▀ ▀▄
      █ ███ █ ▀ ▀▄█ ▄ ▀ █ ███ █ ▀▄█ ▄ ▀ █ ███ █ ███ █ ▀ ▀▄█ ▄
      █▄▄▄█ █ █▄▀ █ ▀█ █ █▄▄▄█ █▄▀ █ ▀█ █▄▄▄█ █▄▄▄█ █ █▄▀ █ ▀
      ▄▄▄▄▄▄█ ▀▄█▄▀ ▀ █▄█▄▄▄▄▄█ ▀▄█▄▀ ▀ █▄▄▄▄▄█▄▄▄▄▄█ ▀▄█▄▀ ▀

    █████████████████████████████████████████████████████████████████
    ██  ║                                                       ║  ██
    ██  ║              ░▒▓ SBM - UTIL ▓▒░                       ║  ██
    ██  ║                                                       ║  ██
    ██  ║    ┌─────────────────────────────────────────────┐    ║  ██
    ██  ║    │  > Transversal Integration Service         │    ║  ██
    ██  ║    │  > Java 21 / Spring Boot                   │    ║  ██
    ██  ║    │  > Email, Files, APIs, Connectors          │    ║  ██
    ██  ║    │  > Notion, Confluence, Jira                │    ║  ██
    ██  ║    │  > Deterministic Shared Infrastructure     │    ║  ██
    ██  ║    │  > Docker-Only Runtime                     │    ║  ██
    ██  ║    │  > STATUS: ACTIVE / IN DEVELOPMENT         │    ║  ██
    ██  ║    └─────────────────────────────────────────────┘    ║  ██
    ██  ║                                                       ║  ██
    ██  ║       ░▒▓ TRANSVERSAL SERVICES ONLINE ▓▒░            ║  ██
    ██  ║                                                       ║  ██
    ██  ╚═══════════════════════════════════════════════════════╝  ██
    ██                                                             ██
    █████████████████████████████████████████████████████████████████
```

# SBM-UTIL

## Role within SBM Suite

SBM-UTIL is the transversal infrastructure and integration service of SBM Suite.

It centralizes deterministic technical capabilities that must be reusable by multiple APIs, services and agents without duplicating provider-specific implementations.

SBM-UTIL does not own business logic, agent reasoning or domain authorization decisions.

Canonical Suite identity:

```text
registry_project_name: sbm-util
repository root: SBM-SUITE/SBM/SBM-UTIL/
runtime root: /suite/sbm/SBM-UTIL
```

## Project status

SBM-UTIL is an active Java/Spring Boot service.

The initial baseline provides:

- Spring Boot application runtime
- Docker-only build and execution
- Spring Boot Actuator health endpoints
- Maven containerized build
- REST service foundation

Global Context and Documentation lifecycle commands are executed from:

```text
SBM-SUITE/context
```

## Technology stack

- Java 21
- Spring Boot 4.1.0
- Spring Web
- Spring Validation
- Spring Boot Actuator
- Maven
- JUnit 5
- Docker
- Docker Compose

Java and Maven are intentionally not required on the host machine.

Build, tests and runtime execute through containers.

## Responsibilities

SBM-UTIL owns reusable deterministic technical capabilities such as:

```text
email
files
ZIP generation
external APIs
Notion
Confluence
Jira
technical transformations
exchange rates
external connectors
```

Future integrations should be grouped by capability and provider without introducing business-domain ownership.

## Non-responsibilities

SBM-UTIL must not own:

```text
business logic
franchise logic
platform business rules
agent reasoning
workflow orchestration
user-facing authorization decisions
domain persistence owned by another API
```

SBM-API, DP-API and other domain APIs remain responsible for deciding **what** operation is authorized and required.

SBM-UTIL is responsible for knowing **how** to execute the corresponding deterministic integration.

## Architecture

```text
                    SBM Suite
                        │
        ┌───────────────┴───────────────┐
        │                               │
     SBM-API                         DP-API
        │                               │
        │ business decision             │ business decision
        │ auth / permissions            │ auth / permissions
        │                               │
        └───────────────┬───────────────┘
                        │
                 service-to-service
                        │
                        ▼
                   SBM-UTIL
                 Spring Boot REST
                        │
        ┌───────────────┼────────────────┐
        │               │                │
      Notion          Email             Jira
        │               │                │
   Confluence         Files          External APIs
```

Simplified responsibility boundary:

```text
SBM-API / DP-API = decide WHAT must happen
SBM-UTIL         = implements HOW the external operation happens
```

## Security model

Every consuming API retains its own security boundary.

For example:

```text
Client
   │
   ▼
DP-API
   │
   ├─ authentication
   ├─ authorization
   ├─ franchise / tenant rules
   └─ business validation
   │
   ▼
SBM-UTIL
   │
   ├─ service authentication
   ├─ provider credentials
   ├─ request validation
   ├─ technical audit
   └─ provider integration
   │
   ▼
External provider
```

SBM-UTIL does not replace the authorization layer of SBM-API, DP-API or other consumers.

External provider credentials should remain isolated from consuming services whenever possible.

## Integration principles

All integrations should follow these principles:

- deterministic execution
- idempotency where applicable
- explicit timeouts
- controlled retries
- stable internal contracts
- provider isolation
- structured errors
- technical auditability
- no hidden business rules
- no secrets committed to Git

Provider-specific implementation must remain behind an internal SBM-UTIL abstraction.

Example:

```text
NotionController
      │
      ▼
NotionService
      │
      ▼
NotionClient
      │
      ▼
Notion API
```

Consumers must not depend on provider SDK details.

## Documentation synchronization

The initial Notion integration will support the canonical flow:

```text
Git / Markdown
      │
      ▼
Documentation lifecycle
      │
      ▼
SBM-UTIL
      │
      ▼
Notion API
      │
      ▼
Notion
```

Git/Markdown remains the source of truth.

Initial synchronization is one-way:

```text
Git → Notion
```

Bidirectional synchronization is outside the initial scope.

The integration must preserve:

- stable page identifiers
- Markdown-to-Notion mapping
- change detection
- idempotency
- synchronization traceability

## Project structure

Target structure:

```text
SBM-UTIL/
├── Dockerfile
├── compose.yaml
├── pom.xml
├── README.md
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── sbm/
│   │   │           └── util/
│   │   │               ├── SbmUtilApplication.java
│   │   │               ├── config/
│   │   │               ├── controller/
│   │   │               ├── integration/
│   │   │               ├── service/
│   │   │               └── security/
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/
│               └── sbm/
│                   └── util/
│                       └── SbmUtilApplicationTests.java
└── context/
```

Provider integrations should evolve below:

```text
integration/
├── notion/
├── confluence/
├── jira/
├── email/
├── files/
└── exchange/
```

Do not create unused provider modules in advance.

## Requirements

Host requirements:

- Docker
- Docker Compose

Not required on host:

- Java
- Maven

## Build

```bash
docker build -t sbm-util:dev .
```

## Run

```bash
docker compose up --build
```

Direct Docker execution:

```bash
docker run -d \
  --name sbm-util-dev \
  -p 8080:8080 \
  sbm-util:dev
```

## Health

```text
GET /actuator/health
```

Example:

```bash
curl http://localhost:8080/actuator/health
```

Expected healthy state:

```json
{
  "status": "UP"
}
```

## Testing

Tests execute inside the Maven container/build environment.

Host Java or Maven installations must not be required.

The baseline Spring context test is located at:

```text
src/test/java/com/sbm/util/SbmUtilApplicationTests.java
```

## Environment configuration

Secrets and provider configuration must be supplied through environment variables.

Examples may eventually include:

```text
NOTION_API_TOKEN
NOTION_ROOT_PAGE_ID
CONFLUENCE_BASE_URL
CONFLUENCE_API_TOKEN
JIRA_BASE_URL
JIRA_API_TOKEN
EMAIL_PROVIDER
```

Exact variables must be introduced only when their integrations are implemented.

Never commit environment files or secret values.

## Authentication and authorization

SBM-UTIL will expose internal service endpoints only.

Consumer authentication is service-to-service.

Domain authorization remains owned by the calling API.

Example:

```text
DP-API authorization
        │
        ▼
authorized technical request
        │
        ▼
SBM-UTIL service authentication
        │
        ▼
external provider
```

## Observability

SBM-UTIL should centralize technical integration observability:

- health
- provider latency
- failures
- retries
- integration audit
- correlation IDs
- provider availability

Spring Boot Actuator provides the initial health foundation.

## Failure handling

External integrations must fail explicitly.

Expected patterns include:

```text
timeout
retry with bounded backoff
idempotency
provider-specific error translation
correlation ID
structured logging
```

Retries must not be applied blindly to non-idempotent operations.

## Database ownership

SBM-UTIL does not currently own a relational database.

Persistence must only be introduced when a transversal capability has a real persistence requirement.

Do not duplicate data owned by SBM-API, DP-API, SBM-DB or another authoritative service.

## Context and Documentation

Project-level Context will live under:

```text
SBM-SUITE/SBM/SBM-UTIL/context/
```

Global Suite Context remains under:

```text
SBM-SUITE/context/
```

Context and Documentation lifecycle operations are executed only from:

```text
SBM-SUITE/context
```

## Security

- Never commit secrets, tokens or environment files.
- Keep provider credentials inside SBM-UTIL whenever feasible.
- Require service-to-service authentication for internal consumers.
- Validate all inbound requests.
- Apply explicit outbound timeouts.
- Do not expose provider errors or credentials directly to consumers.
- Do not bypass authorization performed by the owning business API.
- Do not introduce business authorization rules into SBM-UTIL.
- Audit relevant external operations.

## License

Private SBM Suite project unless a separate repository license states otherwise.

---

```text
Signed by SBM Suite
```

## Context lifecycle

Run Context and Documentation lifecycle commands only from:

```text
SBM-SUITE/context
```

The backend registry name is:

```text
sbm-util
```

Project implementation QA will remain local to this repository once its canonical QA tooling is incorporated.
