# Daily rating snapshot rollout

確認日: 2026-08-23 JST

この文書は日次順位migrationとschedulerの本番適用前監査、選定根拠、本番導入結果をまとめた記録です。2026-08-23 JSTに029、初回snapshot、Supabase Cronを本番導入しました。CloudflareとGitHub Actionsのschedulerには変更を加えていません。

## Production audit

対象はSupabase project `othello`（project ref `zgzllmaoyymoeiqtybck`）です。本番適用直前にSQL Editorでread-only catalog queryを実行し、次を確認しました。

- `public.profiles.last_active_at`: 存在
- `public.touch_last_active()`: 存在
- `public.queue_expired_account_deletions()`: 存在
- `public.rating_daily_snapshot`: 不在
- `public.refresh_rating_daily_snapshot(date)`: 不在
- `cron.job`: `NULL`（pg_cron未導入）

したがって027は`027_APPLIED`、旧daily snapshot候補028は本番未適用です。snapshot refreshを呼ぶ既存Cronもありません。

## Migration numbering

- `202608220028_daily_rating_snapshot.sql`は削除し、028を永久欠番とする。
- 空の028やplaceholderは作らない。
- 028は本番へ適用してはいけない。
- 正式な日次順位migrationは`202608220029_daily_rating_snapshot.sql`とする。

この区別は、未適用候補028に対して過去に誤った本番適用指示が発生したため、将来の人間・自動化による誤適用を防ぐ目的で維持します。

## 029 dependency audit

029が参照する既存application objectは次だけです。

- `public.profiles(id, deleted_at)`
- `public.rating_history(user_id, rating, created_at, id)`
- Supabase標準role `anon`、`authenticated`、`service_role`
- `auth.uid()`

029は`profiles.last_active_at`、`touch_last_active()`、`queue_expired_account_deletions()`、その他027で初めて追加されたobjectを参照しません。ランキング上のactive判定は引き続き`rating_history.created_at`の半開区間`[cutoff - 30 days, cutoff)`だけを使います。

このため029は、必要な001〜026が適用されたDBにも、027適用済みDBにも単独で追加できるadditive migrationです。migration番号の連続性ではなくschema dependencyで適用可否を判断します。

## Production application cases

### Case A: 027_APPLIED

現在の本番状態です。029は027に依存しないため、029だけを適用できます。027を再実行しません。

### Case B: 027_NOT_APPLIED

001〜026の必要schemaが揃っていることをcatalogで確認できれば、029だけを適用できます。別責務の027を番号合わせのために適用しません。

### Case C: 027_PARTIAL_OR_UNCERTAIN

029の直接依存である`profiles(id, deleted_at)`、`rating_history(user_id, rating, created_at, id)`、標準roleと`auth.uid()`を確認します。029の依存が満たされても、027のpartial stateはアカウントライフサイクル側の問題として別途OWNER判断へ戻します。029適用と027修復を同じ作業に混ぜません。

## Recommended migration execution

本番には既存記録上`supabase_migrations.schema_migrations`がないため、`supabase db push`は001以降を未適用と誤認する危険があり、使用しません。025/026と同じく、対象project refを確認したSupabase Dashboard SQL Editorから029だけを明示的な単一transactionで適用する方法を推奨します。

```sql
begin;

-- 202608220029_daily_rating_snapshot.sql の全文

commit;
```

029にはtransaction外実行が必要な`CREATE INDEX CONCURRENTLY`やextension操作がありません。途中でtable、policy、function、ACLのいずれかが失敗すればtransaction全体をrollbackできます。適用前に同名table/functionが不在であることを再確認し、衝突時はその場で修正SQLを追加せず停止します。

## Scheduler comparison

### Supabase Cron / pg_cron — 推奨

- DB ownerが`select public.refresh_rating_daily_snapshot();`をDB内で直接実行でき、HTTPや外部network hopがない。
- service-role keyやdatabase URLをCloudflare/GitHubへ追加しない。
- jobは`cron.job`、実行履歴は`cron.job_run_details`、DashboardではCronのHistoryでDBと同じ運用境界から確認できる。
- 今回は1日1回の短い集計であり、DB内SQL jobというSupabase Cronの用途に一致する。
- 追加される運用資源は`pg_cron` extensionと1 jobだけ。ただしextension有効化は本番schema変更なので、029適用とは分けてOWNER承認後に行う。

SupabaseはCronが内部でpg_cronを使い、SQL/database functionをnetwork hopなしで実行し、job/runをDB内に記録すると説明しています: <https://supabase.com/docs/guides/cron>

### Cloudflare Cron Trigger — 非推奨

- scheduled handlerとSupabaseへの認証済みnetwork callが必要になり、障害点とsecret境界が増える。
- public-facing Workerへservice-role相当を置くことはarchitecture違反。trusted `cloudflare-admin`へ置く場合でも、DB内だけで完結する処理を外部admin boundaryへ広げる理由がない。
- Cron TriggerはUTCで動作し、trigger変更の反映に時間がかかる場合がある。

Cloudflareの公式仕様: <https://developers.cloudflare.com/workers/configuration/cron-triggers/>

### GitHub Actions schedule — 非推奨

- database URL等のproduction secret、runner、network、dependency installが必要になる。
- GitHubは高負荷時にscheduled workflowが遅延し、十分な負荷ではdropされる場合があると明記している。
- repository/build運用には適するが、単一DB functionの日次実行を既存Research batch workflowへ混ぜると責務と監視が不明瞭になる。

GitHubの公式仕様: <https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule>

pg_cronが本番planやPostgres versionで利用不能、または将来処理が外部API連携を必須にする場合だけ外部schedulerを再評価します。現要件ではSupabase Cron/pg_cronが最小で最も明確です。

## Production Cron

- extension/module: Supabase Cron (`pg_cron`)
- job name: `daily-rating-snapshot`
- schedule: `10 15 * * *`（UTC 15:10 = 翌日JST 00:10、JSTにDSTなし）
- command: `select public.refresh_rating_daily_snapshot();`
- frequency: 1日1回
- execution principal: jobを登録したprivileged DB owner
- monitoring: Dashboard Cron Historyまたは`cron.job_run_details`

job作成前に`cron.job`で同名jobと同function呼び出しの双方を検索し、重複があれば作成せず停止します。Cronにはcleanup、rating再計算、外部API呼び出しを追加しません。

## Production rollout result

本番導入基準はcommit `86a7d334716c5e924c6b2d6ff73a148b990abdca`です。GitHub Actions CI run `32608987199`が成功し、local Supabase pgTAPを含む全stepの成功を確認してから本番操作を開始しました。

- 029全文をSupabase Dashboard SQL Editorから明示的な`begin` / `commit`の1 transactionで適用し、成功しました。migration history tableがないため`supabase db push`は使用していません。
- `rating_daily_snapshot`、日付index、RLS、本人SELECT policy、`refresh_rating_daily_snapshot(date)`を確認しました。
- tableは`authenticated`のSELECTだけを許可し、`anon` / `PUBLIC`はSELECT不可、`authenticated`はINSERT / UPDATE / DELETE不可です。
- refresh functionはowner `postgres`、`SECURITY DEFINER`、`search_path=""`、default引数1個です。`PUBLIC` / `anon` / `authenticated`はEXECUTE不可、`service_role`はEXECUTE可です。
- `ratings`、`rating_history`、`enqueue_or_match()`、`submit_match_result(uuid,text,text,text,text,jsonb)`、`finalize_match_v2(uuid)`が適用後も存在することを確認しました。
- 初回実行はAsia/Tokyoの前日`2026-08-22`を対象に5行を生成しました。日付は1種類、`active_user_count`は全行5、rankは1〜5、percentileは20〜100、無効値と削除ユーザー混入は0でした。
- 本番履歴から同じcutoff条件で再計算した期待値との差分は0でした。個人識別情報は検証出力へ含めていません。
- 同日retryは5を返し、snapshotの行数とfingerprintが不変でした。
- Dashboardから`pg_cron` 1.6.4を`pg_catalog`へ有効化しました。有効化直後の`cron.job`は0件で、同名・同commandの重複がないことを再確認しました。
- job `daily-rating-snapshot`（job ID `1`）を1件だけ作成しました。scheduleは`10 15 * * *`、commandは`select public.refresh_rating_daily_snapshot();`、database / execution principalは`postgres`、activeです。
- Dashboardのnext run表示は`24 Aug 2026 00:10:00 (+0900)`で、UTC 15:10からJST 00:10への換算と一致しました。
- Cronと同じDB owner・同じSQLを手動retryし、5を返すことを確認しました。初回scheduled run前のため、`cron.job_run_details`のjob実行履歴はまだ0件です。

詳細な時刻、schema / ACL確認、snapshot検証、未確認事項は[`PRODUCTION_CUTOVER_202608230029.md`](PRODUCTION_CUTOVER_202608230029.md)を参照してください。
