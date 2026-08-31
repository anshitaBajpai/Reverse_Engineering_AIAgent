export const API_BASE_URL =
  import.meta.env?.VITE_API_BASE_URL || "http://127.0.0.1:8080";

export const REQUEST_TIMEOUT_MS = 300000;
export const INGEST_POLL_INTERVAL_MS = 2000;
export const INGEST_JOB_TIMEOUT_MS = REQUEST_TIMEOUT_MS;

const TOKEN_KEY = "reagent.token";
const USER_KEY = "reagent.user";

/** Dispatched on `window` whenever the stored token is missing or rejected (401). */
export const AUTH_EVENT = "reagent:unauthorized";

function safeStorage() {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function getToken() {
  try {
    return safeStorage()?.getItem(TOKEN_KEY) || null;
  } catch {
    return null;
  }
}

export function getStoredUser() {
  try {
    const raw = safeStorage()?.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function setSession(token, user) {
  try {
    const store = safeStorage();
    if (!store) return;
    if (token) store.setItem(TOKEN_KEY, token);
    if (user) store.setItem(USER_KEY, JSON.stringify(user));
  } catch {
    // storage unavailable — session lives only for this page load
  }
}

export function clearSession() {
  try {
    const store = safeStorage();
    store?.removeItem(TOKEN_KEY);
    store?.removeItem(USER_KEY);
  } catch {
    // ignore
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

export async function requestJson(path, options = {}) {
  const {
    timeoutMs = REQUEST_TIMEOUT_MS,
    headers = {},
    signal,
    fetchImpl = fetch,
    baseUrl = API_BASE_URL,
    auth = true,
    token = getToken(),
    ...fetchOptions
  } = options;
  const normalizedBody = prepareJsonBody(fetchOptions.body);
  const authHeaders = auth && token ? { Authorization: `Bearer ${token}` } : {};
  const controller = new AbortController();
  const abortRequest = () => controller.abort();
  if (signal?.aborted) controller.abort();
  signal?.addEventListener("abort", abortRequest, { once: true });
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
  let response;
  try {
    response = await fetchImpl(`${baseUrl}${path}`, {
      headers: {
        "Content-Type": "application/json",
        ...authHeaders,
        ...headers,
      },
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
  clearSession();
  emitUnauthorized();
}

/** Current account plus remaining per-user quota (`{ id, username, quota }`). */
export async function fetchMe() {
  return requestJson("/auth/me");
}

function finishAuth(data) {
  const token = data?.access_token;
  if (!token) {
    throw new Error("The server did not return an access token.");
  }
  const user = { username: data.username, role: data.role };
  setSession(token, user);
  return { token, user, expiresInSeconds: data.expires_in_seconds };
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
