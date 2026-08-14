"use client";

import { type FormEvent, useState } from "react";

export default function AccountDeletionForm() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [status, setStatus] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setStatus(null);
    try {
      const response = await fetch("/api/account-deletion/start", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      const body = await response.json().catch(() => ({})) as { message?: string; error?: string };
      if (!response.ok) {
        setStatus(body.error === "too many requests"
          ? "しばらく時間をおいてから、もう一度お試しください。"
          : "入力内容を確認して、もう一度お試しください。");
        return;
      }
      setStatus(body.message ?? "確認処理を受け付けました。");
      setPassword("");
    } catch {
      setStatus("通信に失敗しました。HTTPS接続を確認してから、もう一度お試しください。");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="deletion-form" onSubmit={submit}>
      <h2>アプリを利用できない場合</h2>
      <p>登録済みのちゃんりばアカウントで本人確認を行い、アプリを再インストールせずに削除リクエストを開始できます。</p>
      <label>
        メールアドレス
        <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} autoComplete="username" required />
      </label>
      <label>
        パスワード
        <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required />
      </label>
      <button type="submit" disabled={submitting}>{submitting ? "確認中…" : "削除リクエストを開始"}</button>
      <p className="deletion-form-note">認証情報は削除受付の確認にのみ使用します。パスワードや認証トークンを保存・表示することはありません。</p>
      {status ? <p className="deletion-form-status" aria-live="polite">{status}</p> : null}
    </form>
  );
}
