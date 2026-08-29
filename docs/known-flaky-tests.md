# Known Flaky Tests

この文書は、同一コードでもGitHub Actionsの実行ごとに成功・失敗が変動したテストを記録し、Actionsの履歴だけに依存せず、再発状況と対応方針を追跡するための台帳です。

## 運用ルール

- 同一コードで成功・失敗が変動したテストをFlaky Test候補として記録する。
- アプリ本体の不具合と直ちに断定せず、観測された事実と推定原因を分けて記載する。
- GitHub Actionsのrun番号、commit SHA、対象テスト、失敗状況、再実行結果を記録する。
- 再発した場合は、同じエントリの`Last observed`、`CI / Run`、再発状況を追記する。
- 同じテストが複数回再発する、またはCI・リリースを継続的に妨げる場合はGitHub Issueを作成し、`Issue Opened`へ変更して恒久修正対象に昇格する。
- 恒久修正後も記録は削除せず、`Resolved`として修正Issue、PR、commitと確認結果を記録する。
- CIを単純にリトライして成功させることは、恒久対策とは扱わない。

## Status

- `Monitoring`: 初回または限定的な発生を記録し、再発を監視している。
- `Issue Opened`: 再発または継続的な影響を確認し、恒久修正用Issueを作成している。
- `Resolved`: 恒久修正を適用し、そのIssue、PR、commit、確認結果を記録している。

## Entries

### FT-001 — Lost move transcript synchronization

**Status:** `Monitoring`

**Test:** `OnlineMatchControllerTest.lostMoveCommandConvergesThroughOneTurnTranscriptSync`

**Module:** `:feature:match`

**First observed:** 2026-08-29（GitHub Actions CI #251 attempt 1）

**Last observed:** 2026-08-29（初回事例。以後の再発は未確認）

**CI / Run:** [CI #251 attempt 1（失敗）](https://github.com/shinp-dev/othello/actions/runs/33253840671/attempts/1)、[attempt 2（成功）](https://github.com/shinp-dev/othello/actions/runs/33253840671/attempts/2)

**Commit:** `f9c28af7dc1284b98f4ca342cb9932dff5ca01e5`

**Symptoms:**

- `./gradlew test`実行中に対象テストのみ`AssertionError`で失敗した。
- `:feature:match:test`では全67テスト中1件のみ失敗した。
- PR #41で変更したlanding-page CIおよびrelease audit関連コードとは直接関係しない既存テストだった。

**Reproduction / rerun result:**

- 同一commitのpush CIは成功した。
- 対象テストをローカルでキャッシュなしに再実行し、成功した。
- コード変更なしでCI #251を再実行したattempt 2では、対象テストを含む全Gradle testが成功した。
- attempt 2ではlint、assembleDebug、Supabase関連を含むCI全体も成功した。

**Suspected cause:**

対象テストが以下の実時間ベースの短いタイミングと非同期処理に依存しており、CI runnerの負荷やCoroutineのスケジューリング順序によって結果が変動した可能性がある。

- ACK待機: 5ms
- 同期タイムアウト: 100ms
- 受信処理: 別Coroutineへ非同期dispatch

これは観測結果からの推定であり、確定原因ではない。

**Current action:**

現時点では製品コード・テストコードとも変更せず、再発を監視する。同じテストが再発した場合はIssueを作成し、実時間5ms／100msへの依存をやめて、Coroutineのテストスケジューラ（`runTest`、`advanceTimeBy`、`advanceUntilIdle`など）で決定的に制御できるテストへの変更を検討する。

**Related Issue / PR:** Issueなし（再発時に作成予定）、[PR #41](https://github.com/shinp-dev/othello/pull/41)

**Resolution:** 未解決。`Monitoring`として再発を追跡する。CIの再実行成功は恒久対策とは扱わない。

## Entry template

新しい事例は、以下の項目を維持して追加する。

```markdown
### FT-NNN — Short description

**Status:** `Monitoring` / `Issue Opened` / `Resolved`

**Test:** `TestClass.testMethod`

**Module:** `:module:name`

**First observed:** YYYY-MM-DD

**Last observed:** YYYY-MM-DD

**CI / Run:** CI番号、attempt、URL

**Commit:** `full commit SHA`

**Symptoms:** 観測された事実

**Reproduction / rerun result:** 再現確認と再実行結果

**Suspected cause:** 推定原因。未確定の場合はその旨を明記

**Current action:** 監視、Issue化条件、対応方針

**Related Issue / PR:** Issue、PR、または未作成

**Resolution:** 未解決、または恒久修正のPR・commit・確認結果
```
