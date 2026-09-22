---
name: "Podcini.C — Personal archive"
description: "A precise, restrained native Android listening archive."
colors:
  primary: "#6B437C"
  on-primary: "#FFFFFF"
  primary-container: "#E9E2EF"
  on-primary-container: "#30213D"
  secondary: "#62586D"
  on-secondary: "#FFFFFF"
  secondary-container: "#E9E2EF"
  on-secondary-container: "#30213D"
  surface: "#F7F7FA"
  on-surface: "#252136"
  surface-variant: "#EAE6EF"
  on-surface-variant: "#625B6C"
  surface-container-lowest: "#FFFFFF"
  surface-container-low: "#F1EEF5"
  surface-container: "#EDE9F1"
  surface-container-high: "#E7E2ED"
  surface-container-highest: "#E1DBE7"
  outline: "#7C7485"
  outline-variant: "#D8D1DF"
  dark-primary: "#D8B5EA"
  dark-on-primary: "#3D224C"
  dark-primary-container: "#513560"
  dark-on-primary-container: "#F0DBFA"
  dark-secondary: "#CFC0DB"
  dark-on-secondary: "#352D40"
  dark-secondary-container: "#44384F"
  dark-on-secondary-container: "#F0E3FA"
  dark-surface: "#17141D"
  dark-on-surface: "#ECE5F1"
  dark-surface-variant: "#49414F"
  dark-on-surface-variant: "#CDC3D4"
  dark-surface-container-lowest: "#121016"
  dark-surface-container-low: "#211D28"
  dark-surface-container: "#272230"
  dark-surface-container-high: "#312B3A"
  dark-surface-container-highest: "#3C3446"
  dark-outline: "#998CA5"
  dark-outline-variant: "#49414F"
  black-surface: "#000000"
typography:
  headline-small:
    fontFamily: "sans-serif"
    fontSize: "24sp"
    fontWeight: 400
    lineHeight: "32sp"
    letterSpacing: "0sp"
  title-large:
    fontFamily: "sans-serif"
    fontSize: "22sp"
    fontWeight: 400
    lineHeight: "28sp"
    letterSpacing: "0sp"
  title-medium:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 500
    lineHeight: "24sp"
    letterSpacing: "0.2sp"
  title-small:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: "20sp"
    letterSpacing: "0.1sp"
  body-large:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
    letterSpacing: "0.5sp"
  body-medium:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "20sp"
    letterSpacing: "0.2sp"
  body-small:
    fontFamily: "sans-serif"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "16sp"
    letterSpacing: "0.4sp"
  label-large:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 500
    lineHeight: "20sp"
    letterSpacing: "0.1sp"
  label-medium:
    fontFamily: "sans-serif"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: "16sp"
    letterSpacing: "0.5sp"
  label-small:
    fontFamily: "sans-serif"
    fontSize: "11sp"
    fontWeight: 500
    lineHeight: "16sp"
    letterSpacing: "0.5sp"
rounded:
  extra-small: "4dp"
  small: "8dp"
  medium: "12dp"
  large: "16dp"
  extra-large: "28dp"
spacing:
  space-4: "4dp"
  space-8: "8dp"
  space-12: "12dp"
  space-16: "16dp"
  space-20: "20dp"
  space-24: "24dp"
  space-32: "32dp"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    typography: "{typography.label-large}"
  button-tonal:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    typography: "{typography.label-large}"
  button-text:
    textColor: "{colors.primary}"
    typography: "{typography.label-large}"
  episode-play:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    size: "48dp"
  episode-pause:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    size: "48dp"
  transport-play:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.on-primary}"
    size: "80dp"
  episode-row:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-medium}"
    rounded: "{rounded.medium}"
    padding: "12dp 8dp"
  episode-row-current:
    backgroundColor: "{colors.primary-container}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-medium}"
    rounded: "{rounded.medium}"
    padding: "12dp 8dp"
  episode-row-selected:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.medium}"
  compact-player:
    backgroundColor: "{colors.surface-container}"
    textColor: "{colors.on-surface}"
    typography: "{typography.title-small}"
    padding: "8dp 12dp"
  artwork:
    backgroundColor: "{colors.surface-container-high}"
    rounded: "{rounded.small}"
  navigation:
    backgroundColor: "{colors.surface-container-low}"
    textColor: "{colors.on-surface-variant}"
  filter-chip-selected:
    backgroundColor: "{colors.secondary-container}"
    textColor: "{colors.on-secondary-container}"
    typography: "{typography.label-large}"
  settings-search:
    textColor: "{colors.on-surface}"
    typography: "{typography.body-large}"
  popup-card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.large}"
---
# Design System: Podcini.C

## Overview

**Creative North Star: "Personal archive"**

A personal listening archive where recognizable episodes and predictable playback lead. The visual character is precise, restrained and familiar: cool-white and aubergine surfaces, quiet plum actions, pale lilac selection, Material typography, restrained separators and real source artwork.

The player and episode list form one continuous experience. Artwork identifies content, typography establishes priority, and color identifies state and action. A listener can browse, organize or study while keeping playback close at hand. Native Android controls, text scaling and theme behavior are part of the identity.

**Key Characteristics:**

- Readable catalogue rows with one prominent playback action.
- Stable tonal player surfaces and modest, uncropped source artwork.
- Named navigation, visible state and deliberate access to deeper tools.
- Native Material 3 behavior across phone, wider windows and large text.

## Colors

The palette is cool and quiet, with a plum action color and aubergine neutrals. The frontmatter records the fixed light and dark palettes. Color names map to `MaterialTheme.colorScheme` roles; a `dark-` prefix identifies the corresponding dark value. Background shares the surface role, and tertiary roles share primary roles.

### Primary

- **Quiet plum / light lilac:** `primary` and `dark-primary` identify playback, progress, text actions and active emphasis. Use the matching `on-primary` foreground.
- **Pale lilac / deep plum:** primary containers identify the current episode without overpowering its title. Primary-container foregrounds serve native controls that use that pair.

### Secondary

- **Muted aubergine:** secondary roles support the primary accent rather than adding another hue. Secondary containers carry tonal buttons, selected filters, navigation indicators and batch selection. Use the corresponding on-container foreground for those controls.

### Neutral

- **Cool white / aubergine night:** surface and on-surface are the reading ground and main text.
- **Quiet ink:** on-surface-variant carries source, time and explanatory metadata.
- **Lilac tonal layers:** surface-container-low supports navigation; surface-container supports the compact player; surface-container-high provides artwork fallback. The remaining container steps support native Material components.
- **Restrained outlines:** outline supports control boundaries; outline-variant separates content and frames popup cards.
- **Black surface:** the optional black theme replaces the dark surface while retaining the dark palette’s other roles.

System light/dark selection, explicit theme overrides and optional Android Dynamic Color use the same semantic assignments. Dynamic Color is available on Android 12 and later. Semantic error and disabled treatments remain native Material roles; plum is not a universal status color.

**The Quiet Plum Rule.** Reserve primary color for actions, progress and active emphasis. Let artwork provide the content’s color variety.

**The Explicit State Rule.** Pair a tonal state with a readable label, selection control or action change; color alone does not identify playback or selection.

## Typography

**Font:** Android’s native sans-serif through Material 3 `Typography()`. Use the semantic roles, with scalable `sp` units and the system’s font settings. There is no separate display face or decorative type layer.

The hierarchy favors readable titles and concise metadata. The frontmatter supplies native sizes, line heights, weights and tracking for the roles used here. `headline-small` maps to `headlineSmall`, and the other hyphenated names follow the same mapping.

| Role | Use |
| --- | --- |
| `headline-small` | Screen titles and empty-state headings. |
| `title-large` | The full episode title in the expanded player. |
| `title-medium` | Episode row titles and named content sections. |
| `title-small` | Compact-player episode titles. |
| `body-large` | Empty-state guidance and personal notes. |
| `body-medium` | Player source names, next-episode titles and explanatory copy. |
| `body-small` | Source, time and status metadata. |
| `label-large` | Material buttons, segments and player names. |
| `label-medium` | Seek times and transport utility labels. |
| `label-small` | Player state summaries and provider attribution. |

Episode row titles allow three lines at ordinary text size and five when font scale is at least 1.3. Source labels use one line, while the expanded player’s centered title wraps without a fixed line count. Compact-player titles allow two lines. Use sentence case for labels and navigation.

**The Title Before Metadata Rule.** Give episode titles the strongest text in a row; keep the source and time together in quieter supporting roles.

## Layout

Dimensions use Android density-independent `dp`; type uses `sp`. The recurring spacing rhythm is 4/8 dp, with 12/16 dp component spacing and 20/24/32 dp margins or task separation. These are native layout values, not web pixel dimensions.

- **Episode lists:** 8 dp outer horizontal inset, 4 dp between rows, and 8 dp horizontal / 12 dp vertical row padding. A text column adds 12 dp horizontal and 4 dp vertical inset. Row height follows the title and metadata.
- **Top-level views:** a named app bar leads the content. Listen uses Latest / Up next / Downloads; Library uses Sources / Folders / Collections / Saved. List controls and applied filters sit immediately above their content.
- **Compact windows:** three labeled destinations sit below the compact player. The player belongs to the bottom layout and reserves its own space.
- **Wide windows (600 dp and above):** bottom destinations become a navigation rail. The full player places its heading and artwork beside the transport controls, with a 32 dp gap.
- **Paired listening (840 dp and above):** when Listen is open with loaded media and the full player is collapsed, list and player share the content area in 56:44 proportions, separated by a native divider. The embedded player uses a single column.
- **Expanded player:** content is centered with a maximum width of 960 dp and 20 dp horizontal padding. Its column scrolls and respects navigation-bar insets. Artwork is 176 dp, reducing to 120 dp below 650 dp screen height or at font scale 1.3 and above.
- **Large text:** Listen replaces its three joined segments with a scrollable tab row above font scale 1.2. Episode artwork reduces from 72 dp to 56 dp at font scale 1.3 and above. Compact-player height follows its contents.
- **Empty states:** left-aligned text within a 32 dp inset, a heading, explanation and one useful action. Discover’s initial invitation uses a 24 dp inset.

Primary episode and compact-player playback actions are 48 dp; the full-player action is 80 dp, flanked by 64 dp rewind and forward controls. Keep Android’s minimum interactive target behavior for native controls and preserve visible labels when space changes.

## Elevation & Depth

The listening shell is flat at rest. Tonal layers distinguish navigation, compact playback and the current episode; whitespace separates routine rows. Bottom navigation explicitly uses zero tonal elevation. Thin native dividers separate the player’s queue section, reading tabs and paired panes. Native popup, menu, dialog and pressed-state behavior supplies transient depth; there is no custom shadow or motion scale.

**The Stable Surface Rule.** Keep player controls on opaque theme surfaces. Artwork belongs inside its own frame.

## Shapes

Use the theme’s five rounded-corner roles in the frontmatter. Artwork uses `small`; episode state surfaces use `medium`; popup cards use `large`. Artwork is square, fitted without cropping and backed by a tonal surface, with a headphones placeholder when unavailable.

Native Material buttons use their built-in pill or circular shapes, chips use their native shape, and segmented controls join into one continuous outline. Preserve joined edges and equal segment heights. Outlines belong to controls and popup boundaries; ordinary episode rows are borderless.

## Components

### Buttons

Calm, familiar and explicit. Primary filled buttons use primary/on-primary; tonal buttons use secondary-container/on-secondary-container; text buttons use primary. Let native Material feedback handle pressed, focused, disabled and selected states.

Episode actions use a tonal play button and a filled primary pause button while playing. Both are separate from the title’s details action. Their vector icon is 28 dp. The compact player uses a filled playback action. The expanded player uses the larger primary control with a 40 dp icon. Speed uses a tonal button with 12 dp content padding; timer and bookmark use tonal icon buttons with visible labels below.

### Chips

Applied episode filters use selected input chips with a named trailing remove action and a separate Clear filters action. Saved uses single-choice filter chips for All saved, Episodes, Notes, Bookmarks and Clips. Chip rows scroll horizontally with 16 dp outer inset and 8 dp gaps. Selection uses native tonal treatment and semantics.

### Cards / Containers

Episode rows are flat content containers, becoming tonal only for current or selected state. Batch selection takes visual precedence over current playback and exposes a checkbox. The compact player uses its own stable container tone.

Popup cards use the surface/on-surface pair, the large corner role, a 1 dp outline-variant border and 16 dp outer padding. Content sets its own padding. Use these bounded containers for temporary choices rather than adding card chrome to every content item.

### Inputs / Fields

Discover uses a native filled text field for a podcast query or feed URL, with a search action and history when available. Settings uses a native outlined, labeled search field, inset 16 dp horizontally and 8 dp vertically. Keep labels visible and let Material manage focus, error and disabled treatment. Retain the query through loading and recoverable failure.

### Navigation

Listen, Library and Discover keep their visible labels in both bottom navigation and the rail. Navigation sits on surface-container-low with a native tonal selected indicator. Top app bars use the surface background and headline-small title; back, search, settings and overflow use native icon buttons with meaningful labels.

Use native segmented controls for a short mutually exclusive choice, scrollable tabs for Library and large-text Listen, and primary tabs for player reading tools. Player tabs are Details and Notes, with Chapters present only when chapter content exists. Tab indicators and text convey selection together.

### Episode row

A small fitted cover, readable title, source, remaining time and meaningful state form a consistent catalogue entry. The current episode has a primary-container background, a Playing or Paused label and a 2 dp progress line. Download progress appears below its metadata. Optional comments or tags remain supporting content.

Tapping the title opens details; Play/Pause acts immediately on that episode. Overflow contains named contextual actions. Long-press opens selection, with a visible Select episodes route in overflow. Batch selection replaces artwork with checkboxes and presents a selected count with named actions.

### Player

The compact player combines 48 dp artwork, a two-line episode title, quieter source or loading/error text, Play/Pause, Next and a 2 dp progress line. The artwork/title region opens the expanded player. Loading and failure retain episode context.

The full player reads in order: artwork and title, seek position, elapsed/total time, transport, labeled utilities, Up next and reading tools. Rewind and forward show the configured intervals. Bookmark feedback names the saved time and offers Undo; Add note remains available. Next is an explicit queue action.

When two players are enabled, joined segments show each player’s name, episode and Playing, Paused or Empty state. Both segments retain the same three-line structure. Switching the selection does not imply stopping the other player. Collapse and system Back return to the browsing context.

## Do's and Don'ts

### Do:

- **Do** use MaterialTheme semantic colors and typography so light, dark and optional Dynamic Color retain the same hierarchy.
- **Do** keep episode titles readable, source and time secondary, and Play/Pause separate from opening details.
- **Do** use native vector icons, meaningful action labels and explicit Playing, Paused or Empty states.
- **Do** let rows and player content grow or scroll with text size; reduce artwork before crowding transport controls.
- **Do** keep both player selectors the same height with name, episode and state lines, including an explicit Empty state.
- **Do** retain source artwork proportions with a stable tonal fallback and preserve contributor and source attribution.

### Don't:

- **Don’t** place playback controls over artwork, blur the artwork into a background, or add gradients, glow or decorative texture.
- **Don’t** turn every episode into a raised card or use ornamental borders to divide routine list content.
- **Don’t** add branding eyebrows, oversized display typography or decorative statistics above everyday listening content.
- **Don’t** use codec details, bitrate, raw IDs or unexplained symbols as default episode metadata.
- **Don’t** make color, animation or long-press the only way to identify or operate an essential action.
- **Don’t** replace the native type roles, component feedback or system text scaling with fixed screenshot dimensions.
