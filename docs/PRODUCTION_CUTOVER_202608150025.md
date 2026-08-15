# Production cutover preflight: `202608150025_private_match_rating.sql`

確認日: 2026-08-15 JST

この資料は、公開プロフィールを閉じ、対局相手へserver-owned rating snapshotだけを返すmigrationの本番適用前preflightです。本番適用、rollback実行、Cloudflare設定変更はこの作業では実施していません。

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

本番には`supabase_migrations.schema_migrations`が存在しないため、現在のまま`supabase db push`を実行してはいけません。CLIは001以降を未適用と判断する可能性があります。次回cutoverでは、Dashboard SQL Editorで025の全文を`begin;` / `commit;`で一括実行し、成功後にread-only検証を行う方法を第一候補とします。

この実行単位なら、途中失敗、function作成後の権限変更失敗、column追加後のRPC作成失敗はいずれもtransaction全体がrollbackされ、partial stateは残りません。`db push --dry-run`はpending fileの列挙であり、SQLを実行検証しない点にも注意します。

## 本番DB read-only snapshot

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

## Live利用影響

- nullable・defaultなしの2列追加は短時間のtable lockを取るが、既存行のrewriteは不要。
- RPC drop/recreateと権限変更は同一transactionで可視化されるため、commit前に新旧のpartial APIを公開しない。
- 適用時に開始済みのmatchにはsnapshotがないため、cutover後に旧matchをclaimした場合はratingがnullとなり、Androidは`---`へfallbackする。UUID/email/nameへのfallbackはしない。
- 進行中WebRTC、logged-in Auth session、result submission、Elo更新、Research functionは025が直接変更しない。
- 旧Android clientはprofile/credential APIが403相当になる可能性がある。初回公開前のため新Androidとの同時cutoverを前提とする。
- 長いmaintenance windowは不要。ただし新規matchmakingを避けられる短い低利用時間帯を選び、適用直前にqueue/非失効active matchを再確認する。

## Rollback

緊急rollback正本は`supabase/rollbacks/202608150025_private_match_rating.rollback.sql`です。1 transactionで次を旧仕様へ戻します。

- 旧`handle_new_user()`（Auth metadata display name取込）
- ratingなしの旧`enqueue_or_match()` / `claim_waiting_match()`
- `public_profiles`、legacy `profiles`の旧権限/policies
- federation credential/submissionの旧権限/policies
- verification Storageのowner INSERT/SELECT policies

snapshot列はデータ損失防止のため残します。完全なschema復元として列をDROPするとcutover後のsnapshotを失うため、自動rollbackには含めません。rollback後に作成された新規userの内部値や、cutover中に作られたsnapshotを旧display nameへ変換する処理もありません。

rollbackはprivacy boundaryを再び開くため、重大なmatchmaking障害でforward fixが間に合わない場合だけOWNER承認で実行します。migration履歴テーブルがないため、rollback後の履歴repairも自動では行いません。

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

## Privacy Policy整合

025適用後は次の点で現在の本番Privacy Policyと一致します。

- display nameを新規収集せず、Auth metadataから取り込まない。
- public profile/nicknameを通常クライアントへ公開しない。
- ratingはserver側で算出し、成立した対局相手へ成立時snapshotだけを返す。
- email/UUID/Auth metadataを相手ratingの代替表示に使わない。
- account deletionのrating削除、共有棋譜匿名化、Research unlink/retentionは025で変更しない。

現時点は025未適用なので、本番Privacy PolicyとDBの公開profile/display name境界が不一致です。cutover成功と上記ACL検証が終わるまで公開BLOCKERです。

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

## OWNER cutover gate

- [ ] 現在のWeb/DB不整合を解消するため025を本番適用することを承認。
- [ ] 適用方法を「SQL Editorでexact 025を明示transaction実行」とすることを承認。
- [ ] 適用直前に非失効queue/active matchがないことを再確認。
- [ ] 適用後に2テストアカウントでmatch/Elo E2Eを行うことを承認。
- [ ] account deletionの破壊的E2Eは別途、削除可能な専用アカウントを指定して承認。
- [ ] migration履歴がない本番を将来CLI管理へ移行するbaseline方針を別作業で決定。
- [ ] production Researchの`collection_enabled=true`が意図した運用状態か確認。025は変更しない。

## 公式資料

- [Supabase Database Migrations](https://supabase.com/docs/guides/deployment/database-migrations)
- [Supabase Local development workflow](https://supabase.com/docs/guides/local-development/cli-workflows)
- [Cloudflare Workers Builds configuration](https://developers.cloudflare.com/workers/ci-cd/builds/configuration/)
- [Cloudflare Workers build branches](https://developers.cloudflare.com/workers/ci-cd/builds/build-branches/)
