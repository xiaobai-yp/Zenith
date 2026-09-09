# Runtime annotations required by Kotlin/Android reflection.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

# Android components referenced from the manifest and XML resources.
-keep,allowoptimization,allowobfuscation class com.zenith.thermal.MainActivity { <init>(...); }
-keep,allowoptimization,allowobfuscation class com.zenith.thermal.AppMonitorService { <init>(...); }
-keep,allowoptimization,allowobfuscation class com.zenith.thermal.BatteryMonitorService { <init>(...); }
-keep,allowoptimization,allowobfuscation class com.zenith.thermal.BootReceiver { <init>(...); }
-keep,allowoptimization,allowobfuscation class com.zenith.thermal.BatteryTargetSlider { <init>(...); }

# Preserve enum/value fields used by generated Kotlin code.
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# The class name is supplied as a string to the Android hidden API lookup.
-keepnames class android.os.SystemProperties

# Remove source-level breadcrumbs from the release artifact.
-renamesourcefileattribute SourceFile

# R8 removes unused classes/members and optimizes platform code by default.
