# kotlinx.serialization: keep generated serializers of our content models
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.momentmal.content.**$$serializer { *; }
-keepclassmembers class app.momentmal.content.** { *** Companion; }
-keepclasseswithmembers class app.momentmal.content.** { kotlinx.serialization.KSerializer serializer(...); }
