import { useEffect, useRef, useState } from "react";
import ConfirmModal from "./ConfirmModal.jsx";

function initialsFrom(name) {
  const parts = String(name)
    .split(/[\s._-]+/)
    .filter(Boolean);
  if (parts.length >= 2) {
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }
  return (
    String(name)
      .replace(/[^a-z0-9]/gi, "")
      .slice(0, 2)
      .toUpperCase() || "?"
  );
}

export default function AccountMenu({ username, onSignOut, onDeleteAccount }) {
  const [open, setOpen] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const rootRef = useRef(null);
  const name = username || "Account";
  const displayName = name.charAt(0).toUpperCase() + name.slice(1);

  useEffect(() => {
    if (!open) return undefined;
    const handlePointer = (event) => {
      if (rootRef.current && !rootRef.current.contains(event.target)) {
        setOpen(false);
      }
    };
    const handleKey = (event) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", handlePointer);
    document.addEventListener("keydown", handleKey);
    return () => {
      document.removeEventListener("mousedown", handlePointer);
      document.removeEventListener("keydown", handleKey);
    };
  }, [open]);

  return (
    <div className="account-menu" ref={rootRef}>
      <button
        type="button"
        className="account-trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account menu for ${displayName}`}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="account-avatar" aria-hidden="true">
          {initialsFrom(name)}
        </span>
        <span className="account-name">{displayName}</span>
        <svg
          className="account-caret"
          width="12"
          height="12"
          viewBox="0 0 12 12"
          aria-hidden="true"
        >
          <path
            d="M2.5 4.5 6 8l3.5-3.5"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </button>

      {open && (
        <div className="account-popover" role="menu">
          <div className="account-popover-head">
            <span
              className="account-avatar account-avatar-lg"
              aria-hidden="true"
            >
              {initialsFrom(name)}
            </span>
            <span className="account-popover-meta">
              <span className="account-popover-label">Signed in as</span>
              <span className="account-popover-name">{displayName}</span>
            </span>
          </div>
          <button
            type="button"
            className="account-menu-item"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onSignOut();
            }}
          >
            <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
              <path
                d="M6 2H3.6A1.6 1.6 0 0 0 2 3.6v8.8A1.6 1.6 0 0 0 3.6 14H6M10.5 11 14 8l-3.5-3M13.5 8H6"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            Sign out
          </button>
          <button
            type="button"
            className="account-menu-item danger"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              setConfirmingDelete(true);
            }}
          >
            <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true">
              <path
                d="M3 4h10M6.5 4V2.8A.8.8 0 0 1 7.3 2h1.4a.8.8 0 0 1 .8.8V4M12 4l-.6 8.3a1.2 1.2 0 0 1-1.2 1.1H5.8a1.2 1.2 0 0 1-1.2-1.1L4 4M6.7 7v4M9.3 7v4"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            Delete account
          </button>
        </div>
      )}
      {confirmingDelete && (
        <ConfirmModal
          title="Delete your account?"
          message="This permanently removes your account and every repository you've ingested. This cannot be undone."
          confirmLabel={deleting ? "Deleting…" : "Delete account"}
          danger
          busy={deleting}
          onConfirm={async () => {
            setDeleting(true);
            try {
              await onDeleteAccount();
              setConfirmingDelete(false);
            } finally {
              setDeleting(false);
            }
          }}
          onCancel={() => setConfirmingDelete(false)}
        />
      )}
    </div>
  );
}
