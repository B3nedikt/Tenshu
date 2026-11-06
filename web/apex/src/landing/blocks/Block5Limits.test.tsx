// Block 5's threat-model link. ⚠ react-i18next parses `<link>` in a catalogue string as the
// void HTML element, so `<link>Read our threat model.</link>` rendered an EMPTY <a> with the
// words beside it — the catalogue tag is `<threatModel>` for that reason (see
// src/i18nCatalogueTags.test.ts for the guard over every catalogue).
import { afterEach, describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Block5Limits } from './Block5Limits'
import i18n from '../../i18n'

const THREAT_MODEL = 'https://github.com/B3nedikt/Tenshu/blob/master/docs/sync/threat-model.md'

describe('Block5Limits', () => {
  afterEach(async () => {
    await i18n.changeLanguage('en')
  })

  it.each([
    ['en', 'Read our threat model.'],
    ['de', 'Lies unser Bedrohungsmodell.'],
    ['fr', 'Lire notre modèle de menace.'],
  ])('in %s, the threat-model link carries its words and points at the threat model', async (lng, name) => {
    await i18n.changeLanguage(lng)
    render(<Block5Limits />)
    const link = screen.getByRole('link', { name })
    expect(link).toHaveAttribute('href', THREAT_MODEL)
    expect(link).toHaveTextContent(name)
  })
})
