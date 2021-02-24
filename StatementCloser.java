import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class StatementCloser {

    static class STORE {
        static String PATH;
        static String FILE_TYPE = ".java";
        static Pattern filePattern = Pattern.compile(".*" + FILE_TYPE);
    }

    static boolean verbose = false;

    static class Logger extends OutputStream {

        private final StringBuffer message = new StringBuffer();
        private final boolean err;

        public Logger(boolean err) {
            this.err = err;
        }

        @Override
        public void write(int b) throws IOException {
            message.append((char) b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            message.append(new String(b, StandardCharsets.UTF_8));
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            message.append(new String(Arrays.copyOfRange(b, off, len), StandardCharsets.UTF_8));
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void close() throws IOException {
            try {
                if (err) {
                    var errors = message.toString().trim();
                    if (!errors.isBlank() && errors.indexOf('\n') != errors.lastIndexOf('\n')) {
                        synchronized (System.err) {
                            System.err.println("\n");
                            System.err.println("Error:");
                            System.err.println(errors);
                            Thread.sleep(2);
                        }
                    }
                } else {
                    var logs = message.toString().trim();
                    if (!logs.isBlank()) {
                        synchronized (System.out) {
                            System.out.println("\n");
                            System.out.println("Log:");
                            System.out.println(logs);
                            Thread.sleep(2);
                        }
                    }
                }
            } catch (InterruptedException ignore) {
            } finally {
                message.setLength(0);
            }
        }
    }

    static ThreadLocal<PrintStream> output = ThreadLocal.withInitial(() -> new PrintStream(new Logger(false), false));
    static ThreadLocal<PrintStream> outputErr = ThreadLocal.withInitial(() -> new PrintStream(new Logger(true), false));

    static void log(Object s) {
        if (verbose) System.out.println(s);
    }

    static void info(Object s) {
        output.get().println(s);
    }

    static void warn(Object s) {
        outputErr.get().println(s);
    }

    static final Object mutex = new Object();

    static void done() {
        output.get().close();
        outputErr.get().close();
        output.set(new PrintStream(new Logger(false), false));
        outputErr.set(new PrintStream(new Logger(true), false));
    }

    static List<Path> read() throws Exception {
        List<Path> collect = Files.list(Paths.get(STORE.PATH)).collect(Collectors.toList());
        while (collect.stream().anyMatch(Files::isDirectory)) {
            collect = collect.parallelStream().flatMap(path -> {
                if (Files.isDirectory(path)) {
                    try {
                        return Files.list(path);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    return STORE.filePattern.matcher(path.getFileName().toString()).matches()
                            ? Stream.of(path)
                            : Stream.empty();
                }
                return Stream.empty();
            }).collect(Collectors.toList());
        }
        return collect;
    }

    static Pattern psNamePattern = Pattern.compile("(?m)^\\s*([\\w.]*PreparedStatement)\\s+([\\w\\d]+)\\s*=\\s*[^;]+?(?<!null);");
    static Pattern psInitPattern = Pattern.compile("(?m)^\\s*[\\w.]*PreparedStatement\\s+[^=]+=\\s*(?!null)([^;]*?)\\s*\\(\\s*([^;]*?\\s*),?(\\s*[\\w.]*KEYS\\s*)?\\);");
    static Pattern rsNamePattern = Pattern.compile("(?m)^\\s*([\\w.]*ResultSet)\\s+([\\w\\d]+)\\s*=\\s*[^;]+?(?<!null);");
    static Pattern rsInitPattern = Pattern.compile("(?m)^\\s*[\\w.]*ResultSet\\s+[^=]+=\\s*([^;]+?)(?<!null);");

    static Pattern usingStatementExecute1 = Pattern.compile("(?m)^.*c\\w+S\\w+\\s*\\(\\s*\\)\\s*(?![^;]*\")[^;]*\\);\n");
    static Pattern usingStatementExecute2 = Pattern.compile("(?m)^.*c\\w+S\\w+\\s*\\(\\s*\\)\\s*(?=[^;]*\")[\\s\\S]*?\"[^\"]*\"[\\s\\S]*?\"?\\);\n");

    static int NAME_GROUP = 2;
    static int TYPE_GROUP = 1;

    static String extractPSTryStatement(String match) {
        try {
            var name = psNamePattern
                    .matcher(match)
                    .results()
                    .findFirst()
                    .map(r -> r.group(NAME_GROUP)
                    ).orElseThrow();
            var statement = psInitPattern
                    .matcher(match)
                    .results()
                    .findFirst()
                    .orElseThrow();
            return String.format(
                    "var %s = %s(%s)",
                    name,
                    statement.group(1).replaceAll("\\s*", ""),
                    statement.group(2) + Optional.ofNullable(statement.group(3)).map(s -> "," + s.trim()).orElse("")
            );
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to match query -> skipping\n" + match);
        }
    }

    static String extractRSTryStatement(String match) {
        try {
            var name = rsNamePattern
                    .matcher(match)
                    .results()
                    .findFirst()
                    .map(r -> r.group(NAME_GROUP)
                    ).orElseThrow();
            var statement = rsInitPattern
                    .matcher(match)
                    .results()
                    .findFirst()
                    .map(r -> r.group(1))
                    .orElseThrow();
            return String.format(
                    "var %s = %s",
                    name,
                    statement
            );
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to match RS -> skipping\n" + match);
        }
    }

    static String extractContent(String match) {
        var outBraces = 0;
        var end = 0;
        var two = "/";
        var comment = 0;
        var ignore = false;
        for (int i = 0; i < match.length(); i++) {
            var c = match.charAt(i);
            two = two.length() == 2 ? two.substring(1) + c : two + c;
            if (!ignore) {
                if (c == '{') {
                    outBraces++;
                }
                if (c == '}') {
                    outBraces--;
                    if (outBraces < 0) {
                        end = i - 1;
                        break;
                    }
                }
            }
            if (!ignore) {
                if ("//".equals(two)) {
                    ignore = true;
                }
            } else {
                if ('\n' == c) {
                    ignore = false;
                }
            }
            if ("/*".equals(two)) {
                comment++;
            }
            if ("*/".equals(two)) {
                comment--;
            }
        }
        if (comment != 0) {
            throw new IllegalStateException("Comment -> Skipping");
        }
        return match.substring(0, end);
    }

    static String extractContent(Pattern p, String match, Function<String, String> tryExtractor) {
        try {
            log("Extracting content statement");
            var m = p.matcher(match).results().findFirst().orElseThrow();
            log(
                    match.substring(
                            m.start(),
                            Math.min(m.start() + 500, match.length() - 1)
                    )
            );
            var contentStart = p.matcher(match).results().findFirst().orElseThrow().end();
            var c = extractContent(match.substring(contentStart));
            if (c.contains("return " + m.group(2) + ";") || c.contains("return " + m.group(2) + ".exe")) {
                throw new IllegalStateException("Detected a return statement -> skipping");
            }
            var fucked = "(?m)^\\s*" + m.group(NAME_GROUP).trim() + "\\s*=";
            var res = Pattern.compile(fucked).matcher(c).results().collect(Collectors.toList());
            if (res.size() != 0) {
                throw new IllegalStateException("Fucked up definitions detected -> manual");
            }
            log("Extracting try block");
            int min = Math.min(contentStart + 50, match.length() - 1);
            log(match.substring(m.start(), min));
            var t = tryExtractor.apply(match.substring(m.start(), min).replaceAll("(?m)^\\s*//.*\\s*$", ""));
            log("Extract successful");
            log(String.format("try ( %s ) { %s }%n", t, c));
            var name =  m.group(NAME_GROUP);
            var np = Pattern.compile("(\\s+|(?<=[(+,!=]))" + name + "(\\s+|(?=[=.),]))");
            var nn = "generatedVariable" + new Random().nextInt(Integer.MAX_VALUE);
            t = np.matcher(t).replaceFirst("$1" + nn + "$2");
            var rest = match.substring(contentStart + c.length());
            c = np.matcher(c).replaceAll("$1" + nn + "$2");
            return String.format("try ( %s ) { %s } %s", t, c, rest);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException failed) {
            failed.printStackTrace();
            throw new IllegalStateException("Failed to parse a statement -> skipping");
        }
    }

    static String extractPSContent(String match) {
        return extractContent(psNamePattern, match, StatementCloser::extractPSTryStatement);
    }

    static String extractRSContent(String match) {
        return extractContent(rsNamePattern, match, StatementCloser::extractRSTryStatement);
    }

    public static void main(String[] args) throws Exception {
        STORE.PATH = args[0];
        var count = new AtomicInteger(0);
        read().parallelStream().forEach(f -> {
            try {
                var content = new AtomicReference<>(Files.readString(f)
                        //.replaceAll("(?m)^\\s*//.*\\s*$", "")
                );
                var original = content.get();
                if (
                        usingStatementExecute1.matcher(original).results().count() != 0
                                || usingStatementExecute2.matcher(original).results().count() != 0
                ) {
                    warn(f.getFileName());
                    warn("Detected possibly non-closed resources at: " + f.getFileName());
                    done();
                }
                var rsp = rsNamePattern;
                var psp = psNamePattern;
                int rsCnt = 0;
                int psCnt = 0;
                var rspRes = rsp.matcher(content.get()).results().collect(Collectors.toList());
                var pspRes = psp.matcher(content.get()).results().collect(Collectors.toList());
                if (!rspRes.isEmpty() || !pspRes.isEmpty()) {
                    warn(f.getFileName());
                    log(f.getFileName());
                    log("Starting ResultSets");
                    if (!rspRes.isEmpty()) {
                        int i = 1;
                        int skip = 0;
                        for (MatchResult ignored : rspRes) {
                            try {
                                var rm = rsp.matcher(content.get()).results().skip(skip).findFirst().orElseThrow();
                                content.set(content.get().substring(0, rm.start()) + extractRSContent(content.get().substring(rm.start())));
                                rsCnt++;
                            } catch (IllegalStateException e) {
                                warn(e.getMessage());
                                skip++;
                            } catch (RuntimeException e) {
                                warn("Failed ResultSet: " + i);
                                warn("Match size:");
                                warn(rspRes.size());
                                warn("Found:");
                                warn(content.get().substring(
                                        Math.max(ignored.start() - 50, 0),
                                        Math.min(content.get().length() - 1, ignored.end() + 50)
                                ));
                                warn("Original:");
                                warn(original.substring(
                                        Math.max(ignored.start() - 50, 0),
                                        Math.min(content.get().length() - 1, ignored.end() + 50)
                                ));
                                throw e;
                            }
                            i++;
                        }
                    }
                    log("Starting PreparedStatements");
                    log("Beginning: " + pspRes.size() + "\nNow: " + psNamePattern.matcher(content.get()).results().count());
                    if (!pspRes.isEmpty()) {
                        int i = 1;
                        int skip = 0;
                        for (MatchResult ignored : pspRes) {
                            try {
                                var rm = psp.matcher(content.get()).results().skip(skip).findFirst().orElseThrow();
                                content.set(content.get().substring(0, rm.start()) + extractPSContent(content.get().substring(rm.start())));
                                psCnt++;
                            } catch (IllegalStateException e) {
                                warn(e.getMessage());
                                skip++;
                            } catch (RuntimeException e) {
                                warn("Failed PreparedStatement: " + i);
                                warn("Match size:");
                                warn(pspRes.size());
                                warn("Found:");
                                warn(content.get().substring(
                                        Math.max(ignored.start() - 50, 0),
                                        Math.min(content.get().length() - 1, ignored.end() + 50)
                                ));
                                warn("Original:");
                                warn(original.substring(
                                        Math.max(ignored.start() - 50, 0),
                                        Math.min(content.get().length() - 1, ignored.end() + 50)
                                ));
                                throw e;
                            }
                            i++;
                        }
                    }
                    if (rsCnt > 0 || psCnt > 0) {
                        info(f.getFileName());
                        info("Found possible issues:");
                        info("ResultSet - " + rspRes.size());
                        info("PreparedStatement - " + pspRes.size());
                        info("Replaced: ");
                        info("PreparedStatement - " + psCnt);
                        info("ResultSet - " + rsCnt);
                    }
                }
                var statementFix = Pattern.compile("(?m)^\\s*(\\btry\\s*\\(\\s*\\w+\\s*\\w+\\s*=)(\\s*[\\w.()]*\\.createStatement\\(\\)\\s*)(\\.[^{]*?\\{)");
                var fixCnt = statementFix.matcher(content.get()).results().count();
                if (fixCnt != 0) {
                    warn("Loose Statements -> Trying to fix");
                    try {
                        for (long i = 0; i < fixCnt; i++) {
                            var iterText = content.get();
                            var iterMatch = statementFix.matcher(content.get())
                                    .results()
                                    .findFirst()
                                    .orElseThrow();
                            var start = iterText.substring(0, iterMatch.start());
                            var name = "stmt_generated_" + new Random().nextInt(Integer.MAX_VALUE);
                            var stmt = iterMatch.group(2);
                            var toProcess = iterText.substring(iterMatch.end());
                            var c = extractContent(toProcess);
                            content.set(
                                    start
                                            + "try ( var "
                                            + name
                                            + " = "
                                            + stmt
                                            + ") { "
                                            + iterMatch.group(1)
                                            + name
                                            + iterMatch.group(3)
                                            + c
                                            + "}" + toProcess.substring(c.length())
                            );
                        }
                        warn("Success");
                    } catch (RuntimeException e) {
                        warn("Failed: ");
                        e.printStackTrace(outputErr.get());
                    }
                }
                if (rsCnt != 0 || psCnt != 0 || fixCnt != 0) {
                    Files.write(f, content.get().getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
                    count.incrementAndGet();
                }
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(210);
            } catch (Throwable e) {
                e.printStackTrace();
                System.exit(200);
            } finally {
                done();
            }
        });
        info("Replaced" + count);
    }
}
