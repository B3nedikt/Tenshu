// "You" — native `ProfileScreen` (D6): the platform-level name people see when you invite them.
// Live through `contact.ownProfile.observe`.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useOwnProfile } from '../lib/live'
import { setOwnDisplayName } from '../lib/proxy'
import { Button, ErrorText, PromptDialog } from './ui'
import { attempt } from '../lib/outcome'
import { StreamNotice } from './StreamNotice'

export function ProfileView() {
  const { t } = useTranslation()
  const live = useOwnProfile()
  const [editing, setEditing] = useState(false)
  const name = live.data?.displayName ?? ''

  return (
    <section className="space-y-4">
      <StreamNotice live={live} />
      <div className="rounded-xl border border-line p-5">
        <p className="text-sm text-text-2">{t('profile.nameLabel')}</p>
        {live.isPending ? (
          <p className="mt-1 text-sm text-text-3">{t('common.loading')}</p>
        ) : live.error && !live.data ? (
          <ErrorText error={live.error} />
        ) : (
          <p className="mt-1 text-lg font-medium text-text">{name || t('profile.nameUnset')}</p>
        )}
        <p className="mt-1 text-xs text-text-3">{t('profile.nameSupporting')}</p>
        <Button className="mt-3" size="sm" variant="outline" disabled={live.isPending} onClick={() => setEditing(true)}>
          {t('profile.editName')}
        </Button>
      </div>
      <div className="rounded-xl border border-line p-5">
        <p className="text-sm font-medium text-text">{t('profile.identityTitle')}</p>
        {/* `fingerprint` is always '' today: there is no client-readable own-identity op. */}
        <p className="mt-1 text-sm text-text-2">{t('profile.identityPending')}</p>
      </div>
      {editing && (
        <PromptDialog
          title={t('profile.editName')}
          label={t('profile.nameLabel')}
          initialValue={name}
          submitLabel={t('common.save')}
          allowEmpty
          onClose={() => setEditing(false)}
          onSubmit={(value) => attempt(() => setOwnDisplayName(value))}
        />
      )}
    </section>
  )
}
