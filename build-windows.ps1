param(
    [string]$Configuration = "Release"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Build = Join-Path $Root "build"
$Dist = Join-Path $Root "dist"

if (-not $env:JAVA_HOME) {
    $javac = (Get-Command javac -ErrorAction Stop).Source
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javac)
}
Write-Host "JAVA_HOME=$env:JAVA_HOME"

Remove-Item -Recurse -Force $Build,$Dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Build,$Dist | Out-Null

# 1) Native startup JVMTI agent. Uses the normal MSVC/CMake toolchain; no Zig.
$NativeBuild = Join-Path $Build "native"
cmake -S (Join-Path $Root "native") -B $NativeBuild -A x64
cmake --build $NativeBuild --config $Configuration
$dll = Get-ChildItem $NativeBuild -Recurse -Filter "native_recovery_agent.dll" | Select-Object -First 1
if (-not $dll) { throw "native_recovery_agent.dll was not produced" }
Copy-Item $dll.FullName (Join-Path $Dist "native_recovery_agent.dll")

# 2) Extractor JAR. Build once; runtime never compiles anything.
$Extractor = Join-Path $Root "extractor"
Push-Location $Extractor
try {
    if (Get-Command ant -ErrorAction SilentlyContinue) {
        ant clean build
    } else {
        $classes = Join-Path $Extractor "build\classes"
        Remove-Item -Recurse -Force (Join-Path $Extractor "build") -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force $classes | Out-Null
        & (Join-Path $env:JAVA_HOME "bin\javac.exe") -source 8 -target 8 -d $classes (Join-Path $Extractor "src\ClassFileExtractor.java")
        @"
Main-Class: ClassFileExtractor
Agent-Class: ClassFileExtractor
Can-Retransform-Classes: true

"@ | Set-Content -Encoding Ascii (Join-Path $Extractor "build\MANIFEST.MF")
        & (Join-Path $env:JAVA_HOME "bin\jar.exe") cfm (Join-Path $Extractor "build\extractor.jar") (Join-Path $Extractor "build\MANIFEST.MF") -C $classes .
    }
} finally { Pop-Location }
Copy-Item (Join-Path $Extractor "build\extractor.jar") (Join-Path $Dist "extractor.jar")

# 3) Prebuild the c2j-derived JVM recovery tools into runtime/*/lib.
#    This is the only Gradle step. extractor.jar never invokes Gradle at runtime.
$Recovery = Join-Path $Root "recovery-jvm"
Push-Location $Recovery
try {
    & .\gradlew.bat --no-daemon :jar-parser:installDist :trace-to-bytecode:installDist :class-rebuilder:installDist
    if ($LASTEXITCODE -ne 0) { throw "Gradle recovery build failed ($LASTEXITCODE)" }
} finally { Pop-Location }

$Runtime = Join-Path $Dist "runtime"
New-Item -ItemType Directory -Force $Runtime | Out-Null
foreach ($m in @("jar-parser","trace-to-bytecode","class-rebuilder")) {
    $src = Join-Path $Recovery "$m\build\install\$m"
    if (-not (Test-Path $src)) { throw "Missing installDist output: $src" }
    Copy-Item -Recurse $src (Join-Path $Runtime $m)
}

Copy-Item (Join-Path $Root "README.md") (Join-Path $Dist "README.md")
Copy-Item (Join-Path $Root "LICENSE-GPL3-c2j") (Join-Path $Dist "LICENSE-GPL3-c2j")
Copy-Item (Join-Path $Root "LICENSE-UPL-extractor") (Join-Path $Dist "LICENSE-UPL-extractor")

Write-Host ""
Write-Host "Build complete: $Dist"
Write-Host "Runtime files are prebuilt; no CMake/Gradle/Zig is invoked when extractor.jar runs."
