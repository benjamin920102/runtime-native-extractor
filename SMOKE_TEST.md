# Smoke-test status

Validated in the build container with OpenJDK 21 on Linux:

1. `native_recovery_agent` builds successfully with CMake.
2. A JNI demo library was loaded normally and produced `NativeMethodBind`, method enter/exit, and JNI call events.
3. The same JNI library was renamed from `libdemo.so` to `lib847293.zip` and loaded with `System.load`; the agent resolved the runtime module by function address and copied the exact `lib847293.zip` bytes into the session module directory.
4. The extractor successfully attached to a JVM that had already started with `-agentpath` and exported only application classes by default.
5. Integrated `launch` mode successfully injected `-agentpath` before application startup, obtained the child PID, then attached for extraction.

The Windows-specific `module_capture.cpp` path uses `GetModuleHandleEx`, `GetModuleFileName`, PE headers, `VirtualQuery`, and `ReadProcessMemory`. A Windows build is provided through `build-windows.ps1` and `.github/workflows/build-windows.yml`, but the current build container is Linux and therefore does not contain an MSVC-built `.dll` binary.
