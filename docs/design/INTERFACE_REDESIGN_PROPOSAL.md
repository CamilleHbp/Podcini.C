# Podcini.C interface redesign

## A listening app with depth

Open Podcini, recognize what is playing, and choose what comes next. Organizing a collection, saving an idea, and configuring automation remain close at hand without dominating everyday listening.

The player and episode list form one continuous experience. Playback remains available while browsing; the full player provides room for deliberate control, reading, and study. Artwork identifies content. Typography explains priority. Color identifies state and action.

## The player

### Compact player

A persistent player sits directly above primary navigation whenever an episode is loaded. It contains small artwork, the episode title, source, Play/Pause, Next, and a thin progress indicator. Tapping the title or artwork opens the full player. Its background is a stable tonal surface.

The compact player has no codec information, recording dot, alternate-speed dial, or competing time counters. It grows with text size rather than clipping text into a fixed-height region. During loading, the playback action changes to a labeled loading state. A failed stream keeps the episode visible with Retry.

### Full player

The full player opens over the current browsing context. Collapse and system Back return to the same list position and filter state.

The reading order is:

1. Now playing, collapse, and overflow.
2. Artwork, full episode title, and source.
3. Seek position, elapsed time, and total duration.
4. Rewind, prominent Play/Pause, and Forward.
5. Labeled Speed, Sleep timer, and Bookmark actions.
6. The next queued episode with an explicit Next action.
7. Episode details, available chapters, and personal notes.

Existing rewind and forward preferences remain effective; the controls show their actual intervals. Artwork becomes smaller on short screens and in landscape so transport controls remain reachable. Metadata and reading panels scroll independently of the transport region where space permits.

![Expanded player concept](/Users/camille/Dev/Personal/Podcini.C/.impeccable/mocks/decision/personal-archive-player.png)

### Predictable controls

| Action | Result | Feedback |
|---|---|---|
| Play/Pause | Starts or pauses the displayed episode | Icon and accessible label change with state. |
| Rewind / Forward | Moves by the displayed configured interval | Position updates immediately. |
| Next | Advances in the active queue | New title and queue position are visible. |
| Bookmark | Saves the current position, including while paused | “Bookmark saved at 08:42” with Undo and Add note. |
| Speed | Opens a sheet with current value, presets, and fine adjustment | The current multiplier remains visible. |
| Sleep timer | Opens duration and end-of-episode choices | An active timer is labeled with time remaining. |
| Record clip | Opens an explicit recording action in player tools | Recording state and Finish/Cancel remain visible. |
| Output | Shows available playback destinations | Current destination is named. |

Long presses can provide accelerators, but every essential action also has a visible or labeled route. Pressing Next never silently changes playback speed. Bookmark and recording use different symbols and names.

### Reading, study, video, and two players

Details contains show notes, source links, and media information. Chapters appears only when chapters exist. Notes contains position marks, personal notes, and saved clips, with a timestamp on every relevant item. Available transcripts and reader tools open from Details or a clearly labeled entry beside it. Returning from them preserves position and playback.

Video uses the same transport vocabulary. The video surface replaces the audio artwork and exposes a clear audio-only action where supported. Captions and quality are labeled secondary controls.

Dual-player mode is explicitly enabled from Playback settings. When enabled, the player shows a named Player 1 / Player 2 selector, with each player's episode and playback state. Switching the selected player does not stop the other. Stopping or closing a player is a separate action. Compact browsing presents a clear indication when both players are active; it never hides the mode in decorative artwork beside Settings.

## Episode lists

The list answers three questions: what is this, how much listening does it represent, and what will tapping do?

Each row contains artwork, a title of up to three lines at normal text size, source, duration or remaining time, and one primary Play/Pause action. A restrained secondary line can show publication date and meaningful status such as Downloaded or Played. Long titles expand appropriately with larger system text. Full titles remain available in episode details and accessibility descriptions.

The current episode has a subtle tonal treatment and an explicit Playing/Paused state. Color is never its only identifier. Missing artwork uses a consistent source placeholder. No row displays codec, bitrate, raw IDs, unexplained ratings symbols, or unlabeled counts by default.

Tapping a row opens episode details; tapping Play starts that episode. Overflow provides Play next, Add to queue, Download/Remove download, Save, Mark played, and further relevant actions. Actions that do not apply are omitted or clearly explained. Playback policies remain effective, with clear feedback whenever a policy prevents immediate play.

Swipe actions remain customizable, have visible alternatives, and offer Undo for reversible changes. Long-press enters selection mode. The top bar then shows the selected count and a small set of batch actions; the list and checkboxes make the selected set explicit. More selection operations use text labels, including Select above and Select below.

Filters open in a sheet. Applied filters appear as removable chips above the list, with Clear all. Sort is a separate labeled choice. An empty filtered result says what is hidden and offers Clear filters; it never suggests the library has disappeared.

## Primary navigation

Three persistent destinations organize the product by listening task.

| Destination | Purpose | Main views |
|---|---|---|
| **Listen** | Choose an episode and manage listening order | Latest, Up next, Downloads |
| **Library** | Browse and organize owned or followed content | Sources, Folders, Collections, Saved |
| **Discover** | Find or add content | Search, feed URL, local media, supported providers |

Search and Settings are consistently placed and named. Each destination retains its own navigation history, list position, and selected view. The compact player persists across them. Back closes the current sheet or full player, then returns through the current navigation path, then follows Android's normal root behavior.

On compact phones, destinations use a labeled bottom navigation bar. On wider windows, navigation becomes a rail and content gains a list/detail layout. The same destinations and vocabulary remain stable.

### Listen and queues

Latest aggregates episodes from the library. Up next displays the active queue, current item, next item, and total remaining listening time. Downloads provides local availability and explicit progress, pause, retry, and removal actions.

The queue name opens a named queue picker with Create queue and Manage queues. Drag handles appear in queue-editing mode; accessible Move up / Move down actions provide the same operation. A Repeat queue control explains circular playback. Per-source queue associations remain available in source settings and identify the queue they affect.

When a queue is empty, offer Add episodes and Browse library. When playback ends with repetition disabled, say Queue finished and offer a useful next action. Reordering never changes which episode is currently playing.

### Library

Sources contains subscribed podcasts, local sources, and external-provider content, with a visible type label when it helps distinguish them. Source details lead with artwork, name, Follow/Following or the appropriate local-source state, and an episode list.

Folders expose the existing volume hierarchy with a clear parent path. Collections expose synthetic feeds as personal sets of episodes. A folder groups sources and other folders; a collection groups episodes. Creation dialogs explain that distinction in one sentence and avoid asking users to learn storage terminology.

Saved gathers existing saved material and personal annotations with understandable filters for episodes, notes, bookmarks, and clips. Existing ratings, tags, playback states, and related-media relationships remain available through contextual tools and advanced filtering.

Source settings group behavior into Updates, Downloads, Queue, Playback, and Advanced. Automation descriptions state both the condition and result—for example, “When new episodes arrive, add them to Morning queue.” Advanced policy choices include a short explanation and a visible summary of the effective policy.

### Discover and adding content

Discover begins with “Find a podcast” and a search field that also accepts a feed URL. Before a query, show an invitation and the supported entry routes, not a no-results message. Search results lead with artwork, source name, creator, and a concise description. Provider names and URLs belong in secondary metadata.

Subscription preview has one clear primary Subscribe action. Episode limits and automation are optional secondary settings. Successful subscription offers View episodes and confirms the source is in Library.

Add local media opens Android's folder picker with a concise explanation of the access needed. Provider-dependent content explains which provider is missing and gives an appropriate installation or settings route. Existing source data remains visible if a provider becomes unavailable.

## Settings, history, and data

Settings uses task-based categories: Playback, Downloads & storage, Library & automation, Appearance & accessibility, Providers, Sync & backup, and About & help. A consistent search entry helps locate advanced options.

Listening history and statistics are reachable from Listen and Library secondary menus. Technical logs live under Help & diagnostics. Routine users do not encounter diagnostics as a primary destination.

Import subscriptions and Restore backup are available during initial setup and under Sync & backup. Import previews identify what will be added or replaced. Progress is visible; recoverable errors keep the chosen source and provide Retry. Destructive replacement explains its exact consequences before proceeding.

Sync describes the subscription and episode-action fields it supports. Backup is clearly separate and includes its own last-success state. The interface never presents limited synchronization as a complete backup of notes, settings, or personal metadata.

## States and interruption handling

| Situation | User-facing response |
|---|---|
| First launch | Brief introduction, Find podcasts, Import subscriptions, Add local media. |
| Notification permission | Explain the immediate benefit when needed; use Android's permission dialog without a technical preamble. |
| Loading | One coherent loading state that retains useful context. |
| Empty library | Clear entry actions; no empty-result warning. |
| No search matches | Repeat the completed query and offer editing or another source. |
| No filter matches | Show active filters and Clear filters. |
| Offline | Keep local content usable; explain remote availability and offer Retry. |
| Download failure | Preserve the episode and show a recoverable reason beside Retry. |
| Playback buffering | Retain title and position; make loading visible without hiding controls. |
| Missing local folder | Explain lost access and offer Choose folder again. |
| Permission denied | Retain the user's intent and provide the relevant system-settings route when needed. |
| Background interruption | Resume with the same episode, position, selected view, and scroll state. |
| Reversible edit | Confirm briefly with Undo rather than interrupting with a dialog. |
| Destructive operation | Name the affected data, scope, and consequences before confirmation. |

Initial, loading, results, empty, and error states are mutually exclusive where they describe the same task. Technical exception details stay in diagnostics. Errors do not cover unrelated controls or erase user input.

## Personal archive

A precise personal catalogue gives episodes a consistent, readable structure. The episode list leads immediately; a quiet plum accent identifies playback and selection. Native Android affordances provide familiar behavior, while the type hierarchy and tonal surfaces give Podcini.C a coherent identity.

The light palette uses a cool-white ground (#F7F7FA), aubergine-black text (#252136), plum primary actions (#6B437C), and pale-lilac selected surfaces (#E9E2EF). Dark surfaces retain the same hierarchy with lighter foreground and primary roles. Artwork supplies variety without becoming the background behind controls.

![Personal archive episode-list concept](/Users/camille/Dev/Personal/Podcini.C/.impeccable/mocks/decision/personal-archive.png)

### Visual rules

Use Material typography roles with a system-compatible font: a clearly named screen, readable episode titles, quieter source and time metadata, and legible action labels. Avoid stretching text or assigning unrelated sizes per screen. Spacing follows a consistent 4/8 dp rhythm, with larger gaps separating tasks rather than every individual item.

Use a restrained color strategy: neutral surfaces, one primary action color, and semantic status colors. Artwork provides content variety. Controls sit on stable surfaces rather than blurred images. Selected states combine tonal treatment with shape, label, or icon change.

Light and dark themes follow the system by default, with an explicit override. Dark theme uses its own tonal surfaces and contrast choices. Dynamic Color can be enabled without changing semantic status meaning. Playback, selection, download, and error states remain distinguishable in both themes.

## Accessibility and different screens

Every interactive element exposes a meaningful role, action, and state. Play/Pause labels reflect the current action; episode actions include enough title context to identify the target. Sliders provide a readable position and accessible adjustment. Selection, recording, errors, and download completion are announced appropriately.

Touch targets are at least 48 × 48 dp, with adequate separation. Normal text meets a 4.5:1 contrast target; large text and essential non-text controls meet applicable 3:1 targets. Color, animation, and long-press are never the only way to perceive or operate an essential feature. Android's animation preference is respected.

At 1.3× and 2× system text, titles and labels can wrap, the compact player grows, and secondary controls move into scrollable or stacked regions. On small phones, artwork yields space before transport controls do. Landscape places artwork beside controls. Tablet and expanded-window layouts pair an episode list with details or playback without stretching phone rows across the screen.

Relevant standards: [Android accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [Android navigation principles](https://developer.android.com/guide/navigation/principles), [adaptive app layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive).

## Experience requirements

- A returning listener can recognize the current episode and pause it immediately.
- A new listener can find and subscribe to a source without learning Facets, volumes, or synthetic feeds first.
- The primary episode action is available through touch, TalkBack, and switch access.
- Next advances in the named active queue; alternate speeds have separate controls.
- Opening and closing the player preserves browsing context.
- Offline, empty, filtered, and failed states offer distinct explanations and useful next actions.
- Existing libraries, queues, annotations, imports, exports, and automation settings remain intact as presentation changes.
- Advanced capabilities stay available through explicit, consistently named routes.
