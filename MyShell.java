import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.nio.file.attribute.FileTime;

/**
 * Minimal-but-complete Java shell for Deliverable 1.
 * Features:
 * - Built-ins: cd, pwd, exit, echo, clear, ls, cat, mkdir, rmdir, rm, touch,
 * kill, jobs, fg, bg
 * - External commands (foreground and background using &)
 * - Job table with job ids, pid, status, command line, per-job log file for
 * background jobs
 * - Unix-only STOP/CONT support for bg/fg on STOPPED jobs
 *
 * Tested with Java 11+. Platform notes:
 * - Unix (Linux/macOS): bg/fg can try SIGCONT via `kill -CONT <pid>`.
 * - Windows: bg/fg can only wait on RUNNING jobs; STOPPED not supported.
 */
public class MyShell {

    // ==== Types and State ====
    enum JobStatus {
        RUNNING, STOPPED, DONE
    }

    static final class Job {
        final int jobId;
        final long pid;
        final Process process;
        final String commandLine;
        volatile JobStatus status;
        final Instant startTime;
        final Path logFile; // for background output (may be null for foreground)

        Job(int jobId, Process process, String commandLine, JobStatus status, Path logFile) {
            this.jobId = jobId;
            this.process = process;
            this.pid = safePid(process);
            this.commandLine = commandLine;
            this.status = status;
            this.startTime = Instant.now();
            this.logFile = logFile;
        }

        private static long safePid(Process p) {
            try {
                return p.pid(); // Java 9+
            } catch (Throwable t) {
                return -1L;
            }
        }
    }

    static final class JobTable {
        private final Map<Integer, Job> byId = new ConcurrentHashMap<>();
        private final Map<Long, Integer> idByPid = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger(1);

        int add(Job j) {
            byId.put(j.jobId, j);
            if (j.pid > 0)
                idByPid.put(j.pid, j.jobId);
            return j.jobId;
        }

        int newJobId() {
            return nextId.getAndIncrement();
        }

        Job getById(int id) {
            return byId.get(id);
        }

        Job getByPid(long pid) {
            Integer id = idByPid.get(pid);
            return id == null ? null : byId.get(id);
        }

        Collection<Job> all() {
            return byId.values();
        }

        void remove(int id) {
            Job j = byId.remove(id);
            if (j != null && j.pid > 0)
                idByPid.remove(j.pid);
        }

        void cleanupDone() {
            // keep DONE jobs so they show up once in `jobs`; remove older DONE if desired
        }
    }

    private Path cwd; // current working directory (shell state)
    private final JobTable jobs; // background jobs
    private final boolean isUnix; // simple OS check

    public MyShell() {
        this.cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        this.jobs = new JobTable();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        this.isUnix = !(os.contains("win"));
    }

    // ==== Entry point ====
    public static void main(String[] args) throws Exception {
        MyShell shell = new MyShell();
        shell.repl();
    }

    // ==== REPL ====
    public void repl() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print(prompt());
                String line = br.readLine();
                if (line == null)
                    break; // EOF (Ctrl-D)
                line = line.trim();
                if (line.isEmpty())
                    continue;

                boolean background = false;
                if (line.endsWith("&")) {
                    background = true;
                    line = line.substring(0, line.length() - 1).trim();
                }

                List<String> tokens = tokenize(line);
                if (tokens.isEmpty())
                    continue;
                String cmd = tokens.get(0);

                try {
                    if (handleBuiltin(cmd, tokens, background)) {
                        continue; // handled in-process
                    }
                    // external command
                    runExternal(tokens, line, background);
                } catch (ShellExit e) {
                    System.out.println("bye");
                    return;
                } catch (Exception ex) {
                    System.out.println("error: " + ex.getMessage());
                }
            }
        } catch (IOException ioe) {
            System.out.println("fatal: " + ioe.getMessage());
        }
    }

    private String prompt() {
        return cwd + " $ ";
    }

    // ==== Parsing ====
    // Very simple tokenizer supporting "double quotes" and 'single quotes'
    private static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0)
            out.add(cur.toString());
        return out;
    }

    // ==== Built-ins ====
    private static final class ShellExit extends RuntimeException {
    }

    private boolean handleBuiltin(String cmd, List<String> tokens, boolean background) throws IOException {
        switch (cmd) {
            case "cd":
                return bi_cd(tokens);
            case "pwd":
                return bi_pwd(tokens);
            case "exit":
                return bi_exit(tokens);
            case "echo":
                return bi_echo(tokens);
            case "clear":
                return bi_clear(tokens);
            case "ls":
                return bi_ls(tokens);
            case "cat":
                return bi_cat(tokens);
            case "mkdir":
                return bi_mkdir(tokens);
            case "rmdir":
                return bi_rmdir(tokens);
            case "rm":
                return bi_rm(tokens);
            case "touch":
                return bi_touch(tokens);
            case "kill":
                return bi_kill(tokens);
            case "jobs":
                return bi_jobs(tokens);
            case "fg":
                return bi_fg(tokens);
            case "bg":
                return bi_bg(tokens);
            case "rr":
                return bi_rr(tokens);
            case "priority":
                return bi_priority(tokens);
            default:
                return false;
        }
    }

     private boolean bi_cd(List<String> t) {
    if (t.size() == 1) {
        cwd = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        return true;
    }
    // Join tokens after 'cd' into a single path (handles spaces in folder names)
    String targetPath = String.join(" ", t.subList(1, t.size()));
    Path p = cwd.resolve(targetPath).normalize();
    if (Files.isDirectory(p)) {
        cwd = p;
    } else {
        System.out.println("cd: no such directory: " + targetPath);
    }
    return true;
}
    private boolean bi_pwd(List<String> t) {
        System.out.println(cwd);
        return true;
    }

    private boolean bi_exit(List<String> t) {
        throw new ShellExit();
    }

    private boolean bi_echo(List<String> t) {
        if (t.size() == 1) {
            System.out.println();
        } else {
            System.out.println(String.join(" ", t.subList(1, t.size())));
        }
        return true;
    }

    private boolean bi_clear(List<String> t) {
        // ANSI clear
        System.out.print("\033[H\033[2J");
        System.out.flush();
        return true;
    }

    private boolean bi_ls(List<String> t) throws IOException {
        Path dir = cwd;
        if (t.size() > 1)
            dir = cwd.resolve(t.get(1)).normalize();
        if (!Files.exists(dir)) {
            System.out.println("ls: no such file or directory: " + dir);
            return true;
        }
        if (Files.isRegularFile(dir)) {
            System.out.println(dir.getFileName());
            return true;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            List<String> names = new ArrayList<>();
            for (Path p : ds)
                names.add(p.getFileName().toString());
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            for (String n : names)
                System.out.println(n);
        }
        return true;
    }

    private boolean bi_cat(List<String> t) throws IOException {
        if (t.size() < 2) {
            System.out.println("usage: cat <filename>");
            return true;
        }
        Path f = cwd.resolve(t.get(1)).normalize();
        if (!Files.exists(f) || !Files.isRegularFile(f)) {
            System.out.println("cat: no such file: " + t.get(1));
            return true;
        }
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null)
                System.out.println(line);
        }
        return true;
    }

    private boolean bi_mkdir(List<String> t) throws IOException {
        if (t.size() < 2) {
            System.out.println("usage: mkdir <dir>");
            return true;
        }
        Path d = cwd.resolve(t.get(1)).normalize();
        try {
            Files.createDirectories(d);
        } catch (FileAlreadyExistsException fae) {
            System.out.println("mkdir: already exists: " + t.get(1));
        }
        return true;
    }

    private boolean bi_rmdir(List<String> t) throws IOException {
        if (t.size() < 2) {
            System.out.println("usage: rmdir <dir>");
            return true;
        }
        Path d = cwd.resolve(t.get(1)).normalize();
        if (!Files.isDirectory(d)) {
            System.out.println("rmdir: not a directory: " + t.get(1));
            return true;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            if (ds.iterator().hasNext()) {
                System.out.println("rmdir: directory not empty: " + t.get(1));
                return true;
            }
        }
        Files.delete(d);
        return true;
    }

    private boolean bi_rm(List<String> t) throws IOException {
        if (t.size() < 2) {
            System.out.println("usage: rm <filename>");
            return true;
        }
        Path f = cwd.resolve(t.get(1)).normalize();
        try {
            Files.delete(f);
        } catch (NoSuchFileException nsf) {
            System.out.println("rm: no such file: " + t.get(1));
        } catch (DirectoryNotEmptyException dne) {
            System.out.println("rm: is a directory (use rmdir): " + t.get(1));
        }
        return true;
    }

    private boolean bi_touch(List<String> t) throws IOException {
        if (t.size() < 2) {
            System.out.println("usage: touch <filename>");
            return true;
        }
        Path f = cwd.resolve(t.get(1)).normalize();
        if (Files.exists(f)) {
            Files.setLastModifiedTime(f, FileTime.from(Instant.now()));
        } else {
            Files.createFile(f);
        }
        return true;
    }

    private boolean bi_kill(List<String> t) {
        if (t.size() < 2) {
            System.out.println("usage: kill <pid>");
            return true;
        }
        long pid;
        try {
            pid = Long.parseLong(t.get(1));
        } catch (NumberFormatException nfe) {
            System.out.println("kill: pid must be a number");
            return true;
        }

        Job j = jobs.getByPid(pid);
        boolean terminated = false;

        if (j != null) {
            try {
                // Ask nicely first
                j.process.destroy(); // returns void
                terminated = j.process.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS);

                // If still alive, force kill
                if (!terminated && j.process.isAlive()) {
                    j.process.destroyForcibly(); // returns Process
                    terminated = j.process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                }

                if (terminated) {
                    j.status = JobStatus.DONE;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } else if (isUnix) {
            // Fallback for unknown pids on Unix
            try {
                Process p = new ProcessBuilder("kill", "-TERM", Long.toString(pid)).start();
                p.waitFor();
                terminated = (p.exitValue() == 0);
            } catch (Exception ignored) {
            }
        }

        if (!terminated) {
            System.out.println("kill: could not terminate pid " + pid);
        }
        return true;
    }

    private boolean bi_jobs(List<String> t) {
        List<Job> list = new ArrayList<>(jobs.all());
        list.sort(Comparator.comparingInt(j -> j.jobId));
        for (Job j : list) {
            System.out.printf("[%d] %d %s  %s%n", j.jobId, j.pid, j.status, j.commandLine);
        }
        return true;
    }

    private boolean bi_fg(List<String> t) {
        if (t.size() < 2) {
            System.out.println("usage: fg <job_id>");
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(t.get(1));
        } catch (NumberFormatException nfe) {
            System.out.println("fg: job_id must be a number");
            return true;
        }
        Job j = jobs.getById(id);
        if (j == null) {
            System.out.println("fg: no such job " + id);
            return true;
        }
        // If STOPPED and on Unix, try to continue
        if (j.status == JobStatus.STOPPED && isUnix && j.pid > 0) {
            try {
                new ProcessBuilder("kill", "-CONT", Long.toString(j.pid)).inheritIO().start().waitFor();
                j.status = JobStatus.RUNNING;
            } catch (Exception e) {
                System.out.println("fg: could not continue job " + id + " (needs Unix)");
                return true;
            }
        }
        // Wait in foreground
        try {
            int code = j.process.waitFor();
            j.status = JobStatus.DONE;
            System.out.println("exit code: " + code);
        } catch (InterruptedException e) {
            System.out.println("fg: interrupted");
        }
        return true;
    }

    private boolean bi_bg(List<String> t) {
        if (t.size() < 2) {
            System.out.println("usage: bg <job_id>");
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(t.get(1));
        } catch (NumberFormatException nfe) {
            System.out.println("bg: job_id must be a number");
            return true;
        }
        Job j = jobs.getById(id);
        if (j == null) {
            System.out.println("bg: no such job " + id);
            return true;
        }
        if (j.status != JobStatus.STOPPED) {
            System.out.println("bg: job " + id + " is not stopped");
            return true;
        }
        if (!isUnix || j.pid <= 0) {
            System.out.println("bg: resume requires Unix STOP/CONT support");
            return true;
        }
        try {
            new ProcessBuilder("kill", "-CONT", Long.toString(j.pid)).inheritIO().start().waitFor();
            j.status = JobStatus.RUNNING;
            System.out.printf("[%d] %d %s  %s%n", j.jobId, j.pid, j.status, j.commandLine);
        } catch (Exception e) {
            System.out.println("bg: failed to resume job " + id);
        }
        return true;
    }

    private boolean bi_rr(List<String> t) {
        if (t.size() < 2) {
            System.out.println("usage: rr <quantum_ticks>");
            System.out.println("example: rr 2   (each tick = 100 ms)");
            return true;
        }
        int quantum = parsePositiveInt(t.get(1), 1);
        List<SimProcess> procs = demoProcesses(); // you can swap to your own set
        Scheduler.runRoundRobin(procs, quantum, 100); // tick = 100 ms
        return true;
    }

    private boolean bi_priority(List<String> t) {
    int tickMs = (t.size() >= 2) ? parsePositiveInt(t.get(1), 100) : 100;
    List<SimProcess> procs = demoProcesses();
    Scheduler.runPreemptivePriority(procs, tickMs);
    return true;
    }

    private static int parsePositiveInt(String s, int fallback) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // simple demo set; change as you like for screenshots
    private List<SimProcess> demoProcesses() {
        List<SimProcess> list = new ArrayList<>();
        // pid, burst, priority (1=highest), arrival
        list.add(new SimProcess(1, 5, 2, 0)); // needs 5 ticks
        list.add(new SimProcess(2, 3, 1, 0)); // higher priority
        list.add(new SimProcess(3, 8, 3, 2)); // arrives at time 2
        return list;
    }

    // ==== External commands ====
    private void runExternal(List<String> tokens, String originalLine, boolean background)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(tokens);
        pb.directory(cwd.toFile());

        if (background) {
            int jobId = jobs.newJobId();
            Path logsDir = cwd.resolve(".myshell-logs");
            Files.createDirectories(logsDir);
            Path logFile = logsDir.resolve("job-" + jobId + ".log");
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Process p = pb.start();
            Job j = new Job(jobId, p, originalLine + " &", JobStatus.RUNNING, logFile);
            jobs.add(j);

            // Attach watcher to mark DONE and print a completion notice
            p.onExit().thenRun(() -> {
                j.status = JobStatus.DONE;
                System.out.printf("%n[%d] %d DONE  %s (see %s)%n", j.jobId, j.pid, j.commandLine, j.logFile);
                System.out.print(prompt());
                System.out.flush();
            });

            System.out.printf("[%d] %d RUNNING  %s (output -> %s)%n", jobId, j.pid, j.commandLine, logFile);
        } else {
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            System.out.println("exit code: " + code);
        }
    }

    // ==== Deliverable 2: Scheduling Simulation ====
    static final class SimProcess {
        final int pid;
        final int burstTime; // total CPU time required (in ticks)
        int remainingTime; // decremented as it "runs"
        final int priority; // 1 = highest priority
        final int arrivalTime; // when it becomes ready (in ticks)

        int startTime = -1; // first time scheduled
        int completionTime = -1; // when finished

        SimProcess(int pid, int burstTime, int priority, int arrivalTime) {
            this.pid = pid;
            this.burstTime = burstTime;
            this.remainingTime = burstTime;
            this.priority = priority;
            this.arrivalTime = arrivalTime;
        }

        SimProcess copy() { // to reuse the same set across different algorithms
            return new SimProcess(pid, burstTime, priority, arrivalTime);
        }
    }

    static final class Scheduler {

        // Round Robin with fixed quantum (in ticks). tickMillis controls wall-clock
        // speed.
        static void runRoundRobin(List<SimProcess> original, int quantum, int tickMillis) {
            System.out.println("=== Round Robin (quantum=" + quantum + " ticks, 1 tick=" + tickMillis + " ms) ===");
            // copy so we don't mutate caller state
            List<SimProcess> processes = original.stream().map(SimProcess::copy).toList();

            // ready queue considering arrivals over time
            Queue<SimProcess> ready = new ArrayDeque<>();
            int time = 0;
            int finished = 0;
            int n = processes.size();

            // to simplify arrivals, keep a set of not-yet-arrived
            List<SimProcess> notArrived = new ArrayList<>(processes);

            while (finished < n) {
                // move newly arrived to ready
                for (Iterator<SimProcess> it = notArrived.iterator(); it.hasNext();) {
                    SimProcess p = it.next();
                    if (p.arrivalTime <= time) {
                        ready.offer(p);
                        it.remove();
                    }
                }

                if (ready.isEmpty()) {
                    // idle tick
                    System.out.println("Time " + time + ": CPU idle");
                    sleep(tickMillis);
                    time += 1;
                    continue;
                }

                SimProcess p = ready.poll();
                if (p.startTime == -1)
                    p.startTime = time;

                int slice = Math.min(quantum, p.remainingTime);
                System.out.println("Time " + time + ": P" + p.pid + " runs for " + slice + " tick(s)");

                // run per tick so arrivals can happen in between
                for (int i = 0; i < slice; i++) {
                    sleep(tickMillis);
                    time += 1;
                    p.remainingTime -= 1;

                    // bring in any arrivals at this exact time
                    for (Iterator<SimProcess> it = notArrived.iterator(); it.hasNext();) {
                        SimProcess a = it.next();
                        if (a.arrivalTime <= time) {
                            ready.offer(a);
                            it.remove();
                        }
                    }

                    if (p.remainingTime == 0)
                        break;
                }

                if (p.remainingTime == 0) {
                    p.completionTime = time;
                    finished += 1;
                    System.out.println("P" + p.pid + " completed at t=" + time);
                } else {
                    // quantum expired: requeue
                    ready.offer(p);
                }
            }

            printMetrics(processes);
        }

        // Preemptive Priority (lower number = higher priority). tickMillis controls
        // wall-clock speed.
        static void runPreemptivePriority(List<SimProcess> original, int tickMillis) {
            System.out.println("=== Preemptive Priority (1 = highest, tick=" + tickMillis + " ms) ===");
            List<SimProcess> processes = original.stream().map(SimProcess::copy).toList();
            int n = processes.size();
            int finished = 0;
            int time = 0;

            // not yet arrived list
            List<SimProcess> notArrived = new ArrayList<>(processes);

            // ready queue by priority, FCFS tie-break using arrival then pid
            PriorityQueue<SimProcess> ready = new PriorityQueue<>(
                    Comparator.comparingInt((SimProcess p) -> p.priority)
                            .thenComparingInt(p -> p.arrivalTime)
                            .thenComparingInt(p -> p.pid));

            SimProcess current = null;

            while (finished < n) {
                // arrivals at current time
                for (Iterator<SimProcess> it = notArrived.iterator(); it.hasNext();) {
                    SimProcess p = it.next();
                    if (p.arrivalTime <= time) {
                        ready.offer(p);
                        it.remove();
                    }
                }

                // choose process to run (preempt if necessary)
                if (current == null && !ready.isEmpty()) {
                    current = ready.poll();
                    if (current.startTime == -1)
                        current.startTime = time;
                    System.out.println("Time " + time + ": PICK P" + current.pid + " (prio " + current.priority + ")");
                } else if (current != null && !ready.isEmpty()) {
                    SimProcess top = ready.peek();
                    // if a higher priority process is ready, preempt
                    if (top.priority < current.priority) {
                        System.out.println("Time " + time + ": PREEMPT P" + current.pid + " -> P" + top.pid);
                        ready.offer(current);
                        current = ready.poll();
                        if (current.startTime == -1)
                            current.startTime = time;
                    }
                }

                if (current == null) {
                    // CPU idle
                    System.out.println("Time " + time + ": CPU idle");
                    sleep(tickMillis);
                    time += 1;
                    continue;
                }

                // run current for one tick
                System.out.println("Time " + time + ": P" + current.pid + " runs 1 tick");
                sleep(tickMillis);
                time += 1;
                current.remainingTime -= 1;

                if (current.remainingTime == 0) {
                    current.completionTime = time;
                    System.out.println("P" + current.pid + " completed at t=" + time);
                    finished += 1;
                    current = null; // pick new next loop
                }
            }

            printMetrics(processes);
        }

        private static void printMetrics(List<SimProcess> processes) {
            double totalWT = 0, totalTAT = 0, totalRT = 0;
            System.out.println("\nPID | Burst | Prio | Arrival | Start | Complete | Waiting | Turnaround | Response");
            for (SimProcess p : processes) {
                int tat = p.completionTime - p.arrivalTime;
                int wt = tat - p.burstTime;
                int rt = p.startTime - p.arrivalTime;
                totalWT += wt;
                totalTAT += tat;
                totalRT += rt;
                System.out.printf("P%-3d %6d %6d %8d %7d %9d %9d %11d %9d%n",
                        p.pid, p.burstTime, p.priority, p.arrivalTime,
                        p.startTime, p.completionTime, wt, tat, rt);
            }
            int n = processes.size();
            System.out.printf("\nAverages -> Waiting: %.2f  Turnaround: %.2f  Response: %.2f%n",
                    totalWT / n, totalTAT / n, totalRT / n);
        }

        private static void sleep(int ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
            }
        }
    }

}
