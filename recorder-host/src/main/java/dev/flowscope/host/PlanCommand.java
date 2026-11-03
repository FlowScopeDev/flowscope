package dev.flowscope.host;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** CLI adapter; loading/showing/validating a plan never asks ADB for devices. */
final class PlanCommand {
    private PlanCommand() {}
    static int run(List<String> args, AdbClient adb) throws Exception {
        if (args.isEmpty() || !Set.of("validate", "show", "run").contains(args.getFirst()))
            throw new IllegalArgumentException("plan requires validate, show, or run and a .runplan.json file");
        String action = args.getFirst();
        boolean run = action.equals("run");
        CliOptions options = CliOptions.parse(args.subList(1, args.size()),
            run ? Set.of("--max-parallel", "--output") : Set.of(),
            run ? Set.of("--keep-hierarchy") : Set.of(),
            run ? Set.of("--device", "--secrets-file") : Set.of());
        options.requireNoExtraPositionals(1, "plan " + action);
        if (options.positional(0) == null) throw new IllegalArgumentException("plan " + action + " requires a file");
        ExecutionPlan plan = ExecutionPlan.read(Path.of(options.positional(0)));
        if (action.equals("validate")) {
            System.out.printf("Valid: %s (%d projects, %d device queues, %d jobs)%n", plan.name(),
                plan.projects().size(), plan.queues().size(), plan.queues().stream().mapToInt(q -> q.jobs().size()).sum());
            return 0;
        }
        if (action.equals("show")) {
            System.out.println("Plan: " + plan.name());
            System.out.println("Parallel devices: " + plan.maxParallel());
            for (ExecutionPlan.Queue queue : plan.queues()) {
                System.out.printf("%s / %s · %s · %s%n", queue.project(), queue.device(),
                    plan.projects().get(queue.project()).root(), queue.continueOnFailure() ? "continue-on-failure" : "stop-on-failure");
                for (int i = 0; i < queue.jobs().size(); i++) System.out.printf("  %02d %s%n", i + 1, queue.jobs().get(i).reference());
            }
            return 0;
        }
        var bindings = plan.bindings(options.values("--device"));
        var secrets = new LinkedHashMap<String, Path>();
        ExecutionPlan.assignments(options.values("--secrets-file")).forEach((key, value) -> secrets.put(key, Path.of(value)));
        long parallel = options.positiveLong("--max-parallel", plan.maxParallel());
        if (parallel > Integer.MAX_VALUE) throw new IllegalArgumentException("--max-parallel is too large");
        var result = ExecutionPlanRunner.run(plan, bindings, secrets, options.path("--output", null),
            (int) parallel, options.flag("--keep-hierarchy"), adb);
        System.out.println("Report: " + result.report());
        return result.passed() ? 0 : 1;
    }
}
