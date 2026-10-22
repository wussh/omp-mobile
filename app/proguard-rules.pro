# Keep wire DTOs for kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.omp.mobile.**$$serializer { *; }
-keepclassmembers class dev.omp.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class dev.omp.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
