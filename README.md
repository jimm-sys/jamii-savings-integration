# Jamii Savings - Integration Assignment

Three integrations on **WSO2 Micro Integrator 4.6.0**, published through **WSO2 API Manager 4.6.0**, delivered by one **Jenkins pipeline**.

    Client -> APIM Gateway (8280/8243) -> Micro Integrator (8290) -> backend (PostgreSQL | REST | SOAP)

## The three APIs

| API | Backend | Security | Tier |
|---|---|---|---|
| `GET /accounts/{accountNumber}/balance` | PostgreSQL (`db/schema.sql`, `db/seed.sql`), parameterized query | OAuth2 | Gold |
| `GET /customers/{customerId}` | JSONPlaceholder `https://jsonplaceholder.typicode.com/users/{id}` | API Key | Silver |
| `POST /loans/eligibility` | dneonline Calculator SOAP service | API Key | Bronze |

- **JSONPlaceholder** was chosen because it is free, needs no API key, is stable and returns realistic user records.
- **SOAP mapping:** the calculator stands in for a credit-scoring service. `Divide(requestedAmount, monthlyIncome)` returns a whole-number loan-to-income multiple. Eligible when the multiple is below 12, which is exactly "requested amount < 12 x monthly income" because the service rounds down. A monthly income of 0 makes the service return a SOAP Fault, used to demonstrate fault mapping. The raw fault (which contains a stack trace) is logged trimmed and never sent to the client.
- **Security:** balances are the most sensitive data, so Accounts uses OAuth2 (short-lived tokens). The other two use API Key, which is simpler for partners.
- **Tiers:** Gold for Accounts (cheap, high-volume database read). Bronze for Loans, the strictest, because it calls a fragile third-party SOAP service.
- **Gateway policy:** the built-in *Add Header* policy adds `X-Served-By` to Customers responses at the gateway (MI never sees it).
- **API Product:** `JamiiBankingProduct` (`/banking`) bundles all three so a partner needs one subscription, one key and one plan instead of three.
- **Developer Portal:** open `https://localhost:9443/devportal`, find the API, create an Application, subscribe it to the APIs (or the Product), then under Production Keys generate an OAuth2 token and/or an API Key.

## Common behaviour
- One error shape on all APIs: `{"error":{"code","message","status"},"requestId","timestamp"}`.
- Bad input returns **400**, never 500. Unknown resource **404**. Database failure **503 DATABASE_ERROR**. Backend timeout **504**. Backend down **503**. SOAP fault or bad backend reply **502**.
- Logs show request in, backend call, response out, with account numbers and customer IDs masked (last digits only). Income and amounts are never logged.
- `X-Correlation-ID` is kept if valid, otherwise generated, and returned. Backend and request headers are removed from responses (allow-list).

## Architecture decisions and trade-offs
- **DB Lookup mediator** instead of a data service: one artifact, full control of the 404 and error shape. Trade-off: single-row lookups only.
- **Shared sequences** (`JamiiErrorSeq`, `JamiiStripHeadersSeq`) keep the error shape and headers consistent.
- **Timeout 5 s plus a 5 s circuit breaker** on both backends. Trade-off: one slow call briefly rejects other callers. `synapse.timeout_handler_interval` is set to 1 s, because the 15 s default made a 5 s timeout take up to 19 s.
- **CAR built by a small Java program** (`scripts/BuildCar.java`) instead of the WSO2 Maven plugins: cross-platform, no dependencies, tested on a real MI. Trade-off: not the official tooling.
- **apictl projects are exports of the running APIM**, so the repo holds exactly what was verified.
- **Pipeline (`Jenkinsfile`):** one pipeline loops over all three APIs. Stages: static tests, build the CAR, check the APIM projects, deploy DEV, then an optional approved PROD stage. Phase 1 of the APIM import uses `--skip-deployments` (nothing reaches the gateway); phase 2 deploys; any failure fails the build with the failed names. `dev` is wired up; `prod` needs `DEPLOY_PROD` plus a manual approval. Credentials come from Jenkins; `apictl logout` runs afterwards because apictl stores credentials in plain text.

## What I would do differently with more time
- Return our error envelope for gateway errors (401/429); they currently use WSO2's native format.
- A path that does not match any route gets MI's bare 404 instead of our JSON.
- Customers is a pass-through, so it returns the backend's full record; I would return only the needed fields.
- Use the official MI Maven plugins, a docker-compose environment, and CI tests against mock backends.
- Bonuses (aggregation API, message queue) were not attempted.

## How to run locally
Requirements: JDK 21, PostgreSQL, MI 4.6.0, APIM 4.6.0, apictl 4.6.0.

1. **Database:** create user `jamii` and database `jamii`, then `psql -U jamii -d jamii -f db/schema.sql` and `-f db/seed.sql`.
2. **MI config:** append `mi/conf/deployment-additions.toml` to `<MI_HOME>/conf/deployment.toml`, copy the PostgreSQL JDBC jar into `<MI_HOME>/lib`, and start MI.
3. **Test and build:**
   `java scripts/ValidateArtifacts.java mi/artifacts` then `java scripts/BuildCar.java mi/artifacts build`.
   Copy `build/*.car` to `<MI_HOME>/repository/deployment/server/carbonapps/` and run `java scripts/SmokeTest.java http://localhost:8290 --external`.
4. **APIM:** start it, then `apictl add env dev --apim https://localhost:9443`, `apictl login dev -u admin -p admin -k`, and import each folder in `apim/projects/` with `apictl import api -f <folder> -e dev --update -k`, then `apictl import api-product -f apim/products/JamiiBankingProduct-1.0.0 -e dev -k --update-api-product`.
5. **Call through the gateway:** get an API key and OAuth2 token from the Developer Portal, then
   `curl -H "Authorization: Bearer <token>" http://localhost:8280/accounts/1.0.0/1000000001/balance`
   `curl -H "ApiKey: <key>" http://localhost:8280/customers/1.0.0/1`
   `curl -X POST -H "ApiKey: <key>" -H "Content-Type: application/json" -d '{"applicantId":"A123","monthlyIncome":80000,"requestedAmount":500000}' http://localhost:8280/loans/1.0.0/eligibility`
