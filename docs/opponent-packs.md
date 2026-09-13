# Standard Opponent Packs

Standard AIの対戦企画は、外部配信可能なOpponentPackとPlayerで定義する。
実装はアプリの `OpponentPack.kt`、読み込みは `OpponentPackFormat.kt`、導入は
`OpponentPackRepository.kt`。配信の正本は `shinp-dev/chanriva-content/opponents/`。
カードのindex/schema/storageとは独立している。

## モデルと責務

- OpponentPack: 安定ID、単調増加version、多言語タイトル/説明、バナー、Home表示指定、
  公開期間（任意）、Player一覧を持つ。1 Packが1つの対戦企画。
- Player: Pack内で安定したID、多言語名、表示順、解放に必要なPlayer ID、
  AI設定、登場/勝利/敗北画像、解放時の演出種別を持つ。
- 進捗: SupabaseユーザーID + Pack ID + Player ID。表示順・versionはキーに含めない。
  `requires`の全員を撃破すると解放される。有向非巡回グラフを検証し、複数の入口や分岐に対応。
  解放済み状態も保存するので、更新時に解放条件が変わっても再ロックしない。
- `StandardAiEngine`とEdax候補評価は再利用する。外部JSONは許可済みのweighted/best、
  adaptive think-time、adaptive tension policyへ変換するだけで、コードや式を評価しない。
  従来の`StandardAiLevel`とどうぶつ用の本番プリセットは廃止した。

Homeは対応済みのセクション`FEATURED`（ガチャより前）と`CHALLENGES`（ガチャ/図鑑より後）へ
Packを配置する。各セクション内は`home.order`、同順位はID順。
表示は`HERO`または`COMPACT`。任意座標・HTML・UIコードは受け付けない。
複数Packはスクロールして選べる。Playerの順序は強さを意味せず、解放グラフと独立する。

`availableFrom`（含む）/`availableUntil`（含まない）はISO-8601 UTC/offset日時。
端末時計で入場・表示時に評価するため、課金や不正防止の権限制御には使わない。
期間が終わっても保存済み進捗は削除しない。

## 配信構成

```text
chanriva-content/
  opponents/
    index.json
    schema/{index,manifest}.schema.json
    packs/animal/manifest.json
    packs/animal/assets/*.{png,webp,jpg,jpeg}
    dist/animal-v1.zip
  scripts/opponents.py
```

indexはschemaVersion=1とpacks配列を持つ。各descriptorは
`id/version/url/sizeBytes/sha256`を持つ。ZIPには`manifest.json`と、そこから参照される
`assets/`直下の画像だけを含める。画像の名前は英小文字・数字・ハイフン・アンダースコア。
JSONの全文例と正式なフィールド定義は配信リポジトリのanimal manifest/schemaを参照。

多言語テキストは`{"en":"Chick","ja":"ひよこ"}`の形式。enは必須で、未対応言語はenへfallback。
勝敗画像はPlayer側の視点なので、人間が勝ったら`loseImage`、負けたら`winImage`を表示する。
引き分けは`winImage`を使う。表示テキストはアプリ側の汎用結果文言。
`unlockCelebration=MILESTONE`は重要な相手の解放を強調する汎用演出。

AIの`personality`は`NATURAL`（重み付き候補選択）と`SERIOUS`（最善候補）。
`moves`の序盤/中盤/終盤の重み、ミス許容量、フェーズ境界で性格を調整する。
SERIOUSでもmovesは検証するが、選択は常に最善候補。
`think`と`tension`は既存のadaptive profileと同じ意味。許容範囲はschemaとAndroidのparserに固定する。
Edaxは1～4、重みは有限の0～1（先頭は正）、候補数1～16、評価損失0～64、
各思考時間0～5000ms。任意のネイティブ設定・book・evalパスは指定できない。

## 更新・復旧・Bootstrap

Standard入場時にCard Pack → Opponent Pack → Standard evalをI/O dispatcherで準備する。
準備完了時のsnapshotをHomeと対戦画面へ渡し、画面は通信・インストールを行わない。
evalは導入済みなら再取得せず、evalだけ失敗してもHome/カード/ガチャ等には入れる。
モード退出のキャンセルは伝播し、完了前のsnapshotを公開しない。
Advanced側のeval、book、設定、エンジンには変更しない。

保存先は`files/standard-opponents/v1/`。ダウンロードと展開は一時領域で行い、
全Packの検証後に`active.json`を一度だけ置き換える。version/hash別のディレクトリは
不変で、表示中のsnapshotが指すファイルを書き換えない。後続Packの更新に失敗した場合も
以前のactive catalogを保持する。失敗した一時ファイルは削除する。
同一versionで内容変更、version巻き戻し、未知フィールド、未知schemaを拒否する。

制限はindex 128KiB/32 Pack、ZIP 32MiB、展開64MiB、256 entry、manifest 1MiB、
画像8MiB/2048×2048、64 Player。HTTPS、SHA-256、実バイト数、IDの一致、
ZIPパス・重複・参照画像・解放DAG・画像デコードを検証する。
配信validatorは加えてソース/ZIPの一致と画像形式を検査する。

オフライン初回用にアプリへ`assets/opponents/index.json`と`animal-v1.zip`を同梱する。
有効な導入済みPackを優先し、未導入/破損時は同梱版を復旧用の独立領域へ展開する。
季節限定catalogがオフライン中に期限切れになった場合も同梱版の入口を表示する。
初回オフラインではeval未導入によりAI対局は未準備になるが、対戦Packの入口は残る。
古い不変versionは現時点では自動削除しない。将来GCを入れる際は表示中snapshotの参照を考慮する。

## 進捗移行

既存`standard-ai-progress`のユーザー別`highest_unlocked/cleared`を、animal初回読込時だけ
固定IDへ変換する。対応はchick/rabbit/koala/elephant/wild-chick/wild-rabbit/wild-koala/wild-elephant。
新しい進捗は`<user>.pack.<packId>.v2`へ1つのJSON値で保存し、旧キーは消さない。
削除されたPlayer IDも保持するので同ID復活時に進捗が戻る。Undo勝利や未解放相手にはクリアを付与しない。
イントロ既読もPack/Playerで識別し、animalのみ旧番号キーを参照して継承する。
カード取得状態・無料ガチャ回数・棋譜には触れない。

## 新規Packの追加手順

1. 配信リポジトリで`opponents/packs/<new-id>/manifest.json`と画像を用意する。
   animalの構造を参考にし、新企画は新Pack IDにする。Player IDは公開後に別人へ使い回さない。
2. schemaに沿って表示セクション、style、order、Playerと解放条件、AI設定を記述する。
3. `pip install -r scripts/opponent-requirements.txt`を実行する。
4. `python scripts/opponents.py build`、`python scripts/opponents.py validate`、
   `python -m unittest discover -s scripts -p test_opponents.py`を実行する。
5. source、生成ZIP、indexをPRに含める。既存ZIPを編集せず、変更時はversionを上げる。
6. mainに公開後、対応アプリの次回Standard入場から反映される。カード配信はそのまま。

アプリ同梱版を更新する場合は、配信repoの生成indexと指定ZIPを`app/src/main/assets/opponents/`へ
コピーし、`python tools/check_opponent_baseline.py --content-root <chanriva-content checkout>`で照合する。
同梱indexは実際に含めるbaseline ZIPだけを列挙する。通常の追加配信にアプリ更新は不要。

検証は`./gradlew test lint assembleDebug :app:compileDebugAndroidTestKotlin`と既存境界/SQLチェック。
UIテストには複数PackのHome表示・選択、Player選択、登場/結果画像を含む。
