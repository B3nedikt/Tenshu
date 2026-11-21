# Generated people — image prompts

The faces for Photos' People and Contacts' portraits
([`store-screenshot-stories.md`](../../../docs/proposals/store-screenshot-stories.md) §4.1a). Every
person is invented; their names, jobs and birthdays are in
[`../contacts/manifest.json`](../contacts/manifest.json). Paste each prompt as it stands — each one
already carries the style block, so none depends on another conversation turn.

**How to run them**

- **Recurring people (A–E):** generate prompt 1 (the portrait) first, pick the best of a few, then
  attach it as the face reference for prompts 2–6. Keep the same reference for all five.
- **Group photos (F):** attach every portrait the scene names.
- **Single portraits (G):** one prompt each, no reference.
- ⚠ **The model has a default face** — a woman in her late 20s with dark curls and gold hoops. It
  replaced Elena twice and came back as four different Contacts. Attach the portrait for every
  named person, and for a new face say what she must *not* look like.
- **Ask for one full-size image, never a collage** — a crop of a 2×2 grid is 768 px wide.
- **Paste one prompt per request.** A batch request ("a café shot for everyone") came back as five
  near-identical laptop selfies, and two of them were a different person — vary the scene, keep the face.
- Reject an image with visible text, a logo, extra fingers, warped glasses or jewellery, or a face
  that drifts from the reference — People will split a drifting face into two people.

**Where the files go** (never committed):

- Portraits: `~/Movies/Tenshu promo/people/contacts/<Given> <Family>.jpg` — exactly the manifest
  name, e.g. `Hannah Becker.jpg`, `Ana Lucía Torres.jpg`. A PNG straight from the generator works too —
  `generate_contacts.py` re-encodes it to a 512 px JPEG.
- Everything else: `~/Movies/Tenshu promo/photos/<Album>/`, album named under each prompt.
- Before staging, give each file the date suggested under its prompt, e.g.
  `touch -t 202607141830 "Hannah's birthday/02.jpg"` — a generated image has no capture date.

---

## A. Hannah Becker — sister, 26, German

Look: long wavy dark-blonde hair, usually in a messy bun with loose strands, light freckles across
nose and cheeks, small gold hoop earrings, no glasses, slim, casual style (knit sweaters, denim).

**A1 — portrait** ✓ generated 2026-11-19 — use that file as the reference → `people/contacts/Hannah Becker.jpg` (4:5)
> Head-and-shoulders portrait of a 26-year-old German woman with long wavy dark-blonde hair in a
> messy bun with loose strands, light freckles across her nose and cheeks, small gold hoop earrings, wearing an
> oatmeal knit sweater. She is outdoors in a park on an overcast day, the background soft green and
> out of focus, looking into the camera with a small relaxed smile. Candid photo taken on a recent
> smartphone's main camera, natural available light, ordinary framing, slight sensor noise,
> realistic skin texture with pores and small imperfections, not retouched, no studio lighting, no
> exaggerated background blur. No text, no logos, no brand names, no readable signs.

**A2 — café** ⏸ held out: `Weekends/IMG_1612` (with the F3 hike it formed a second Hannah) → album *Weekends*, date 2026-03-08 11:20 (4:3)
> The same woman as in the reference image, laughing at something off camera while sitting at a
> small wooden café table with a cappuccino and a half-eaten croissant, seen from across the table,
> morning light from a window beside her. Candid photo taken on a recent smartphone's main camera,
> natural available light, ordinary framing, slight sensor noise, realistic skin texture, not
> retouched, no studio lighting, no exaggerated background blur. No text, no logos, no brand
> names, no readable signs.

**A3 — autumn walk** ✓ `Weekends/IMG_0987` (riverside park) → album *Weekends*, date 2025-10-19 15:40 (3:4)
> The same woman as in the reference image walking along a forest path covered in yellow and orange
> leaves, wearing a dark green rain jacket and a scarf, three-quarter view, glancing toward the
> camera. Late afternoon autumn light. Candid photo taken on a recent smartphone's main camera,
> natural available light, ordinary framing, slight sensor noise, realistic skin texture, not
> retouched, no studio lighting, no exaggerated background blur. No text, no logos, no brand names,
> no readable signs.

**A4 — cooking** ✓ `Home/IMG_1402` → album *Home*, date 2026-01-24 19:10 (4:3)
> The same woman as in the reference image in a small home kitchen, stirring a pot of pasta sauce
> with a wooden spoon, sleeves pushed up, steam rising, warm evening lamp light, a few
> vegetables on the counter. Candid photo taken on a recent smartphone's main camera, natural
> available light, ordinary framing, slight sensor noise, realistic skin texture, not retouched, no
> studio lighting. No text, no logos, no brand names, no readable labels.

**A5 — trip** ✓ `Lisbon with Hannah/IMG_0598` (full resolution) → album *Lisbon with Hannah*, date 2025-06-02 18:30 (4:3)
> The same woman as in the reference image standing at a stone railing of a hilltop viewpoint above
> an old city of terracotta roofs and a river, golden hour, wind in her hair, seen from a few metres
> away, smiling at the camera. Candid photo taken on a recent smartphone's main camera, natural
> available light, ordinary framing, slight sensor noise, realistic skin texture, not retouched, no
> exaggerated background blur. No text, no logos, no brand names, no readable signs.

**A6 — selfie** ✓ filled by a café selfie (`Weekends/IMG_1844`) → album *Weekends*, date 2026-05-17 13:05 (3:4)
> A selfie taken at arm's length by the same woman as in the reference image, slightly off-centre,
> sitting on a picnic blanket in a sunny park, squinting a little in the sunlight, sunglasses pushed
> up into her messy bun. Candid photo taken on a recent smartphone's front camera,
> natural light, slight wide-angle distortion, realistic skin texture, not retouched. No text, no
> logos, no brand names.

---

## B. Elena Petrova — mum, 54, piano teacher

> ⚠⚠ **All five scene shots are held out:** they drew a blonde woman of ~40, and People merged her
> with Hannah (`store-screenshot-stories.md` §4.1a). A redo must look 54 — grey-streaked ash-brown
> bob, laugh lines, the B1 portrait attached — and must pass `check_people.py` before it goes back in.

Look: chin-length ash-brown bob, fair skin, laugh lines, small pearl stud earrings, elegant but
relaxed (cardigans, a silk scarf).

**B1 — portrait** ✓ generated 2026-11-19 — use that file as the reference → `people/contacts/Elena Petrova.jpg` (4:5)
> Head-and-shoulders portrait of a 54-year-old woman with a chin-length ash-brown bob, fair skin,
> laugh lines around her eyes, small pearl stud earrings, wearing a navy cardigan over a cream
> blouse. She is in a garden on a bright cloudy day, soft green background, smiling warmly at the
> camera. Candid photo taken on a recent smartphone's main camera, natural available light,
> ordinary framing, slight sensor noise, realistic skin texture with fine lines and small
> imperfections, not retouched, no studio lighting, no exaggerated background blur. No text, no
> logos, no brand names, no readable signs.

**B2 — at the piano** ⏸ held out: `Home/IMG_1311` → album *Home*, date 2025-12-26 17:00 (4:3)
> The same woman as in the reference image sitting at an upright piano in a cosy living room,
> turning toward the camera with a smile, hands still on the keys, warm lamp light, sheet music
> without readable notes. Candid photo taken on a recent smartphone's main camera, natural available
> light, ordinary framing, slight sensor noise, realistic skin texture, not retouched, no studio
> lighting. No text, no logos, no brand names, no readable writing.

**B3 — garden** ⏸ held out: `Visiting Mum/IMG_1720` (hydrangeas) → album *Visiting Mum*, date 2026-04-12 10:30 (3:4)
> The same woman as in the reference image kneeling in a small garden, planting tulip bulbs, wearing
> gardening gloves and a light jacket, three-quarter view, looking up at the camera. Spring morning
> light. Candid photo taken on a recent smartphone's main camera, natural available light, ordinary
> framing, slight sensor noise, realistic skin texture, not retouched. No text, no logos, no brand
> names, no readable signs.

**B4 — dinner** ⏸ held out: `Visiting Mum/IMG_1726` → album *Visiting Mum*, date 2026-04-12 19:45 (4:3)
> The same woman as in the reference image at a dinner table at home, raising a glass of red wine in
> a toast, a roast dish and salad on the table, warm evening light, seen from across the table.
> Candid photo taken on a recent smartphone's main camera, natural available light, ordinary
> framing, slight sensor noise, realistic skin texture, not retouched, no studio lighting. No text,
> no logos, no brand names, no readable labels.

**B5 — trip** ⏸ held out: `Coast trip/IMG_0801` (beach walk) → album *Coast trip*, date 2025-08-21 16:15 (4:3)
> The same woman as in the reference image walking along a sandy beach at the water's edge,
> trousers rolled up, holding her sandals, wind in her hair, seen from a few metres away, laughing.
> Bright summer afternoon. Candid photo taken on a recent smartphone's main camera, natural light,
> ordinary framing, slight sensor noise, realistic skin texture, not retouched. No text, no logos,
> no brand names.

**B6 — selfie** ⏸ held out: `Visiting Mum/IMG_1561` (snow) → album *Visiting Mum*, date 2026-02-14 12:00 (3:4)
> A slightly awkward selfie taken at arm's length by the same woman as in the reference image, a
> little too close and tilted, on a winter walk wearing a wool coat and a scarf, bare trees behind
> her. Candid photo taken on a recent smartphone's front camera, natural light, slight wide-angle
> distortion, realistic skin texture, not retouched. No text, no logos, no brand names.

---

## C. Amara Okafor — friend, 29, paediatrician

Look: long thin twists worn half-up in a bun, deep brown skin, small gold hoop earrings, bright smile,
casual clothes (knitwear, an olive jacket).

**C1 — portrait** ✓ generated 2026-11-19 — use that file as the reference → `people/contacts/Amara Okafor.jpg` (4:5)
> Head-and-shoulders portrait of a 29-year-old Nigerian-British woman with long thin twists
> worn half-up in a bun, deep brown skin, small gold hoop earrings, wearing a cream knit sweater
> under an olive jacket. She is outdoors on
> a city street with trees on an overcast day, the background soft, smiling broadly at the camera.
> Candid photo taken on a recent smartphone's main camera, natural available light, ordinary
> framing, slight sensor noise, realistic skin texture with pores and small imperfections, not
> retouched, no studio lighting, no exaggerated background blur. No text, no logos, no brand names,
> no readable signs.

**C2 — café** ✓ `Weekends/IMG_1532` → album *Weekends*, date 2026-02-01 10:50 (4:3)
> The same woman as in the reference image at a café table holding a mug of tea with both hands,
> mid-laugh, a plate of pastries in front of her, seen from across the table, soft window light.
> Candid photo taken on a recent smartphone's main camera, natural available light, ordinary
> framing, slight sensor noise, realistic skin texture, not retouched, no studio lighting. No text,
> no logos, no brand names, no readable signs.

**C3 — autumn walk** ✓ `Weekends/IMG_1104` → album *Weekends*, date 2025-11-09 14:20 (3:4)
> The same woman as in the reference image walking through a park with fallen leaves, wearing a
> camel coat and a patterned scarf, three-quarter view, turning to say something to the camera.
> Overcast autumn light. Candid photo taken on a recent smartphone's main camera, natural available
> light, ordinary framing, slight sensor noise, realistic skin texture, not retouched. No text, no
> logos, no brand names, no readable signs.

**C4 — dinner host** ✓ `Dinner at Amara's/IMG_1650` (cooking jollof, 18:40) → album *Dinner at Amara's*, date 2026-03-21 20:00 (4:3)
> The same woman as in the reference image carrying a large bowl of jollof rice to a set dinner
> table in her flat, smiling at the camera, string lights on the wall, warm evening light. Candid
> photo taken on a recent smartphone's main camera, natural available light, ordinary framing,
> slight sensor noise, realistic skin texture, not retouched, no studio lighting. No text, no logos,
> no brand names, no readable labels.

**C5 — trip** ✓ `Coast trip/IMG_0812` → album *Coast trip*, date 2025-08-22 11:00 (4:3)
> The same woman as in the reference image sitting on a rock at a rugged coastline, wearing a denim
> jacket, the sea behind her, seen from a few metres away, looking at the camera. Bright, windy
> morning. Candid photo taken on a recent smartphone's main camera, natural light, ordinary framing,
> slight sensor noise, realistic skin texture, not retouched, no exaggerated background blur. No
> text, no logos, no brand names, no readable signs.

**C6 — selfie** ✓ filled by a café selfie (`Weekends/IMG_2031`) → album *Weekends*, date 2026-06-06 16:30 (3:4)
> A selfie taken at arm's length by the same woman as in the reference image, slightly off-centre,
> on a sunny rooftop terrace, wearing sunglasses on her head, a glass of lemonade in her other hand.
> Candid photo taken on a recent smartphone's front camera, natural light, slight wide-angle
> distortion, realistic skin texture, not retouched. No text, no logos, no brand names.

---

## D. Tomás Oliveira — friend, 28, coffee roaster

Look: dark curly hair, full trimmed beard, olive skin, warm brown eyes, plain T-shirts and an
overshirt or a canvas apron.

**D1 — portrait** ✓ generated 2026-11-19 — use that file as the reference → `people/contacts/Tomás Oliveira.jpg` (4:5)
> Head-and-shoulders portrait of a 28-year-old Portuguese man with short dark wavy hair, a full
> neatly trimmed beard, olive skin, wearing a plain grey T-shirt under an olive overshirt. He is
> outdoors in a narrow old-town street on a cloudy day, the background soft, looking into the
> camera with a calm half smile. Candid photo taken on a recent smartphone's main camera, natural
> available light, ordinary framing, slight sensor noise, realistic skin texture with pores and
> small imperfections, not retouched, no studio lighting, no exaggerated background blur. No text,
> no logos, no brand names, no readable signs.

**D2 — at work** ✓ `Weekends/IMG_1371` → album *Weekends*, date 2026-01-11 09:40 (4:3)
> The same man as in the reference image in a small coffee roastery, wearing a plain canvas apron,
> holding a scoop of freshly roasted coffee beans toward the camera and laughing, sacks of beans
> without any printing behind him. Candid photo taken on a recent smartphone's main camera, natural
> window light, ordinary framing, slight sensor noise, realistic skin texture, not retouched. No
> text, no logos, no brand names, no readable labels.

**D3 — autumn walk** ✓ `Weekends/IMG_1019` (mountain trail) → album *Weekends*, date 2025-10-26 16:10 (3:4)
> The same man as in the reference image walking on a hillside trail with autumn trees, wearing a
> rust-coloured jacket and a small backpack, three-quarter view, looking back over his shoulder at
> the camera. Late afternoon light. Candid photo taken on a recent smartphone's main camera, natural
> light, ordinary framing, slight sensor noise, realistic skin texture, not retouched. No text, no
> logos, no brand names, no readable signs.

**D4 — dinner** ✓ `Dinner at Amara's/IMG_1655` (Amara beside him) → album *Dinner at Amara's*, date 2026-03-21 20:30 (4:3)
> The same man as in the reference image at a dinner table in a friend's flat, refilling glasses of
> water, string lights behind him, plates of food on the table, looking up at the camera with a
> grin. Warm evening light. Candid photo taken on a recent smartphone's main camera, natural
> available light, ordinary framing, slight sensor noise, realistic skin texture, not retouched, no
> studio lighting. No text, no logos, no brand names, no readable labels.

**D5 — trip** ✓ `Lisbon with Hannah/IMG_0615` (selfie with the tram); the café shot `IMG_0612` is the same morning → album *Lisbon with Hannah*, date 2025-06-03 12:15 (4:3)
> The same man as in the reference image standing on a steep cobbled street lined with tiled
> facades, an old yellow tram passing in the distance, seen from a few metres away, squinting in the
> midday sun. Candid photo taken on a recent smartphone's main camera, natural light, ordinary
> framing, slight sensor noise, realistic skin texture, not retouched, no exaggerated background
> blur. No text, no logos, no brand names, no readable signs or numbers.

**D6 — selfie** ✓ `Weekends/IMG_1760` (mountain sunrise) → album *Weekends*, date 2026-04-25 08:50 (3:4)
> A selfie taken at arm's length by the same man as in the reference image, slightly off-centre, on
> a bicycle path in the morning, wearing a cycling cap, a river and trees behind him. Candid photo
> taken on a recent smartphone's front camera, natural light, slight wide-angle distortion,
> realistic skin texture, not retouched. No text, no logos, no brand names.

---

## E. Kwame Mensah — friend, 33, solicitor

Look: short twisted hair, full trimmed beard, dark skin, small gold hoop in one ear, no glasses, smart-casual (shirts,
a navy coat).

**E1 — portrait** ✓ replaced 2026-11-19 by a crop of E6 — five scene shots in a row kept a face with twists and no glasses, so that face became Kwame (the shaved-head one is in `people/retired/`) → `people/contacts/Kwame Mensah.jpg` (4:5)
> Head-and-shoulders portrait of a 33-year-old Ghanaian-British man with short twisted hair, a full
> trimmed beard, dark skin and a small gold hoop in one ear, wearing a light blue shirt with the top button
> open. He is outdoors by a brick wall with ivy on an overcast day, the background soft, smiling at
> the camera. Candid photo taken on a recent smartphone's main camera, natural available light,
> ordinary framing, slight sensor noise, realistic skin texture with pores and small imperfections,
> not retouched, no studio lighting, no exaggerated background blur. No text, no logos, no brand
> names, no readable signs.

**E2 — café** ✓ `Weekends/IMG_1590` → album *Weekends*, date 2026-02-22 12:30 (4:3)
> The same man as in the reference image at a café table reading a paperback book without a visible
> title, looking up at the camera with a surprised smile, a flat white beside him, window light.
> Candid photo taken on a recent smartphone's main camera, natural available light, ordinary
> framing, slight sensor noise, realistic skin texture, not retouched, no studio lighting. No text,
> no logos, no brand names, no readable writing.

**E3 — autumn walk** ✓ `Weekends/IMG_1131` → album *Weekends*, date 2025-11-16 15:00 (3:4)
> The same man as in the reference image walking along a river embankment lined with autumn trees,
> wearing a navy wool coat, hands in pockets, three-quarter view, glancing at the camera. Grey autumn
> afternoon. Candid photo taken on a recent smartphone's main camera, natural light, ordinary
> framing, slight sensor noise, realistic skin texture, not retouched. No text, no logos, no brand
> names, no readable signs.

**E4 — dinner** ✓ `Dinner at Amara's/IMG_1658` (Tomás and Amara at the table) → album *Dinner at Amara's*, date 2026-03-21 21:00 (4:3)
> The same man as in the reference image at a dinner table in a friend's flat, telling a story with
> his hands, laughing, plates and glasses on the table, string lights behind him, warm evening
> light. Candid photo taken on a recent smartphone's main camera, natural available light, ordinary
> framing, slight sensor noise, realistic skin texture, not retouched, no studio lighting. No text,
> no logos, no brand names, no readable labels.

**E5 — trip** ✓ `Coast trip/IMG_0819` (harbour) → album *Coast trip*, date 2025-08-22 18:40 (4:3)
> The same man as in the reference image on a harbour wall at sunset, small fishing boats behind
> him, wearing a linen shirt, seen from a few metres away, looking at the camera. Golden light.
> Candid photo taken on a recent smartphone's main camera, natural light, ordinary framing, slight
> sensor noise, realistic skin texture, not retouched, no exaggerated background blur. No text, no
> logos, no brand names, no readable boat names or numbers.

**E6 — selfie** ✓ `Coast trip/IMG_0821` (coastal sunset, not Weekends) — also the source of E1 → album *Weekends*, date 2026-05-30 14:10 (3:4)
> A selfie taken at arm's length by the same man as in the reference image, slightly off-centre, at
> an outdoor football pitch on a sunny day, wearing a plain sports T-shirt, slightly out of breath
> and smiling. Candid photo taken on a recent smartphone's front camera, natural light, slight
> wide-angle distortion, realistic skin texture, not retouched. No text, no logos, no brand names,
> no numbers on clothing.

---

## F. Group photos

Attach the portraits of everyone named.

**F1 — birthday** (Hannah, Elena) ⏸ held out: `Hannah's birthday/IMG_1705` (third attempt — right faces, Elena finally 54-ish; clean in path order but splits someone in 86% of shuffled orders) → album *Hannah's birthday*, date 2026-04-21 19:30 (4:3)
> The young woman and the older woman from the reference images sitting side by side at a kitchen
> table, the young woman about to blow out candles on a homemade chocolate cake, the older woman
> with an arm around her shoulders, both laughing, warm evening light, a few balloons in the
> background. Candid photo taken on a recent smartphone's main camera, natural available light,
> ordinary framing, slight sensor noise, realistic skin texture, not retouched. No text, no
> numbers on the cake or candles, no logos, no brand names.

**F2 — dinner** (Amara, Tomás, Kwame) ⏸ held out: `Dinner at Amara's/IMG_1661` (its profile faces split into two unnamed people) → album *Dinner at Amara's*, date 2026-03-21 21:20 (4:3)
> The three people from the reference images around a dinner table in a small flat with string
> lights, mid-conversation and laughing, plates of jollof rice and salad, glasses of wine, seen from
> the end of the table as if the photographer is a fourth guest. Candid photo taken on a recent
> smartphone's main camera, warm evening light, ordinary framing, slight sensor noise, realistic
> skin texture, not retouched, no studio lighting. No text, no logos, no brand names, no readable
> labels.

**F3 — hike** (Hannah, Tomás) ✓ `Weekends/IMG_1012` (thermos, 13:00) → album *Weekends*, date 2025-10-26 13:00 (4:3)
> The two people from the reference images resting on a rock on a hiking trail above a valley with
> autumn forests, sharing a thermos of tea, both turned toward the camera smiling, small backpacks
> beside them. Bright autumn day. Candid photo taken on a recent smartphone's main camera, natural
> light, ordinary framing, slight sensor noise, realistic skin texture, not retouched. No text, no
> logos, no brand names, no readable signs.

**F4 — picnic** (all five) ⏸ held out: `Weekends/IMG_2050` (third attempt — right faces; with F1 and without F3 it makes FIVE people, Elena from two group shots, but merges or splits in 90% of shuffled orders) → album *Weekends*, date 2026-06-13 15:30 (4:3)
> The five people from the reference images having a picnic on a blanket in a sunny park: the
> young freckled woman with a messy blonde bun, the older woman with the bob, the woman with long
> twists, the curly-haired bearded man and the man with short twists and a gold earring. Fruit, bread and drinks on the blanket, everyone
> relaxed and laughing, seen from a standing position a few metres away. Candid photo taken on a
> recent smartphone's main camera, natural summer light, ordinary framing, slight sensor noise,
> realistic skin texture, not retouched. No text, no logos, no brand names, no readable labels.

---

## G. Single portraits — Contacts only

One prompt each, 4:5, saved as `people/contacts/<Given> <Family>.jpg`. Every prompt ends with the
same style block:

> *…* Candid photo taken on a recent smartphone's main camera, natural available light, ordinary
> framing, slight sensor noise, realistic skin texture with pores and small imperfections, not
> retouched, no studio lighting, no exaggerated background blur. No text, no logos, no brand names,
> no readable signs.

Paste the line below **followed by that block**.

- **Aiko Tanaka:** ✓ Head-and-shoulders portrait of a 30-year-old Japanese woman with a sleek
  black chin-length bob and straight fringe, wearing a charcoal turtleneck, standing by a
  concrete building on a cloudy day, looking at the camera with a slight smile.
- **Ana Lucía Torres:** ✓ (cropped: a legible sign) Head-and-shoulders portrait of a 34-year-old Mexican woman with long dark
  hair tied back, small silver earrings, wearing a white chef's jacket with no embroidery, outside
  a restaurant's back door in the afternoon, smiling confidently at the camera.
- **Chloé Martin:** ✓ Head-and-shoulders portrait of a 27-year-old French woman with shoulder-length
  curly red hair and freckles, wearing a striped linen shirt, in a sunny courtyard with plants,
  laughing toward the camera.
- **Daniel Weber:** ✓ Head-and-shoulders portrait of a 31-year-old German man with short brown hair
  and a short beard, wearing a dark work shirt with rolled sleeves, in front of a bicycle workshop's
  open door, a bit of grease on his hand, smiling at the camera.
- **Devika Rao:** ✓ Head-and-shoulders portrait of a 28-year-old Indian woman with a long dark braid
  over one shoulder, small gold nose stud, wearing a sage-green blouse, in a leafy garden in soft
  morning light, looking at the camera.
- **Farah Haddad:** ✗ the default face twice — stays a monogram unless a retry looks like nobody else Head-and-shoulders portrait of a 31-year-old Lebanese woman with long dark wavy
  hair, wearing a black denim jacket, a camera strap without branding over her shoulder, on a city
  rooftop at dusk, looking at the camera with a calm expression.
- **Grace Liu:** ✓ (⚠ reads ~30, not 41) Head-and-shoulders portrait of a 41-year-old Chinese-American woman with short
  neat black hair, wearing a camel blazer over a white T-shirt, by a large office window with a
  blurred city behind, smiling at the camera.
- **Isabela Costa:** ✓ Head-and-shoulders portrait of a 24-year-old Brazilian woman with
  sun-lightened brown curls, tanned skin, wearing a plain wetsuit unzipped to the waist over a
  T-shirt, on a beach in bright sun, squinting and grinning at the camera.
- **Layla Hassan:** ✓ Head-and-shoulders portrait of a 29-year-old woman wearing a dusty-rose hijab
  and a cream knit top, in a park in autumn with soft orange background, smiling warmly at the
  camera.
- **Mateo Fernández:** ✓ Head-and-shoulders portrait of a 26-year-old Spanish man with messy dark
  hair and stubble, plain over-ear headphones around his neck, wearing a black T-shirt, in a dim
  recording studio corridor lit by a window, looking at the camera.
- **Mei Chen:** ✓ Head-and-shoulders portrait of a 68-year-old Chinese woman with short grey hair
  and kind eyes, wearing a lilac cardigan, standing at her garden gate on a sunny day, smiling at
  the camera.
- **Noah Bergström:** ✓ Head-and-shoulders portrait of a 23-year-old Swedish man with light blond
  hair under a plain grey beanie, wearing a fleece jacket, at an indoor climbing wall with
  colourful holds behind him, chalk on his hands, grinning at the camera.
- **Yusuf Demir:** ✓ Head-and-shoulders portrait of a 32-year-old Turkish man with short dark hair
  and a neat moustache, wearing a clay-dusted apron over a T-shirt, in a pottery studio with
  shelves of bowls behind him, smiling at the camera.
- **Zanele Dlamini:** ✓ Head-and-shoulders portrait of a 25-year-old South African woman with long
  box braids tied up, wearing a bright orange top, in front of a white wall with afternoon sun,
  laughing at the camera.
