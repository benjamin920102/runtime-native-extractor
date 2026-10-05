# Runtime Native Extractor

This is a startup-JVMTI version of `class-file-extractor` designed for Java programs whose protected methods are registered or implemented by a native library that only exists for one JVM session (for example a randomly named `lib123456.zip` whose bytes are actually a DLL/PE image).

The important design rule is **same-run capture**:

```text
JVM starts
  -> native_recovery_agent.dll is already loaded by -agentpath
  -> random lib*.zip is created/loaded
  -> NativeMethodBind fires
  -> agent records Java method <-> native address
  -> agent resolves module base/path and snapshots that exact module
  -> native method executes
  -> hooked JNIEnv records JNI semantics
  -> extractor attaches only to dump the live .class files
  -> trace-to-bytecode reconstructs observed JVM instructions
  -> class-rebuilder removes ACC_NATIVE from recovered methods
  -> loader/native payload is stripped
  -> clean.jar
```

The JVMTI tracer is loaded at JVM startup. The later Attach API is used only for class extraction; it is **not** relied on to start native tracing.

## What changed

- Startup `Agent_OnLoad` JVMTI tracing, rather than depending on a late attach.
- `NativeMethodBind`, `MethodEntry`, `MethodExit`, exception events and JNIEnv-table hooks are active before application initialization.
- Every non-JDK native bind records `fnAddr`, `moduleBase`, `fnRva`, `modulePath` and module size when available.
- The exact file backing the loaded module is copied immediately. A Windows PE memory image is also snapshotted, so a temporary file can disappear after loading.
- File extension is irrelevant. A module named `lib847293.zip` is captured exactly like a `.dll` when Windows loaded it as a PE module.
- The recovery tools accept `classes.json` directly, so they no longer require a stable native binary / `binary.json` from a different run.
- The rebuilder recognizes `lib*.zip` entries whose bytes have PE/ELF/Mach-O magic and strips them from the rebuilt JAR.
- Complete mode fails instead of silently claiming success when unrecovered native methods remain. Use `--allow-partial` only when you explicitly want a partial output.
- Runtime never builds C2J or the native agent. `build-windows.ps1` produces a self-contained `dist/` once.

## Build on Windows x64

Requirements: JDK 17+, CMake, Visual Studio C++ Build Tools, and Internet access during the one-time Gradle dependency build. Zig is not used.

```powershell
.\build-windows.ps1
```

The output is:

```text
dist/
  extractor.jar
  native_recovery_agent.dll
  runtime/
    jar-parser/
    trace-to-bytecode/
    class-rebuilder/
```

After this build, normal extraction/recovery does not call CMake, Gradle, Git, or Zig.

A GitHub Actions workflow is included at `.github/workflows/build-windows.yml`; pushing this repository to GitHub also produces the Windows x64 `dist/` artifact.

## Recommended workflow: external launcher

Create a session directory, then ask the extractor to print the exact JVM argument:

```bat
java -jar extractor.jar jvmarg ^
  --agent C:\tools\native_recovery_agent.dll ^
  --trace C:\work\session1\trace.jsonl ^
  --capture C:\work\session1\modules
```

It prints something like:

```text
-agentpath:C:\tools\native_recovery_agent.dll=trace=C:\work\session1\trace.jsonl,capture=C:\work\session1\modules
```

Put that JVM argument into the target launcher **before the target PID exists**. Do not wait for the random native library to load and then attach the tracer.

Run the target and exercise the features/native methods you want to recover. Then run:

```bat
java -jar extractor.jar 12345 clean.jar ^
  --trace C:\work\session1\trace.jsonl ^
  --base-jar C:\path\protected.jar
```

`--base-jar` is strongly recommended. Runtime class bytes are used for analysis, while the original JAR is used as the rebuild base so ordinary resources are preserved. Native loader resources such as a PE disguised as `lib*.zip` are stripped during rebuilding.

If you only want a package:

```bat
java -jar extractor.jar 12345 clean.jar pw.hachimi ^
  --trace C:\work\session1\trace.jsonl ^
  --base-jar C:\path\protected.jar
```

## Integrated launcher mode

For a normal `java -jar` application the extractor can start the target itself, guaranteeing that `-agentpath` is present before application code runs:

```bat
java -jar extractor.jar launch ^
  --agent C:\tools\native_recovery_agent.dll ^
  --output clean.jar ^
  --base-jar protected.jar ^
  --work C:\work\session1 ^
  --wait-enter ^
  -- -jar protected.jar
```

The target starts immediately with the startup JVMTI agent. Exercise the desired native code paths, then press ENTER. The extractor attaches to the already-instrumented process, dumps live class bytes and runs recovery automatically.

For an application that executes the relevant paths automatically, use a delay instead:

```bat
java -jar extractor.jar launch ^
  --agent C:\tools\native_recovery_agent.dll ^
  --output clean.jar ^
  --base-jar protected.jar ^
  --delay-ms 10000 ^
  -- -jar protected.jar
```

## Output / working data

For `clean.jar`, the default work directory is `clean.jar.recovery-work/` and includes:

```text
trace.jsonl              startup JVMTI/JNI trace
modules/                 same-session native snapshots
extracted-runtime.jar    live retransformed class bytes
classes.json             class/native-method inventory
recovered/               recovered method instruction JSON
rebuilt-clean.jar        temporary output before atomic move
```

`clean.jar` is only moved into place after the recovery/rebuild commands succeed.

## Random `lib*.zip`

The native agent does not search by filename and does not assume `.dll`.

At `NativeMethodBind` it starts with the function pointer and resolves the module that owns that address. On Windows the bind event includes fields similar to:

```json
{
  "ev": "bind",
  "owner": "example/Protected",
  "name": "check",
  "desc": "(Ljava/lang/String;)Z",
  "fnAddr": "0x7ffb12345678",
  "moduleBase": "0x7ffb12000000",
  "fnRva": "0x345678",
  "modulePath": "C:/Temp/lib847293.zip",
  "diskSnapshot": ".../module_7ffb12000000_lib847293.zip.disk.bin",
  "memorySnapshot": ".../module_7ffb12000000_lib847293.zip.memory.bin"
}
```

The next JVM run may load `lib991002.zip` at a completely different address; that does not affect the previous recovery session because no addresses or binaries are reused across runs.

## Completeness and limitations

Dynamic recovery reconstructs behavior that is observable from the executed native method and its JNI interactions. It is not mathematically possible to guarantee recovery of the original pre-obfuscation bytecode from every arbitrary machine-code function.

In particular:

- A native method or branch that never executes cannot be learned from the runtime trace.
- Pure x64 arithmetic/control flow that stays entirely inside native code and does not expose enough semantics through JNI may need static lifting/emulation in addition to this tracer.
- Runtime constants can reflect the exact inputs used during the observed execution. Use several inputs/code paths when the method contains branches.
- The default mode is deliberately strict: if non-loader native methods remain unrecovered, the rebuild returns non-zero and `clean.jar` is not published. `--allow-partial` disables that safeguard.

For best coverage, use `--wait-enter`, exercise every protected feature you care about, then trigger extraction.

## Development notes

The JVMTI/JNI tracing and bytecode recovery code is derived from the user-supplied `c2j-native-deobfuscator` source and therefore this combined project includes the GPLv3 license. The original `ClassFileExtractor` portion retains its UPL notice; see the included license files.
