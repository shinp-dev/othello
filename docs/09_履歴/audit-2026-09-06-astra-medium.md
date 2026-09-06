# 静的監査記録 — 2026-09-06

この資料は特定commitの監査結果です。現行の運用手順は[技術資料インデックス](../README.md)を参照してください。

## 実施情報

- 実施日：2026-09-06（UTC）
- 実施モデル・推論設定：Astra・中程度
- 対象：`shinp-dev/othello`、監査開始時のmain
- 対象commit：[`d642b4aeb1ae880f88f86138190e1a6e352567c9`](https://github.com/shinp-dev/othello/commit/d642b4aeb1ae880f88f86138190e1a6e352567c9)
- 方法：ソース、後続マイグレーションによる関数の上書き、既存テスト、運用資料、既存CI結果の静的確認
- 作業範囲：監査資料と索引の追加のみ。実装修正、reset、権限変更、本番DB操作、アカウント削除、デプロイは実施していない。
- 実機、エミュレーター、ビルド、テスト、SQL、障害注入は今回実行していない。以下の発生手順はコードから導いた確認シナリオであり、実行済みの再現結果ではない。

## 結果

| ID | 優先度 | 指摘 | 確度 |
| --- | --- | --- | --- |
| OTH-01 | P2・中 | 削除メールのredirect_toをAuthが読み取らないJSON本文に渡している | 高。実際の遷移先は本番Auth設定にも依存 |
| OTH-02 | P2・中 | 削除キューの失敗項目が後続の処理を妨げる | 高。継続的な失敗が発生する条件付き |
| OTH-03 | P3・低 | 個別の削除HTTPエラーがCron全体の失敗に反映されない | 高 |

P2は利用者の処理完了・運用継続に影響する不具合、P3は主に検知・復旧を弱める不具合として分類した。今回の範囲でP0/P1に相当する指摘は確定していないが、脆弱性がないことや本番運用の健全性を保証するものではない。

## OTH-01：削除メールの遷移先指定がAuth契約と一致しない

**根拠：** [landing-page/worker/index.ts:203](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/landing-page/worker/index.ts#L203)、[landing-page/tests/rendered-html.test.mjs:203](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/landing-page/tests/rendered-html.test.mjs#L203)。

`startEmailAccountDeletion`は`/auth/v1/otp`にJSONをPOSTし、その本文に`redirect_to`を格納している。確認したSupabase Auth公式実装では、OTPからMagicLink処理に進み、遷移先は`getRedirectTo`がヘッダーまたはクエリ／フォームから取得する。JSON本文のこのフィールドは対象にならない。指定がなければ有効なReferer、さらにSiteURLへフォールバックする。このWorkerは上流へのRefererも指定していない。

**発生条件・影響：** AuthのSiteURLやメールテンプレートが削除確認ページへの遷移を別途補っていない場合、既存ユーザーが削除メールを開いても`/account-deletion/confirm`に到達できず、メール認証による削除を完了できない。メール送信成功そのものとは別の問題である。本番メールテンプレートと実際の配信リンクは未確認。

**既存テストの不足：** テストは本文中に`account-deletion/confirm`があることを検査しているが、Authがそのフィールドを受理するかは検査していない。モックはOTPに400を返すため、正常なメールリンクの遷移契約も検証していない。

**修正時の方向：** 遷移先をAuthが受け取るクエリパラメーター等で渡し、許可URL設定と整合させる。クエリの値・`create_user=false`を契約テストで確認し、検証環境のメールから確認ページに到達することを確認する。

**公式実装参照：** 監査時に確認したSupabase Authのcommit `0907af9bd6be3c76f472c40a7dcc0dc34abeffaf`。対象リポジトリの本番Authバージョンを特定したという意味ではない。

- [OTP処理](https://github.com/supabase/auth/blob/0907af9bd6be3c76f472c40a7dcc0dc34abeffaf/internal/api/otp.go)
- [MagicLink処理](https://github.com/supabase/auth/blob/0907af9bd6be3c76f472c40a7dcc0dc34abeffaf/internal/api/magic_link.go)
- [GetReferrer / getRedirectTo](https://github.com/supabase/auth/blob/0907af9bd6be3c76f472c40a7dcc0dc34abeffaf/internal/utilities/request.go)

## OTH-02：削除失敗が後続のキュー処理を妨げる

**根拠：** [cloudflare-admin/src/index.ts:114](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/cloudflare-admin/src/index.ts#L114)、[cloudflare-admin/src/index.ts:128](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/cloudflare-admin/src/index.ts#L128)、[supabase/migrations/202608180027_account_lifecycle.sql:44](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/supabase/migrations/202608180027_account_lifecycle.sql#L44)。

削除Workerは毎回`requested_at.asc&limit=50`で先頭50件を取得し、順次処理する。各項目を囲む例外処理がないため、途中のfetchが通信例外やタイムアウトでrejectすると、その回の残りは実行されない。失敗行の次回試行時刻や試行回数を更新する仕組みもない。

HTTPエラーとして返った場合は後続へ進むものの、失敗行はREQUESTED/PROCESSINGのまま同じ順序で取得される。先頭50件すべてが継続して失敗すると51件目以降は取得されない。期限切れアカウントの投入SQLも、既存リクエストを除外せず古い100件を選んでから`on conflict do nothing`に進むため、古い100件が未削除のまま残る条件では、その先の新規対象を投入できない。

**確認シナリオ：**

1. 先頭項目の削除fetchをrejectさせ、後続項目が今回処理されないことを確認する。
2. 先頭50件に継続的なHTTP失敗を設定し、51件目の成功可能な項目が複数回の実行でも選ばれないことを確認する。
3. 期限切れ対象の最古100件に既存リクエストを用意し、101件目の新規対象が投入されないことを確認する。

**影響：** 特定項目の障害から、無関係な利用者の削除待ちが長期化し得る。実際にこの滞留が本番で発生していることは確認していない。

**修正時の方向：** 個別の例外を隔離して残りを処理し、永続的な試行回数・次回試行時刻等で再試行を分散する。投入側は投入済み行を除外した候補にLIMITを適用する。手動復旧可能な失敗状態と最古待ち時間の監視も用意する。単に例外を握りつぶすだけでは次のOTH-03を悪化させる。

## OTH-03：削除HTTPエラーがCronの失敗に反映されない

**根拠：** [cloudflare-admin/src/index.ts:24](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/cloudflare-admin/src/index.ts#L24)、[cloudflare-admin/src/index.ts:123](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/cloudflare-admin/src/index.ts#L123)、[cloudflare-admin/test/account-deletion-worker.test.mjs](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/cloudflare-admin/test/account-deletion-worker.test.mjs)。

`prepare`、研究リンク解除、Auth削除、`complete`のHTTPエラーは、`processAccountDeletion`から非2xxのResponseとして返る。呼び出し側は`console.error`に記録するだけで正常returnする。一方、`runScheduledMaintenance`が失敗扱いするのはPromiseがrejectした場合だけである。

**確認シナリオ・影響：** キュー一覧と対局保守を成功させ、個別のAuth削除だけ503にする。この場合、削除は未完了でもCronのPromiseはresolveする。個別ログは残るため「一切検知不能」ではないが、ジョブ成功・失敗だけを監視している運用では削除障害を見落とす。既存の失敗テストは主に対局保守RPCの失敗を扱い、この個別削除の経路を扱っていない。

**修正時の方向：** 他項目の処理は続けたうえで件数と失敗を集約し、最後にバッチ結果へ反映する。想定内の延期と実障害を区別し、削除成功数・失敗数・待ち時間を観測可能にする。OTH-02は処理の進行保証、本件は結果通知の問題であり、片方だけの修正では両方は解消しない。

## 本番設定の確認が必要な事項

**終端対局の保持期限後の削除ジョブ：** `cleanup_terminal_matches`はマイグレーション009で定義され、010のコメントではSupabase Cron/pg_cronで実行するよう指定されている。今回確認したリポジトリの定期Worker、GitHub workflow、Cron登録SQLからはこの関数の定期呼び出しを確認できなかった。v2/v1の保守RPCは終端化とシグナル・キュー削除を行うが、終端対局の保持期限後の削除は別である。

本番に手動登録されたジョブが存在する可能性があるため、未稼働とは断定しない。読み取り専用で本番Cron定義・実行履歴を確認し、登録済みならリポジトリにも導線を記録する。今回、本番ジョブの照会・登録・実行は行っていない。

参照：[supabase/migrations/202608090009_storage_retention.sql:84](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/supabase/migrations/202608090009_storage_retention.sql#L84)、[supabase/migrations/202608090010_canonical_and_pending_result.sql:133](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/supabase/migrations/202608090010_canonical_and_pending_result.sql#L133)、[supabase/migrations/202608250030_release_match_hardening.sql:2120](https://github.com/shinp-dev/othello/blob/d642b4aeb1ae880f88f86138190e1a6e352567c9/supabase/migrations/202608250030_release_match_hardening.sql#L2120)。

## 確認範囲と限界

重点確認は以下。全ファイル・全経路の網羅や形式検証を意味しない。

- Android認証セッション、Supabaseゲートウェイ、オンライン対局・再接続・結果提出の主要経路
- SupabaseのRLS／実行権限、対局v2の棋譜再生・双方結果照合・自己投了・期限切れ処理、関連する旧定義の上書き
- アカウント削除・研究主体の切り離し・休眠アカウント投入、Cloudflare管理／公開Worker
- ローカル棋譜保存・取消、理論解析キャッシュとセッション保存、Edaxデータ取り込み・検証の主要部分
- 研究集計の公開処理、関連するテストとCI／運用資料

双方の通常結果を棋譜再生して照合する処理、自己申告の敗北を確定する処理、無合意の切断申告を期限後に無効化する処理を確認した。片側からの切断申告だけで直ちに相手へ敗北を付ける、という指摘はしていない。STUNのみの接続制約や実機確認の残件は、明示された運用上の制約として扱った。

対象commitの[既存CI #299](https://github.com/shinp-dev/othello/actions/runs/34002199381)はGitHub上で`completed / success`だった。これは今回テストを実行したという意味でも、上記の未検証シナリオが通過したという意味でもない。

本番の適用済みSQL・RLS・Auth設定、外部サービス権限、端末挙動、負荷・競合時の挙動、依存関係の脆弱性照合、Edax全ソースのメモリ安全性の網羅監査は対象外。修正の採否や実装は別作業として扱う。

