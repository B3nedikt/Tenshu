// react-i18next bootstrap for Contacts — the app-kit's (`initAppI18n`, the apex's setup per ADR
// 0016): ICU MessageFormat, the browser's language by default, and bundled JSON catalogues so init
// is synchronous and tests need no ready-wait.
//
// Languages: en, de, fr — every key in all three; `i18n.test.ts` fails on a missing one.
import { appLanguages, initAppI18n } from '@tenshu/app-kit/i18n'

import en from '../locales/en/contacts.json'
import de from '../locales/de/contacts.json'
import fr from '../locales/fr/contacts.json'

export const supportedLanguages = appLanguages

const i18n = initAppI18n('contacts', { en, de, fr })

export default i18n
