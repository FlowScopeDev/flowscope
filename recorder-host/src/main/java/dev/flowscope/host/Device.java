package dev.flowscope.host;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Device(String serial, String state, Map<String, String> attributes) {
    static List<Device> parse(String adbOutput) {
        List<Device> devices = new ArrayList<>();
        for (String rawLine : adbOutput.lines().toList()) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("List of devices")) continue;
            String[] fields = line.split("\\s+");
            if (fields.length < 2) continue;
            Map<String, String> attributes = new LinkedHashMap<>();
            for (int i = 2; i < fields.length; i++) {
                int separator = fields[i].indexOf(':');
                if (separator > 0) {
                    attributes.put(fields[i].substring(0, separator), fields[i].substring(separator + 1));
                }
            }
            devices.add(new Device(fields[0], fields[1], attributes));
        }
        return devices;
    }

    public String displayName() {
        return attributes.getOrDefault("model", attributes.getOrDefault("device", "unknown"));
    }
}
