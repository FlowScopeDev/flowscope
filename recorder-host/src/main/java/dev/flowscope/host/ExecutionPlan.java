package dev.flowscope.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned, portable assignments. Loading performs no device access or output writes. */
public record ExecutionPlan(String name, Map<String, FlowProject> projects,
                            List<Queue> queues, int maxParallel) {
    public ExecutionPlan {
        projects = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(projects));
        queues = List.copyOf(queues);
    }
    public record Job(String reference, Path file, FlowTest test, FlowSuite suite,
                      List<FlowTest> children) {
        public Job { children = List.copyOf(children); }
    }
    public record Queue(String project, String device, List<Job> jobs, boolean continueOnFailure) {
        public Queue { jobs = List.copyOf(jobs); }
    }

    public record Assignment(String project, String device, List<String> jobs, boolean continueOnFailure) {
        public Assignment { jobs = List.copyOf(jobs); }
    }

    /** Native desktop/CLI interchange. Validate in the destination directory before publishing. */
    public static void save(Path file, String name, Map<String, Path> projects,
                            List<Assignment> queues, int maxParallel) throws IOException {
        Path destination = file.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        var projectJson = new java.util.StringJoiner(",", "{", "}");
        for (var project : projects.entrySet()) projectJson.add(Json.quote(project.getKey()) + ":" +
            Json.quote(parent.relativize(project.getValue().toAbsolutePath().normalize()).toString().replace('\\', '/')));
        var queueJson = new java.util.StringJoiner(",\n", "[", "]");
        for (var queue : queues) {
            var jobs = new java.util.StringJoiner(",", "[", "]");
            queue.jobs().forEach(job -> jobs.add(Json.quote(job)));
            queueJson.add("{\"project\":" + Json.quote(queue.project()) + ",\"device\":" + Json.quote(queue.device())
                + ",\"continueOnFailure\":" + queue.continueOnFailure() + ",\"jobs\":" + jobs + "}");
        }
        String json = "{\n\"version\":1,\"name\":" + Json.quote(name) + ",\"projects\":" + projectJson
            + ",\"maxParallel\":" + maxParallel + ",\"queues\":" + queueJson + "\n}\n";
        Path staging = Files.createTempFile(parent, ".execution-plan-", ".tmp");
        try {
            Files.writeString(staging, json);
            read(staging);
            Files.move(staging, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging); }
    }

    public static ExecutionPlan read(Path file) throws IOException {
        Path base = file.toAbsolutePath().normalize().getParent();
        Map<String, Object> root = Json.parseObject(Files.readString(file));
        Json.rejectUnknownKeys(root, Set.of("version", "name", "projects", "queues", "maxParallel"), "plan");
        if (!Long.valueOf(1).equals(root.get("version"))) throw invalid("version must be 1");
        String name = text(root, "name", "plan");
        Map<String, Object> rawProjects = Json.optionalObject(root, "projects", "plan");
        if (rawProjects == null || rawProjects.isEmpty()) throw invalid("projects must be a nonempty object");
        var projects = new LinkedHashMap<String, FlowProject>();
        for (var entry : rawProjects.entrySet()) {
            identifier(entry.getKey());
            if (!(entry.getValue() instanceof String path) || path.isBlank()) throw invalid("project path must be a string");
            portablePath(path, true);
            projects.put(entry.getKey(), FlowProject.open(base.resolve(path)));
        }
        if (!(root.get("queues") instanceof List<?> rawQueues) || rawQueues.isEmpty())
            throw invalid("queues must be a nonempty array");
        var queues = new ArrayList<Queue>();
        var roles = new HashSet<String>();
        for (Object raw : rawQueues) {
            Map<String, Object> queue = object(raw);
            Json.rejectUnknownKeys(queue, Set.of("project", "device", "jobs", "continueOnFailure"), "queue");
            String projectId = text(queue, "project", "queue");
            FlowProject project = projects.get(projectId);
            if (project == null) throw invalid("unknown project: " + projectId);
            String device = text(queue, "device", "queue");
            identifier(device);
            if (!roles.add(device)) throw invalid("device role must have exactly one queue: " + device);
            if (!(queue.get("jobs") instanceof List<?> rawJobs) || rawJobs.isEmpty())
                throw invalid("jobs must be a nonempty array");
            var jobs = new ArrayList<Job>();
            for (Object job : rawJobs) {
                if (!(job instanceof String reference)) throw invalid("job must be a test or suite reference");
                portablePath(reference, false);
                boolean suiteJob = reference.endsWith(".suite");
                if (!suiteJob && !reference.endsWith(".flow")) throw invalid("jobs must end in .flow or .suite");
                Path resolved = suiteJob ? project.resolveSuiteArgument(reference) : project.resolveTestArgument(reference);
                inside(project, resolved);
                if (!suiteJob) {
                    jobs.add(new Job(reference, resolved, FlowTestParser.parse(resolved), null, List.of()));
                } else {
                    FlowSuite suite = FlowSuiteParser.parse(resolved);
                    FlowFiles.validate(resolved.getParent(), suite.hooks());
                    var children = new ArrayList<FlowTest>();
                    for (FlowSuite.Entry child : suite.entries()) {
                        portablePath(child.reference(), false);
                        Path childFile = project.resolveSuiteEntry(child.reference());
                        inside(project, childFile);
                        children.add(FlowTestParser.parse(childFile));
                    }
                    jobs.add(new Job(reference, resolved, null, suite, children));
                }
            }
            Boolean keepGoing = Json.optionalBoolean(queue, "continueOnFailure", "queue");
            queues.add(new Queue(projectId, device, jobs, Boolean.TRUE.equals(keepGoing)));
        }
        Long parallel = Json.optionalInteger(root, "maxParallel", "plan");
        if (parallel != null && (parallel < 1 || parallel > Integer.MAX_VALUE)) throw invalid("maxParallel must be a positive integer");
        return new ExecutionPlan(name, projects, queues, parallel == null ? queues.size() : parallel.intValue());
    }

    public Map<String, String> bindings(List<String> assignments) {
        Map<String, String> result = assignments(assignments);
        Set<String> roles = new HashSet<>();
        for (Queue queue : queues) roles.add(queue.device());
        if (!result.keySet().equals(roles)) throw invalid("bind every device role exactly once: " + String.join(", ", roles.stream().sorted().toList()));
        if (new HashSet<>(result.values()).size() != result.size()) throw invalid("different device roles must use different serials");
        return result;
    }

    static Map<String, String> assignments(List<String> values) {
        var result = new LinkedHashMap<String, String>();
        for (String value : values) {
            int split = value.indexOf('=');
            if (split < 1 || split == value.length() - 1) throw invalid("binding must use name=value");
            String key = value.substring(0, split), target = value.substring(split + 1);
            identifier(key);
            if (target.isBlank() || target.chars().anyMatch(Character::isISOControl)) throw invalid("binding value is empty or contains control characters");
            if (result.putIfAbsent(key, target) != null) throw invalid("duplicate binding: " + key);
        }
        return Map.copyOf(result);
    }

    private static void inside(FlowProject project, Path file) throws IOException {
        if (!file.toRealPath().startsWith(project.root().toRealPath())) throw invalid("job escapes its project through a symbolic link");
    }
    private static void portablePath(String path, boolean project) {
        if (path.isBlank() || path.contains("\\") || path.matches("^[A-Za-z]:.*") || Path.of(path).isAbsolute()
            || path.contains("${") || path.chars().anyMatch(Character::isISOControl)) throw invalid("paths must be literal relative paths using forward slashes");
        if (!project) for (Path part : Path.of(path)) if (part.toString().equals("..")) throw invalid("job reference cannot contain '..'");
    }
    private static void identifier(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw invalid("project and device names must be identifiers of at most 64 characters");
    }
    private static String text(Map<String, Object> object, String key, String where) {
        String text = Json.requireString(object, key, where);
        if (text.isBlank() || text.length() > 200 || text.contains("${") || text.chars().anyMatch(Character::isISOControl)) throw invalid(key + " must be nonempty literal text without control characters (max 200)");
        return text;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map<?, ?>)) throw invalid("queue must be an object");
        return (Map<String, Object>) raw;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Execution plan: " + message); }
}
