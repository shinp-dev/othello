# Standard content packs

Standardのガチャ・図鑑で使うコンテンツは、アプリ本体のリリースと切り離した
versioned content packとして配信する。

## 基本方針

- ユーザーから見える図鑑は1冊にまとめる。
- 配信はカテゴリ/企画単位の複数packに分ける。
- packのversionは配信更新判定だけに使う。
- 取得状態にはpack versionを保存しない。
- 一度公開したcard idは別の意味へ使い回さない。
- 文章修正や画像差し替えは同じcard idのまま新しいpack versionで配信する。
- 別のカードにしたい場合は新しいcard idを発行する。
- packから消えたcard idは図鑑に表示されないが、端末の取得済みIDからは削除しない。
  将来同じidが復活した場合は取得済みとして復元される。

端末側のコレクション状態は概念的に次だけを持つ。

```text
obtainedCardIds = {
  "trivia.001",
  "book.004"
}
```

これによりpack更新時のコレクションmigrationを不要にする。

## 配信構造

```text
content/index.json
  -> trivia v12 -> trivia-v12.zip
  -> books  v5  -> books-v5.zip
  -> collab v2  -> collab-v2.zip
```

index entryは次を持つ。

```json
{
  "id": "books",
  "version": 5,
  "url": "https://...",
  "sizeBytes": 12345,
  "sha256": "<64 hex chars>"
}
```

packはZIPで、実行コードを含めない。

```text
manifest.json
cards.json
assets/
  ...
```

`manifest.json`:

```json
{
  "schemaVersion": 1,
  "id": "books",
  "version": 5
}
```

`cards.json`:

```json
{
  "schemaVersion": 1,
  "cards": [
    {
      "id": "book.001",
      "type": "book",
      "rarity": "rare",
      "title": "タイトル",
      "summary": "短い説明",
      "body": "任意の本文",
      "imagePath": "assets/book_001.webp",
      "seriesId": "books.basic",
      "tags": ["book"],
      "attributes": {
        "author": "著者名",
        "publisher": "出版社"
      },
      "sourceLabel": "出版社",
      "sourceUrl": "https://...",
      "externalUrl": "https://...",
      "sortOrder": 10
    }
  ]
}
```

card type v1:

- `trivia`
- `book`
- `person`
- `history`
- `collab`

rarity v1:

- `common`
- `rare`
- `special`

## 更新手順

1. HTTPSでindexを取得する。
2. 未導入の新versionだけZIPを一時領域へ取得する。
3. index記載のsizeとSHA-256を検証する。
4. ZIPをprivate staging directoryへ展開する。
5. path traversal、ファイル数、展開サイズ、許可拡張子、schemaを検証する。
6. manifestとindexのpack id/version一致を確認する。
7. cardが参照するassetの存在を確認する。
8. 全検証成功後だけpack directoryへ移動する。
9. active version pointerを一時ファイルから切り替える。

途中で失敗した場合、以前のactive packはそのまま使う。

versionの巻き戻しは受け付けない。緊急で以前の内容へ戻したい場合も、
以前の内容を新しいversion番号で再発行する。

## 図鑑

`StandardContentRepository` はactive packを横断して1つのsnapshotを作る。

同じcard idが複数のactive packに存在した場合は、どちらかを勝手に採用せず
format errorとしてfail closedする。

将来の図鑑UIはこのsnapshotを、

- 全件
- type別
- series別
- 未取得
- 取得済み

などで表示する。

## ガチャ

ガチャ抽選はサーバーRPCではなく、取得済みのcontent snapshotを使って端末内で行う。
抽選ロジックと演出はこの基盤とは分離する。

## 取得状態

v1では`StandardCollectionStore`が認証ユーザー単位の`Set<cardId>`を
端末内SharedPreferencesへ保存する。

クラウド同期や購入権利はこのStoreへ混ぜない。

- 図鑑取得状態: Local collection
- content本体: Versioned content pack
- 将来の有料pack所有権: Play Billing等のentitlement

という境界を維持する。

## 非対象

この基盤はリモートコード配信機構ではない。
ZIP内で許可するのはmanifest/cards JSONと画像・音声assetのみで、
APKのロジックや任意スクリプトを更新する用途には使わない。

StandardのUI、Advanced、対局ロジック、Edax経路にはこのPRでは接続しない。
