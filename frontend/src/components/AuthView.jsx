import { useState } from "react";
import logoUrl from "../Logo.png";
import { login, register } from "../apiClient.js";

export default function AuthView({ onAuthenticated, backendStatus }) {
  const [mode, setMode] = useState("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [signupCode, setSignupCode] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const isRegister = mode === "register";
  const offline = backendStatus === "offline";

  function switchMode(next) {
    if (next === mode) return;
    setMode(next);
    setError("");
    setShowPassword(false);
  }

  async function submit(event) {
    event.preventDefault();
    setError("");
    if (username.trim().length < 3) {
      setError("Username must be at least 3 characters.");
      return;
    }
    if (password.length < 8) {
      setError("Password must be at least 8 characters.");
      return;
    }
    setBusy(true);
    try {
      const { user } = isRegister
        ? await register(username.trim(), password, signupCode.trim())
        : await login(username.trim(), password);
      onAuthenticated(user);
    } catch (err) {
      setError(err.message || "Authentication failed.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app-shell auth-shell">
      <div className="auth-bg" aria-hidden="true">
        <div className="auth-bg-aurora" />
        <div className="auth-bg-beam" />
        <div className="auth-bg-particles">
          {Array.from({ length: 20 }).map((_, i) => (
            <span
              key={i}
              style={{
                left: `${(i * 4.7 + (i % 4) * 3) % 100}%`,
                animationDuration: `${11 + (i % 6) * 2.4}s`,
                animationDelay: `${(i * 1.3) % 14}s`,
              }}
            />
          ))}
        </div>
      </div>
      <main className="auth-card">
        <div className="auth-brand">
          <img className="auth-mark" src={logoUrl} alt="" aria-hidden="true" />
          <span className="auth-brand-name">Reverse Engineering AI Agent</span>
        </div>

        <div
          className="auth-tabs"
          role="tablist"
          aria-label="Authentication mode"
        >
          <button
            type="button"
            role="tab"
            aria-selected={!isRegister}
            className={isRegister ? "" : "is-active"}
            onClick={() => switchMode("login")}
          >
            Sign in
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={isRegister}
            className={isRegister ? "is-active" : ""}
            onClick={() => switchMode("register")}
          >
            Create account
          </button>
        </div>

        <p className="auth-lede">
          {isRegister
            ? "Set up an account. Your ingested repositories stay private to you."
            : "Sign in to ingest repositories and ask questions about them."}
        </p>

        <form className="auth-form" onSubmit={submit}>
          <label className="field">
            <span className="field-label">Username</span>
            <input
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              placeholder="Sam"
              autoFocus
            />
          </label>

          <label className="field">
            <span className="field-label">Password</span>
            <span className="field-input">
              <input
                type={showPassword ? "text" : "password"}
                autoComplete={isRegister ? "new-password" : "current-password"}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder={
                  isRegister ? "At least 8 characters" : "Your password"
                }
              />
              <button
                type="button"
                className="field-toggle"
                onClick={() => setShowPassword((visible) => !visible)}
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                {showPassword ? "Hide" : "Show"}
              </button>
            </span>
          </label>

          {isRegister && (
            <label className="field">
              <span className="field-label">Invite code</span>
              <input
                value={signupCode}
                onChange={(event) => setSignupCode(event.target.value)}
                placeholder="Provided by the site owner"
              />
            </label>
          )}

          {error && (
            <div className="notice error" role="alert">
              {error}
            </div>
          )}

          <button
            className="primary-button auth-submit"
            disabled={busy || offline}
          >
            {busy && <span className="auth-spinner" aria-hidden="true" />}
            {busy ? "Working…" : isRegister ? "Create account" : "Sign in"}
          </button>
        </form>

        {offline && (
          <p className="auth-status" role="status">
            The backend looks offline — start it and try again.
          </p>
        )}
      </main>
      <p className="auth-footnote">
        Clone a GitHub repo · chunk it · ask how it works
      </p>
    </div>
  );
}
