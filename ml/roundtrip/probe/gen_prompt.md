# Probe-sentence generation prompt (v2)

Loaded by `gen_probe.py`. Only the three blocks between `<!-- BEGIN name -->` and
`<!-- END name -->` are sent; everything outside them is documentation. Placeholders use
Python `string.Template` syntax (`$name`); a literal dollar sign is `$$`.

Placeholders:
- `$n` — number of slots in this request
- `$slots_json` — JSON array of slots: `slot`, `lang`, `domain`, `words` (target range),
  `terms` (each with `term`, `category`, `use_one_of` = allowed written forms, `notes`)
- `$examples` — a few existing sentences of the same languages, for register only
- `$error` — (repair block) why the previous reply was rejected

<!-- BEGIN system -->
You write probe sentences for testing speech recognition on technical vocabulary.
The speakers are Russian-speaking software and ML engineers who dictate into their phone:
messenger replies, work-chat updates, e-mails, notes, stand-up updates, commit and pull-request
descriptions, prompts to an AI assistant, terminal-style requests, calendar requests and docs.
They dictate in Russian, in English, and in Russian with English technical terms kept in Latin script.

Every sentence is the IDEAL FINAL WRITTEN TEXT the user wants to end up with: correct spelling,
capitalisation, punctuation and term spelling, as a careful engineer would type it.

Rules for each slot:
1. Use EVERY required term of the slot, each exactly once, written with one of its `use_one_of` forms.
   Russian (Cyrillic) terms may and should be inflected naturally ("на стейджинге", "задеплоили",
   "в векторной базе"). Latin-script terms are never inflected, transliterated or translated
   ("в Kubernetes", "через LoRA", "на H100").
2. Language:
   - "ru": Russian only. No Latin letters anywhere (digits and punctuation are fine).
   - "en": English only. No Cyrillic letters anywhere.
   - "mixed": a Russian sentence in which the Latin-script terms stay in Latin.
     Russian words must clearly outnumber English words. Do not add Latin words that are not required
     terms, except at most one product name, person name or version string.
3. Length: the number of words must be inside the slot's `words` range, and always between 6 and 35.
   The sentence must end with ".", "!", "?" or "…".
4. Sound like real dictation for the slot's `domain`, with natural grammar and natural word order.
   Add concrete realistic details where they fit: first names, colleagues, version numbers, numbers,
   dates, times, durations, file names. Keep facts plausible; do not invent statistics about real companies.
   Write numbers the way a careful writer types the final text: digits for times, dates, money,
   percentages, measurements, versions and numbers above ten ("в 11:30", "20 минут", "80%", "40 GB",
   "at 9:00"); small counts may stay words ("два бага", "three times"). In Russian sentences write people's
   names in Cyrillic and decline them ("с Марком"). Plain text only: no backticks, no Markdown, straight
   apostrophes (').
5. Do not add other hard technical terms beyond the required ones (at most one extra, ordinary one).
6. Every slot must read differently: vary the opening words, sentence structure and intent.
   Never write two sentences that differ only in the terms.
7. `tts_text` is a reading hint for text-to-speech:
   - "mixed": REQUIRED. The same sentence with every Latin-script word respelled in Cyrillic the way
     a Russian engineer pronounces it (Kubernetes -> кубернетес, LoRA -> лора, H100 -> аш сто,
     GitHub Actions -> гитхаб экшнс). Change nothing else.
   - "ru": null, unless the text has digits; then the same sentence with every number in words,
     declined as said ("до 11:00" -> "до одиннадцати", "80%" -> "восемьдесят процентов").
   - "en": null, unless a term is read differently from its spelling (k8s -> kubernetes,
     nginx -> engine x) or the text has digits; then the sentence with only those words replaced
     and numbers in words ("9:30" -> "nine thirty").
   - "mixed": numbers in words as well, declined as said.
8. `names`: every person name used in the text, exactly as written in the text (inflected form included).
   Empty list if none.
9. No offensive, political, medical or personal-data content; no real people other than public figures.

Reply with ONE JSON object and nothing else, no markdown fences:
{"sentences": [{"slot": <int>, "text": "<sentence>", "tts_text": "<reading hint>" or null, "names": ["<name>", ...]}]}
Exactly one item per slot, in slot order.
<!-- END system -->

<!-- BEGIN user -->
Write $n sentences, one per slot.

Slots:
$slots_json

Register examples (do not copy their wording or structure):
$examples

Return only the JSON object.
<!-- END user -->

<!-- BEGIN repair -->
Your previous reply could not be used: $error
Reply again with only the JSON object in the required shape, one item per slot, following all rules.
<!-- END repair -->
