-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**

# Données sauvegardées (kotlinx.serialization)
-keep class app.yomu.data.** { *; }
