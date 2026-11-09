# R8 rules for the release build. Libraries bring their own rules; these cover what the app reaches by name.

# The app's own classes: Gson reads and writes its saved state (sessions, settings, offline lists, notebooks' models)
# by field name, and several are found by reflection. Kept whole: the app is small next to its libraries, which R8
# still shrinks.
-keep class com.avfstudio.nativeide.** { *; }

# Aardink's internals, reached by reflection in AardinkGeometry (backlog A19): the command handler, the controller, its
# view and hover state. Kept whole, so a pinned version's names stay as the release review checked them.
-keep class com.aardarch.aardink.** { *; }

# tm4e reads grammars and themes into its raw model classes with Gson; joni and jcodings load encodings and
# tables by class name.
-keep class org.eclipse.tm4e.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-dontwarn org.eclipse.jdt.annotation.**

# Gson needs generic signatures and annotations kept to read lists and maps of the app's types.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# msgpack, the daemon protocol's encoding, picks its buffer implementation by class name (MessageBufferU and others).
-keep class org.msgpack.** { *; }
-dontwarn sun.misc.Unsafe
-dontwarn sun.nio.ch.DirectBuffer
