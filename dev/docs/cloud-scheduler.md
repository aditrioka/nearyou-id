# Cloud Scheduler for the `/internal/*` workers

Every OIDC-gated `/internal/*` worker does nothing until a Cloud Scheduler job
calls it. [`dev/scripts/provision-schedulers.sh`](/dev/scripts/provision-schedulers.sh)
is the one declared source for all nine jobs. It is idempotent, and the same
script covers staging now and production later. The worker table, with the cadence
each spec sets, is in [`docs/07-Operations.md` § Internal worker schedules](/docs/07-Operations.md#internal-worker-schedules-cloud-scheduler).

## What the script manages

| Job (`<env>` = `staging` / `prod`) | Endpoint | Schedule | Staging (WIB) |
|---|---|---|---|
| `nearyou-unban-worker-<env>` | `POST /internal/unban-worker` | `0 21 * * *` UTC (04:00 WIB) | `4 3 * * *` |
| `nearyou-privacy-flip-worker-<env>` | `POST /internal/privacy-flip-worker` | `0 * * * *` WIB | `7 3 * * *` |
| `nearyou-login-anomaly-check-<env>` | `POST /internal/login-anomaly-check` | `0 * * * *` WIB | `8 3 * * *` |
| `nearyou-data-export-worker-<env>` | `POST /internal/data-export-worker` | `45 * * * *` WIB | `9 3 * * *` |
| `nearyou-csam-archive-purge-<env>` | `POST /internal/csam-archive-purge` | `0 2 * * *` WIB | `0 3 * * *` |
| `nearyou-cleanup-orphan-images-<env>` | `POST /internal/cleanup-orphan-images` | `30 2 * * *` WIB | `1 3 * * *` |
| `nearyou-retention-cleanup-<env>` | `POST /internal/cleanup` | `0 3 * * *` WIB | `2 3 * * *` |
| `nearyou-account-hard-delete-worker-<env>` | `POST /internal/account-hard-delete-worker` | `30 3 * * *` WIB | `3 3 * * *` |
| `nearyou-referral-activity-check-<env>` | `POST /internal/referral-activity-check` | `30 4 * * *` WIB | `6 3 * * *` |

Every job uses the same settings:

- `POST` to `https://<API_HOST><path>`.
- A Google OIDC token signed by the invoker SA `scheduler-invoker-<env>` (in the env's project).
- Token audience `INTERNAL_OIDC_AUDIENCE`, which must equal the deployed value: `https://api-staging.nearyou.id` on staging, set in `deploy-staging.yml`.
- Retries: up to 3 retries after the first attempt, backoff 30 s to 5 min, attempt deadline 180 s. An update also clears hand-added headers and body, so every job matches the script.

In production the daily jobs are spread out between 02:00 and 05:00 WIB. The referral check runs after the 04:00 unban, so a suspension that ended overnight doesn't void an inviter's tickets on a stale `is_banned` flag.

Staging (`ENV_NAME=staging`) uses the last column instead: all nine jobs once a day, one minute apart from 03:00 WIB, in the same order, so the set costs one Cloud Run wake a day. The reason and the trade-offs are in [`docs/07-Operations.md` § Internal worker schedules](/docs/07-Operations.md#internal-worker-schedules-cloud-scheduler). Force-run a job to test a worker between windows.

`/internal/csam-webhook`, `/internal/apple/s2s-notifications` and
`/internal/revenuecat-webhook` are push endpoints that vendors call. They have no schedule.

## Run it

Before you start you need: `gcloud` signed in as Owner, or with Service Usage Admin + Service Account Admin + Cloud Run Admin + Cloud Scheduler Admin, plus Service Account User on the invoker SA.

```bash
dev/scripts/provision-schedulers.sh --dry-run              # shows create vs update and every mutation
dev/scripts/provision-schedulers.sh --run-now              # provision, force-run all 9, print the request log
```

`--run-now` is safe because every worker is idempotent. A forced run does
exactly what the next scheduled tick would do, against staging's synthetic data.
It skips paused jobs.

On a brand-new project, a step right after the API enable or the SA create can
fail while IAM propagates. Wait a minute and re-run.

Production, once the prod project exists. There are no defaults on purpose, so
the script refuses to run without `PROJECT_ID` and `API_HOST`:

```bash
ENV_NAME=prod PROJECT_ID=<prod-project> API_HOST=api.nearyou.id dev/scripts/provision-schedulers.sh --run-now
```

Overrides: `REGION` (default `asia-southeast1`), `SERVICE` (default `nearyou-backend-<env>`),
`INTERNAL_OIDC_AUDIENCE` (default `https://<API_HOST>`), `SA_NAME` (default `scheduler-invoker-<env>`).

## Verify

Each worker should show a `200` from `Google-Cloud-Scheduler`:

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="nearyou-backend-staging" AND httpRequest.requestUrl:"/internal/" AND httpRequest.userAgent:"Google-Cloud-Scheduler"' \
    --project=nearyou-staging --freshness=1h --format='table(timestamp,httpRequest.status,httpRequest.requestUrl)'
gcloud scheduler jobs list --project=nearyou-staging --location=asia-southeast1 \
    --format='table(name.basename(),schedule,state,lastAttemptTime,status.code)'
```

What the status codes mean:

| Code | Cause | Fix |
|---|---|---|
| `401` | Wrong audience or no token | Compare `--oidc-token-audience` with `INTERNAL_OIDC_AUDIENCE` |
| `403` | SA not in `INTERNAL_OIDC_ALLOWED_PRINCIPALS` (`principal_not_allowed`, [#544](https://github.com/aditrioka/nearyou-id/issues/544)) | Add the SA to the allowlist |
| `404` | Route not deployed on the live revision | Redeploy, or wait for the merge to deploy |
| `504` | Run went past Cloud Run's 60 s `--timeout` | Retried automatically; the worker claims are idempotent |

The data-export worker processes at most `DataExportWorker.MAX_EXPORTS_PER_RUN` (5) exports per hourly run, oldest first. The rest stay `pending` for the next run, and the response and the `event=data_export_worker_applied` log report them as `remaining`. The cap bounds the count, not the wall time, so slow R2 or Resend calls can still cause a `504`.

## Invoker identity and the caller allowlist

The invoker SA email must appear in two places:

1. As `--oidc-service-account-email` on every job. This script sets it.
2. In `INTERNAL_OIDC_ALLOWED_PRINCIPALS`, the `/internal/*` caller allowlist. Issue [#544](https://github.com/aditrioka/nearyou-id/issues/544) added it and wires it in `deploy-staging.yml` (staging lists only `scheduler-invoker-staging`). The check matches the OIDC `email` claim, and an empty list fails boot on staging/production and refuses every call in dev/test (fail closed).

Staging history: three jobs (unban, privacy-flip, login-anomaly) were created by
hand in the original changes on the legacy SA `unban-scheduler-staging`. The
script's first run (2026-10-04) moved them to `scheduler-invoker-staging`, and
`gcloud scheduler jobs list` now shows the new SA on all nine jobs. No job uses the
legacy SA any more, but it still holds `run.invoker`. To retire it:

1. Nothing to drop from `INTERNAL_OIDC_ALLOWED_PRINCIPALS`: #544 shipped allowlisting only `scheduler-invoker-staging`.
2. Remove its `run.invoker` binding:

   ```bash
   PROJECT=nearyou-staging
   gcloud run services remove-iam-policy-binding nearyou-backend-staging --region=asia-southeast1 --project="$PROJECT" \
       --member="serviceAccount:unban-scheduler-staging@${PROJECT}.iam.gserviceaccount.com" --role=roles/run.invoker
   ```

3. Optionally delete the SA itself.

The `run.invoker` binding is defence in depth for now. Staging still deploys
with `--allow-unauthenticated`, so today the OIDC check plus the allowlist is the
gate. The binding starts to matter once ingress is locked down.

## Pause, roll back, add a worker

- **Pause one worker:** `gcloud scheduler jobs pause <job> --location=asia-southeast1 --project=<project>`. A re-run of the script updates the job but does not resume it. Resume with `jobs resume`.
- **Remove a job:** `gcloud scheduler jobs delete <job> …`. The script never deletes jobs that are missing from its table.
- **Add a worker:** add one row to `JOBS` in the script (`id|path|cron|tz|staging-cron`, with a comment naming the spec that sets the cadence; give the staging cron a free minute in the 03:00 WIB window), add the row to the docs/07 table and to the table above, then re-run the script.

## Cost

Cloud Scheduler bills $0.10 per job per month. The first 3 jobs per billing
account are free. Nine jobs on staging cost about $0.60 a month. Production adds
9 more on whichever billing account it uses.

The bigger cost is the Cloud Run time the jobs keep awake. The service deploys
with `--no-cpu-throttling`, so a wake bills the instance for about 15 minutes of
idle after the last request, not just the request itself. With the hourly
cadence, staging was awake about 13 hours a day (about Rp1 jt a month,
October 2026). The single 03:00 WIB window costs one wake a day, which stays
inside the Cloud Run free tier. Check the awake time with the
`run.googleapis.com/container/billable_instance_time` metric (Cloud Monitoring →
Metrics explorer, aligned per day).
