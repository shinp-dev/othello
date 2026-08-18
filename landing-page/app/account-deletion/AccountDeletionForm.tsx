"use client";

import { type FormEvent, useState } from "react";

export default function AccountDeletionForm() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [status, setStatus] = useState<string | null>(null);
  const [emailLinkStatus, setEmailLinkStatus] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [emailLinkSending, setEmailLinkSending] = useState(false);

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

  async function sendEmailLink() {
    setEmailLinkSending(true);
    setEmailLinkStatus(null);
    try {
      const response = await fetch("/api/account-deletion/email/start", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ email }),
      });
      const body = await response.json().catch(() => ({})) as { message?: string; error?: string };
      if (!response.ok) {
        setEmailLinkStatus(body.error === "too many requests"
          ? "しばらく時間をおいてから、もう一度お試しください。"
          : "入力内容を確認して、もう一度お試しください。");
        return;
      }
      setEmailLinkStatus(body.message ?? "登録済みの場合は確認メールを送信しました。メール内のリンクから削除を続けてください。");
    } catch {
      setEmailLinkStatus("通信に失敗しました。HTTPS接続を確認してから、もう一度お試しください。");
    } finally {
      setEmailLinkSending(false);
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
      <div className="deletion-form-divider">
        <h3>パスワードを使えない場合</h3>
        <p>登録メールアドレスへ確認リンクを送信します。リンクを開くと、アプリを再インストールせずに削除リクエストを開始できます。</p>
        <button type="button" onClick={sendEmailLink} disabled={emailLinkSending || email.trim() === ""}>
          {emailLinkSending ? "送信中…" : "メールで削除を続ける"}
        </button>
        {emailLinkStatus ? <p className="deletion-form-status" aria-live="polite">{emailLinkStatus}</p> : null}
      </div>
    </form>
  );
}
