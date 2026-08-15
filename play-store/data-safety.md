# Data Safety回答案

これはコード、Supabase migration、Cloudflare Worker、`docs/RESEARCH_DATA_DESIGN.md`とGoogle Play公式の[Data safety定義](https://support.google.com/googleplay/android-developer/answer/10787469)から確認したドラフトです。Play Consoleへの最終回答は、Supabase/Cloudflareの実際のログ設定・保持期間と運営者の法的判断を確認してから行います。

| データ | collected | shared | purpose | required/optional | deletion / retention |
|---|---|---|---|---|---|
| メールアドレス | Yes（Email/Password Auth） | Supabase Auth / 認証基盤 | Account management | アカウント機能ではrequired。local matchのみoptional | Account deletionでAuth identityを削除。Auth側のバックアップ・ログ保持はOWNER確認 |
| ユーザーID | Yes | Supabase DB、match相手に必要な範囲 | Auth、matchmaking、records、rating | Online/recordsではrequired | private参照を削除。Research subjectとのlinkはunlink |
| 表示名・ニックネーム | No | No | N/A | N/A | 初回公開版には入力・公開プロフィール・収集経路なし。legacy DB列はクライアント非公開・更新不可 |
| オンライン対局情報・システム算出rating | Yes。Google分類候補は `App activity > Other actions`（gameplayを含む） | `No`回答候補。対局相手への成立時rating表示はユーザーが開始したオンライン対局で合理的に期待される転送、Supabase/Cloudflareはservice providerとして処理。ただし最終回答はPlay Console文言でOWNER確認 | Matchmaking、対局確定、rating | Onlineのみrequired | signaling/queue等はcleanup。ratingはaccountに紐づき削除対象。確定共有棋譜は相手の記録保護のため残る場合あり |
| GameRecord / rating | Yes | 相手に関係する共有記録、Supabase | Records、rating、review | Onlineではrequired。localは端末のみ | user_game_records/rating/private historyを削除。共有GameRecordは条件により保持 |
| 研究参加・着手・局面 | 明示同意時のみYes | 個人を直接識別しない集計・研究処理 | Research | Optional opt-in | OFF後は新規captureと閲覧停止。accepted contributionはaccount unlink後も研究・再集計目的で保持する場合あり |
| 連盟段級位・証明画像 | No（初回公開版） | No | N/A | N/A | Android UI、通常クライアント権限、Storage upload policyを閉鎖。legacy DB/削除処理は互換性のため残す |
| Edax評価データ・book | 端末内のみ | No | Local review | Optional import | アプリ内で削除。アプリには同梱・自動配布しない |
| device / network / server logs | アプリコードで独自収集する実装なし。ただし基盤サービスの接続ログは可能性あり | Supabase/Cloudflare/WebRTC provider | security / operations | Service-dependent | 具体的な項目・期間はprovider設定をOWNER確認 |
| analytics / crash reports | No（SDK dependency/codeなし） | No | N/A | N/A | third-party analytics/crash SDKは現在なし |

## Security / declarations

- Supabase/Cloudflare APIはHTTPS。Data in transit encryptionはYesとして回答候補。ただしPlay Consoleの分類は最終確認する。
- Data at rest、providerのbackup、server access logの保持はコードだけでは確認不能。OWNER/各providerで確認する。
- 「shared」は第三者SDKへの広告共有ではなく、オンライン対局の相手やサービス処理に必要な範囲を意味する。Play Consoleの分類に合わせて入力する。
- Googleの定義では`Name`にnicknameが含まれ、`Other user-generated content`にはbio・note・自由回答等が含まれる。初回公開版にはいずれも存在しない。
- ratingはユーザー入力ではなく、account-linkedなgameplayからサーバーが算出する値。対局相手には成立時snapshotだけを表示し、名前・メール・UUIDはUIへ表示しない。
- 広告SDK・広告表示は現在なし。Ads declarationは `Contains ads: No` の候補。
- account creationがあるため、in-app deletionに加えてWeb deletion linkが必要。LPには既存Authで本人確認する受付を実装し、`https://chanriva.shinp-studio.com/account-deletion` で本番稼働を確認済み。OWNER実機E2Eでは受付から約4分54秒で `COMPLETED` となり、Auth identity、個人DB参照、証明Storageの削除と共有棋譜の匿名化保持を確認した。Research参加履歴を持つ専用テストアカウントでのunlink/retention E2Eは未実施。
