import logoUrl from "../Logo.png";
import AccountMenu from "./AccountMenu.jsx";

export default function AppHeader({ backendStatus, username, onSignOut, onDeleteAccount }) {
  const online = backendStatus === "online";
  return (
    <header className="topbar">
      <div className="hero-copy">
        <span className="brand-row">
          <img className="brand-mark" src={logoUrl} alt="" aria-hidden="true" />
          <span className="eyebrow">Reverse Engineering AI Agent</span>
        </span>
        <h1>Understand any codebase.</h1>
        <p>
          Ingest a repository, explore it with grounded answers, and generate a
          technical document.
        </p>
      </div>
      <div className="topbar-side">
        <div className={`status-pill ${online ? "" : "offline"}`}>
          <span className="status-dot" />
          {backendStatus === "checking"
            ? "Checking backend…"
            : online
              ? "Backend ready"
              : "Backend offline"}
        </div>
        <AccountMenu
          username={username}
          onSignOut={onSignOut}
          onDeleteAccount={onDeleteAccount}
        />
      </div>
    </header>
  );
}
