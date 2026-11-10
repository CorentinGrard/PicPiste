# R8 rules for PicPiste. The libraries (kotlinx.serialization, WorkManager, Car App Library)
# ship their own; add rules here only for code reached by reflection.

# Readable crash reports in the Play Console (with the mapping file uploaded by CI).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
