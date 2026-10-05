/*
 * Runtime Native Class Extractor
 *
 * Based on Andrei Pangin's Class File Extractor (UPL-1.0).
 * Runtime recovery orchestration added for startup JVMTI tracing.
 */

import com.sun.tools.attach.VirtualMachine;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class ClassFileExtractor {

    // ---------------- javaagent side ----------------

    public static void agentmain(String cmdline, Instrumentation inst) throws Exception {
        Map<String, String> kv = parseAgentCommand(cmdline);
        String output = decode64(kv.get("out64"));
        String prefix = decode64(kv.get("prefix64"));
        if (output == null || output.isEmpty()) {
            throw new IllegalArgumentException("missing output path");
        }
        if (prefix == null) prefix = "";
        final String finalPrefix = prefix;
        String slashPrefix = prefix.replace('.', '/');

        final String finalSlashPrefix = slashPrefix;
        Class<?>[] classes = Arrays.stream(inst.getAllLoadedClasses())
                .filter(c -> matchesPrefix(c.getName(), finalPrefix) && !c.isArray() && inst.isModifiableClass(c))
                .toArray(Class[]::new);

        Map<String, byte[]> classData = new ConcurrentHashMap<>();
        ClassFileTransformer extractor = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> cls,
                                    ProtectionDomain pd, byte[] classBytes) {
                if (className != null && matchesInternalPrefix(className, finalSlashPrefix)) {
                    classData.put(className, classBytes);
                }
                return null;
            }
        };

        inst.addTransformer(extractor, true);
        try {
            // Retransform in smaller batches: some large applications reject one
            // giant retransformClasses() request even though individual classes are modifiable.
            final int batch = 256;
            for (int i = 0; i < classes.length; i += batch) {
                int to = Math.min(classes.length, i + batch);
                Class<?>[] part = Arrays.copyOfRange(classes, i, to);
                try {
                    inst.retransformClasses(part);
                } catch (Throwable batchFailure) {
                    // Fall back to one-by-one so one problematic class does not
                    // prevent every other loaded class from being extracted.
                    for (Class<?> c : part) {
                        try { inst.retransformClasses(c); } catch (Throwable ignored) { }
                    }
                }
            }
        } finally {
            inst.removeTransformer(extractor);
        }

        Path out = Paths.get(output).toAbsolutePath();
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>(classData.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(out.toFile()))) {
            for (Map.Entry<String, byte[]> entry : entries) {
                jar.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    private static boolean matchesPrefix(String className, String prefix) {
        if (prefix != null && !prefix.isEmpty()) return className.startsWith(prefix);
        return !isSystemClassName(className) && !className.startsWith("ClassFileExtractor");
    }

    private static boolean matchesInternalPrefix(String className, String slashPrefix) {
        if (slashPrefix != null && !slashPrefix.isEmpty()) return className.startsWith(slashPrefix);
        String dotted = className.replace('/', '.');
        return !isSystemClassName(dotted) && !dotted.startsWith("ClassFileExtractor");
    }

    private static boolean isSystemClassName(String n) {
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") ||
               n.startsWith("sun.") || n.startsWith("com.sun.");
    }

    // ---------------- controller side ----------------

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage();
            return;
        }
        if ("launch".equals(args[0])) {
            launchMode(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if ("jvmarg".equals(args[0])) {
            jvmArgMode(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        attachMode(args);
    }

    private static void attachMode(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            System.exit(2);
        }
        String pid = args[0];
        Path output = Paths.get(args[1]).toAbsolutePath();
        String prefix = "";
        int i = 2;
        if (i < args.length && !args[i].startsWith("--")) prefix = args[i++];

        Options opt = new Options();
        while (i < args.length) i = parseOption(args, i, opt);
        opt.output = output;
        opt.prefix = prefix;
        runExtractAndRecover(pid, opt);
    }

    private static void launchMode(String[] args) throws Exception {
        Options opt = new Options();
        String javaExe = null;
        Path nativeAgent = null;
        long delayMs = 5000L;
        boolean waitEnter = false;
        boolean stopTarget = false;
        List<String> targetArgs = new ArrayList<>();

        int i = 0;
        while (i < args.length) {
            String a = args[i];
            if ("--".equals(a)) {
                targetArgs.addAll(Arrays.asList(args).subList(i + 1, args.length));
                break;
            } else if ("--java".equals(a)) {
                javaExe = need(args, ++i, a); i++;
            } else if ("--agent".equals(a)) {
                nativeAgent = Paths.get(need(args, ++i, a)).toAbsolutePath(); i++;
            } else if ("--delay-ms".equals(a)) {
                delayMs = Long.parseLong(need(args, ++i, a)); i++;
            } else if ("--wait-enter".equals(a)) {
                waitEnter = true; i++;
            } else if ("--stop-target".equals(a)) {
                stopTarget = true; i++;
            } else {
                i = parseOption(args, i, opt);
            }
        }
        if (javaExe == null) javaExe = defaultJava();
        if (nativeAgent == null) throw new IllegalArgumentException("launch requires --agent <native_recovery_agent.dll>");
        if (opt.output == null) throw new IllegalArgumentException("launch requires --output <clean.jar>");
        if (targetArgs.isEmpty()) throw new IllegalArgumentException("launch requires target JVM arguments after --");

        Path work = opt.work != null ? opt.work : defaultWork(opt.output);
        Files.createDirectories(work);
        if (opt.trace == null) opt.trace = work.resolve("trace.jsonl");
        Path capture = opt.capture != null ? opt.capture : work.resolve("modules");
        Files.createDirectories(capture);
        rejectComma(nativeAgent); rejectComma(opt.trace); rejectComma(capture);

        String agentArg = "-agentpath:" + nativeAgent + "=trace=" + opt.trace + ",capture=" + capture;
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add(agentArg);
        cmd.addAll(targetArgs);
        System.out.println("[launcher] " + joinForDisplay(cmd));
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        String pid = processPid(p);
        System.out.println("[launcher] target PID=" + pid);

        if (waitEnter) {
            System.out.println("[launcher] Exercise the native code paths now, then press ENTER to extract/recover.");
            System.in.read();
        } else if (delayMs > 0) {
            Thread.sleep(delayMs);
        }

        try {
            runExtractAndRecover(pid, opt);
        } finally {
            if (stopTarget && p.isAlive()) p.destroy();
        }
    }

    private static void jvmArgMode(String[] args) throws Exception {
        Path agent = null, trace = null, capture = null;
        for (int i = 0; i < args.length; ) {
            String a = args[i];
            if ("--agent".equals(a)) { agent = Paths.get(need(args, ++i, a)).toAbsolutePath(); i++; }
            else if ("--trace".equals(a)) { trace = Paths.get(need(args, ++i, a)).toAbsolutePath(); i++; }
            else if ("--capture".equals(a)) { capture = Paths.get(need(args, ++i, a)).toAbsolutePath(); i++; }
            else throw new IllegalArgumentException("Unknown jvmarg option: " + a);
        }
        if (agent == null || trace == null) throw new IllegalArgumentException("jvmarg requires --agent and --trace");
        if (capture == null) capture = trace.getParent() == null ? Paths.get("modules") : trace.getParent().resolve("modules");
        rejectComma(agent); rejectComma(trace); rejectComma(capture);
        System.out.println("-agentpath:" + agent + "=trace=" + trace + ",capture=" + capture);
    }

    private static void runExtractAndRecover(String pid, Options opt) throws Exception {
        boolean recover = opt.trace != null && !opt.noRecover;
        Path work = opt.work != null ? opt.work : defaultWork(opt.output);
        Files.createDirectories(work);
        Path rawJar = recover ? work.resolve("extracted-runtime.jar") : opt.output;

        System.out.println("[extract] attaching to PID " + pid);
        attachAndExtract(pid, rawJar, opt.prefix == null ? "" : opt.prefix);
        System.out.println("[extract] loaded classes -> " + rawJar);

        if (!recover) {
            System.out.println("Done");
            return;
        }
        if (!Files.isRegularFile(opt.trace)) {
            throw new IllegalStateException("Trace file does not exist: " + opt.trace +
                    "\nThe target JVM must be started with -agentpath before its native library loads.");
        }

        Path runtime = opt.runtime != null ? opt.runtime : defaultRuntimeRoot();
        Path classesJson = work.resolve("classes.json");
        Path recovered = work.resolve("recovered");
        Files.createDirectories(recovered);

        runTool(runtime, "jar-parser", "j2c.jarparser.MainKt",
                Arrays.asList(rawJar.toString(), "-o", classesJson.toString()));

        runTool(runtime, "trace-to-bytecode", "j2c.tracetobc.MainKt",
                Arrays.asList("--trace", opt.trace.toString(), "--classes", classesJson.toString(),
                        "-o", recovered.toString(), "--confidence", "runtime-observed"));

        Path rebuildInput = opt.baseJar != null ? opt.baseJar : rawJar;
        Path rebuiltTmp = work.resolve("rebuilt-clean.jar");
        Files.deleteIfExists(rebuiltTmp);
        List<String> rebuildArgs = new ArrayList<>(Arrays.asList(
                "--input", rebuildInput.toString(),
                "--recovered", recovered.toString(),
                "--classes", classesJson.toString(),
                "-o", rebuiltTmp.toString(),
                "--annotate-runtime-values=false"));
        if (!opt.allowPartial) rebuildArgs.add("--require-complete");
        runTool(runtime, "class-rebuilder", "j2c.classrebuilder.MainKt", rebuildArgs);
        if (opt.output.getParent() != null) Files.createDirectories(opt.output.getParent());
        Files.move(rebuiltTmp, opt.output, StandardCopyOption.REPLACE_EXISTING);

        System.out.println("[recover] clean jar -> " + opt.output);
        if (opt.allowPartial) {
            System.out.println("[recover] partial mode enabled: methods without an observed/recoverable trace may remain native.");
        } else {
            System.out.println("[recover] complete mode: output is accepted only when all non-loader native methods were recovered.");
        }
        System.out.println("Done");
    }

    private static void attachAndExtract(String pid, Path out, String prefix) throws Exception {
        Path self = selfJar();
        String command = "out64=" + encode64(out.toString()) + ";prefix64=" + encode64(prefix == null ? "" : prefix);
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(self.toString(), command);
        } finally {
            vm.detach();
        }
    }

    private static void runTool(Path runtimeRoot, String module, String mainClass, List<String> args) throws Exception {
        Path lib = runtimeRoot.resolve(module).resolve("lib");
        if (!Files.isDirectory(lib)) {
            throw new IllegalStateException("Missing prebuilt recovery runtime: " + lib +
                    "\nRun build-windows.ps1 once (or use a packaged release). Runtime never builds tools automatically.");
        }
        List<String> jars = new ArrayList<>();
        Files.list(lib).filter(p -> p.toString().endsWith(".jar")).sorted().forEach(p -> jars.add(p.toString()));
        if (jars.isEmpty()) throw new IllegalStateException("No jars in " + lib);
        String cp = String.join(File.pathSeparator, jars);
        List<String> cmd = new ArrayList<>();
        cmd.add(defaultJava());
        cmd.add("-cp"); cmd.add(cp); cmd.add(mainClass); cmd.addAll(args);
        System.out.println("[recovery] " + module);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int rc = p.waitFor();
        if (rc != 0) throw new IllegalStateException(module + " failed with exit code " + rc);
    }

    private static int parseOption(String[] args, int i, Options opt) {
        String a = args[i];
        if ("--trace".equals(a)) { opt.trace = Paths.get(need(args, i + 1, a)).toAbsolutePath(); return i + 2; }
        if ("--runtime".equals(a)) { opt.runtime = Paths.get(need(args, i + 1, a)).toAbsolutePath(); return i + 2; }
        if ("--work".equals(a)) { opt.work = Paths.get(need(args, i + 1, a)).toAbsolutePath(); return i + 2; }
        if ("--base-jar".equals(a)) { opt.baseJar = Paths.get(need(args, i + 1, a)).toAbsolutePath(); return i + 2; }
        if ("--output".equals(a)) { opt.output = Paths.get(need(args, i + 1, a)).toAbsolutePath(); return i + 2; }
        if ("--prefix".equals(a)) { opt.prefix = need(args, i + 1, a); return i + 2; }
        if ("--capture".equals(a)) { opt.capture = Paths.get(need(args, i + 1, a)).toAbsolutePath(); return i + 2; }
        if ("--allow-partial".equals(a)) { opt.allowPartial = true; return i + 1; }
        if ("--no-recover".equals(a)) { opt.noRecover = true; return i + 1; }
        throw new IllegalArgumentException("Unknown option: " + a);
    }

    private static Path defaultWork(Path output) {
        Path parent = output.getParent() != null ? output.getParent() : Paths.get(".").toAbsolutePath();
        return parent.resolve(output.getFileName().toString() + ".recovery-work");
    }

    private static Path defaultRuntimeRoot() throws Exception {
        Path parent = selfJar().getParent();
        return parent.resolve("runtime");
    }

    private static Path selfJar() throws Exception {
        String url = ClassFileExtractor.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
        Path p = Paths.get(url).toAbsolutePath();
        if (!Files.isRegularFile(p) || !p.toString().toLowerCase().endsWith(".jar")) {
            throw new IllegalStateException("ClassFileExtractor must run from a JAR");
        }
        return p;
    }

    private static String defaultJava() {
        String exe = isWindows() ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static String processPid(Process p) throws Exception {
        try {
            Method m = Process.class.getMethod("pid");
            Object v = m.invoke(p);
            return String.valueOf(v);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("launch mode requires JDK 9+ to obtain the child PID; use external -agentpath + attach mode on JDK 8");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void rejectComma(Path p) {
        if (p.toString().contains(",")) throw new IllegalArgumentException("Agent option paths cannot contain commas: " + p);
    }

    private static String encode64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode64(String s) {
        if (s == null) return null;
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseAgentCommand(String s) {
        Map<String, String> out = new HashMap<>();
        if (s == null) return out;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return out;
    }

    private static String need(String[] args, int i, String opt) {
        if (i >= args.length) throw new IllegalArgumentException(opt + " requires a value");
        return args[i];
    }

    private static String joinForDisplay(List<String> cmd) {
        StringBuilder b = new StringBuilder();
        for (String s : cmd) {
            if (b.length() > 0) b.append(' ');
            b.append(s.indexOf(' ') >= 0 ? ('"' + s + '"') : s);
        }
        return b.toString();
    }

    private static void usage() {
        System.out.println("Runtime Native Class Extractor\n" +
                "\nAttach to an already running JVM that was started with the native agent:\n" +
                "  java -jar extractor.jar <pid> <output.jar> [prefix] --trace <trace.jsonl> [options]\n" +
                "\nLaunch a JVM with -agentpath before any application native code loads:\n" +
                "  java -jar extractor.jar launch --agent <native_recovery_agent.dll> --output <clean.jar> [options] -- -jar <target.jar>\n" +
                "\nPrint the JVM argument for an external launcher:\n" +
                "  java -jar extractor.jar jvmarg --agent <dll> --trace <trace.jsonl> [--capture <dir>]\n" +
                "\nOptions:\n" +
                "  --base-jar <jar>     rebuild the full original jar instead of only extracted classes\n" +
                "  --runtime <dir>      prebuilt recovery runtime (default: ./runtime next to extractor.jar)\n" +
                "  --work <dir>         recovery intermediates\n" +
                "  --allow-partial      permit unrecovered native methods to remain native\n" +
                "  --no-recover         only extract runtime class files\n" +
                "  --prefix <pkg>       class prefix filter (launch mode)\n" +
                "  --delay-ms <ms>      launch mode delay before extraction (default 5000)\n" +
                "  --wait-enter         launch mode: wait for ENTER after you exercise native paths\n" +
                "  --stop-target        terminate the launched target after extraction/recovery\n");
    }

    private static final class Options {
        Path output;
        String prefix = "";
        Path trace;
        Path runtime;
        Path work;
        Path baseJar;
        Path capture;
        boolean allowPartial;
        boolean noRecover;
    }
}
