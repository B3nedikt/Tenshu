// react-i18next bootstrap — the app-kit's (`initAppI18n`): ICU MessageFormat, the browser's
// language by default, and bundled JSON catalogues so init is synchronous and tests need no
// ready-wait. Languages: en, de, fr — every key in all three; `i18n.test.ts` fails on a missing one.
import { initAppI18n } from '@tenshu/app-kit/i18n'

import en from '../locales/en/home.json'
import de from '../locales/de/home.json'
import fr from '../locales/fr/home.json'

const i18n = initAppI18n('home', { en, de, fr })

export default i18n
