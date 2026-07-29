# Build wrapper.
# ponytail: -D override instead of editing ~/.gradle/gradle.properties - that file pins JDK 17
# for another project on this box, and user-home properties outrank project ones.
param([string]$Task = "build")

# Loom 1.17.17 requires Gradle 9.5+; 8.14 fails variant matching.
$gradle = "C:\cc\gradle-9.6.1\bin\gradle.bat"
$jdk21  = "C:/cc/jdk21"

& $gradle --project-dir "C:\cc\isocraft-rts" $Task `
    "-Dorg.gradle.java.home=$jdk21" `
    --no-daemon --console=plain
exit $LASTEXITCODE
