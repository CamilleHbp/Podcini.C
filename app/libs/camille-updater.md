# Shared Android updater

Source and releases: https://github.com/CamilleHbp/android-updater (private).

Pinned library: `camille-updater-1.0.0.aar`. Verify its adjacent SHA-256 file before replacing it. Fetch updates from that repository; keep updater implementation changes in the shared source repository.

The library checks the promoted Android download on foreground entry, offers a localized update, verifies its bytes and signing identity, and opens Android’s installer after an explicit tap. It preserves channels and Podcini editions. See the shared repository for the catalogue contract and integration documentation.
