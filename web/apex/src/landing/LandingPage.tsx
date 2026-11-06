// Top-level public landing at `/`. Single long-scroll page: hero
// carries the smart CTA + a centered "Learn more" chevron; subsequent
// sections build the emotional → logical narrative (big-tech problem,
// Tenshu's answer, then deeper detail). Footer sits at the bottom of
// the full scroll. Marketing chrome replaces the post-auth `Layout`
// for this route only — that visual split signals "you're not inside
// Tenshu yet" once the user clicks through.
//
// State probe lives in `LandingCtaProvider`: one `Session.detect()`
// call per route mount, shared by every CTA + banner instance.
import { Block1Hero } from './blocks/Block1Hero'
import { Block2Problem } from './blocks/Block2Problem'
import { Block3Answer } from './blocks/Block3Answer'
import { Block4Realm } from './blocks/Block4Realm'
import { Block5Limits } from './blocks/Block5Limits'
import { Block6Multiplatform } from './blocks/Block6Multiplatform'
import { Block7Apps } from './blocks/Block7Apps'
import { Block8Hardware } from './blocks/Block8Hardware'
import { Block9Signup } from './blocks/Block9Signup'
import { BlockDevelopers } from './blocks/BlockDevelopers'
import { BlockFounder } from './blocks/BlockFounder'
import { BlockPublishing } from './blocks/BlockPublishing'
import { LandingCtaProvider } from './LandingCtaProvider'
import { MarketingFooter } from './MarketingFooter'
import { MarketingHeader } from './MarketingHeader'
import { SignedInBanner } from './SignedInBanner'
import { MARKETING_PAGE_CLASSES } from './marketingNav'

export function LandingPage() {
  return (
    <LandingCtaProvider>
      <div className={MARKETING_PAGE_CLASSES}>
        <SignedInBanner />
        <MarketingHeader />
        <main>
          <Block1Hero />
          <Block2Problem />
          <Block3Answer />
          <Block4Realm />
          <Block5Limits />
          <Block6Multiplatform />
          <Block7Apps />
          <BlockDevelopers />
          <BlockPublishing />
          <Block8Hardware />
          <BlockFounder />
          <Block9Signup />
        </main>
        <MarketingFooter />
      </div>
    </LandingCtaProvider>
  )
}
