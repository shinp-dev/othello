# Production cutover record: `202608150025_private_match_rating.sql`

確認日: 2026-08-15 JST

この資料は、公開プロフィールを閉じ、対局相手へserver-owned rating snapshotだけを返すmigrationの本番preflightと適用結果です。2026-08-15 JSTにSupabase Dashboard SQL Editorから1 transactionで本番適用し、rollbackとCloudflare設定変更は実施していません。

後方互換不要というOWNER判断により、続く`202608150026_remove_retired_profile_verification.sql`で表示名・公開profile・連盟verification surfaceを物理削除しました。025用rollbackはそれらを復活させるため廃止し、026適用後は使用しません。

## 026 cleanup適用結果

- `othello-admin`を先に本番deployし、account deletionから旧verification DB/Storage依存を除去（Cloudflare Version `dc88fbb0-bf20-4132-b72e-a034912fd7a5`）。
- 026をSupabase Dashboard SQL Editorから1 transactionで本番適用。
- `profiles`は内部参照用の`id`と削除tombstone用の`deleted_at`だけに縮小。`display_name`、未使用の`created_at`、`updated_at`を物理削除。
- `public_profiles` view、`federation_credentials`、`verification_submissions`、`credential_status`、旧verification/account-deletion evidence RPCを物理削除。
- 空であることを確認した旧`verification` Storage bucketをSupabase Storage UIから削除。
- `ratings`、`rating_history`、match開始時rating snapshot、matchmakingの`opponent_rating`は維持。
- Researchはsegment 2件、position aggregate 56件、move aggregate 56件、accepted contributor 2件で不変。`rating_before`とalgorithm versionも維持。
- Privacy PolicyをDB最終仕様へ合わせて本番deploy（Cloudflare Version `a4774d15-f3c0-456b-b654-456480c05c0d`）。`/privacy`と`/account-deletion`はHTTPS 200を確認。

## Migration監査

### Schema / function

- `public.matches.black_rating_at_start integer`をnullable・正数check付きで追加。
- `public.matches.white_rating_at_start integer`をnullable・正数check付きで追加。
- `public.handle_new_user()`を置換。新規profileのlegacy `display_name`には固定内部値を保存し、Auth metadataを参照しない。
- 引数なし`public.enqueue_or_match()`をdrop/recreateし、戻り値へ`opponent_rating integer`を追加。match作成時に両者のrating snapshotを保存。
- 引数なし`public.claim_waiting_match()`をdrop/recreateし、保存済みsnapshotから`opponent_rating integer`を返す。
- DROP COLUMN、ALTER COLUMN、VIEW、INDEX、table DROP/CREATEはない。

### 権限 / RLS

- `public.public_profiles`と`public.profiles`を`public`、`anon`、`authenticated`から全面REVOKE。
- `profiles`の公開SELECT policyとowner UPDATE policyを削除。
- `federation_credentials`、`verification_submissions`を通常クライアントから全面REVOKE。
- `submit_verification_submission(uuid,text)`の通常クライアントEXECUTEをREVOKE。
- credential/submissionのowner SELECT/INSERT policiesとverification Storage owner INSERT/SELECT policiesを削除。
- 新しいmatchmaking RPCは`authenticated`だけにEXECUTEをGRANT。
- `service_role`の権限は変更しない。

### Migration実行時のデータ変更

- migration自身が即時実行する`UPDATE`、`DELETE`、`INSERT`はない。
- 既存display name、rating、credential、Storage object、matchを変更・削除しない。
- function bodyには将来の呼び出し時に実行されるINSERT/DELETEがあるが、migration適用だけでは実行されない。
- 既存ユーザーデータを上書き・破壊する処理はない。

## Transaction性

対象の`ALTER TABLE ADD COLUMN`、function/policyのCREATE/DROP、GRANT/REVOKEはPostgreSQL transaction内で実行可能です。`CREATE INDEX CONCURRENTLY`等のtransaction外必須処理は含みません。

本番には`supabase_migrations.schema_migrations`が存在しないため、現在のまま`supabase db push`を実行してはいけません。CLIは001以降を未適用と判断する可能性があります。025はDashboard SQL Editorで全文を`begin;` / `commit;`で一括実行し、成功後にread-only検証しました。

この実行単位なら、途中失敗、function作成後の権限変更失敗、column追加後のRPC作成失敗はいずれもtransaction全体がrollbackされ、partial stateは残りません。`db push --dry-run`はpending fileの列挙であり、SQLを実行検証しない点にも注意します。

## 本番DB適用前read-only snapshot

確認時点の構造は025適用前です。

- migration history table: 存在しない。正式なproduction migration versionはDB履歴から判定不能。
- 024までの主要構造: account deletion RPC、Realtime contract、Research schema/role、`service_role`のverification submission SELECTを確認。
- rating schema: `user_id`、`current_rating`、`peak_rating`、`algorithm_version`、`updated_at`。
- rating snapshot columns: 0列。
- `enqueue_or_match()`戻り値: ratingなしの4項目。
- `claim_waiting_match()`戻り値: ratingなしの3項目。
- `handle_new_user()`はAuth `raw_user_meta_data`を参照。
- `public_profiles`: anon/authenticated SELECT可。
- `profiles`: authenticated SELECTおよび`display_name` UPDATE可。anon SELECTはtable privilege上不可。
- federation credential/submissionおよびverification Storage owner policies: 旧クライアント経路が有効。
- live aggregate: queue 0、CREATED match 0、PENDING_RESULT match 1、active participant 2。
- 上記PENDING_RESULT/participantの期限は2026-08-09に失効済みで、現在進行中であることを示すものではない。ただしstale rowが残っている。
- Research active policy: 1件、`collection_enabled=true`。025はこの状態を変更しない。

## 本番適用結果

- 2026-08-15 JST、適用直前のqueue 0件、`CREATED` match 0件を確認。
- 025全文を明示的な`begin` / `commit`で実行し、成功。
- rating snapshot列は0列から2列へ増加。
- `enqueue_or_match()`と`claim_waiting_match()`の戻り値に`opponent_rating integer`が追加されたことを確認。
- `handle_new_user()`がAuth metadataを参照せず、内部固定値を使うことを確認。
- anon/authenticatedの`public_profiles` SELECT、authenticatedの`profiles` SELECTと`display_name` UPDATEが不可になったことを確認。
- federation credential/submission/verification Storageの旧クライアント経路が閉じたことを確認。
- Researchは適用前後ともsegment `ALL` 2件、position aggregate 56件、move aggregate 56件、accepted contributor 2件で不変。
- `research_private.game_contributors.rating_before`と`rating_algorithm_version`を維持。レート帯別分析は現在未公開の将来機能だが、固定rating segmentを追加して再集計するためのschemaと元ratingは失われていない。

## Live利用影響

- nullable・defaultなしの2列追加は短時間のtable lockを取るが、既存行のrewriteは不要。
- RPC drop/recreateと権限変更は同一transactionで可視化されるため、commit前に新旧のpartial APIを公開しない。
- 適用時に開始済みのmatchにはsnapshotがないため、cutover後に旧matchをclaimした場合はratingがnullとなり、Androidは`---`へfallbackする。UUID/email/nameへのfallbackはしない。
- 進行中WebRTC、logged-in Auth session、result submission、Elo更新、Research functionは025が直接変更しない。
- 旧Android clientはprofile/credential APIが403相当になる可能性がある。初回公開前のため新Androidとの同時cutoverを前提とする。
- 長いmaintenance windowは不要。ただし新規matchmakingを避けられる短い低利用時間帯を選び、適用直前にqueue/非失効active matchを再確認する。

## Rollback

025用rollbackは、不要と確定した表示名・公開profile・連盟verification surfaceを復活させるため廃止しました。rating snapshotと`opponent_rating`経路で問題が出た場合は、不要機能を再公開せずforward fixします。

## Cutover後検証

### DB / ACL

1. snapshot列2つと各check constraintを確認。
2. 新RPCの戻り値に`opponent_rating`があることを確認。
3. anon/authenticatedが`public_profiles`をSELECTできないことを確認。
4. authenticatedが`profiles`をSELECT/UPDATEできないことを確認。
5. credential/submission/verification Storageの旧client経路が閉じていることを確認。
6. 新規テストuserでAuth metadataへ任意display nameを指定しても公開経路がなく、triggerがmetadataを取り込まないことを確認。
7. 025の適用記録方法は別途OWNER判断する。履歴テーブルがない状態を「version確認済み」と扱わない。

### Matchmaking / Android

1. 削除可能な2テストアカウントでenqueueし、match成立を確認。
2. 両者の`opponent_rating`がserver-side `ratings.current_rating`と一致することを確認。
3. match rowのblack/white snapshotが成立時点の値であることを確認。
4. start ACK、WebRTC開始、同一結果の両者submit、CONFIRMED、Elo更新まで確認。
5. Android UIは相手ratingだけを表示し、nickname/email/UUIDを表示しないことを確認。
6. snapshotなしの場合は`レート ---`となることを確認。

### Account deletion / Research

1. read-onlyでrequest RPC、matchmaking拒否trigger、prepare/complete RPC、Research unlink関数と権限が維持されていることを確認。
2. 削除request中のテストuserがenqueueを拒否されることを確認。
3. rating削除、profile tombstone、Research account unlink、accepted contribution保持は、OWNERが削除可能と指定した専用アカウントでのみE2E確認する。
4. 実アカウント削除は行わない。

### 2026-08-15 E2E follow-up

- 新規／使い捨てテストアカウントでオンライン対局を成立・完了し、実機では対局相手の成立時rating snapshot表示と結果確定後のrating更新を確認した。
- Research参加中の対局がcaptureされることを確認した。
- 同じ使い捨てテストアカウントの削除完了後、Auth identityとprivate rating等が削除され、profile tombstoneと共有棋譜の匿名化保持が維持されることを確認した。
- Research account linkはunlinkされ、accepted contributionと統計値は減少せず、Research schema／公開集計から削除済みaccountへ逆参照する識別子が残らないことを確認した。
- これらはdebug実機と本番backendの確認であり、signed release / Play生成APKのruntime確認は未実施。

## Privacy Policy整合

025適用後は次の点で現在の本番Privacy Policyと一致します。

- display nameを新規収集せず、Auth metadataから取り込まない。
- public profile/nicknameを通常クライアントへ公開しない。
- ratingはserver側で算出し、成立した対局相手へ成立時snapshotだけを返す。
- email/UUID/Auth metadataを相手ratingの代替表示に使わない。
- account deletionのrating削除、共有棋譜匿名化、Research unlink/retentionは025で変更しない。

025の本番適用とACL検証が完了し、本番Privacy PolicyとDBの公開profile/display name境界の一時的不整合は解消しました。

## Cloudflare Workers Builds経路

Dashboardで確認した`chanriva`の実設定:

- Git repository: `shinp-dev/othello`
- production branch: `main`
- root directory: `landing-page`
- build command: `npm run build`
- production deploy command: `npx wrangler deploy`
- non-production command: `npx wrangler versions upload`
- non-production branch builds: ON
- include paths: `*`、excludeなし
- deploy hooks: なし
- GitHub ActionsのCI workflowにWeb deploy step: なし

`fab4020846662a57764883926e239b7fb8ff7374`のmain pushはCloudflare Workers Buildsにより成功し、active deploymentへ100%昇格しました。Privacy Policyとaccount deletionページは同じ`landing-page` Workerに含まれるため、両方が同時に本番反映されました。

## Git安全境界

- local commitのみ: 外部状態変更なし。
- feature branch push: GitHub CIに加え、Cloudflareでnon-production buildとversion uploadが発生する。production trafficは変更しないが、外部状態変更あり。
- main push: GitHub CIに加え、`landing-page`全体をCloudflare本番へbuild/deployする。OWNER承認必須。
- GitHub Actions `Research batch`の手動実行: main refではproduction Research DBへ処理を行い得るためOWNER承認必須。通常CIにはdeploy処理なし。
- tag push: GitHubの通常CIは起動対象。Cloudflare設定はbranch buildでありproduction deployは確認できないが、安全側で事前確認なしにpushしない。
- Cloudflare deploy hook: 現在なし。

今後、エージェントは`main` pushを単なるGit操作として扱わず、本番Web deployとして扱います。feature branch pushもCloudflare version作成を伴うためOWNER承認対象です。

## 適用後の残件

- [x] 現在のWeb/DB不整合を解消するため025を本番適用。
- [x] SQL Editorでexact 025を明示transaction実行。
- [x] 適用直前にqueue 0件、`CREATED` match 0件を確認。
- [x] 適用後にテストアカウントでmatch、相手rating snapshot、結果確定、Elo更新をE2E確認。
- [x] Research参加済みの削除可能な使い捨てテストアカウントでaccount deletion、unlink、統計値保持、逆参照不可をE2E確認。
- [ ] migration履歴がない本番を将来CLI管理へ移行するbaseline方針を別作業で決定。
- [ ] production Researchの`collection_enabled=true`が意図した運用状態か確認。025は変更しない。

## 公式資料

- [Supabase Database Migrations](https://supabase.com/docs/guides/deployment/database-migrations)
- [Supabase Local development workflow](https://supabase.com/docs/guides/local-development/cli-workflows)
- [Cloudflare Workers Builds configuration](https://developers.cloudflare.com/workers/ci-cd/builds/configuration/)
- [Cloudflare Workers build branches](https://developers.cloudflare.com/workers/ci-cd/builds/build-branches/)
