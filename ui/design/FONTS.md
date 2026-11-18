# Bundled fonts (`:ui:design`)

DESIGN 3.2 / tokens.json `meta.fonts`: Onest for everything, JetBrains Mono where digits must line up. Both are
SIL Open Font License 1.1 and cover Cyrillic. Downloaded 2026-11-19 from pinned commits (the immutable URLs below),
verified with `sha256sum` and fontTools (axes, Cyrillic coverage).

| File in the module | Source (exact download URL) | sha256 | Notes |
|---|---|---|---|
| `src/main/res/font/onest_variable.ttf` | https://raw.githubusercontent.com/google/fonts/4c1db3aec83c67dd223dd82a68c039cab30917a9/ofl/onest/Onest%5Bwght%5D.ttf | `966c5c29b4755da84b6854d5c21dd4eaa2420225d0e9874de602de176d4a9f31` | Onest 2.001, variable `wght` 100-900, 193,056 bytes. Google Fonts' build of https://github.com/simpals/onest (commit 8739b19). Has `tnum`. |
| `src/main/res/font/jetbrains_mono_regular.ttf` | https://raw.githubusercontent.com/JetBrains/JetBrainsMono/02bb50b082dad9ef8a0f33ac393839202b760223/fonts/ttf/JetBrainsMono-Regular.ttf | `e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1` | JetBrains Mono 2.305, weight 400, 270,224 bytes. |
| `src/main/res/font/jetbrains_mono_medium.ttf` | https://raw.githubusercontent.com/JetBrains/JetBrainsMono/02bb50b082dad9ef8a0f33ac393839202b760223/fonts/ttf/JetBrainsMono-Medium.ttf | `d16e6dc99672734698d629705f617c79f6eb6040f5113efe3a145204dc988109` | JetBrains Mono 2.305, weight 500, 270,316 bytes. |

Licences (shipped in the APK as assets, OFL 1.1 requires the licence to travel with the fonts):

| File | Source | sha256 |
|---|---|---|
| `src/main/assets/licenses/Onest-OFL.txt` | https://raw.githubusercontent.com/google/fonts/4c1db3aec83c67dd223dd82a68c039cab30917a9/ofl/onest/OFL.txt | `071195d8806e226faeee60259c28ca67b458227af5195a73f5cfcab06e3003bc` |
| `src/main/assets/licenses/JetBrainsMono-OFL.txt` | https://raw.githubusercontent.com/JetBrains/JetBrainsMono/02bb50b082dad9ef8a0f33ac393839202b760223/OFL.txt | `30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad` |

The files are unmodified (OFL allows bundling; no Reserved Font Name is affected because nothing is renamed or
subset). `MurmurFonts.sans` pins the `wght` axis per weight with `FontVariation.Settings(FontVariation.weight(w))`
for 400/500/600/700; `MurmurFonts.mono` maps 400/500 to the two static files.

Re-verify:

```
sha256sum ui/design/src/main/res/font/*.ttf ui/design/src/main/assets/licenses/*.txt
```
