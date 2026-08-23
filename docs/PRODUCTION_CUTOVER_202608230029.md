# Production cutover record: `202608220029_daily_rating_snapshot.sql`

確認日: 2026-08-23 JST

この資料は、ちゃんりばの「前日順位」を本番Supabaseへ導入した際のpreflight、migration適用、初回snapshot、Supabase Cron設定、事後検証の履歴です。現在状態の正本は[`OPERATIONS_MAP.md`](OPERATIONS_MAP.md)、設計と導入手順は[`DAILY_RATING_SNAPSHOT_ROLLOUT.md`](DAILY_RATING_SNAPSHOT_ROLLOUT.md)を参照してください。

## 対象と基準

- Supabase project: `othello`
- project ref: `zgzllmaoyymoeiqtybck`
- database region: Northeast Asia (Tokyo), `ap-northeast-1`
- repository: `shinp-dev/othello`
- 本番導入基準commit: `86a7d334716c5e924c6b2d6ff73a148b990abdca`
- 開始時のlocal `HEAD` / `origin/main`: 一致
- 開始時working tree: clean
- GitHub Actions CI: run `32608987199`、success

CIではadmin WorkerのCI / typecheck / test、Androidのtest / lint / debug / release / AAB、boundary / SQL security / release検証、local Supabase起動とpgTAPが成功していました。

## 本番preflight

2026-08-23 12:32 JST前後に本番SQL Editorからread-only catalog queryを実行し、次を確認しました。

- `profiles.last_active_at`、`touch_last_active()`、`queue_expired_account_deletions()`が存在し、027適用済み状態と一致。
- `rating_daily_snapshot`と`refresh_rating_daily_snapshot(date)`は不在で、029は未適用。
- 同名refresh functionのoverloadは0件。
- `profiles` / `rating_history`の029依存column、`auth.uid()`、`anon` / `authenticated` / `service_role`が存在。
- `pg_cron` extensionと`cron.job`は不在。既存Cronとの重複なし。
- `supabase_migrations.schema_migrations`は不在。

本番にmigration history tableがないため`supabase db push`は使用せず、029だけを明示的に適用しました。028は本番へ適用しておらず、今後も永久欠番・適用禁止です。

## 029適用

repositoryの`supabase/migrations/202608220029_daily_rating_snapshot.sql`全文を`begin;` / `commit;`で囲み、Supabase Dashboard SQL Editorから1 transactionで実行しました。結果は`Success. No rows returned.`で、追加SQLや場当たり的な修正SQLは実行していません。

適用後のread-only catalog監査結果:

- table `public.rating_daily_snapshot`: owner `postgres`、RLS enabled。
- columns: `user_id uuid`、`snapshot_date date`、`rank integer`、`active_user_count integer`、`top_percentile numeric(8,4)`。全columnがNOT NULL。
- primary key、profile foreign key、rank / active count / percentile check constraint、日付indexが存在。
- policy `users read own daily rating snapshot`: SELECT、`auth.uid() = user_id`。
- table ACL: `authenticated`はSELECTのみ。INSERT / UPDATE / DELETE不可。`anon` / `PUBLIC`はSELECT不可。
- function `public.refresh_rating_daily_snapshot(date)`: owner `postgres`、`SECURITY DEFINER`、`search_path=""`、default引数1個。
- function ACL: `service_role`はEXECUTE可。`PUBLIC` / `anon` / `authenticated`はEXECUTE不可。
- function定義は`rating_history`とAsia/Tokyo、`RANK()`を使用し、`last_active_at`を使用しない。
- 既存`ratings`、`rating_history`、`enqueue_or_match()`、`submit_match_result(uuid,text,text,text,text,jsonb)`、`finalize_match_v2(uuid)`は存在。

029は既存table / RPC / RLS contractを変更しないadditive migrationです。旧クライアントが使う現在レート、matchmaking、result submissionのschema / functionを置換していません。本番テスト対局やダミーデータ作成は行わず、既存contractの存在とACLをread-onlyで確認しました。

## 初回snapshot

実行直前のAsia/Tokyo日時は2026-08-23 12:36、期待されるsnapshot dateは`2026-08-22`、tableは0行でした。default引数で`select public.refresh_rating_daily_snapshot();`を1回実行し、5行を生成しました。

集計検証:

- snapshot date: `2026-08-22`だけ
- row count / active user count: 5
- rank: 1〜5
- top percentile: 20.0000〜100.0000
- invalid rank / count / percentile: 0件
- 削除ユーザー混入: 0件
- 本番`rating_history`から同じJST cutoff、30日半開区間、cutoff前最新rating、`RANK()`、percentile式で再計算した結果との差分: 0件
- 同日retryの戻り値: 5
- retry前後のrow count: 5 / 5
- retry前後のaggregate fingerprint: 不変

検証では個人のUUID、rating、rankを列挙せず、件数・値域・差分数・fingerprintだけを確認しました。

## Supabase Cron

初回snapshotの検証後、Supabase DashboardのDatabase Extensionsから`pg_cron` 1.6.4を`pg_catalog`へ有効化しました。有効化直後に`cron.job`と`cron.job_run_details`の存在、job総数0、同名job 0、同command job 0を確認してから1件だけ登録しました。

- job name: `daily-rating-snapshot`
- job ID: `1`
- active: `true`
- schedule: `10 15 * * *`
- UTC: 毎日15:10
- JST: 毎日00:10（翌日、DSTなし）
- command: `select public.refresh_rating_daily_snapshot();`
- database: `postgres`
- execution principal: `postgres`
- Dashboard next run: `24 Aug 2026 00:10:00 (+0900)`

登録後の`cron.job`は総数1、同名または同commandに一致するjobも1で、重複はありません。Cronと同じdatabase owner、同じSQLを手動で再実行し5を返すことを確認しました。これは同日retryのためsnapshotを変更しません。

## 監視

初回scheduled runは2026-08-24 00:10 JSTです。本番導入直後はまだscheduled runが発生していないため、`cron.job_run_details`のjob ID 1の履歴は0件でした。

実行成否は次で確認します。

1. Supabase Dashboard → Integrations → Cron → Jobs → `daily-rating-snapshot`の実行履歴。
2. 同画面の`View Cron logs`。
3. `cron.job_run_details`で`jobid = 1`の`status`、`return_message`、`start_time`、`end_time`をread-only確認。
4. Postgres logsでfunction errorを確認。
5. `rating_daily_snapshot`のdateがAsia/Tokyoの前日、日付が1種類、rank / count / percentileが有効範囲であることをaggregate queryで確認。

Cronが停止・失敗してもAndroidは古い・当日・取得失敗のsnapshotを「前日順位」として表示せず、端末内最高も更新しません。現在レートとアプリ全体はsnapshotの有無から独立しています。

## Rollback / recovery上の注意

- jobの異常時は最初に`daily-rating-snapshot`をdisableし、追加実行を止めます。他のCronは変更しません。
- 同じsnapshot dateのretryは既存結果を変更せず、より新しいsnapshotがある状態で古いdateへ巻き戻す実行はfunctionが拒否します。
- 029を取り消すdrop SQLは今回作成・実行していません。table / function削除はAndroidの新client contractを失わせるため、影響確認とOWNER承認なしに行いません。
- 本番にmigration history tableがない状態は継続しています。CLI migration管理へのbaseline移行は別作業です。

## 本番で変更したもの

- 029が定義する`rating_daily_snapshot`、index、RLS / policy / ACL、refresh function / ACL。
- `2026-08-22`の初回snapshot 5行。
- `pg_cron` 1.6.4 extension。
- Supabase Cron job `daily-rating-snapshot` 1件。

## 変更していないもの

- 028およびその他のmigration。
- rating algorithm、`ratings`、`rating_history`、rating history retention。
- matchmaking、result submission、Research、Auth設定、既存RLS / RPC、その他Cron。
- Cloudflare、Google Play、Android version、LP。
- 本番テストアカウント、ダミー対局、既存ユーザーデータ。

## 未確認事項

- 初回scheduled runの実履歴と成功結果。2026-08-24 00:10 JST以後に確認する。
- Android端末 / emulatorが接続されていなかったため、本番AccountScreenでの表示はこの作業では未確認。Androidのunit test / lint / buildとCIは基準commitで成功済み。
- 実ユーザーのログイン、対局、result submissionを伴う本番E2Eは、安全なread-only検証の範囲を超えるため実施していない。
