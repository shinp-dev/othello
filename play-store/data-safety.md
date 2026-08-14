# Data Safety回答案

これはコード、Supabase migration、Cloudflare Worker、`docs/RESEARCH_DATA_DESIGN.md`から確認したドラフトです。Play Consoleへの最終回答は、Supabase/Cloudflareの実際のログ設定・保持期間と運営者の法的判断を確認してから行います。

| データ | collected | shared | purpose | required/optional | deletion / retention |
|---|---|---|---|---|---|
| メールアドレス | Yes（Email/Password Auth） | Supabase Auth / 認証基盤 | Account management | アカウント機能ではrequired。local matchのみoptional | Account deletionでAuth identityを削除。Auth側のバックアップ・ログ保持はOWNER確認 |
| ユーザーID | Yes | Supabase DB、match相手に必要な範囲 | Auth、matchmaking、records、rating | Online/recordsではrequired | private参照を削除。Research subjectとのlinkはunlink |
| 表示名 | Yes（プロフィール） | オンライン対局・公開プロフィールに必要な範囲 | Profile / gameplay | Onlineではrequired | 削除時に「退会済みユーザー」tombstoneへ匿名化 |
| オンライン対局情報 | Yes | 対局相手、Supabase/WebRTC signaling | Matchmaking、対局確定、rating | Onlineのみrequired | signaling/queue等はcleanup。確定共有棋譜は相手の記録保護のため残る場合あり |
| GameRecord / rating | Yes | 相手に関係する共有記録、Supabase | Records、rating、review | Onlineではrequired。localは端末のみ | user_game_records/rating/private historyを削除。共有GameRecordは条件により保持 |
| 研究参加・着手・局面 | 明示同意時のみYes | 個人を直接識別しない集計・研究処理 | Research | Optional opt-in | OFF後は新規captureと閲覧停止。accepted contributionはaccount unlink後も研究・再集計目的で保持する場合あり |
| 資格情報・証明画像 | 任意申請時のみYes | 運営者の審査Worker / private Storage | Credential verification | Optional | deletion workerでcredentialとStorage evidenceを削除 |
| Edax評価データ・book | 端末内のみ | No | Local review | Optional import | アプリ内で削除。アプリには同梱・自動配布しない |
| device / network / server logs | アプリコードで独自収集する実装なし。ただし基盤サービスの接続ログは可能性あり | Supabase/Cloudflare/WebRTC provider | security / operations | Service-dependent | 具体的な項目・期間はprovider設定をOWNER確認 |
| analytics / crash reports | No（SDK dependency/codeなし） | No | N/A | N/A | third-party analytics/crash SDKは現在なし |

## Security / declarations

- Supabase/Cloudflare APIはHTTPS。Data in transit encryptionはYesとして回答候補。ただしPlay Consoleの分類は最終確認する。
- Data at rest、providerのbackup、server access logの保持はコードだけでは確認不能。OWNER/各providerで確認する。
- 「shared」は第三者SDKへの広告共有ではなく、オンライン対局の相手やサービス処理に必要な範囲を意味する。Play Consoleの分類に合わせて入力する。
- 広告SDK・広告表示は現在なし。Ads declarationは `Contains ads: No` の候補。
- account creationがあるため、in-app deletionに加えてWeb deletion linkが必要。現状Webでの送信受付は未実装で、公開BLOCKER。
