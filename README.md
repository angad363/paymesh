# PayMesh

PayMesh is a learning-focused Payment-as-a-Service backend platform.

The project simulates how modern payment systems handle:

- merchant onboarding
- customers and orders
- payment processing
- payment providers
- refunds
- double-entry ledger accounting
- merchant balances
- settlements
- webhooks
- fraud and risk evaluation
- reporting and operational monitoring

## Important disclaimer

PayMesh is an educational and portfolio project.

It does not process real money, store real card details, or claim compliance
with banking or payment-industry regulations.

## Project goals

The purpose of PayMesh is to learn and demonstrate:

- Java and Spring Boot
- REST API design
- PostgreSQL database design
- authentication and authorization
- clean architecture
- modular monoliths and microservices
- event-driven systems
- Kafka and Redis
- distributed system reliability
- testing and observability
- Docker, Kubernetes, and Terraform

## Repository structure

```text
paymesh/
├── backend/          Spring Boot backend application
├── docs/             Architecture, decisions, and learning notes
├── infrastructure/   Docker, Kubernetes, and Terraform configuration
└── scripts/          Development helper scripts
```

## Running it locally

The application ships **no secrets in `application.yaml`**. That file leaves
`paymesh.security.jwt.secret` and the datasource credentials empty on purpose, so a
deployment that forgets to supply them fails at startup instead of silently signing
every access token with a key published in this repository.

The local values live in `backend/src/main/resources/application-dev.yaml` and are
loaded only when the `dev` profile is active. Each supported way of starting the
application activates it differently:

| How you start it | What activates `dev` |
|---|---|
| `cd backend && ./mvnw spring-boot:run` | The `<profiles>` block on `spring-boot-maven-plugin` in `backend/pom.xml` |
| IntelliJ run button | The shared run configuration `BackendApplication [dev]` in `.run/` |
| `./mvnw test` | `@ActiveProfiles("dev")` on the test classes that boot a context |
| `java -jar` | **Nothing** — you supply the values yourself, see below |

If you see this at startup:

```text
Property: paymesh.security.jwt.secret
Reason: must not be blank
```

the `dev` profile is not active. That is the guard working, not a misconfiguration.
Start the application one of the first three ways, or supply the values yourself.

### Running the packaged jar

Nothing bakes a profile into the jar, so a real deployment must provide three values:

```bash
PAYMESH_SECURITY_JWT_SECRET=<32+ random bytes> \
SPRING_DATASOURCE_USERNAME=<user> \
SPRING_DATASOURCE_PASSWORD=<password> \
java -jar backend/target/backend-0.0.1-SNAPSHOT.jar
```

Do **not** copy the secret out of `application-dev.yaml`. It is public, and the
application refuses to start on it whenever `dev` is not the only active profile —
including `dev,production`, which is how layered configuration usually goes wrong.

### Verifying a change

```bash
cd backend
./mvnw test                     # needs Docker; uses Testcontainers, never a local database

# the API contract end to end, against a running application
npx newman run docs/api/postman/paymesh.postman_collection.json \
  --env-var baseUrl=http://localhost:8080