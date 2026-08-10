export const API_BASE_URL =
  import.meta.env?.VITE_API_BASE_URL || "http://127.0.0.1:8080";

export const REQUEST_TIMEOUT_MS = 300000;
export const INGEST_POLL_INTERVAL_MS = 2000;
export const INGEST_JOB_TIMEOUT_MS = REQUEST_TIMEOUT_MS;

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
    ...fetchOptions
  } = options;
  const normalizedBody = prepareJsonBody(fetchOptions.body);
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
