export const shortSha = (sha) => (sha ? sha.slice(0, 7) : "Unknown");

export const escapeFilename = (value) =>
  String(value)
    .trim()
    .replace(/[\\/:*?"<>|]+/g, "-")
    .slice(0, 80);

/** "3 of 20 questions left." / the exhausted message, or null when unlimited. */
export function quotaMessage(used, limit, noun) {
  if (!limit || limit <= 0) return null;
  if (used >= limit) {
    return `You've used all your ${noun}s for today. The limit resets tomorrow.`;
  }
  const left = limit - used;
  return `${left} of ${limit} ${noun}${limit === 1 ? "" : "s"} left today.`;
}

export function isExhausted(used, limit) {
  return limit > 0 && used >= limit;
}
