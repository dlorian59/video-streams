// Bound response bytes and stop waiting even when a custom transport ignores cancellation.
// Ограничиваем байты ответа и прекращаем ожидание даже при игнорировании отмены transport-ом.
export async function readBody(response: Response, limit: number, signal: AbortSignal, onBytes: (bytes: number) => void = () => {}): Promise<Uint8Array<ArrayBuffer>> {
  const length = response.headers.get('content-length');
  if (length !== null && (!/^\d+$/.test(length) || !Number.isSafeInteger(Number(length)) || Number(length) > limit)) {
    void response.body?.cancel().catch(() => {}); throw new Error('Invalid or oversized video response');
  }
  if (!response.body) throw new Error('Missing response body');
  const reader = response.body.getReader(); const chunks: Uint8Array[] = []; let count = 0;
  const cancel = () => { void reader.cancel(signal.reason).catch(() => {}); };
  signal.addEventListener('abort', cancel, { once: true });
  try {
    while (true) {
      signal.throwIfAborted();
      const item = await new Promise<ReadableStreamReadResult<Uint8Array>>((resolve, reject) => {
        const abort = () => reject(signal.reason); signal.addEventListener('abort', abort, { once: true });
        void reader.read().then(resolve, reject).finally(() => signal.removeEventListener('abort', abort));
        if (signal.aborted) abort();
      });
      signal.throwIfAborted(); if (item.done) break;
      count += item.value.byteLength; if (count > limit) throw new Error('Video response exceeds the byte limit');
      chunks.push(item.value.slice()); onBytes(item.value.byteLength);
    }
    const data = new Uint8Array(count); let offset = 0;
    for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.byteLength; }
    return data;
  } catch (error) { cancel(); throw error; }
  finally { signal.removeEventListener('abort', cancel); reader.releaseLock(); }
}

export function abortable<T>(pending: PromiseLike<T>, signal: AbortSignal, late?: (value: T) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    let stopped = false;
    const abort = () => { stopped = true; signal.removeEventListener('abort', abort); reject(signal.reason); };
    signal.addEventListener('abort', abort, { once: true });
    void Promise.resolve(pending).then(value => {
      signal.removeEventListener('abort', abort);
      if (stopped || signal.aborted) { late?.(value); reject(signal.reason); } else resolve(value);
    }, error => { signal.removeEventListener('abort', abort); reject(error); });
    if (signal.aborted) abort();
  });
}
