import type { ReactNode } from 'react'
import { render } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createAppQueryClient } from '@tenshu/app-kit/query'
import i18n from '../i18n'
import { LiveProvider } from '../lib/live'
import { FeedProvider } from '../lib/feed'
import { ThumbnailCacheContext, loadTile } from '../lib/thumbnails'
import { ThumbnailCache } from '../lib/thumbnailCache'

// Renders under a fresh QueryClient (no retries, like `mountApp`), the app's providers and a fresh
// thumbnail cache (returned, so a test can read its bound), in English.
export function renderWithProviders(ui: ReactNode, options: { cache?: ThumbnailCache } = {}) {
  void i18n.changeLanguage('en')
  const client = createAppQueryClient()
  const cache = options.cache ?? new ThumbnailCache(loadTile)
  const result = render(
    <QueryClientProvider client={client}>
      <ThumbnailCacheContext.Provider value={cache}>
        <LiveProvider>
          <FeedProvider>{ui}</FeedProvider>
        </LiveProvider>
      </ThumbnailCacheContext.Provider>
    </QueryClientProvider>,
  )
  return { ...result, client, cache }
}
