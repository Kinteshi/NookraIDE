# tm4e core

[Eclipse tm4e](https://github.com/eclipse-tm4e/tm4e)'s core: it reads TextMate grammars and themes and tokenizes lines,
which is how the editor colours code. Licensed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/)
(`SPDX-License-Identifier: EPL-2.0`); each file keeps its header.

## Where it comes from

These are the `org.eclipse.tm4e.core` sources that sora-editor 0.23.6 carried in its `language-textmate` module
(github.com/Rosemoe/sora-editor, tag `0.23.6`, `language-textmate/src/main/java/org/eclipse/tm4e/core`), which adapts
tm4e to Android. They were taken when sora-editor left the app (Aardink switch, phase 7), so the colours stay exactly
as they were. The `languageconfiguration` package was left out: the app reads `language-configuration.json` itself.

## Changes made here

* `internal/utils/Logger.java` and `internal/utils/MatcherUtils.java` are new, written for this module in place of
  sora-editor's own `Logger` and `MatcherUtils` (which are sora-editor's code, under its licence). Seven files import
  them instead of sora-editor's.

Moving to upstream tm4e (0.18.0 or later) instead is possible; it needs a check that it runs on Android.
