/* eslint-disable @next/next/no-html-link-for-pages -- vinext Link prefetch throws in the production legal pages. */
"use client";

import { useEffect, useState } from "react";

export default function AccountDeletionConfirmPage() {
  const [status, setStatus] = useState("確認リンクを確認しています…");

  useEffect(() => {
    const hash = new URLSearchParams(window.location.hash.slice(1));
    const accessToken = hash.get("access_token");
    window.history.replaceState({}, document.title, window.location.pathname);
    if (!accessToken) {
      queueMicrotask(() => setStatus("確認リンクが見つかりません。メール内のリンクをもう一度開いてください。"));
      return;
    }

    void fetch("/api/account-deletion/email/confirm", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ access_token: accessToken }),
    })
      .then(async (response) => {
        const body = await response.json().catch(() => ({})) as { message?: string; error?: string };
        if (!response.ok) throw new Error(body.error ?? "削除リクエストを開始できませんでした。");
        setStatus(body.message ?? "削除リクエストを受け付けました。アプリを開かずにこのページを閉じてください。");
      })
      .catch((error: unknown) => {
        setStatus(error instanceof Error ? error.message : "削除リクエストを開始できませんでした。");
      });
  }, []);

  return (
    <main className="policy-page">
      <div className="policy-page-inner">
        <a className="policy-back" href="/account-deletion">← アカウント削除へ戻る</a>
        <p className="section-kicker">CHANRIVA / ACCOUNT DELETION</p>
        <h1>削除リクエストの確認</h1>
        <p>メール内の確認リンクからアクセスしてください。本人確認後、アプリを開かずに削除リクエストを開始します。</p>
        <p aria-live="polite">{status}</p>
        <p className="policy-footer"><a href="/privacy">プライバシーポリシー</a> · <a href="/">ちゃんりばトップへ</a></p>
      </div>
    </main>
  );
}
