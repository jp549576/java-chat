import java.util.HashMap;
import java.util.Map;

/**
 * Tiny command-line parser: "--port 12345 --host 10.0.0.5 --scan".
 * Every option has a default, so "./server.sh" and "./client.sh" just work.
 */
final class Cli {

    private final Map<String, String> options = new HashMap<>();

    Cli(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument '" + args[i] + "' (options look like --port 12345)");
            }
            String key = args[i].substring(2);
            boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            options.put(key, hasValue ? args[++i] : "true");
        }
    }

    String get(String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }

    int getInt(String key, int defaultValue) {
        return options.containsKey(key) ? Integer.parseInt(options.get(key)) : defaultValue;
    }

    boolean has(String key) {
        return options.containsKey(key);
    }
}
