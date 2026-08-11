# ちゃんりば Research Operations

This runbook contains no credentials. The Android app never receives the
Supabase `service_role` key.

## Production project

- Supabase project: `othello`
- Project ref: `zgzllmaoyymoeiqtybck`
- Project URL: `https://zgzllmaoyymoeiqtybck.supabase.co`
- Plan assumption: Free / no credit card or paid add-on

Confirm the project ref in the Dashboard URL before every production change.

## Migration rollout

Apply migrations in filename order:

1. `202608110019_research_capture_validator.sql`
2. Verify capture trigger, validator RPC ACL, and `collection_enabled=false`.
3. `202608110020_research_aggregation_privacy.sql`
4. Verify private aggregate tables, privacy RPC, and service-only build ACL.
5. `202608110021_research_account_unlink.sql`
6. Verify service-only unlink RPC and `collection_enabled=false` again.
7. `202608110022_research_actions_batch.sql`
8. Verify the `research_batch` role is `NOLOGIN`, has no private-table access,
   and can execute only the batch wrapper functions.
9. `202608110023_research_batch_platform_acl.sql`
10. Verify Hosted Supabase's `public.rls_auto_enable()` event-trigger helper
    is not ambiently executable and the batch role still has exactly eight
    application-schema functions.

Migration 021 is required in production because it completes the account
deletion boundary introduced by PR 2E. Migration 022 moves only the delayed
research batch executor to GitHub Actions. Migration 023 closes an ambient ACL
present on Hosted Supabase but absent from the local stack. Do not edit or
re-run an already applied migration. Inspect the result after each file.

## Worker configuration

The trusted Cloudflare Worker is `othello-admin`. It processes account
deletion and operator HTTP requests; it does not run research validation or
aggregation. Non-secret vars are in `cloudflare-admin/wrangler.toml`. Set
these only in the Worker secret store:

- `SUPABASE_SERVICE_ROLE_KEY`
- `ADMIN_TOKEN`

The service-role key is used only by the Worker for service-only RPCs.
`ADMIN_TOKEN` protects operator HTTP endpoints. Never commit either value.

The Worker Cron runs every 10 minutes and attempts:

- pending account deletion processing;

Account deletion therefore does not wait for the lower-priority research
batch. Worker failure does not affect live matchmaking, rating, GameRecord,
or Edax.

## Research batch executor

Research validation and aggregation run in `.github/workflows/research-batch.yml`
at minutes 17 and 47, and can also be started with `workflow_dispatch`. The
workflow runs only from `shinp-dev/othello` `main`, has read-only GitHub token
permissions, a 30-minute timeout, and a single concurrency group.

Migration 022 creates `research_batch` as a non-login, non-inheriting role.
After migration verification, generate a strong random password outside Git,
enable login for that role in the trusted Supabase SQL operator surface, and
store only its transaction-pooler connection URL as the
`RESEARCH_BATCH_DATABASE_URL` secret in the GitHub `research-production`
environment. Never give the workflow the Supabase `service_role`, project
database owner password, JWT signing secret, or Cloudflare credentials.

The role has no direct table or sequence access and can execute only bounded
research claim/complete/append/checkpoint/publish/fail wrappers. The URL must
use the Supabase shared transaction pooler because GitHub-hosted runners are
IPv4-only. Restrict the GitHub environment to the `main` branch. Rotate the
role password by updating Postgres and the environment secret together.
The hosted contract verifies the role's privileges both before and after LOGIN
is enabled; inspect `rolcanlogin` separately when checking deployment state.

Each run validates at most 200 games and appends at most 500 games to one
immutable generation. Validation leases expire after five minutes. An
aggregation run checkpoints by expiring its lease without failing the
generation; the next run reclaims it and selects only games absent from
`generation_processed_games`. A terminated runner therefore leaves backlog
in Postgres rather than losing work. Deterministically invalid accepted source
data marks only the new generation FAILED; the old published generation stays
active.

At the default twice-hourly schedule, the nominal aggregation catch-up bounds
are one run for 100 games, two runs for 1,000, about 20 runs for 10,000, and
about 200 runs for 100,000. These are backlog-throughput estimates, not latency
guarantees; GitHub schedule runs can be delayed or dropped. A manual dispatch
is the recovery path. Public repositories receive standard hosted-runner time
without billable minutes, but scheduled workflows are disabled after 60 days
without repository activity and must then be re-enabled.

Validation is capped separately at 200 games per run: 100 games take one run,
1,000 take about five runs, 10,000 take about 50 runs, and 100,000 take about
500 runs (roughly 10.5 days at two successful runs per hour). New rows remain
`PENDING` until a later run; increasing the cap or schedule frequency is an
explicit operational change, not a correctness requirement.

Why Actions: Workers Free currently allows 10 ms CPU and 50 external
subrequests per invocation. Independent Reversi replay and a full-generation
loop scale with game count, so they are not a safe long-term Cron workload.
The Cloudflare account remains available for lightweight online/admin uses.

## Collection activation and emergency stop

Keep the active policy disabled until pre-collection smoke checks pass:

```sql
select collection_enabled
from research_private.policy_versions
where is_active;
```

Emergency stop:

```sql
update research_private.policy_versions
   set collection_enabled = false
 where is_active;
```

This stops new capture and privacy API responses. It does not delete accepted
research data or stop online matches, rating, GameRecord, or Edax.

After all activation checks pass, change only the active policy row:

```sql
update research_private.policy_versions
   set collection_enabled = true
 where is_active;
```

Do not manufacture 100/20-threshold fixtures in production.

## Monitoring

Run these queries only from the trusted SQL operator surface:

```sql
select validation_status, count(*)
  from research_private.games
 group by validation_status
 order by validation_status;

select generation_id, status, created_at, published_at, failure_code
  from research_private.aggregation_generations
 order by generation_id desc
 limit 5;

select pg.generation_id, g.status, g.published_at
  from research_private.published_generation pg
  join research_private.aggregation_generations g using (generation_id)
 where pg.singleton;
```

Monitor validation backlog/rejection rate, last successful published
generation, BUILDING/FAILED generations, scheduled-workflow failures or age,
RPC errors, and the current collection flag. Do not copy research subject IDs
or raw moves into public logs or support messages. Alert operationally if no
successful Research batch workflow has run for more than two scheduled
intervals.

## Failed generation recovery

1. Set `collection_enabled=false` if the failure may affect new capture.
2. Inspect the latest FAILED generation and private `failure_code`.
3. Identify the offending accepted source in private research tables.
4. Do not delete accepted source data automatically.
5. Correct data or code under a reviewed change, then manually dispatch the
   Research batch workflow. The old published generation remains active until
   the new generation reaches `PUBLISHED` and the pointer switches atomically.
6. Re-run private ACL and public response checks.

Lease expiry and Actions retry are expected recovery paths. A poison source
must not block other maintenance tasks permanently.

## Account deletion verification

The deletion request closes the open research period and sets the subject to
`DELETION_PENDING`. The Worker calls the service-only unlink RPC after
`prepare_account_deletion` and before deleting Auth. The RPC is idempotent.
After unlink, `account_user_id` is null and `link_state=UNLINKED`; accepted
contributors and aggregate weights remain.

## Rollback / incident procedure

- Do not roll back by deleting research tables or accepted contributors.
- First disable collection server-side.
- Keep the last published generation while investigating.
- Roll back the Worker only if the previous version preserves account-unlink
  ordering. Disable the scheduled Research workflow separately if its runner
  is faulty.
- Migration rollback is not automatic; use a reviewed additive migration.
- Record UTC time, project ref, Worker deployment version, affected generation,
  and recovery result without recording secrets or raw personal data.

## Primary references

- Cloudflare Workers limits: https://developers.cloudflare.com/workers/platform/limits/
- GitHub scheduled workflow behavior: https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule
- GitHub Actions billing: https://docs.github.com/en/actions/concepts/billing-and-usage
- GitHub Actions secret security: https://docs.github.com/en/actions/reference/security/secure-use
- Supabase Postgres roles: https://supabase.com/docs/guides/database/postgres/roles
- Supabase connection modes: https://supabase.com/docs/guides/database/connecting-to-postgres
