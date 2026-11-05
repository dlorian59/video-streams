import {
  createVideoStreams, prepareVideo,
  type PreparedVideo, type VideoSession, type VideoState,
} from '@59alex/video-streams';

export interface PreparedPlayback {
  readonly asset: PreparedVideo;
  readonly session: VideoSession;
}

// The caller owns the AbortController and the existing authenticated session.
// Вызывающий код управляет AbortController и существующей авторизованной сессией.
export async function prepareAndAttach(
  video: HTMLVideoElement,
  sourceKey: string,
  identity: { scope: string; csrfHeader: string; csrfToken: string },
  signal: AbortSignal,
  onState: (state: VideoState) => void,
): Promise<PreparedPlayback> {
  // sourceKey must match the previously committed upload key, not a storage URL.
  // sourceKey должен совпадать с ключом завершённой загрузки, а не URL хранилища.
  const asset = await prepareVideo({
    prepareUrl: `/api/videos/${encodeURIComponent(sourceKey)}/prepare`,
    sourceKey,
    scope: identity.scope,
    credentials: 'same-origin',
    headers: { [identity.csrfHeader]: identity.csrfToken },
    signal,
  });
  signal.throwIfAborted();
  const player = createVideoStreams({ profile: 'preview', credentials: 'same-origin' });
  const session = player.attach(video, {
    playlistUrl: asset.playlistUrl,
    scope: identity.scope,
    preload: 'preview',
    signal,
    onState,
  });
  // Start play from a user gesture; call session.dispose() and abort on unmount/logout.
  // Запускайте play по действию пользователя; при уходе/выходе вызывайте dispose и abort.
  return { asset, session };
}
