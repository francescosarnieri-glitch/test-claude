# R8 rules for the release build.
#
# Compose, Hilt, Room and kotlinx-serialization all ship their own rules inside their own
# artifacts — the content classes and the generated database keep themselves. What is left
# here is only what this app does that no library can know about.

-dontwarn org.jetbrains.annotations.**

# The school stores enums by name — "STRICT", "NORMAL", "CAREER" — in SQLite and reads them
# back with valueOf. If R8 renamed those constants, every saved profile would fail to load in
# release and only in release, which is the worst kind of bug there is.
-keepclassmembers enum com.cybersensei.academy.core.model.** { *; }
-keepclassmembers enum com.cybersensei.academy.engine.mastery.** { *; }
-keepclassmembers enum com.cybersensei.academy.engine.tutor.StudyEvent$Kind { *; }

# When the app dies it shows the student the stack trace instead of reopening as though
# nothing had happened. An obfuscated trace would make that screen useless.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
