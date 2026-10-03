# Podcini.C

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Regular listeners who want powerful control over podcasts and other media. They build personal libraries, choose what plays next, manage downloads and streaming, and return to material they have saved or annotated.

Learning and repeated study are supported workflows within this broader listening product.

## Product Purpose

Podcini.C is an open-source Android media app for collecting, organizing, playing, and revisiting podcasts, local files, and media supplied by external provider apps.

Success means listeners can find the media they want, control playback and automation, and retain their library and personal context over time.

## Positioning

The product combines several capabilities in one personal media library:

- RSS/Atom feeds, local media, and extensible external sources.
- One listening queue, saved manual and smart playlists, and hierarchical tags for podcasts, music and video.
- Per-feed automation alongside detailed filtering, sorting, and playback controls.
- Notes, tags, ratings, playback states, clips, position marks, and repeated listening.

Its depth of listener control is central to the product's purpose.

## Operating Context

Listeners can start by finding or subscribing to feeds, importing subscriptions or another supported app's database, selecting local media directories, or sharing supported media into the app.

Listen, Library and Search are the primary destinations. Library opens with a search entry scoped to the saved library, named pins and six browsing categories: Podcasts, Creators, Albums, Tags, Playlists and Media. Pins retain a browsing destination and its filters, sorting and view choice; listeners can rename, reorder or remove them. Podcasts, creators, albums and nested tags lead to scoped child views, with search and applicable filtering, sorting and view controls. Highlights remains a separate route, and Manage is available from Library options. Its Add menu and Manage view provide access to import and export. The listening queue determines the current session’s order; player controls remain available while browsing. Downloads and local files support listening without streaming, while background playback supports listening outside the app. Android Auto is another playback surface.

Library filters start with unfinished, downloaded and favourite media and media type. Expandable choices add any or all selected tags, excluded tags, creators and a maximum duration. Tag browsing can include subtags or match only the current tag; child-tag counts reflect the filters and scope that opening that child will use. Filtered media views can be saved as smart playlists. The Playlists group retains media filters for a return to Media or Podcasts, hides their controls and chips while they are paused, and explains that state. On expanded windows, a persistent Library index sits beside child views.

Search uses one query for saved podcasts, episodes and playlists, with online podcast results alongside local matches. Listeners can narrow the scope or search only their library, preview a podcast, add it and return to the retained query. Podcast membership, updates and download choices remain distinct. Advanced search, directories and provider settings remain accessible from Search.

External provider apps must be installed separately and enabled in settings. Server synchronization is optional; direct device-to-device exchange and backups are separate workflows.

## Capabilities and Constraints

- The existing application uses Kotlin and Jetpack Compose with native Android navigation, media services, notifications, permissions, and storage integration.
- A **podcast** groups episodes. A **playlist** holds references to media or selects them using rules. **Tags** organize items, podcasts and playlists. The single **listening queue** captures the order of the current session. **Connections** provide library content; local files and remote URLs are playable locations for the same media. Legacy feed, volume and queue tables remain compatible with backups.
- Playback supports audio and video, streaming and downloads, speed controls, sleep and auto-play timers, and one active listening session. Per-feed policies support automatic downloading, enqueuing, and spaced repetition.
- Personal organization includes notes, tags, todos, ratings, playback states, related media, clips, and position marks. Transcripts, captions, reader views, and text-to-speech are supported where applicable.
- Library data and preferences are stored locally. Network access is used for remote content, discovery, external sources, and optional synchronization. The privacy commitment is to keep personal data local except where needed for a user-selected service or feature; the repository's [privacy policy](PrivacyPolicy.md) describes these interactions.
- Imports include OPML, AntennaPod databases, and Podcast Addict databases. Exports and backups support retaining and moving personal data.
- Server synchronization covers a limited set of subscription and episode-action fields. It is not a full backup of local metadata or preferences.
- Free and Play variants share the core product. Casting is available in the Play variant. Legacy variants differ in native-library packaging.
- Existing libraries, saved preferences, imports, exports, and external provider compatibility are product constraints when changing behavior or identity. Restoring a previous full backup must preserve reading and playback status, playlists and collections, downloaded media references, subscriptions and annotations.

## Brand Commitments

**Podcini.C** is this fork's product identity.

The installed app is named **Podcini.C** (**Podcini.C Debug** for debug builds), and exported APK filenames use **Podcini.C**. Its application ID is `studio.camille.podcini`; debug builds add `.debug`. Android app IDs use the owner's `studio.camille.[name]` convention. The Kotlin namespace remains `ac.mdiq.podcini` to preserve installation continuity, stored data, and integrations.

Preserve open-source attribution and existing contributor and dependency credits.

## Evidence on Hand

- [README.md](README.md): existing product description, terminology, feature behavior, and usage examples.
- [Store description](fastlane/metadata/android/en-US/full_description.txt): existing public product copy.
- [images/](images/) and [store screenshots](fastlane/metadata/android/en-US/images/phoneScreenshots/): images of existing product workflows.
- [App resources](app/src/main/res/): existing icons, localized strings, and other shipped assets.
- [AndroidAuto.md](AndroidAuto.md): the existing Android Auto setup guidance.
- [CONTRIBUTORS.md](CONTRIBUTORS.md) and [Licenses_and_permissions.md](Licenses_and_permissions.md): contributor and dependency attribution.

## Product Principles

1. Keep listeners in control of their sources, organization, playback, and automation.
2. Make everyday listening straightforward while keeping advanced capabilities accessible.
3. Treat the personal library, listening history, and annotations as lasting user data.
4. Support continuity across browsing, background playback, downloaded media, and local files.
5. Keep synchronization and external integrations optional and clear about their limits.

## Open Product Decisions

- Product-specific accessibility requirements and the supported device-size matrix beyond the existing Android implementation.
