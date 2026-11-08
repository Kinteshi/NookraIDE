# Release plan: 0.1.0

Decisions and open questions for the first release, from the user's discussion on 2026-11-08. The release backlog (R1 to
R11) is in [NATIVE-IDE-BATCHES.md](NATIVE-IDE-BATCHES.md#release-backlog-first-release); this file holds what was decided
and the options still open, so they survive a context reset. Nothing here is implemented yet.

## Next batch, then 0.1.0 (decided)

The next batch does, in this order of dependency:

* **A5, A6, A7:** the user's daily use of Aardink ends with this batch, and sora-editor is removed (phase 7 of the
  [switch plan](AARDINK-SWITCH-PLAN.md): the setting, `SoraEditorSurface`, `SoraCompletion`, `NativeCodeEditor`,
  `BracketColorizer`, the files in sora's package, both sora dependencies; tm4e carried as a module; licence notes). A7 is
  the user's own try of the Book Cover and the on-screen keyboard.
* **G20:** an expand button on the hover card, showing the whole of it in a split panel or in a docs tab in the bottom panel.
* **G6:** the app can reopen as a partly off-screen floating window.
* **Group 2 of the general backlog, "Guest away, and offline", all of it:** G14 (refine the offline experience, after a
  walkthrough with the guest stopped), G17 (warn near the phantom process limit, and say so when the guest goes away), G5
  (Recent keeps folders that no longer exist), and its loose ends: the offline file list keeps deleted files, the unsaved dot
  once seen after saving a restored deleted file, and a notebook tab not restored after a force stop (perhaps a preview tab).
  G14 and G17 need the guest stopped on purpose: ask the user first, at a moment nothing of theirs is running.

**After that batch: 0.1.0** (R1). The rest of the release backlog (R2 to R11) is done for it.

G15 (a VS Code port) was dropped: won't do.

## Licence (decided)

**Apache-2.0** for the whole repository, app and daemon (R3). The workspace's `Cargo.toml` says MIT today and changes with it.
A `LICENSE` file at the root, a `NOTICE` file, and a third-party notices screen in the app: Aardink (Apache-2.0), tm4e
(EPL-2.0, its files keep their licence), termlib (Apache-2.0) and the rest. sora-editor's LGPL is gone with A6 before then.

## Name and icon (open, the user's)

The name goes to the repository, the app, the app id and the daemon (`<name>d` or `<name>-daemon`). The current icon is pretty
but common, a default look, so it changes with the name to give the app an identity. Icons must work as Android's themed
(monochrome) icons, which Material You recolours, and avoid the `</>`-on-a-gradient look. Check the store, GitHub and
trademarks before choosing.

**The user's shortlist (11-08):**

| Name | Why | Icon |
|---|---|---|
| **Burrow** | The Linux machine living beneath the tablet | A rounded hillside with a round door, and a `>_` prompt in the doorway |
| **Quire** | A gathering of folded pages, an old word for a notebook or a workspace | A folded sheet seen from the side, its fold drawing a `<` |
| **Traveler** (the user's idea) | Inspired by Destiny's Traveler: it goes from place to place, a whole IDE on the go | Suggested: a compass whose north needle is a `>` chevron, or a satchel whose flap is a tablet. Keep clear of Destiny's own imagery (the cracked white sphere): that's Bungie's design |
| **Clarity** (the user's idea) | Also from Destiny's lore: seeing clearly | Suggested: a prism splitting a beam into Material You colour bands, with a cursor bar where the beam enters. Note that Microsoft Clarity (web analytics) uses the name |

**The other options suggested on 11-08:** Cairn (three rounded stones stacked, the top one a text cursor: app on daemon on
Linux), Tidepool (a rounded pool whose wave crest is a `>`: a small world inside a larger one), Glasshouse (an arched
greenhouse whose glazing bars draw `{ }`: a protected place where things grow), Inkwell (a round inkwell whose ink is a
cursor bar in the Material You colour), Lathe (a spindle with a curl of shaving shaped like `}`). Names had before:
MaterialCode, AVF Studio.

## How releases work (recommended, not yet decided)

* **One repository** for the app and the daemon: the APK carries the arm64 daemon and installs it, and both share
  `avf-protocol`. Split only if the daemon gains users of its own.
* **One version for both**, from a tag (`v0.1.0`): CI builds the signed APK with its daemon inside, the daemon for Linux and
  WSL hosts, and checksums, and makes a GitHub release with notes (R4, R5). The protocol number stays the compatibility check;
  the app compares the guest daemon's version with the one it carries and offers to update it (R6).
* **Distribution:** GitHub releases first (sideloading, Obtainium for updates); the Play Store later.

## The repository (R11, decided in principle)

This branch (`agents/reset-and-implement-native-android-ide`) becomes the definitive `main`; everything else is scrapped
(user, 11-08). On 11-08 the repository had `main` (Track A's history) and four `agents/*` branches on GitHub besides this one.
Suggested order, each destructive step confirmed with the user right before it:

1. Scan the whole history for secrets (gitleaks or similar): batches handled tokens and pairing codes.
2. Keep this branch's history unless the scan finds something; otherwise squash into one first commit.
3. Replace `main` on GitHub with this branch (a force push) and delete the other branches there and locally. An optional
   local bundle as a backup.
4. With R10, clean the tree: Track A's SPEC.md and scripts, `docs/model-guide-arc-b580.md` (unrelated), the aardink-lab
   module, and the untracked `android/` folder (Track A's Android project; ask before deleting it from disk).
5. Rename the repository to the chosen name, protect `main`, tag `v0.1.0`.
