# Data Safety回答案

これはコード、Supabase migration、Cloudflare Worker、`docs/RESEARCH_DATA_DESIGN.md`とGoogle Play公式の[Data safety定義](https://support.google.com/googleplay/android-developer/answer/10787469)から確認したドラフトです。Play Consoleへの最終回答は、Supabase/Cloudflareの実際のログ設定・保持期間と運営者の法的判断を確認してから行います。

| データ | collected | shared | purpose | required/optional | deletion / retention |
|---|---|---|---|---|---|
| メールアドレス | Yes（Email/Password Auth） | `No`回答候補。Supabase Authは運営者の指示で処理するサービス提供者 | Account management | アカウント機能ではrequired。local matchのみoptional | Account deletionでAuth identityを削除。Auth側のバックアップ・ログ保持はOWNER確認 |
| アカウント利用時刻 | Yes（`last_active_at`。アプリ起動・ログイン成功時に最大1日1回更新） | `No`回答候補。Supabaseは運営者の指示で処理するサービス提供者 | Account lifecycle / security | アカウント機能ではrequired | 最終利用から365日経過した確認済みアカウントは既存削除処理の対象 |
| ユーザーID | Yes（Auth・対局制御の内部識別子） | `No`回答候補。Supabase/Cloudflareは運営者の指示で処理するサービス提供者。対局成立・WebRTC制御に内部IDを使うが、相手向けUIへ表示しない | Auth、matchmaking、records、rating | Online/recordsではrequired | private参照を削除。Research subjectとのlinkはunlink |
| 表示名・ニックネーム | No | No | N/A | N/A | 初回公開版には入力・公開プロフィール・DB列・収集経路なし |
| オンライン対局情報・システム算出rating | Yes。Google分類候補は `App activity > Other actions`（gameplayを含む） | `No`回答候補。Supabase/Cloudflareは運営者の指示で処理するサービス提供者。対局相手への成立時rating表示は、ユーザーが開始したオンライン対局に伴うアプリ内表示として扱う候補。最終回答はPlay Consoleの選択肢と実際の運用をOWNER確認 | Matchmaking、対局確定、rating | Onlineのみrequired | signaling/queue等はcleanup。ratingはaccountに紐づき削除対象。確定共有棋譜は相手の記録保護のため残る場合あり |
| GameRecord / rating | Yes | `No`回答候補。Supabaseはサービス提供者。対局相手への共有記録・成立時rating表示は、ユーザーが開始したオンライン対局に伴うアプリ内表示としてOWNER確認 | Records、rating、review | Onlineではrequired。localは端末のみ | user_game_records/rating/private historyを削除。共有GameRecordは条件により保持 |
| 研究参加・着手・局面 | 明示同意時のみYes | `No`回答候補。運営者が同意に基づき研究処理を行う。個人を直接識別しない集計へ利用 | Research | Optional opt-in | OFF後は新規captureと閲覧停止。accepted contributionはaccount unlink後も研究・再集計目的で保持する場合あり |
| 連盟段級位・証明画像 | No（初回公開版） | No | N/A | N/A | Android UI、DB table/RPC、管理Worker経路を削除。旧Storage bucketも正式なStorage操作で削除 |
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
- account creationがあるため、in-app deletionに加えてWeb deletion linkが必要。LPにはEmail/Password受付と、パスワードを使えない場合のSupabase Auth確認メール受付を実装・本番反映済み。確認メール経路のRedirect URL登録とE2EはOWNER ACTION REQUIRED。既存のEmail/Password経路は`https://chanriva.shinp-studio.com/account-deletion`で本番稼働を確認済み。

この資料の「No回答候補」はPlay Consoleへの提出値ではありません。Google公式定義では、アプリから外部へ送信する情報は収集に含まれ、サービス提供者への委託処理は通常「共有」から除外されます。一方、対局相手へのアプリ内表示をどの回答へ対応付けるか、実際のSupabase/Cloudflareログ・保持設定を含む最終回答はOWNERが確定してください。
