# Othello Online MVP

責務分離を優先したAndroid向けオンラインオセロの基盤です。現在の実行可能なMVPは、Supabase設定なしで動く端末内2人対局です。ホームの「対局する」から8x8盤を開き、合法手をタップして遊べます。

## 開発環境

- Android Studio Koala以降
- JDK 17
- Android SDK 35
- Kotlin 2.2.10 / Compose Compiler plugin / Compose 1.6.8

## ビルドとテスト

```powershell
./gradlew test
./gradlew :app:assembleDebug
pwsh ./scripts/check-boundaries.ps1
```

Android Studioでルートを開いて同期し、`app` configurationを実行することもできます。リポジトリにはGradle Wrapperを含めています。

## 構成

詳細は [ARCHITECTURE.md](ARCHITECTURE.md) と [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) を参照してください。`core:game`はAndroid/Supabase/WebRTC/Rating/Edaxを参照しない純粋Kotlinです。`feature:match`は`analysis`へ依存せず、Reviewだけが`analysis:api`を参照します。

## Supabase

1. Supabase projectを作成します。
2. `supabase/migrations/202608090001_init.sql`をSupabase SQL EditorまたはSupabase CLIで適用します。
3. Android側へservice-role keyを置かず、AuthユーザーのJWTと公開anon keyだけをアプリ設定へ渡します。
4. `finalize_match`は参加者のみ実行でき、提出が一致しない場合は`DISPUTED`になります。Ratingの正式更新はこの後のサーバー側処理として追加します。

## Cloudflare Admin

`cloudflare-admin`は段級位申請の管理BFFです。service-role keyはWorker secretにだけ置き、`ADMIN_TOKEN`もWorker secretに置きます。ブラウザへservice-role keyを配布しません。

```powershell
cd cloudflare-admin
npm install
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler secret put ADMIN_TOKEN
npm run deploy
```

## オンライン対局の実装順

MVP後は `MatchTransport` にWebRTC DataChannel実装を追加し、Supabase RealtimeはSDP offer/answerの確立時だけ使用します。P2P接続後に着手や時計をSupabaseへ送信しません。Android実機2台での確認は、Auth設定、同一Supabase project、TURN/STUN設定、2台のqueue参加、DataChannel成立、双方の同一棋譜・hash確認、結果提出の順で行います。

## Edax / OSS

Edax JNIはまだバイナリを同梱していません。`analysis:api`の`AnalysisEngine`と`analysis:edax`の`LocalAnalysisEngine`が差し替え境界です。Edaxを追加する際は、Edaxの配布ライセンスとJNI/NDKビルド成果物の著作権表示をアプリのOSS画面へ追加してください。現時点の依存ライセンスはGradleのCompose/AndroidX/Kotlin標準ライセンスに従います。
