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
$GradleArgs = @(
    "--no-daemon",
    ":jar-parser:installDist",
    ":trace-to-bytecode:installDist",
    ":class-rebuilder:installDist"
)

function Invoke-RecoveryGradle {
    param([string]$RecoveryDir)

    $wrapper = Join-Path $RecoveryDir "gradlew.bat"
    $wrapperJar = Join-Path $RecoveryDir "gradle\wrapper\gradle-wrapper.jar"

    # Preferred path: use the standard Gradle Wrapper when its JAR is present.
    if ((Test-Path $wrapper) -and (Test-Path $wrapperJar)) {
        Write-Host "Using Gradle Wrapper."
        & $wrapper @GradleArgs
        return $LASTEXITCODE
    }

    # GitHub Actions installs Gradle explicitly; this also supports developers
    # who already have Gradle on PATH.
    $gradle = Get-Command gradle -ErrorAction SilentlyContinue
    if ($gradle) {
        Write-Warning "gradle-wrapper.jar is missing; using Gradle from PATH: $($gradle.Source)"
        & $gradle.Source @GradleArgs
        return $LASTEXITCODE
    }

    # Last-resort bootstrap for a clean Windows machine: download the exact
    # distribution declared by gradle-wrapper.properties and invoke it directly.
    $properties = Join-Path $RecoveryDir "gradle\wrapper\gradle-wrapper.properties"
    if (-not (Test-Path $properties)) {
        throw "Missing Gradle wrapper properties: $properties"
    }

    $distributionLine = Get-Content $properties | Where-Object { $_ -match '^distributionUrl=' } | Select-Object -First 1
    if (-not $distributionLine) {
        throw "distributionUrl is missing from $properties"
    }

    $distributionUrl = ($distributionLine -replace '^distributionUrl=', '') -replace '\\:', ':'
    $bootstrapDir = Join-Path $Build "gradle-bootstrap"
    $bootstrapZip = Join-Path $Build "gradle-bootstrap.zip"

    Write-Warning "gradle-wrapper.jar and Gradle on PATH are unavailable."
    Write-Host "Bootstrapping Gradle from $distributionUrl"
    Remove-Item -Recurse -Force $bootstrapDir -ErrorAction SilentlyContinue
    Remove-Item -Force $bootstrapZip -ErrorAction SilentlyContinue

    Invoke-WebRequest -Uri $distributionUrl -OutFile $bootstrapZip -UseBasicParsing
    Expand-Archive -Path $bootstrapZip -DestinationPath $bootstrapDir -Force

    $gradleBat = Get-ChildItem $bootstrapDir -Recurse -Filter "gradle.bat" |
        Where-Object { $_.Directory.Name -eq "bin" } |
        Select-Object -First 1
    if (-not $gradleBat) {
        throw "Gradle bootstrap failed: gradle.bat was not found after extracting $distributionUrl"
    }

    & $gradleBat.FullName @GradleArgs
    return $LASTEXITCODE
}

Push-Location $Recovery
try {
    $gradleExitCode = Invoke-RecoveryGradle -RecoveryDir $Recovery
    if ($gradleExitCode -ne 0) { throw "Gradle recovery build failed ($gradleExitCode)" }
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
