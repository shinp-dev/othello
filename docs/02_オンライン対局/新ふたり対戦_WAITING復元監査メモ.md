# 新ふたり対戦：WAITING復元の監査経緯と判断

- 記録日: 2026-09-30
- 対象: PR #120 / `codex/people-play-light-phase6-hardening`
- 確認コード: `e6489396731bd160d39c62f1786b92d4fd805fa6`
- 正本: [軽量採用設計](新ふたり対戦_DO_WebSocket_軽量採用設計.md) §18、§22、§28

本メモは監査で確認できた範囲と対応判断の記録であり、正本の仕様を変更しない。

## 指摘と確認した範囲

監査では「保存されたWAITINGの席に対応するsocketが復元時に存在しない場合、席が残る」という指摘を行った。

先の監査で、保存済みWAITING stateにseat Aを置き、`getWebSockets()`が空配列を返す状態をテスト用に構成したところ、RoomはWAITINGのままで、`getLobbyStatus()`も席を含むactive projectionを返した。

これは人工的に与えた状態に対する挙動の確認である。**通常のユーザー操作からその状態に到達する経路は実証していない。実Cloudflare上で、切断処理を経ずに席だけ残る現象も再現していない。**

コード根拠:

- `cloudflare-people-play/src/room.ts` のconstructorはattachment hintを照合するが、WAITINGのsocket欠落に対する席の回収処理は持たない。
- 同ファイルの`getLobbyStatus()`は、この状態を自動閉鎖しない。
- 通常のclose/errorは`handleSocketDeparture()`から`disconnectWaitingMember()`へ進み、該当席を空け、残存memberが0ならCLOSEDにする。

## 発生経路の区別

| ケース | 今回の判断 |
|---|---|
| 通常の退出、アプリ終了などでclose/errorが処理される | 既存の席解放・0人閉鎖経路がある |
| 圏外、機内モード、端末の強制終了 | 切断検知が遅れる問題と、復元時にsocketが存在しない問題を区別する。今回の席残留への到達は未実証 |
| 通常のHibernation | socketを維持する仕組みであり、休眠しただけでsocketが失われるとは扱わない |
| デプロイやruntime再起動 | 接続が終了することはあり得るが、close処理を経ずにWAITINGの席だけ残る一連の経路は未確認 |

Cloudflareの[DOライフサイクル資料](https://developers.cloudflare.com/durable-objects/concepts/durable-object-lifecycle/#shutdown-behavior)と[WebSocket資料](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)はプラットフォーム挙動の参考資料であり、このアプリで席残留が発生した証拠ではない。

## 既存の回収契機

- 元socketのclose/errorが後から有効なattachmentとともに処理されれば、既存の切断処理が働く。
- 別memberが入室し、そのmemberがWAITINGの最後の接続として退出すれば、0人閉鎖される。
- Lobby GETだけでは、この人工的なWAITING状態は回収されない。

これらはコード上の経路であり、現実の障害で必ず回収されるという保証ではない。

## 経緯と結論

当初の監査では、人工的な状態での回収不足を、通常利用で起こる不具合として強く評価していた。ユーザーから「現実的に起こり得るかを確認しないと、対策コストだけが増える」と指摘を受け、評価を訂正した。

**結論: 発生経路が未確認の異常復旧ケースとして保留する。本件だけを理由に公開を止めたり、防御処理・監視・追加プロトコルを実装したりしない。** 今回はコードを修正しない。発生しないと証明したという意味でもない。

再検討は、通常操作による再現手順、実環境で席残留を示す観測、または当該状態への到達を裏付ける具体的なプラットフォーム挙動が得られた場合に行う。その際に発生頻度・利用者への影響・既存の回収契機を確認し、対策コストに見合う最小修正を判断する。人工的な不整合状態を作れることだけを根拠に、修正対象を増やさない。
