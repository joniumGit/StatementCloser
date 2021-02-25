import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class GeneratedVariableAnalyzer {

    static final class Pair<K, V> {
        private final K a;
        private final V b;

        public Pair(K a, V b) {
            this.a = a;
            this.b = b;
        }

        public K getA() {
            return a;
        }

        public V getB() {
            return b;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Pair) {
                return Objects.equals(a, ((Pair<?, ?>) o).a)
                        && Objects.equals(b, ((Pair<?, ?>) o).b);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(a, b);
        }

        @Override
        public String toString() {
            return "Pair={"
                    + "\"a\"="
                    + a
                    + "\"b\"="
                    + b
                    + "}";
        }
    }

    public static void main(String[] args) throws Exception {
        var startPath = args[0];
        var filePattern = Pattern.compile(".*\\.java");
        List<Path> files = Files.list(Paths.get(startPath)).collect(Collectors.toList());
        while (files.stream().anyMatch(Files::isDirectory)) {
            files = files.parallelStream().flatMap(path -> {
                if (Files.isDirectory(path)) {
                    try {
                        return Files.list(path);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    return filePattern.matcher(path.getFileName().toString()).matches()
                            ? Stream.of(path)
                            : Stream.empty();
                }
                return Stream.empty();
            }).collect(Collectors.toList());
        }
        var collect = files.parallelStream().map(f -> {
            try {
                var content = Files.readString(f);
                var def = Pattern.compile("(?m)\\s+(generatedVariable\\d+)\\s+");
                var broken = def.matcher(content).results().map(rs -> {
                    var name = rs.group(1);
                    if (name == null || name.trim().isBlank()) return null;
                    var exec = Pattern.compile("(?m)\\b" + name + "\\.exe.*");
                    var next = Pattern.compile("(?m)\\b" + name + "\\.next.*");
                    return exec.matcher(content).results().count() == 0 && next.matcher(content).results().count() == 0
                            ? name
                            : null;
                }).filter(Objects::nonNull).collect(Collectors.toList());
                return !broken.isEmpty()
                        ? new Pair<>(f.toString(), broken)
                        : null;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
        System.out.println(collect.size());
        collect.forEach(p -> {
            try {
                System.out.println(p.getA());
                Thread.sleep(10);
                p.getB().forEach(System.err::println);
                Thread.sleep(10);
                System.out.println();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }
}
