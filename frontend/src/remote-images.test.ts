import { describe, expect, it } from 'vitest';
import { remoteImageResult } from './remote-images';

describe('remote image loading results', () => {
  const status = (total: number, loaded: number, failed: number) =>
    remoteImageResult({ type: 'dispatch-image-status', view: '2', total, loaded, failed }, '2');

  it('waits for every image before claiming success', () => {
    expect(status(2, 1, 0)).toEqual({ label: 'Loading remote images…', retry: false });
    expect(status(2, 2, 0)).toEqual({ label: 'Remote images loaded', retry: false });
  });

  it('reports partial and complete failures and permits a retry', () => {
    expect(status(2, 1, 1)).toEqual({ label: 'Some remote images could not be loaded', retry: true });
    expect(status(2, 0, 2)).toEqual({ label: 'Remote images could not be loaded', retry: true });
  });

  it('does not claim that an empty message loaded images', () => {
    expect(status(0, 0, 0)).toEqual({ label: 'No remote images', retry: false });
  });

  it('ignores results from a previous frame and malformed counts', () => {
    expect(remoteImageResult({ type: 'dispatch-image-status', view: '1', total: 1, loaded: 1, failed: 0 }, '2')).toBeNull();
    expect(status(1, 2, 0)).toBeNull();
    expect(status(1, -1, 0)).toBeNull();
    expect(status(1, NaN, 0)).toBeNull();
    expect(remoteImageResult({ type: 'dispatch-image-status', view: '2', total: '1', loaded: 1, failed: 0 }, '2')).toBeNull();
  });
});
