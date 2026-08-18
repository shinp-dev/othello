"use client";

import { FormEvent, useEffect, useState } from "react";

const PASSWORD_RESET_API = "/api/password-reset/complete";

export default function ResetPasswordForm() {
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    const hash = window.location.hash.replace(/^#/, "");
    const params = new URLSearchParams(hash);
    const token = params.get("access_token");
    const type = params.get("type");
    if (token && (!type || type === "recovery")) {
      // This is the hydration boundary: the token exists only in the URL hash.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setAccessToken(token);
    }
    window.history.replaceState({}, document.title, window.location.pathname + window.location.search);
    setReady(true);
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage(null);
    setError(null);
    if (!accessToken) {
      setError("再設定リンクが無効または期限切れです。アプリから再設定メールをもう一度送信してください。");
      return;
    }
    if (password.length < 8) {
      setError("新しいパスワードは8文字以上で入力してください。");
      return;
    }
    if (password !== confirmation) {
      setError("新しいパスワードが一致しません。");
      return;
    }

    setSubmitting(true);
    try {
      const response = await fetch(PASSWORD_RESET_API, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ access_token: accessToken, password }),
      });
      if (!response.ok) throw new Error("password reset failed");
      setAccessToken(null);
      setPassword("");
      setConfirmation("");
      setMessage("パスワードを更新しました。アプリに戻って新しいパスワードでログインしてください。");
    } catch {
      setError("パスワードを更新できませんでした。リンクが期限切れの場合は、アプリから再設定メールをもう一度送信してください。");
    } finally {
      setSubmitting(false);
    }
  }

  if (!ready) return <p>再設定リンクを確認しています…</p>;

  return (
    <>
      {accessToken ? (
        <form className="deletion-form" onSubmit={submit}>
          <label htmlFor="new-password">新しいパスワード</label>
          <input
            id="new-password"
            type="password"
            autoComplete="new-password"
            minLength={8}
            maxLength={256}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
          <label htmlFor="new-password-confirmation">新しいパスワード（確認）</label>
          <input
            id="new-password-confirmation"
            type="password"
            autoComplete="new-password"
            minLength={8}
            maxLength={256}
            value={confirmation}
            onChange={(event) => setConfirmation(event.target.value)}
            required
          />
          <button type="submit" disabled={submitting}>{submitting ? "更新中…" : "パスワードを更新"}</button>
        </form>
      ) : null}
      {!accessToken && !message ? <p>アプリから届いた再設定メールのリンクをこのページで開いてください。</p> : null}
      {message ? <p className="policy-notice">{message}</p> : null}
      {error ? <p role="alert" className="deletion-form-status">{error}</p> : null}
    </>
  );
}
