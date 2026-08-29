export const SIGNUP_CONFIRMATION_STATE = Object.freeze({
  CHECKING: "checking",
  SUCCESS: "success",
  EXPIRED: "expired",
  FAILURE: "failure",
});

export const SIGNUP_CONFIRMATION_MESSAGE = Object.freeze({
  [SIGNUP_CONFIRMATION_STATE.CHECKING]: {
    title: "メールアドレスの確認結果を確認しています…",
    notice: "しばらくお待ちください。",
    action: null,
    detail: null,
  },
  [SIGNUP_CONFIRMATION_STATE.SUCCESS]: {
    title: "登録完了",
    notice: "メールアドレスの確認が完了しました。",
    action: "アプリに戻ってログインしてください",
    detail: "ちゃんりばアプリを開き、登録したメールアドレスとパスワードでログインしてください。",
  },
  [SIGNUP_CONFIRMATION_STATE.EXPIRED]: {
    title: "確認リンクの期限が切れています",
    notice: "確認リンクの有効期限が切れたため、メールアドレスを確認できませんでした。",
    action: "アプリに戻って確認メールをもう一度送信してください",
    detail: "ちゃんりばアプリを開き、確認メールの送信をもう一度行ってから、新しいリンクを開いてください。",
  },
  [SIGNUP_CONFIRMATION_STATE.FAILURE]: {
    title: "メールアドレスを確認できませんでした",
    notice: "確認リンクが無効か、メールアドレスの確認処理に失敗しました。",
    action: "アプリに戻って確認メールをもう一度送信してください",
    detail: "新しい確認メールでも解決しない場合は、時間をおいてからもう一度お試しください。",
  },
});

export function resolveSignupConfirmationState(hash = "", search = "") {
  const hashParams = new URLSearchParams(hash.replace(/^#/, ""));
  const searchParams = new URLSearchParams(search.replace(/^\?/, ""));
  const value = (name) => hashParams.get(name) ?? searchParams.get(name);
  const error = value("error");
  const errorCode = value("error_code");
  const errorDescription = value("error_description") ?? "";

  if (!error && !errorCode) return SIGNUP_CONFIRMATION_STATE.SUCCESS;
  if (errorCode === "otp_expired" || /expired/i.test(errorDescription)) {
    return SIGNUP_CONFIRMATION_STATE.EXPIRED;
  }
  return SIGNUP_CONFIRMATION_STATE.FAILURE;
}
