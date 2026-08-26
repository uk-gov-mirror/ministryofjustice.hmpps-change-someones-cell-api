# hmpps-change-someones-cell-api

[![Ministry of Justice Repository Compliance Badge](https://github-community.service.justice.gov.uk/repository-standards/api/hmpps-change-someones-cell-api/badge?style=flat)](https://github-community.service.justice.gov.uk/repository-standards/hmpps-change-someones-cell-api)
[![Docker Repository on ghcr](https://img.shields.io/badge/ghcr.io-repository-2496ED.svg?logo=docker)](https://ghcr.io/ministryofjustice/hmpps-change-someones-cell-api)
[![API docs](https://img.shields.io/badge/API_docs_-view-85EA2D.svg?logo=swagger)](https://change-someones-cell-api-dev.hmpps.service.justice.gov.uk/swagger-ui/index.html)

Cell movement API for HMPPS. Records a prisoner's move to a different cell, orchestrating the
NOMIS move, the `MOVED_CELL` case note and this service's own record of why the move happened.

It replaces the `/cell/*` endpoints of `whereabouts-api`, which is being decommissioned.
Whereabouts did not simply proxy a cell move: it also owned a `CELL_MOVE_REASON` table linking a
move to its case note, which existed nowhere else. The background, the options considered and the
phasing are recorded in
[`docs/cell-move-architecture-and-replatform.md`](https://github.com/ministryofjustice/hmpps-change-someones-cell/blob/main/docs/cell-move-architecture-and-replatform.md)
in the UI repo, under epic MAPA-275.

Consumers:

- [`hmpps-change-someones-cell`](https://github.com/ministryofjustice/hmpps-change-someones-cell) - the UI that performs cell and reception moves
- [`hmpps-prisoner-profile`](https://github.com/ministryofjustice/hmpps-prisoner-profile) - reads "what happened" on the location history page

## Roles

| Role | Purpose |
|---|---|
| `ROLE_CELL_MOVEMENTS__RO` | Read cell movements |
| `ROLE_CELL_MOVEMENTS__RW` | Record a cell movement |
| `ROLE_CELL_MOVEMENTS__SYNC__RW` | NOMIS sync and migration |

Every endpoint must carry `@PreAuthorize`; `ResourceSecurityTest` fails the build otherwise.
Note whereabouts required no role at all for a cell move, so this is a deliberate tightening.

## Authentication to downstream services

Calls to prison-api and offender-case-notes must carry the **end user's** username, not just this
service's client credentials: prison-api's cell move is `@ProxyUser` so NOMIS audit columns record
the real user, and offender-case-notes rejects `MOVED_CELL` without a NOMIS user.

`WebClientConfiguration` therefore overrides the autoconfigured `OAuth2AuthorizedClientManager`
with `usernameAwareTokenRequestOAuth2AuthorizedClientManager` from hmpps-kotlin-lib. That manager
reads the authentication when it is *constructed*, so it and the authenticated web clients are
request scoped - see the comments in that class before changing the scoping.
`UsernamePropagationTest` guards the behaviour.

## Database

Postgres, with Flyway migrations in `src/main/resources/db/migration`. `ddl-auto` is `none`, so the
schema only ever comes from migrations. The RDS instance lives in the
`hmpps-prisoner-cell-allocation-{dev,preprod,prod}` namespaces, which this service shares with the
UI rather than having its own.

Tests reuse a Postgres already listening on 5432 if there is one, and otherwise start a
testcontainer, so `docker compose up` first makes the test loop faster but is not required.

## Common Kotlin patterns

Many patterns have evolved for HMPPS Kotlin applications. Using these patterns provides consistency across our suite of
Kotlin microservices and allows you to concentrate on building your business needs rather than reinventing the
technical approach.

Documentation for these patterns can be found in the [HMPPS tech docs](https://tech-docs.hmpps.service.justice.gov.uk/common-kotlin-patterns/).
If this documentation is incorrect or needs improving please report to [#ask-prisons-digital-sre](https://moj.enterprise.slack.com/archives/C06MWP0UKDE)
or [raise a PR](https://github.com/ministryofjustice/hmpps-tech-docs).

## Running the application locally

The application comes with a `dev` spring profile that includes default settings for running locally. This is not
necessary when deploying to kubernetes as these values are included in the helm configuration templates -
e.g. `values-dev.yaml`.

There is also a `docker-compose.yml` that can be used to run a local instance of the template in docker and also an
instance of HMPPS Auth (required if your service calls out to other services using a token).

```bash
docker compose pull && docker compose up
```

will run the application and HMPPS Auth within a local docker instance.

### Running the application in Intellij

```bash
docker compose pull && docker compose up --scale hmpps-change-someones-cell-api=0
```

will just start a docker instance of HMPPS Auth. The application should then be started with a `dev` active profile
in Intellij.

### Building and running the docker image locally

The `Dockerfile` relies on the application being built first. Steps to build the docker image:
1. Build the jar files
```
./gradlew clean assemble
```
2. Copy the jar files to the base directory so that the docker build can find them
```
cp build/libs/*.jar .
```
3. Build the docker image with required arguments
```
docker build --build-arg GIT_REF=21345 --build-arg GIT_BRANCH=bob --build-arg BUILD_NUMBER=$(date '+%Y-%m-%d') .
```
4. Run the docker image, setting the auth url so that it starts up
```
docker run -e HMPPS_AUTH_URL="https://sign-in-dev.hmpps.service.justice.gov.uk/auth" <sha from step 3>
```

## One-off backfill of whereabouts CELL_MOVE_REASON (MAPA-304)

### The link sweep is complete

Every row of whereabouts-api's `CELL_MOVE_REASON` is in `cell_movement_nomis`. The sweep
reconciled against whereabouts' own `select count(*)` in all three environments - dev 1,460,
preprod 3,481,920, prod 3,516,520 - and the source table was re-counted afterwards and had not
moved, proving nothing appeared below the cursor mid-run. The `/link-sweep` endpoint and
whereabouts' `/cell/cell-move-reasons` export that fed it were both removed with MAPA-282.

Nothing further is needed from whereabouts, and nothing here calls it any more. The read-through
that fetched an unmigrated movement on first read went with MAPA-282 once hmpps-prisoner-profile
reached production, taking `WhereaboutsApiClient`, its web client and `WHEREABOUTS_API_URL` with it -
this service now has no whereabouts dependency at all. The enrichment pass below reads case notes
through the `case_note_legacy_id` the sweep already copied across.

### Enrichment

`/migration/cell-move-reasons/enrich` resolves each migrated row's case note - reason code,
explanation, timestamp - onto it, once. Operator-driven and chunked: each call does a bounded
amount of work and returns a cursor, and the loop below - with its `sleep` - is both the scheduler
and the rate limit on case-notes. Idempotent, so a timed-out call, a crash or a repeat costs
nothing; re-run from the last cursor, or from the start, and rows already enriched are skipped.

Sizing: one case-notes GET per unenriched row, sequentially. In prod that is ~3.5M calls at
roughly 25-50 req/s, so 20-40 hours. Agree it with the offender-case-notes team before running.

Prerequisites: a client-credentials token with `ROLE_CELL_MOVEMENTS__SYNC__RW`, and
`ROLE_VIEW_PRISONER_DATA` on this service's own client for the prison-api historic-booking
fallback.

```bash
API=https://change-someones-cell-api-dev.hmpps.service.justice.gov.uk
TOKEN=... # client credentials grant

CURSOR="lastBookingId=0&lastBedAssignmentSequence=0"
while :; do
  R=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" "$API/migration/cell-move-reasons/enrich?$CURSOR&batchSize=50")
  echo "$R"
  [ "$(jq -r .complete <<<"$R")" = true ] && break
  CURSOR="lastBookingId=$(jq -r .nextCursor.lastBookingId <<<"$R")&lastBedAssignmentSequence=$(jq -r .nextCursor.lastBedAssignmentSequence <<<"$R")"
  sleep 1
done

curl -s -H "Authorization: Bearer $TOKEN" "$API/migration/cell-move-reasons/status" | jq .
```

A long run needs its token refreshed - it expires well inside the wall clock above.

Record `sampleUnresolvedBookingIds` - bookings neither prisoner-search nor prison-api can resolve -
on the ticket: that is the "explicitly accounted for" list. These endpoints are deleted once
enrichment has run to completion.
