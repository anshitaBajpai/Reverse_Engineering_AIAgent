export const API_BASE_URL =
  import.meta.env?.VITE_API_BASE_URL || "http://127.0.0.1:8080";

export const REQUEST_TIMEOUT_MS = 300000;
/** Streamed answers/documents; matches the backend's 10-minute stream timeout. */
export const STREAM_TIMEOUT_MS = 600000;
export const INGEST_POLL_INTERVAL_MS = 2000;
export const INGEST_JOB_TIMEOUT_MS = REQUEST_TIMEOUT_MS;

const USER_KEY = "reagent.user";

/** Dispatched on `window` whenever the session is missing or rejected (401). */
export const AUTH_EVENT = "reagent:unauthorized";

function safeStorage() {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/**
 * Cached username/role from the last successful login, used only to avoid a
 * login-screen flash while the real session — an httpOnly cookie the JS layer
 * can't read — is verified against the server. Never treat this as proof of auth.
 */
export function getStoredUser() {
  try {
    const raw = safeStorage()?.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function setStoredUser(user) {
  try {
    const store = safeStorage();
    if (store && user) store.setItem(USER_KEY, JSON.stringify(user));
  } catch {
    // storage unavailable — cached hint lives only for this page load
  }
}

export function clearSession() {
  try {
    safeStorage()?.removeItem(USER_KEY);
  } catch {
    // ignore
  }
}

/** Reads the JS-visible XSRF-TOKEN cookie the backend sets alongside the httpOnly session cookie. */
function getCsrfToken() {
  try {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
  } catch {
    return null;
  }
}

function emitUnauthorized() {
  try {
    window.dispatchEvent(new CustomEvent(AUTH_EVENT));
  } catch {
    // non-browser / test environment
  }
}

function normalizeJsonValue(value) {
  if (typeof value === "string") return value.trim();
  if (Array.isArray(value)) return value.map(normalizeJsonValue);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, nestedValue]) => [
        key,
        normalizeJsonValue(nestedValue),
      ]),
    );
  }
  return value;
}

function prepareJsonBody(body) {
  if (typeof body === "string") {
    try {
      return JSON.stringify(normalizeJsonValue(JSON.parse(body)));
    } catch {
      return body;
    }
  }
  if (body && typeof body === "object") {
    return JSON.stringify(normalizeJsonValue(body));
  }
  return body;
}

function requestHeaders(method, headers = {}) {
  const csrfToken = method !== "GET" && method !== "HEAD" ? getCsrfToken() : null;
  return {
    "Content-Type": "application/json",
    // Double-submit CSRF token, required by the backend on state-changing
    // requests; absent on GET/HEAD and before the cookie is first set.
    ...(csrfToken ? { "X-XSRF-TOKEN": csrfToken } : {}),
    ...headers,
  };
}

/** Throws (and signals sign-out on 401) for an auth failure; no-op otherwise. */
function throwIfUnauthorized(response, auth) {
  if ((response.status === 401 || response.status === 403) && auth) {
    if (response.status === 401) {
      clearSession();
      emitUnauthorized();
    }
    const err = new Error(
      response.status === 401
        ? "Your session has expired. Please sign in again."
        : "You do not have access to this resource.",
    );
    err.status = response.status;
    throw err;
  }
}

export async function requestJson(path, options = {}) {
  const {
    timeoutMs = REQUEST_TIMEOUT_MS,
    headers = {},
    signal,
    fetchImpl = fetch,
    baseUrl = API_BASE_URL,
    auth = true,
    ...fetchOptions
  } = options;
  const normalizedBody = prepareJsonBody(fetchOptions.body);
  const method = (fetchOptions.method || "GET").toUpperCase();
  const controller = new AbortController();
  const abortRequest = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener("abort", abortRequest, { once: true });
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  let response;
  try {
    response = await fetchImpl(`${baseUrl}${path}`, {
      // The session lives in an httpOnly cookie; fetch only attaches it
      // cross-origin (frontend/backend run on different ports) when asked.
      credentials: "include",
      headers: requestHeaders(method, headers),
      ...fetchOptions,
      body: normalizedBody,
      signal: controller.signal,
    });
  } catch (err) {
    if (err.name === "AbortError") {
      throw new Error("The request took too long. Please try again.");
    }
    throw new Error(getNetworkErrorMessage(err, baseUrl));
  } finally {
    clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortRequest);
  }

  throwIfUnauthorized(response, auth);

  let text = "";
  try {
    text = await response.text();
  } catch {
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}`);
    }
    return null;
  }
  const data = parseResponseBody(text);
  if (!response.ok) {
    throw new Error(getErrorMessage(data, text, response.status));
  }
  return data;
}

/** True for the error `streamJson` throws when its `signal` was aborted by the caller. */
export function isAbortError(err) {
  return err?.name === "AbortError";
}

function abortError() {
  const err = new Error("Stopped.");
  err.name = "AbortError";
  return err;
}

/**
 * Incremental parser for a `text/event-stream` body. Feed it decoded text in
 * chunks of any size; it calls `onEvent(name, data)` once per complete event,
 * with `data` JSON-parsed when it is JSON.
 */
export function createSseParser(onEvent) {
  let buffer = "";

  function dispatch(block) {
    let name = "message";
    const data = [];
    for (const line of block.split("\n")) {
      if (!line || line.startsWith(":")) continue; // blank or comment
      const colon = line.indexOf(":");
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? "" : line.slice(colon + 1);
      if (value.startsWith(" ")) value = value.slice(1);
      if (field === "event") name = value;
      else if (field === "data") data.push(value);
    }
    if (!data.length) return;
    const raw = data.join("\n");
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      parsed = raw;
    }
    onEvent(name, parsed);
  }

  return {
    push(text) {
      buffer = (buffer + text).replace(/\r\n/g, "\n");
      let boundary;
      while ((boundary = buffer.indexOf("\n\n")) >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        dispatch(block);
      }
    },
  };
}

/**
 * POSTs `body` to a streaming endpoint and reads its server-sent events.
 * Progress events (`sources`, `step`, `delta`) go to `onEvent(name, data)`; the
 * promise resolves with the `done` event's data (the full response) or rejects
 * with the `error` event's message. Aborting `signal` rejects with an error for
 * which `isAbortError` is true. Errors before the stream starts (validation,
 * quota, rate limit) are ordinary JSON responses and are thrown like `requestJson`'s.
 */
export async function streamJson(path, options = {}) {
  const {
    body,
    onEvent,
    signal,
    timeoutMs = STREAM_TIMEOUT_MS,
    fetchImpl = fetch,
    baseUrl = API_BASE_URL,
  } = options;
  const controller = new AbortController();
  let timedOut = false;
  const abortRequest = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener("abort", abortRequest, { once: true });
  const timeoutId = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);
  const failFor = (err) => {
    if (signal?.aborted) return abortError();
    if (timedOut) return new Error("The request took too long. Please try again.");
    return err;
  };

  let reader;
  try {
    let response;
    try {
      response = await fetchImpl(`${baseUrl}${path}`, {
        method: "POST",
        credentials: "include",
        headers: requestHeaders("POST", { Accept: "text/event-stream" }),
        body: prepareJsonBody(body),
        signal: controller.signal,
      });
    } catch (err) {
      throw failFor(new Error(getNetworkErrorMessage(err, baseUrl)));
    }

    throwIfUnauthorized(response, true);
    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => "");
      throw new Error(getErrorMessage(parseResponseBody(text), text, response.status));
    }

    let result;
    let failure;
    const parser = createSseParser((name, data) => {
      if (name === "done") result = data;
      else if (name === "error") failure = new Error(data?.message || "The request failed.");
      else onEvent?.(name, data);
    });
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    try {
      while (result === undefined && !failure) {
        const { value, done } = await reader.read();
        if (done) break;
        parser.push(decoder.decode(value, { stream: true }));
      }
      parser.push(decoder.decode());
    } catch {
      throw failFor(new Error("The connection was interrupted. Please try again."));
    }
    if (failure) throw failure;
    if (result === undefined) {
      throw failFor(new Error("The response ended before it finished. Please try again."));
    }
    return result;
  } finally {
    clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortRequest);
    reader?.cancel().catch(() => {});
  }
}

export async function login(username, password) {
  const data = await requestJson("/auth/login", {
    auth: false,
    method: "POST",
    body: { username, password },
  });
  return finishAuth(data);
}

export async function register(username, password, signupCode) {
  const data = await requestJson("/auth/register", {
    auth: false,
    method: "POST",
    body: { username, password, signup_code: signupCode || undefined },
  });
  return finishAuth(data);
}

export function logout() {
  // Best-effort server-side session teardown; ignore any failure and clear locally.
  try {
    requestJson("/auth/logout", { method: "POST" }).catch(() => {});
  } catch {
    // ignore
  }
  clearSession();
  emitUnauthorized();
}

/** Current account plus remaining per-user quota (`{ id, username, quota }`). */
export async function fetchMe() {
  return requestJson("/auth/me");
}

/**
 * Permanently deletes the signed-in account and every repository it ingested,
 * then clears the local session. The session cookie is already dead server-side.
 */
export async function deleteAccount() {
  await requestJson("/auth/account", { method: "DELETE" });
  clearSession();
  emitUnauthorized();
}

function finishAuth(data) {
  const user = { username: data?.username, role: data?.role };
  setStoredUser(user);
  return { user, expiresInSeconds: data?.expires_in_seconds };
}

export async function ingestRepositoryAsync(repoUrl, options = {}) {
  const {
    pollIntervalMs = INGEST_POLL_INTERVAL_MS,
    timeoutMs = INGEST_JOB_TIMEOUT_MS,
    onJobUpdate,
    sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    ...requestOptions
  } = options;

  const job = await requestJson("/ingest/async", {
    ...requestOptions,
    method: "POST",
    body: JSON.stringify({ repo_url: repoUrl }),
  });
  onJobUpdate?.(job);

  const startedAt = Date.now();
  let currentJob = job;
  while (currentJob?.status === "PENDING" || currentJob?.status === "RUNNING") {
    if (Date.now() - startedAt >= timeoutMs) {
      throw new Error("Repository ingestion is still running. Check the job status and try again.");
    }
    await sleep(pollIntervalMs);
    currentJob = await requestJson(`/jobs/${encodeURIComponent(currentJob.job_id)}`, requestOptions);
    onJobUpdate?.(currentJob);
  }

  if (currentJob?.status === "SUCCEEDED") {
    return currentJob.result;
  }
  if (currentJob?.status === "FAILED") {
    throw new Error(currentJob.error || "Repository ingestion failed.");
  }
  throw new Error(`Repository ingestion ended with status ${currentJob?.status || "unknown"}.`);
}

export function parseResponseBody(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export function getErrorMessage(data, text, status) {
  const fieldMessage = formatFieldErrors(data?.fields || data?.violations);
  if (fieldMessage) return fieldMessage;
  if (data?.message && data?.error) return `${data.message}`;
  if (data?.message) return data.message;
  if (data?.detail) return data.detail;
  if (data?.error) return data.error;
  if (data && typeof data === "object") {
    const messages = Object.values(data).filter(
      (value) => typeof value === "string" && value.trim(),
    );
    if (messages.length > 0) return messages.join(", ");
  }
  if (text?.trim()) {
    return `Server returned ${status}: ${text.trim().slice(0, 240)}`;
  }
  return `Request failed with status ${status}`;
}

export function formatFieldErrors(fields) {
  if (!fields || typeof fields !== "object") return "";
  return Object.entries(fields)
    .filter(([, message]) => typeof message === "string" && message.trim())
    .map(([field, message]) => `${field}: ${message}`)
    .join(", ");
}

export function getNetworkErrorMessage(err, baseUrl = API_BASE_URL) {
  if (err instanceof TypeError) {
    return `Cannot reach backend at ${baseUrl}. Start the backend and try again.`;
  }
  return err?.message || "Network request failed. Please try again.";
}
