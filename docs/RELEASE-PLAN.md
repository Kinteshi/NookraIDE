# Release plan: 0.1.0

Decisions and open questions for the first release, from the user's discussion on 2026-11-08. The release backlog (R1 to
R11) is in [NATIVE-IDE-BATCHES.md](NATIVE-IDE-BATCHES.md#release-backlog-first-release); this file holds what was decided
and the options still open, so they survive a context reset. Nothing here is implemented yet.

## Next batch, then 0.1.0 (decided; the batch is done)

Done in batch 57, except A7 (the user's) and G17's check with the guest stopped on purpose. It did, in this order:

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

**Chosen (user, 11-08): Quire, as QuireCode** (app id `io.github.kinteshi.quirecode`; done in the code in batch 58), with its icon (the folded pages drawing `<`, a cursor beside them). The exact form is
the user's: plain "Quire" is taken in nearby places (quire.io, a project-management app on Google Play as `io.quire.app`
since 2018, with a VS Code extension and the tagline "Unfold your ideas"; and Getty's Quire, an open-source publishing
framework edited in VS Code, `@thegetty/quire-cli`). No app or repository named QuireCode or Quire IDE was found
(web search, 11-08; not a trademark search). Recommended: **QuireCode** as the app's name, and for the
repository (`Kinteshi/QuireCode`, transferred and renamed by the user on 11-08), the app id and the daemon (`quirecode-daemon`); check trademarks (INPI, USPTO, class 9) before the store.

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

## The release key (R4; the user's, being set up 11-08)

Android only installs an update when it's signed with the same key as the installed app, so the key is the app's
identity for as long as it exists: lose it and installed copies can't be updated (users would uninstall, losing the app's
data on the device); leak it and someone could sign a fake update. The debug key this machine makes by itself is fine for
development, never for releases. Steps, run by the user so the password never passes through a session log:

1. `mkdir -p ~/keys && chmod 700 ~/keys`, then
   `keytool -genkeypair -v -keystore ~/keys/android-release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Kinteshi"`
   (it asks for a password twice; a long one from a password manager).
2. Back it up: the `.jks` file and the password in a password manager (as an attachment), and a second copy off this
   machine. Check it with `keytool -list -keystore ~/keys/android-release.jks`.
3. After the repository's transfer and rename (secrets are per repository): `base64 -w0 ~/keys/android-release.jks | gh
   secret set RELEASE_KEYSTORE`, `gh secret set RELEASE_KEYSTORE_PASSWORD` (it asks), `gh secret set RELEASE_KEY_ALIAS
   --body release`, and `gh secret set RELEASE_KEY_PASSWORD` (the same password: the keystore is PKCS12).
4. The tablet's app is debug-signed: the first release-signed build needs it uninstalled first (Android refuses the
   switch), which clears its settings, connections and offline copies on the device (the guest's files are untouched).
   Export settings.json first. The new app id from R2 makes it a separate app anyway.

**Set up by the user, 11-08**: the keystore is `~/keys/android-release.jks` (mode 0600 now), and the four secrets are on
`Kinteshi/QuireCode`. Checked with the release workflow's dry run (run 42550650913, manual): everything built, and the
APK is signed by `CN=Kinteshi`, certificate SHA-256 `3553139293d0c4514898daa2294db0b05a19960be7479d71fb7902fa49f6db4c`
(compare with `keytool -list -v -keystore ~/keys/android-release.jks`). GitHub hides the alias's value wherever it appears
in the logs, so the word "release" shows as `***` there.

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
Order agreed with the user (11-08): **ownership first, then the name, then the history**, so every step happens once,
at the repository's final address:

* **Needed before the rename in the code** (nothing on GitHub has to happen yet): the main account's username, for the
  app id (`io.github.<username>.quirecode`) and the links in NOTICE, the tm4e notice and the README; and its private
  commit address (GitHub, Settings > Emails: `<id>+<username>@users.noreply.github.com`), for the history rewrite and
  later commits.
* **On GitHub, by the user:** transfer the repository to the main account (Settings > Danger zone > Transfer; the main
  account accepts it). GitHub redirects the old address. Then rename it to `QuireCode` (redirects again). **Done by the user, 11-08**: the repository is `Kinteshi/QuireCode`, private, and `origin` points there.
* **Then, here:** point `origin` at the new address; rewrite the history's author emails to the main account's private
  address; force-push this branch as `main`; delete the other branches; protect `main`. Then the release key's secrets
  are set on the final repository, and `v0.1.0` is tagged.
  **Done 11-08 (with the user's go-ahead):** a backup bundle of every branch (`~/backups/AVFStudio-before-rewrite-2026-11-08.bundle`,
  outside the repository); the 179 commits' author and committer rewritten to `Kinteshi <36821534+Kinteshi@users.noreply.github.com>` (`git filter-branch`;
  the final tree identical, no old address left in any message); this branch force-pushed as `main` (`7c70aae`); the five
  `agents/*` branches deleted on GitHub and the old ones here. Left: protecting `main` (the user, as admin), the secrets, the tag.

Suggested order of the clean-up itself, each destructive step confirmed with the user right before it:

1. Scan the whole history for secrets (gitleaks or similar): batches handled tokens and pairing codes. **Done 11-08
   (batch 58)**, with a pattern scan of every commit on every branch (GitHub, Google, AWS, Slack and OpenAI-style tokens,
   private keys, token/secret/password assignments, long hex strings): **no secrets found**. The one hit was a VS Code CLI
   version hash. Also seen, for the user to decide on before the history goes public:
   * The first two commits (`23bc319` "Add AVF Studio spec and docs" and `9747fba` "Translate spec and docs to English",
     10-15) are authored as `[redacted username] <[redacted personal email]>`, a personal email; every later commit uses the GitHub
     noreply address. They're in every branch and already on GitHub. Rewriting their author (a mailmap with
     `git filter-repo`) changes every commit's hash, so it would go with step 3's force push, or be left as is.
     **User, 11-08:** they'll swap it for GitHub's private (noreply) address, later. Done once, with step 3's force push:
     a mailmap that rewrites both those commits and the `Kinteshi-dev` ones to the address of the account that will own
     the repository (ownership is moving to the user's main account). Until then, new commits keep using
     `320711684+Kinteshi-dev@users.noreply.github.com`.
   * Private network addresses of the tablet and the dev machine (192.168.1.x, 10.x) appear in docs and tests: harmless,
     listed only for completeness.
2. Keep this branch's history unless the scan finds something; otherwise squash into one first commit.
3. Replace `main` on GitHub with this branch (a force push) and delete the other branches there and locally. An optional
   local bundle as a backup.
4. With R10, clean the tree: Track A's SPEC.md and scripts, `docs/model-guide-arc-b580.md` (unrelated), the aardink-lab
   module, and the untracked `android/` folder (Track A's Android project; ask before deleting it from disk).
5. Rename the repository to the chosen name, protect `main`, tag `v0.1.0`.
