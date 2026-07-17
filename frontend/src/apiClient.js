export const API_BASE_URL =
  import.meta.env?.VITE_API_BASE_URL || "http://127.0.0.1:8080";

export const REQUEST_TIMEOUT_MS = 300000;

export async function requestJson(path, options = {}) {
  const {
    timeoutMs = REQUEST_TIMEOUT_MS,
    headers = {},
    signal,
    fetchImpl = fetch,
    baseUrl = API_BASE_URL,
    ...fetchOptions
  } = options;
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
