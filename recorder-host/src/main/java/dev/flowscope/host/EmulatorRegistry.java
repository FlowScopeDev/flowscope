package dev.flowscope.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The emulators FlowScope launched, kept in {@code ~/.flowscope/emulators.json} so a later
 * {@code emulator stop} can tell its own emulators from ones the user started elsewhere.
 * Format: {@code {"emulators":[{"avd":"Pixel_7","pid":1234}]}}, read by the strict {@link Json}
 * parser. Entries whose process has exited are pruned on every read.
 */
final class EmulatorRegistry {
    record Entry(String avd, long pid) {}

    private final Path file;

    EmulatorRegistry(Path file) {
        this.file = file;
    }

    static EmulatorRegistry defaultRegistry() {
        return new EmulatorRegistry(Path.of(System.getProperty("user.home"), ".flowscope", "emulators.json"));
    }

    /** Live entries only. A missing file is an empty registry; a malformed one is rejected. */
    List<Entry> read() throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        Json.rejectUnknownKeys(root, Set.of("emulators"), "emulators.json");
        Object raw = root.get("emulators");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> items)) {
            throw new IllegalArgumentException("emulators.json: emulators must be an array");
        }
        List<Entry> entries = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("emulators.json: each emulator must be an object");
            }
            @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) map;
            Json.rejectUnknownKeys(object, Set.of("avd", "pid"), "emulators.json emulators[]");
            String avd = Json.requireString(object, "avd", "emulators.json emulators[]");
            Long pid = Json.optionalInteger(object, "pid", "emulators.json emulators[]");
            if (pid == null) throw new IllegalArgumentException("emulators.json emulators[].pid is required");
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                entries.add(new Entry(avd, pid));
            }
        }
        return entries;
    }

    void add(Entry entry) throws IOException {
        List<Entry> entries = new ArrayList<>(read());
        entries.removeIf(existing -> existing.pid() == entry.pid());
        entries.add(entry);
        write(entries);
    }

    void remove(long pid) throws IOException {
        List<Entry> entries = new ArrayList<>(read());
        entries.removeIf(existing -> existing.pid() == pid);
        write(entries);
    }

    Entry find(String avd) throws IOException {
        return read().stream().filter(entry -> entry.avd().equals(avd)).findFirst().orElse(null);
    }

    private void write(List<Entry> entries) throws IOException {
        StringBuilder out = new StringBuilder("{\"emulators\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(',');
            out.append("{\"avd\":").append(Json.quote(entries.get(i).avd()))
                .append(",\"pid\":").append(entries.get(i).pid()).append('}');
        }
        out.append("]}").append(System.lineSeparator());
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }
}
