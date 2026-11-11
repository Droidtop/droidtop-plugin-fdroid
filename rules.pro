# R8 rules for the plugin payload (build.sh).

# The entry class droidtop loads by name (manifest entryClass).
-keep class dev.droidtop.plugins.fdroid.FdroidPlugin { public <init>(); public *; }

# Every bundled class that is not kept moves here, out of the way of droidtop's own copies.
-repackageclasses dev.droidtop.plugins.fdroid.lib
-allowaccessmodification

# Upstream's own consumer rule (org.fdroid:index's proguard.txt): keep serializer() on companions of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Parts of the bundled libraries this plugin never reaches refer to classes it does not bundle (androidx, coroutines).
-dontwarn **
-dontnote **
