"use client";

import { useEffect, useState } from "react";
import {
  resolveSignupConfirmationState,
  SIGNUP_CONFIRMATION_MESSAGE,
  SIGNUP_CONFIRMATION_STATE,
} from "./confirmation-state.js";

export default function SignupConfirmationStatus() {
  const [state, setState] = useState(SIGNUP_CONFIRMATION_STATE.CHECKING);
  const message = SIGNUP_CONFIRMATION_MESSAGE[state];

  useEffect(() => {
    const resolvedState = resolveSignupConfirmationState(window.location.hash, window.location.search);
    window.history.replaceState({}, document.title, window.location.pathname);
    // This is the hydration boundary: Supabase returns Auth errors in the URL fragment.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setState(resolvedState);
  }, []);

  return (
    <section aria-live="polite">
      <h1>{message.title}</h1>
      <p className="policy-notice">{message.notice}</p>
      {message.action ? <h2>{message.action}</h2> : null}
      {message.detail ? <p>{message.detail}</p> : null}
    </section>
  );
}
