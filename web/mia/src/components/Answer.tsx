// One of Mia's answers, by verdict — native `TenshuConversationRepository.bodyOf` + `MessageRow`:
//   - `answered` with hits → the citations cell: the explanation as its caption, then the rows,
//     each with its kind's glyph (native's `CitationsCell`);
//   - `answered` with none → "<explanation> — nothing matched." (the ONLY empty result there is);
//   - `noMatch` → the explanation alone. ⚠⚠ It covers "not understood" AND every refusal (a grant
//     switched off, an action this device withholds); the sentence says which. It is never rendered
//     as a result list, empty or not (mia-query-layer.md §14.1);
//   - `unavailable` → the explanation alone: Mia could not look, which asserts nothing.
//
// ⚠ A citation does not open its row here: cross-app open has no web route yet
// (control-cross-app-open.md), so the rows are text, not dead links.
import { useTranslation } from 'react-i18next'
import type { MiaAnswer, MiaHit } from '@tenshu/sdk/mia'
import { hitKind } from '../lib/hits'
import { HitIcon } from './HitIcon'

const BUBBLE = 'max-w-[85%] rounded-2xl px-4 py-2.5 text-sm'

export function Answer({ answer }: { answer: MiaAnswer }) {
  const { t } = useTranslation()
  if (answer.verdict === 'answered' && answer.hits.length > 0) {
    return (
      <div data-verdict="answered" className={`${BUBBLE} bg-surface-2 text-text`}>
        <p className="text-xs text-text-2">{answer.explanation}</p>
        <ul aria-label={t('chat.results')} className="mt-2 space-y-2">
          {answer.hits.map((hit, i) => (
            <HitRow key={`${hit.target.type}:${hit.target.keys.join('/')}:${i}`} hit={hit} />
          ))}
        </ul>
      </div>
    )
  }
  const text =
    answer.verdict === 'answered' ? t('chat.nothingMatched', { explanation: answer.explanation }) : answer.explanation
  return (
    <p data-verdict={answer.verdict} className={`${BUBBLE} bg-surface-2 text-text`}>
      {text}
    </p>
  )
}

function HitRow({ hit }: { hit: MiaHit }) {
  const { t } = useTranslation()
  const kind = hitKind(hit.target.type)
  return (
    <li className="flex items-start gap-2">
      <HitIcon kind={kind} />
      <div className="min-w-0">
        <p className="text-sm text-text">
          <span className="sr-only">{t(`hitKind.${kind}`)}: </span>
          {hit.title}
        </p>
        {hit.subtitle !== '' && <p className="text-xs text-text-2">{hit.subtitle}</p>}
      </div>
    </li>
  )
}

export function Question({ utterance }: { utterance: string }) {
  return (
    <div className="flex justify-end">
      <p className={`${BUBBLE} bg-primary text-on-primary`}>{utterance}</p>
    </div>
  )
}
