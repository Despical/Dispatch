export type RemoteImageControl = { label: string; retry: boolean };

export function remoteImageResult(data: unknown, view: string): RemoteImageControl | null {
  if (!data || typeof data !== 'object') return null;
  const result = data as { type?: unknown; view?: unknown; total?: unknown; loaded?: unknown; failed?: unknown };
  if (result.type !== 'dispatch-image-status' || result.view !== view) return null;
  const { total, loaded, failed } = result;
  if (![total, loaded, failed].every(value => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0)) return null;
  const count = total as number, success = loaded as number, errors = failed as number;
  if (success + errors > count) return null;
  if (count === 0) return { label: 'No remote images', retry: false };
  if (success + errors < count) return { label: 'Loading remote images…', retry: false };
  if (errors === 0) return { label: 'Remote images loaded', retry: false };
  return { label: success > 0 ? 'Some remote images could not be loaded' : 'Remote images could not be loaded', retry: true };
}
