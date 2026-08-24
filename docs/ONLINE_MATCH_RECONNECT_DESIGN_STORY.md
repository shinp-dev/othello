# Online Match Reconnect — Design Evolution and Failure Recovery

初めてオンライン対局の設計を読む場合は、先に[Online Match Connection — Design Story](ONLINE_MATCH_CONNECTION_DESIGN_STORY.md)を読む。そちらがP2PとServerの責任分界、通常のepoch 0 signaling、DataChannel、start ACK、`ACTIVE` / `PLAYING`までを説明し、この資料は開始済み対局の切断後を引き継ぐ。通常接続を理解している場合は、この資料から読んでもよい。

## まずこれだけ分かれば読める

再接続で最も大切なのは、**「端末同士の通信がつながったこと」と「対局を安全に再開できること」は別**だという点である。

通信路が復旧しただけでは、相手端末も同じ再接続を認識しているか、サーバーが復旧を確認しているか、両端末の棋譜や盤面が一致しているかは分からない。そのため、このアプリは次の順序で対局を再開する。

```text
端末同士の通信が復旧
  -> 双方が同じ再接続番号を認識
  -> サーバーで双方の確認が取れる
  -> 棋譜と盤面状態を同期
  -> 初めて対局を再開
```

この「再接続番号」を **epoch**、「端末からサーバーへの確認通知」を **ACK** と呼ぶ。DataChannelが`OPEN`になっただけでは`PLAYING`へ戻さず、サーバー確認と同期を終えてから戻す、というのが現在設計の骨格である。

## この資料で使う用語

専門用語は削らず、まず平易な意味を示してから正式名を使う。本文でも重要な用語の初出には短い日本語説明を添える。

| 用語 | この資料での意味 |
|---|---|
| **epoch** | 同じmatch内で、どの接続世代を扱っているかを識別する番号。epoch 0は初回接続、epoch 1は1回目、epoch 2は2回目、epoch 3は3回目の再接続である。Reconnectの文脈ではepoch 1..3が再接続回数にも対応し、現在のsystemでは0..3だけを使う。 |
| **current epoch** | その時点でサーバーが正式に扱っている現在のepoch。古い端末内状態ではなく、サーバーDBの`negotiation_epoch`が基準になる。 |
| **authoritative** | 「サーバーが持つ正式な状態」という意味。端末側の推測、表示、callbackより、server DB上の状態を優先する。 |
| **ACK** | 受領確認（acknowledgement）。この資料では主に、「このepochのDataChannelが使えることを端末が確認した」というサーバーへの通知を指す。 |
| **start ACK** | 初回接続または再接続のDataChannelを利用できると、端末が対象epoch付きでserverへ送るACK。着手ごとのpeer ACKとは別である。 |
| **bilateral** | 両者・双方という意味。bilateral ACKは「両端末のACKが揃っている」状態である。 |
| **adopt** | **サーバーが示した再接続epochを、自分の端末も現在参加する再接続として受け入れること。** 例えばサーバーがepoch 2で再接続中なら、端末Bは自分で切断を検知していなくても、epoch 2が正式な再接続だと受け入れて処理に参加する。これがadoptである。 |
| **passive peer** | 自分では切断を検知していないが、相手端末が始めた再接続へ参加する端末。本資料では初出後、「相手端末」またはpassive peerと書く。 |
| **completed epoch** | 双方のACKだけでなく、棋譜と盤面状態の同期まで完了した再接続epoch。コードでは主に`completedEpoch`で表す。 |
| **fresh disconnect** | 以前のretryや古いcallbackではなく、新たに発生した本当の通信切断。コードでは、この切断に次epochが必要かを`freshEpochRequired`で表す。 |
| **stale request** | 送信時点では正しかったが、到着時には古くなっているrequest。例えばepoch 2向けACKが、サーバーがepoch 3になった後に届く場合である。 |
| **reconcile / reconciliation** | 端末が持つ状態とサーバーの正式状態を照合し、正しい状態へ合わせ直すこと。本資料では「照合」「状態照合」とも書く。 |
| **idempotent** | 同じ処理が重複して実行されても、結果が壊れたり二重処理になったりしない性質。日本語では「冪等」ともいう。 |
| **bounded** | retry、待機、検索を無制限に続けず、回数、時間、件数などの範囲を決めて行うこと。 |
| **transport** | 実際にデータを運ぶ通信路。本資料では主にWebRTC DataChannelと、その接続状態を指す。 |
| **signaling** | 一般には、WebRTCのP2P通信路を作るためにOFFER / ANSWER等の接続情報を交換する処理。current implementationはICE candidateを1件ずつpublishせず、ICE gathering後のcandidateをOFFER / ANSWERのSDPへまとめるnon-trickle ICEである。 |
| **OFFER / ANSWER** | OFFERは接続条件の提案、ANSWERはその応答。current roleではBLACKがOFFER、WHITEがANSWERを担当する。 |
| **SDP** | OFFER / ANSWERに含まれる接続条件の表現。current implementationでは収集済みICE candidateもここへ含まれる。 |
| **ICE / non-trickle ICE** | ICEは2台の端末間で通信可能なrouteを探す仕組み。non-trickle ICEはcandidateを個別送信せず、候補収集後のSDPへまとめる方式である。 |
| **debounce** | 一瞬の通信揺れを本当の切断として扱わないため、少し待ってから切断と判定する処理。現在のclient pathでは1.5秒待つ。 |
| **transcript** | この対局で行われた着手の記録。このアプリではほぼ「棋譜」と考えてよい。 |
| **canonical** | 同じ内容が必ず同じ表現になるよう正規化された、サーバーと端末が共通に解釈できる形式。`canonical transcript`は正規形式の棋譜を指す。 |
| **ply** | 片方のプレイヤーが1回着手する単位。オセロでは通常、1手進むごとにplyが1増える。 |
| **state hash** | 盤面などの状態から計算する照合用の値。同じ棋譜を再生した結果が同じ状態かを検査するために使う。 |
| **row lock / FOR UPDATE** | 同じ対局データを複数の処理が同時に変更しないよう、DB側で一方ずつ順番に処理させる仕組み。`FOR UPDATE`はこの行ロックを取得するSQL表現である。 |
| **invariant** | 処理順や通信状況が変わっても、必ず守らなければならない設計上の約束。 |
| **failure model** | 「どんな壊れ方を想定して設計するか」という障害パターン。例は片側だけの切断観測やHTTP responseだけの喪失である。 |
| **mutation** | サーバーやDBの状態を書き換える操作。状態を読むだけの処理と区別するときに使う。 |
| **participant** | server上でそのmatchへ正式に割り当てられた参加者。自分の端末のuserと相手端末のuserの2者を指す。 |
| **role / disc** | 盤上のBLACK / WHITE。signalingでもBLACKがOFFER、WHITEがRESUME / ANSWERを担当するrole境界になる。 |
| **generation** | どの世代の再接続処理かを区別する概念。本資料では原則としてepochと表現し、一般原則を述べる箇所だけgenerationも使う。 |
| **liveness** | 処理がいつまでも止まらず、成功または安全な終了へ進める性質。再接続できない場合に`EXPIRED`へ収束できることも含む。 |
| **fencing** | 古いrequestが新しい状態を書き換えないよう、対象epochを照合して境界を設けること。expected epochがその識別札になる。 |
| **expected epoch** | ACKやresume requestが「どのepochを対象にしているか」をserverへ伝える番号。stale requestをcurrent epochへ誤適用しないために使う。 |
| **planner** | サーバー状態と端末状態を読み、「同期する」「現在epochでsignalingする」「新epochを始める」など次の行動を選ぶ判断関数。現在は`planReleaseRenegotiation()`を指す。 |
| **force retry** | 通常の一時復旧なら処理を見送る条件を越えて、サーバーとの状態照合をもう一度試すretry。完了済みepochを破棄して新epochを必ず作る命令ではない。 |
| **client state / server state** | client stateは1台のAndroid端末のsession状態、server stateは`matches.release_status`等の正式な永続状態。`PLAYING`はclient state、`ACTIVE`はserver stateであり、同じstate名ではない。 |

## 1. この資料について

この資料は、現在のRPCやclassを網羅的に列挙するreferenceではない。オンライン対戦の再接続が、なぜ単純な通信路の復旧（transport recovery）ではなく、サーバーが持つ正式状態（server authority）と再接続epochを明示した設計になったのかを残すdesign storyである。

この資料の主対象は、epoch 0の双方start ACKによってserverが`ACTIVE`になり、通常経路ではclientも`PLAYING`へ入った後のrecoveryである。ACK response lossやprocess recoveryではclientがまだ`P2P_CONNECTED`に見えることもあるため、protocol上の境界はclient表示だけでなくserverの正式状態も基準にする。serverがまだ`MATCHED / epoch 0`の初回開始前は次epochを作らず、同じepoch 0のbounded retry、または`ABANDONED` / `MATCH_START_TIMEOUT`によるunrated `EXPIRED`へ進む。その境界は[Connection Design Storyの第19章](ONLINE_MATCH_CONNECTION_DESIGN_STORY.md#19-通常系からreconnectへ)に記載する。

- Baseline: `release-hardening` at `6b914f5acfc0cc9f0182ec2ab78ae687d8e34d22`
- Main implementation: [`WebRtcMatchCoordinator.kt`](../app/src/main/kotlin/com/example/othello/WebRtcMatchCoordinator.kt), [`OnlineMatchController.kt`](../feature/match/src/main/kotlin/com/example/othello/match/OnlineMatchController.kt)
- Client/server contract: [`OnlineMatchContracts.kt`](../feature/match/src/main/kotlin/com/example/othello/match/OnlineMatchContracts.kt), [`SupabaseContracts.kt`](../data/supabase/src/main/kotlin/com/example/othello/data/supabase/SupabaseContracts.kt)
- Server authority: [`202608250030_release_match_hardening.sql`](../supabase/migrations/202608250030_release_match_hardening.sql)

説明の基準は上記baselineのactual codeである。途中のcommitに存在した挙動は、想定する障害パターン（failure model）を説明するために扱い、現在仕様と混同しない。設計の変化は、commit messageの順番ではなく、次のような「不足していた情報を何で補ったか」という流れとして読む。

| Commit | 閉じようとしたfailure modelと設計上の役割 |
|---|---|
| `d30af8d38c7a06a71ff84273eac20f4f41d8f87b` — `Harden reconnect and recovery abuse boundaries` | reconnectを無制限な端末retryにせず、サーバー管理のepoch 0..3、role/epochで隔離したsignaling、回数と時間に上限のある復旧として定義した |
| `f19b5b294f69637157fe0c63f5d30afb6d5443e1` — `Harden legacy result and recovery boundaries` | 一時的な`DISCONNECTED`を除外するdebounceと、再交渉前のserver state read／plannerを導入した |
| `e76b605fb87e9a318dfda42eb60dd73fcb7e28f9` — `Close reconnect race and schedule legacy cleanup` | 古い`ACTIVE`を読んだ処理とdisconnect reportの競合を、同じDB行を順番に更新してepochが1回だけ増える状態へ収束させた |
| `69cb78c8c0722c4620de5172876a385d4683e499` — `Make reconnect recovery negotiation epoch-aware` | 端末の`RECONNECTING`という粗い状態を分解し、passive peer、ACK response loss、古いepochのrequestを扱えるepoch-aware stateとexpected-epoch RPCへ移行した |
| `6b914f5acfc0cc9f0182ec2ab78ae687d8e34d22` — `Prevent completed reconnect retries from spending epochs` | 完了済みepochの後から発火するforce retryと、本当に新しいdisconnectを区別した |

## 2. 背景

CHANRIVAの対局中の着手、時計、棋譜（transcript）の同期は、端末同士を直接つなぐWebRTC DataChannelで進む。この実際にデータを運ぶ通信路をtransportと呼ぶ。一方、Supabase PostgreSQLはmatchmaking、WebRTC接続情報の交換（signaling）の許可、match lifecycle、start ACK、result authority、Rating、GameRecord、Research起点となる確定データを担当する。

この責務分担では、自分の端末のWebRTCが`OPEN`へ戻ったことと、サーバー上で対局の再開が確定したことは同じではない。片側だけが新しいDataChannelを見ていても、相手端末が同じepochへ参加したとは限らない。また、RPC responseが届かなかったことだけからDB transactionの成否は判断できない。

したがってreconnectで守る対象は、画面上の「接続したように見える状態」だけではない。対局参加者である両端末（participants）が同じ再接続epochを共有し、そのepochのDataChannelを双方がACKし、棋譜を同期してから対局へ戻る必要がある。これを満たせない場合は、勝者を推測してrated resultを作らず、期限と回数に上限のある`EXPIRED`へ収束させる。

## 3. 初期モデル

初期のreconnectは、概念的には次のモデルだった。

```text
自分の端末で DISCONNECTED
  -> RECONNECTING
  -> renegotiation
  -> DataChannel OPEN
  -> PLAYING
```

これはMVPとして不合理な設計ではない。両端末がほぼ同時に切断を認識し、RPCが明確に成功し、callbackが順番どおり届く通常ケースには十分だった。

しかし、P2Pでは両端末の観測が同じになるとは限らない。Android callback、WebRTC state、signaling、HTTP response、PostgreSQL transactionは別々に進む。単一の`RECONNECTING`だけでは、少なくとも次を区別できなかった。

- 自分の端末が通信切断を観測して新しいreconnectを要求しているのか
- 相手端末が始め、サーバーが正式状態として示したepochへ参加しているだけなのか
- ACK requestをまだ送っていないのか、送ったがresponseだけ失ったのか
- サーバーではACKが片側だけなのか、双方完了して`ACTIVE`なのか
- 同じepochを現在交渉中なのか、すでに同期まで完了したのか
- retryが現在の障害に対するものか、以前予約された遅延jobなのか

現在の複雑さは、この不足していた情報を端末状態とserver contractへ明示した結果である。

### Reconnect周辺の状態遷移

次の図は、現在の`MatchStatus`のうちreconnectを理解するために必要な状態だけを抜き出したものである。処理手順ではなく、「何が起きると、どの状態へ移るか」を示す。

```mermaid
stateDiagram-v2
    [*] --> P2P_CONNECTED: P2P接続を準備
    P2P_CONNECTED --> P2P_CONNECTED: DataChannel OPEN
    P2P_CONNECTED --> PLAYING: 初回epochの双方ACKをサーバーで確認
    P2P_CONNECTED --> DISCONNECTED: 初回開始前の持続切断<br/>epochは増やさない

    PLAYING --> MOVE_CONFIRMING: 着手を送信
    MOVE_CONFIRMING --> PLAYING: 着手ACKを受信
    PLAYING --> SYNCHRONIZING: 状態同期が必要
    MOVE_CONFIRMING --> SYNCHRONIZING: 状態同期が必要

    PLAYING --> RECONNECTING: DISCONNECTEDがdebounceを超える
    MOVE_CONFIRMING --> RECONNECTING: 通信切断
    SYNCHRONIZING --> RECONNECTING: 通信切断または同期失敗

    RECONNECTING --> SYNCHRONIZING: DataChannel OPEN + サーバー双方ACK
    SYNCHRONIZING --> PLAYING: 棋譜・盤面状態が一致
    RECONNECTING --> EXPIRED: 復旧期限またはbudget exhaustion
```

特に重要なのは、`RECONNECTING`から`PLAYING`への直接の矢印がないことである。DataChannelが`OPEN`になっても、サーバー上の双方ACKと棋譜／盤面同期を経由しなければ対局再開にはならない。

`P2P_CONNECTED`はactual `MatchStatus`名だが、名前だけでDataChannel OPENやserver双方ACK済みを意味しない。serverがまだ`MATCHED`のまま初回接続に失敗した場合は、図の`DISCONNECTED`へ進む初回接続失敗であり、epochを増やす`resume_match_v2`は呼ばない。Controllerが`MatchStatus.RECONNECTING`へ直接遷移するのは、開始済み対局の`PLAYING` / `MOVE_CONFIRMING` / `SYNCHRONIZING`からのrecoveryである。ただしACK response lossやprocess recoveryではclient stateが`P2P_CONNECTED`でもserverがすでに`ACTIVE` / `RECONNECTING`の場合があり、Coordinatorはそのserver stateを基準にReconnect処理を選ぶ。

## 4. Failure 1 — 片側だけが切断を観測する

WebRTCの切断観測は両端末で同時とは限らない。例えば端末Aだけが`DISCONNECTED`を観測し、1.5秒のdebounce後のdisconnect reportによってサーバーが`RECONNECTING / epoch 1`へ進んでも、端末Bは旧DataChannel上で`PLAYING`のままということがある。

端末Bはepoch 1の`OFFER`または`RESUME`を受け取り、新しいPeerConnection/DataChannelを準備できる。しかし以前のモデルでは、「再接続へ参加する端末であること」が自端末の`DISCONNECTED`から導かれていた。端末Bは切断を観測していないためControllerがreconnect recoveryへ入らず、すでに対局開始済みであることを理由にDataChannel `OPEN`後のstart ACK pathへ入れなかった。

その結果、通信路自体は復旧しても、現在epochについて両端末のACK（bilateral ACK）が成立しない。サーバーは`RECONNECTING`から`ACTIVE`へ戻せず、期限後はrated winnerを作らない`EXPIRED`へ進む。この障害から、「切断を申告したこと」と「サーバーが正式に開始したreconnect epochへ参加すること」を同じbooleanで表してはいけないことが分かった。

## 5. 設計上の転換 — local transport stateとprotocol stateを分離

`69cb78c8`では、Controllerのreconnect stateを`ReconnectEpochProgress`として分解した。中心となる考え方は、**自分の端末が通信切断を観測したか**と、**サーバーが正式に管理する再接続epochへ参加しているか**を別の軸にすることである。コード上では、端末が観測したtransport stateと、サーバーが持つ正式なprotocol stateを分離した、と表現できる。

| Field | 現在の意味 | なぜ必要か |
|---|---|---|
| `authoritativeEpoch` | 自分の端末が受け入れた、最新のserver negotiation epoch | 端末callbackの回数ではなく、どのserver epochが現在の基準かを保持するため |
| `adoptedEpoch` | 現在、自分の端末がsignalingとDataChannel ACKへ参加しているepoch。完了後は`null`になる | passive peerを含め、「このepochの参加者である」ことを自端末の切断観測とは独立に表すため |
| `completedEpoch` | 両端末のACK後、棋譜と盤面状態の同期まで完了したepoch | 完了済みepochを遅延retryや重複signalingで再び開かないため |
| `signalingStarted` | adopted epochについてsignaling開始を記録した節目 | epochを知っただけの状態と、実際に再接続交渉を開始した状態を区別するため |
| `ackRequestSent` | adopted epochについてACK requestを送信しようとしたこと | request送信とserver commit確認を同一視しないため。これは成功の証明ではない |
| `serverLocalAcked` | adopted epochについて、サーバーが自分の端末のACKを確認した状態 | HTTP responseの有無ではなく、server DB上の確認済み事実を表すため |
| `serverBothAcked` | adopted epochについて、サーバーが両端末のACKを確認した状態 | DataChannel `OPEN`だけで`ACTIVE`へ戻さず、双方確認を要求するため |
| `localDisconnectObserved` | 今回のadoptの原因に、自分の端末での切断観測があったか | 切断を申告した端末と、申告せず参加するpassive peerを区別するため |
| `freshEpochRequired` | debounceを越えた新しい切断が、まだサーバーのepochをadoptすることで処理されていないこと | 完了済みepochのretryと、本当に次epochを必要とする新しい切断を区別するため |

`passiveParticipation`は`adoptedEpoch != null && !localDisconnectObserved`から導出される。つまりpassiveであることは別のserver statusではない。「同じサーバー正式epochへ参加しているが、自分の端末では切断を観測していない」という端末側の関係を表す。

代表的なstate snapshotは次のようになる。

| 状況 | authoritative | adopted | completed | local disconnect | fresh required |
|---|---:|---:|---:|---:|---:|
| epoch Eの同期完了後 | E | `null` | E | false | false |
| その後に新しい切断がdebounceを通過 | E | `null` | E | true | true |
| passive peerがepoch E+1 signalingを採用 | E+1 | E+1 | E | false | false |
| 切断を申告した端末がepoch E+1を採用 | E+1 | E+1 | E | true | false |

## 6. Passive reconnect participation

自分では切断を検知していない相手端末（passive peer）も、サーバーが正式に開始した再接続epochへ参加できなければならない。現在の`WebRtcMatchCoordinator.handle()`は、自分の役割（BLACK／WHITE）に合うepoch付きの`RESUME`、`OFFER`、`ANSWER`を受信すると、未完了の新epochかを`shouldAdoptReconnectEpochFromSignal()`で判定する。新しい正式epochであれば、passive peerでも`adoptAuthoritativeReconnectEpoch()`を呼ぶ。

`adoptAuthoritativeReconnectEpoch()`は、その再接続番号を自端末でも現在参加するepochとして受け入れる（adopt）。具体的には、そのepochを`authoritativeEpoch`と`adoptedEpoch`へ設定し、対局開始済みなら`PLAYING`／`MOVE_CONFIRMING`／`SYNCHRONIZING`から`RECONNECTING`へ遷移する。このpathは`beginReconnectGrace()`を通らないため、`freshEpochRequired`やlocal disconnect claimを新しく作らない。つまりpassive peerは再接続には参加するが、「自分が切断した」という証拠を作らない。

`onDataChannelOpen(epoch)`は、開始済み対局でもControllerが`RECONNECTING`で`adoptedEpoch == epoch`なら`handleTransportRecovered(epoch)`へ進む。ACKは単にtransportが開いたという通知ではなく、adopt済みの具体的epochへ結び付く。

次の図は端末AがBLACK側として`OFFER`を作る例である。端末の役割が逆なら`RESUME`／`OFFER`／`ANSWER`の担当も入れ替わるが、epochのadoptとACKの条件は同じである。

```mermaid
sequenceDiagram
    participant A as 端末A
    participant S as Supabase / Server
    participant B as 端末B

    Note over A: DISCONNECTEDを観測
    A->>S: disconnect report
    S->>S: epoch E+1 / RECONNECTING
    S-->>A: 正式なepoch E+1

    A->>S: epoch E+1のOFFERをpublish
    S-->>B: epoch E+1のOFFERを配信
    Note over B: DISCONNECTEDは観測していない
    B->>B: 正式epoch E+1をadopt
    B->>S: epoch E+1のANSWERをpublish
    S-->>A: epoch E+1のANSWERを配信
    Note over A,B: WebRTC DataChannelを再接続

    A->>S: ACK epoch E+1
    B->>S: ACK epoch E+1
    S->>S: both ACKを確認してACTIVE
    S-->>A: ACTIVE / epoch E+1
    S-->>B: ACTIVE / epoch E+1

    A->>B: 棋譜・盤面stateを同期
    B-->>A: 一致したstateを確認
    Note over A,B: synchronization完了後にPLAYING
```

端末Bは切断を申告していないが、再接続には参加する。これにより、余分なdisconnect claimを作らずに、両端末が同じepochをACKできる。

## 7. Failure 2 — RPC response loss

分散システムでは、**request failure（requestが失敗したように見えること）とoperation failure（server処理そのものの失敗）は同じではない**。通信上は失敗に見えても、サーバー側の処理はすでに成功していることがある。

```mermaid
sequenceDiagram
    participant C as 端末
    participant S as Server
    participant N as Network

    C->>S: ACK epoch 3
    S->>S: DB commit成功<br/>both ACK / ACTIVE / epoch 3
    S->>N: HTTP response
    N--xC: responseだけ喪失
    Note over C: ACK失敗に見え、RECONNECTINGが残る

    C->>S: getMatchStartState()
    S-->>C: ACTIVE / epoch 3 / local ACK / both ACK
    Note over C: 新epochを開始せず<br/>棋譜・盤面同期へ進む
```

ここで自分の端末が「失敗したように見えたから次のreconnectを開始する」と判断すると、サーバーでは成功済みのepoch 3に対してfresh resumeを要求することになる。epoch budgetのserver contractから見ると、それは4回目のreconnect要求であり、`RECONNECT_BUDGET_EXHAUSTED_UNRATED`による`EXPIRED`が正しい応答になってしまう。原因はサーバーではなく、端末が結果未確認のRPCを新しいprotocol eventとして解釈したことにある。

`69cb78c8`は、端末の`RECONNECTING`だけを次epoch開始の根拠にしないようにした。ACK requestのresponseを失ったときは、まずserver stateを読み、同じepochのACKがcommit済みかを確認する。

## 8. Server authoritative reconciliation

`OnlineMatchController.handleTransportRecovered()`は、adoptしたepochを指定して`ackMatchStarted()`を呼ぶ。例外になった場合、直ちに別epochを作るのではなく、`getMatchStartState()`でサーバーが持つ正式なmatch rowを再取得する。この照合処理がreconciliationである。

取得した`MatchStartAck`には`serverStatus`、`negotiationEpoch`、`localAcked`、`bothAcked`が含まれる。サーバーが同じepochについて`ACTIVE / localAcked / bothAcked`を返せば、端末はACK responseを受け取れなかった事実よりserver commitを優先し、`reconcileAuthoritativeStartState()`から棋譜と盤面状態の同期へ進む。

設計原則は「API呼出しが失敗したら同じ書き換え操作（mutation）を無条件に再送する」ではなく、「成否が曖昧なら、次のmutationより先にサーバーの正式状態を読む」である。`ackRequestSent`は送信の事実、`serverLocalAcked`と`serverBothAcked`はサーバー確認済みの事実として分離されている。

同期ではrequest ID付きの`SyncMessage`を交換し、正規形式の棋譜（canonical transcript）、着手数（ply）、盤面の照合値（state hash）を検証する。`applySyncSnapshot()`が両端末の状態一致を確認して初めて`completedEpoch = authoritativeEpoch`となり、Controllerは`PLAYING`へ戻る。したがってserver ACKと棋譜同期完了も別の節目である。

## 9. expected epoch contract

現在のKotlin contractは、`OnlineMatchRepository.ackMatchStarted()`と`resumeMatch()`の両方でnon-nullな`expectedNegotiationEpoch: Int`を必須にしている。`SupabaseOnlineMatchRepository`はそれを`p_expected_epoch`として`ack_match_started_v2()`／`resume_match_v2()`へ渡す。

これはretryがどのepochを対象にしていたかをサーバーが判定し、古いrequestを現在状態へ混ぜないための識別札（fencing context）である。例えば「epoch 2用DataChannelのOPEN」で送ったACKがepoch 3へ遅れて届いても、epoch 3のACKとして扱ってはならない。

| RPC / request | 現在のserver behavior |
|---|---|
| `ack_match_started_v2`, future expected epoch | live matchの正式epochより先なら拒否する |
| `ack_match_started_v2`, stale expected epoch | 現在状態を返すだけで、新しいepochへACK rowを追加しない |
| `ack_match_started_v2`, current expected epoch | current epochへ重複安全（idempotent）にACKし、双方分が揃った場合だけ`ACTIVE`へ戻す |
| `resume_match_v2`, future expected epoch | 拒否する |
| `resume_match_v2`, stale expected epoch while `ACTIVE` | 現在状態を読むだけで返し、epochもbudgetも消費しない |
| `resume_match_v2` while `RECONNECTING` | 現在の正式epochへ参加し、epochは増やさない |
| `resume_match_v2`, current expected epoch while `ACTIVE` | fresh reconnectとしてepochを1増やす。epoch 3なら増やさずunrated expiryへ進む |

SQL functionの`p_expected_epoch`にはmigration上`default null`が残っているが、現在のAndroid interfaceとSupabase adapterは必ずintegerを渡す。上表のstale/future fencingは、このcurrent Android pathのnon-null expected epochに対するcontractである。

一般化すると、retry可能なmutationには「何をしたいか」だけでなく、「どのepochに対する操作だったか」を識別できるcontextが必要である。

## 10. Failure 3 — disconnect reportとresumeのrace

自分の端末でdisconnectを観測したControllerは、debounce後に`submit_match_result_v2(..., DISCONNECT, opponent)`を送り、サーバーを期限付きのrecoveryへ進める。同時にCoordinatorもserver stateを読み、必要なら`resume_match_v2()`を送る。

平易に言えば、同じ対局に対して2つの処理が同時に「次の再接続番号へ進めよう」とすると、1回の切断なのにepochを2回消費する恐れがある。そこでDBは、同じ対局の更新を1つずつ順番に処理する。

さらに、Coordinatorが`ACTIVE / same epoch`を読んだ直後にdisconnect reportがcommitする到着順もある。`f19b5b2`時点では、DataChannelがOPENで、端末のControllerが`RECONNECTING`なら同期して終了する判断があり、read後にサーバーだけが次epochへ進む可能性が残った。`e76b605`は、debounceを越えたlocal reconnectでは古い`ACTIVE` readを完了証明とみなさず、`START_NEW_EPOCH`へ進むようにした。

正式なDB用語では、`resume_match_v2()`と`submit_match_result_v2()`が同じ`matches`行を行ロック（row lock）する。そのSQLが`FOR UPDATE`である。現在の更新式は、lock取得後に見た状態が`ACTIVE`の要求だけを`+1`し、`RECONNECTING`を見た後着要求は`+0`でcurrent epochへ参加する。

```text
reportが先: ACTIVE E -> RECONNECTING E+1 -> resumeはE+1へ参加
resumeが先: ACTIVE E -> RECONNECTING E+1 -> reportはE+1へ参加
```

これにより、どちらのrequestが先に到着しても、サーバー上ではepochが1回だけ増えた同じ状態へ収束する。端末plannerの`freshEpochRequired`は、このrow-locked transitionへ入るべき新しい切断が存在することを表す。

## 11. Failure 4 — completed recovery後のdelayed force retry

epoch-aware化後にも、最後にもう1つの障害が残った。問題はACK response lossそのものではなく、その間に予約され、後から発火するforce retryだった。force retryは、通常なら「一時的に戻ったので何もしない」と判断する条件を越えて、サーバーとの照合を再試行するjobである。

```text
epoch 3 ACKがサーバーでcommit
  -> HTTP response loss
  -> 端末は一時的にRECONNECTING
  -> retryCurrentOperation()がforce=true jobを予約
  -> 別経路のserver state照合と棋譜同期が成功
  -> PLAYING / completedEpoch=3 / adoptedEpoch=null / freshEpochRequired=false
  -> 予約済みforce jobが遅れて発火
```

`69cb78c8`時点の判断関数（planner）は`adoptedEpoch`と`freshEpochRequired`を見ていたが、`completedEpoch`を入力に持っていなかった。同期完了後は`adoptedEpoch`が`null`になるため、遅延jobからは「epoch 3を完了済み」という情報が見えない。`force=true`によって一時復旧時のskipを越え、`START_NEW_EPOCH`を選べた。

その結果、`resume_match_v2(expected_epoch=3)`はサーバーから見れば古いrequest（stale）ではなく、current epochへのfresh requestである。サーバーは契約どおりbudget exhaustionとして`EXPIRED`にするが、端末の意図は古いretryだった。この障害は、expected epochだけでは「同じcurrent epochの完了済みretry」と「同じcurrent epochから始まる新しいdisconnect」を区別できないことを示した。

## 12. completedEpoch と freshEpochRequired

`6b914f5`では、`scheduleTransportRenegotiation()`が実行時点の`ReconnectEpochProgress`を取り直し、`completedReconnectEpoch`を`planReleaseRenegotiation()`へ渡すようにした。重要なのはfield追加そのものではなく、判定の優先順位である。

次の図は状態遷移図ではなく、新しいepochを作るべきかをplannerが判断するためのdecision flowである。

```mermaid
flowchart TD
    A[サーバー ACTIVE / epoch E] --> B{freshEpochRequired?}
    B -->|true| C[START_NEW_EPOCH<br/>新しい切断をサーバーへ提示]
    B -->|false| D{transport OPEN<br/>localAcked and bothAcked<br/>completedEpoch or adoptedEpoch == E?}
    D -->|true| E[SYNCHRONIZE_CURRENT_EPOCH<br/>または完了状態の安全な照合]
    D -->|false| F[残りの既存planner判定]
    G[force = true] -. 一時復旧時のskipを越えて再確認 .-> B
```

actual plannerでは終了済みstate、`RECONNECTING`／`MATCHED`のcurrent-epoch signalingを先に処理し、その後の`ACTIVE`で次の順序になる。

1. `freshReconnectEpochRequired == true`なら`START_NEW_EPOCH`。
2. freshでなく、transportが`OPEN`、サーバーのlocal/both ACKがtrueで、server epochが端末より先、adopt済み、またはcompletedのいずれかなら`SYNCHRONIZE_CURRENT_EPOCH`。
3. それ以外を一時復旧のskipや新epoch開始の既存判定へ渡す。

`SYNCHRONIZE_CURRENT_EPOCH`分岐は`requestReconnectEpochIfRequired()`より前にreturnするため、completed epochの遅延force retryは`resume_match_v2()`を呼ばない。Controller側も、`PLAYING / OPEN / adoptedEpoch=null / completedEpoch==serverEpoch / fresh=false`というサーバーの`ACTIVE`状態を、重複実行しても状態を壊さない成功（idempotent success）として扱う。

ここで`force=true`は、「サーバー上で完了済みのepochを破棄して、必ず新epochを作る」という意味ではない。通常の一時復旧判定で止めず、サーバーとの状態照合を試すためのhintであり、新しい切断と完了済みの証拠の意味を上書きしない。

## 13. Reconnect budget

reconnect budgetは端末ごとのretry counterではなく、対局全体で共有するserver stateである。epochは同じmatchの接続世代を識別し、epoch 0が初回接続、epoch 1..3が利用可能な再接続である。Reconnectの文脈では1..3が再接続回数に対応し、DB constraint、signaling、ACK、result evidenceにも0..3の境界が適用される。

完了済みepochへのstale requestやdelayed force retryはbudgetを使わない。一方、epoch 3のrecoveryが完了して`PLAYING`へ戻った後、本当に新しい`DISCONNECTED`がdebounceを越えれば、ControllerはcompletedEpoch 3を保持したまま`freshEpochRequired=true`にする。plannerではfreshがcompletedより優先される。

```text
ACTIVE / epoch 3 / completedEpoch 3
  -> new DISCONNECTED
  -> debounce経過
  -> freshEpochRequired=true
  -> current epoch 3へのfresh resumeまたはdisconnect report
  -> RECONNECT_BUDGET_EXHAUSTED_UNRATED
  -> EXPIRED（epoch 4は作らない）
```

つまり「retry時に状態を照合するようにした」ことは、「reconnect budgetを無効にした」ことではない。曖昧な再送は消費させず、新しい障害だけをserver budgetへ提示する。

budget exhaustionは「対局を継続できない」という進行可能性の障害（liveness failure）であり、勝者の証拠ではない。`match_results_v2`、`rating_history`、`game_records`、`user_game_records`やResearch起点を作らず、rated forfeitにも変換しない。

## 14. 現在のreconnect lifecycle

現在の正常recoveryは、通信路、サーバー上の対局状態、端末上のprotocol stateを次の順で同じ状態へ合わせる。

次の図はserver `ACTIVE`かつclient開始済みの対局から始まる。serverが`MATCHED / epoch 0`である初回開始前の切断は、このflowへ入らずConnection Design Storyの初回接続失敗pathで扱う。

```mermaid
flowchart TD
    A[開始済み対局で<br/>Transport DISCONNECTED] --> B{1.5秒以内にOPENへ復帰?}
    B -->|yes| C[reportなし / epoch消費なし<br/>PLAYING継続]
    B -->|no| D[localDisconnectObserved=true<br/>freshEpochRequired=true]
    D --> E[disconnect report / resume<br/>matches rowをFOR UPDATE]
    E --> F[サーバーの正式なRECONNECTING epochを取得]
    P[相手端末からnew-epoch signalingを受信<br/>自端末の切断未観測でもよい] --> F
    F --> G[そのepochをadopt]
    G --> H[epoch-scoped OFFER / ANSWER / RESUME]
    H --> I[DataChannel OPEN]
    I --> J[ack_match_started_v2 expected epoch]
    J --> K{both ACK?}
    K -->|no| L[RECONNECTINGで相手端末のACK待ち]
    K -->|yes| M[サーバー ACTIVE]
    M --> N[棋譜 / 盤面state synchronization]
    N --> O[completedEpoch = authoritativeEpoch]
    O --> Q[PLAYING]
```

切断を観測した端末ではdebounce後に`freshEpochRequired`を立て、disconnect reportとresumeのどちらかがserver epochを開始する。passive peerではnew-epoch signalingそのものがadoptのきっかけであり、disconnect reportは送らない。どちらもadopt後は同じepoch付きsignaling、ACK、同期pathへ合流する。

process death recoveryでも、サーバーから回収したactive assignmentの`negotiationEpoch`とapp-private checkpointを起点にする。端末内snapshotだけを正式状態とせず、server lifecycleを再確認してからsignalingまたはreconciliationを選ぶ。

## 15. 現在の設計原則

1. **Transport state is not protocol state.** 通信路の状態と対局protocolの状態は別である。`OPEN`はDataChannelの状態であり、サーバー上の双方ACKや棋譜一致を意味しない。
2. **Local observation is not authoritative state.** 自分の端末で見えた状態は正式状態とは限らない。一方の`DISCONNECTED`や`RECONNECTING`だけから、相手端末やサーバーのepochを推測しない。
3. **Request failure is not operation failure.** request失敗とserver処理失敗は同じではない。HTTP response loss後はDB commit済みの可能性を考え、サーバーの正式状態を読む。
4. **Retries must be idempotent or state-aware.** retryは重複安全、または現在状態を確認する必要がある。同じrequestを安全に再送できない場合は、mutationの前にcurrent stateと対象epochを照合する。
5. **Stale requests must identify the generation/epoch they target.** 古いrequestは対象epochを識別できなければならない。ACKとresumeはcurrent Android contractでexpected epochを必ず送り、古いDataChannelやretryを新しいepochへ適用しない。
6. **Authoritative state belongs on the server.** 正式状態はサーバーが持つ。lifecycle、current epoch、ACK集合、deadline、budget exhaustionはSupabaseが決める。
7. **Reconnect budget must be server-authoritative.** 再接続回数の上限はサーバーが管理する。端末processごとのcounterではなく、lockしたmatch rowのepoch 0..3で制限する。
8. **Completed recovery and fresh disconnect are different events.** 完了済みrecoveryと新しい切断は別eventである。`completedEpoch`と`freshEpochRequired`を分け、前者のretryを抑止しつつ後者を新しいrecoveryへ進める。
9. **Race ordering should converge to the same state.** 同時処理は到着順が違っても同じ状態へ収束させる。disconnect reportとresumeは、どちらが先でも同じ1 epochへ参加する。
10. **Recovery must not manufacture rated results or persistent match records.** 再接続失敗からrated resultや永続対局recordを作らない。対局を継続できないreconnect failureはunrated expiryとし、Rating、GameRecord、Research入力へ変換しない。

## 16. Server-side invariants

現在のSQLが保証するreconnect関連の主要な設計上の約束（invariants）は次のとおりである。

- `matches.negotiation_epoch`、protocol 2のACK、signal、result claimは0..3に制約される。
- `ack_match_started_v2()`と`resume_match_v2()`は、認証済みの対局参加者だけを受け入れ、対象`matches` rowを`FOR UPDATE`する。
- non-null expected epochがサーバーより先なら、live requestを拒否する。
- stale ACKはサーバーの新しいepochへACKを追加せず、現在状態だけを返す。
- stale `ACTIVE` resumeは新しいepochを変更せず、budgetを消費しない。
- `ACTIVE`のcurrent epochへのfresh resumeだけがepochを`+1`する。`RECONNECTING`へのresumeは`+0`で現在epochへ参加する。
- disconnect reportも同じrowをlockし、`ACTIVE`でのみ`+1`、`RECONNECTING`では`+0`となる。
- `publish_match_signal_v2()`はparticipant、role、protocol version、current epoch、live statusを検証し、別epochのsignalingを混在させない。
- current epochの参加者2名のACKが揃った場合だけ、`MATCHED`／`RECONNECTING`から`ACTIVE`へ遷移する。
- epoch 3のcurrent `ACTIVE`からfresh reconnectが必要なら、epoch 4を作らず`RECONNECT_BUDGET_EXHAUSTED_UNRATED`で`EXPIRED`にする。
- 終了済みmatchをreconnectで再開しない。
- reconnect budget exhaustionや片側だけの切断申告では、rated result、Rating、GameRecord、Research起点を確定しない。

## 17. Client-side invariants

現在のKotlin実装が維持する端末側のinvariantsは次のとおりである。

- 一時的な`DISCONNECTED`が1.5秒以内に`OPEN`へ戻れば、disconnect reportを送らずepochを消費しない。
- Coordinatorは再交渉前にserver stateを読み、自分の端末のtransport stateだけで次epochを決めない。
- passive peerは、サーバーが正式に開始したnew-epoch signalingからepochをadoptできる。
- passive peerのadoptは`beginReconnectGrace()`を通らず、disconnect claimを生成しない。
- DataChannel `OPEN`時のACKは`adoptedEpoch`をexpected epochとして送る。
- ACK response loss時は`getMatchStartState()`でserver ACK状態を照合する。
- サーバーのlocal/both ACK確認後に棋譜同期へ進み、その完了後に`completedEpoch`を更新して`PLAYING`へ戻る。
- サーバー上で完了済みのepochは、`force=true`の遅延retryでも新epochとして再消費しない。
- debounceを越えた新しい切断の`freshEpochRequired=true`はcompleted判定より優先される。
- stale signalingはepochで除外し、許可されたroleとcurrent epochを満たすsignalだけをtransportへ適用する。

## 18. テスト戦略

reconnect testは正常系だけの関数呼出し数ではなく、障害パターンごとに守るべきinvariantを固定している。

- **一時的な切断:** `oneSidedTransientDisconnectReturnsToOpenWithoutConsumingServerReconnect`と`transientOpenWithUnchangedActiveEpochDoesNotRenegotiate`が、debounce内復帰でreport/resume/epoch消費がないことを確認する。
- **Passive peer:** `passivePeerAdoptsAuthoritativeEpochAndAcksWithoutDisconnectClaim`がclaimなしのadopt、current epoch ACK、同期後`PLAYING`を確認する。`passivePeerNewEpochSignalUsesCoordinatorAdoptionGate`はCoordinator `handle()`が使用するactual adoption gateを固定する。
- **Report/resumeの到着順:** planner testがreport先着時のcurrent-epoch signalingと、古い`ACTIVE` read時のfresh STARTを分ける。pgTAPはreport-first／resume-first双方でepochが1回だけ増え、双方ACK後に`ACTIVE`へ戻るfixtureを持つ。
- **ACK response loss:** `committedEpochTwoAckWithLostResponseReconcilesFromServerWithoutResume`と`committedEpochThreeAckWithLostResponseDoesNotConsumeEpochFour`が、ACK commit後のresponse lossをserver readから回復し、resumeを呼ばないことを確認する。
- **遅延force retry:** `delayedForcedRetryAfterCompletedEpochThreeSynchronizesWithoutResume`が、`completedEpoch=3 / fresh=false / force=true`で同期を選び、actual Coordinatorのresume dispatch callbackが0回であることを確認する。Controller testは完了済み`ACTIVE`の再照合が冪等に`PLAYING`を維持することも確認する。
- **本当のepoch 3 exhaustion:** `genuineDisconnectAfterCompletedEpochThreeRequestsBudgetDecision`がfresh=trueのplanner優先順位を固定する。pgTAPはcurrent epoch 3 resumeとDISCONNECT reportの両pathがepoch 4を作らずunrated `EXPIRED`になることを確認する。
- **Stale expected epoch:** pgTAPは古いDataChannel ACKが新しいepochへACK rowを作らないことと、stale `ACTIVE` resumeがcompleted epoch 3を変更しないことを確認する。future epochの拒否はSQL本体の分岐として実装されている。
- **Persistent data非生成:** budget exhaustion、片側disconnect、mutual disconnect、one-sided NORMAL等について、`match_results_v2`、`rating_history`、`game_records`が作られないことをpgTAPで確認する。

baselineのlocal Supabase pgTAPは542件すべてPASSしている。Kotlin unit test、Gradle compile/lint/assemble、Worker test、SQL security／boundary checkを含むGitHub Actions run `32719923138`も対象commitでsuccessした。

## 19. 最終監査結果

`6b914f5`に対する最後の限定監査結果は次のとおりだった。

```text
Critical: 0
High: 0
Medium blockers: 0
```

completed-epoch delayed force retryは、actual planner input、Coordinator dispatch、Controllerの冪等な状態照合まで確認してCLOSEDと判定された。本当のepoch 3 exhaustion、stale ACTIVE race、passive peer participationも維持され、オンライン対戦release-hardeningは実装修正フェーズを終了して2台実機smokeへ進むGO判定となった。

これはreconnectにバグが存在しないことの証明ではない。unit testはplanner、dispatch gate、Controller、SQL transactionを層別に固定している一方、実Android WebRTC、実network handover、HTTP response loss、1.5秒delayと複数callbackの実scheduler orderingを1つのfixtureですべて再現してはいない。これらは2台実機smokeで確認する、残っている統合上のriskである。

## 20. What this document is not

この資料は次のものではない。

- WebRTC一般の解説
- Supabase一般の解説
- オンライン対戦全体のAPI／state reference
- Research設計
- Rating policy仕様
- protocol 1 legacy compatibility仕様書

対象範囲は、protocol 2 reconnect designが現在のepoch-aware modelへ至った理由と、そのfailure recovery invariantに限定する。
