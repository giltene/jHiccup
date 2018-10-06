/**
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 *
 * @author Gil Tene
 */

package org.jhiccup;

import org.HdrHistogram.*;

import java.io.*;
import java.security.CodeSource;
import java.util.*;
import java.text.SimpleDateFormat;
import java.lang.management.*;
import java.util.concurrent.TimeUnit;

/**
 * HiccupMeter is a platform pause measurement tool, it is meant to observe
 * the underlying platform (JVM, OS, HW, etc.) responsiveness while under
 * an unrelated application load, and establish a lower bound for the stalls
 * the application would experience. It can be run as a wrapper around
 * other applications so that measurements can be done without any changes
 * to application code.
 * <p>
 * The purpose of HiccupMeter is to aid application operators and testers
 * in characterizing the inherent "platform hiccups" (execution stalls)
 * that a Java platform will display when running under load. The hiccups
 * measured are NOT stalls caused by the application's code. They are stalls
 * caused by the platform (JVM, OS, HW, etc.) that would be visible to and
 * affect any application thread running on the platform at the time of the
 * stall. It is generally safe to assume that if HiccupMeter experiences and
 * records a certain level of measured platform hiccups, the application
 * running on the same JVM platform during that time had experienced
 * hiccup/stall effects that are at least as large as the measured level.
 * <p>
 * HiccupMeter's measurement works on the simple basis of measuring the time
 * it takes an effectively empty workload to perform work (while running
 * alongside whatever load the platform is carrying). Hiccup measurements are
 * performed by a thread that repeatedly sleeps for a given interval (-r for
 * resolutionMs, defaults to 1 msec), and logs the amount of time it took to
 * actually wake up each time in a  detailed internal hiccup histogram. The
 * assumption is that if the measuring thread experienced some delay in waking
 * up, any other thread in the system could/would have experienced a similar
 * delay, resulting in application stalls.
 * <p>
 * HiccupMeter collects both raw and corrected (weighted) histogram results.
 * When the reported time in a histogram exceeds the interval used between
 * measurements, the raw histogram data would reflect only the single reported
 * results, while the corrected histogram data will reflect an appropriate
 * number of additional results with linearly decreasing times (down to a time
 * that is lower than the measurement interval). While raw and corrected
 * histogram data are both tracked internally, it is the corrected numbers that
 * are logged and reported, as they will more accurately reflect the response
 * time that a random, uncoordinated request would have experienced.
 * <p>
 * HiccupMeter logs a single line with hiccup %'ile stats each reporting
 * interval (set with -i <reportingIntervalMs>, defaults to 5000 msec) to a
 * log file. The log file name can be optionally controlled with the -l <logfile>
 * flag, and will default (if no logfile name is supplied)to a name derived from
 * the process id and time (hiccup.yyMMdd.HHmm.pid). HiccupMeter will also produce
 * a detailed histogram log file under the <logfile>.histogram name. A new
 * histogram log file will be produced each interval, and will replace the
 * one generated in the previous interval.
 * <p>
 * HiccupMeter can be configured to delay the start of measurement
 * (using the -d <startDelayMs> flag, defaults to 0 msec). It can also be
 * configured to terminate measurement after a given length of time (using the
 * -t <runTimeMs> flag). If the -t flag is not used, HiccupMeter will continue
 * to run until the Class executed with via the -exec parameter (see below)
 * exits, or indefinitely (if no -exec is used).
 * <p>
 * HiccupMeter can be configured to launch a separate "control process" on
 * startup (using the -c <controlProcessLogFileName> flag, defaulting to
 * nothing). This option launches a separate, standalone instance of
 * HiccupMeter wrapping running an idle workload, such that a concurrent
 * control measurement is established on the same system, at the same time
 * as the main observed application load is run.
 * <p>
 * For convenience in testing, HiccupMeter is typically launched as a
 * javaagent. For example, if your program were normally executed as:
 * <p>
 * java UsefulProgram -a -b -c
 * <p>
 * This is how you would execute the same program so that HiccupMeter would
 * record hiccups while it is running:
 * <p>
 * java -javaagent:jHiccup.jar UsefulProgram -a -b -c
 * <p>
 * Common use example:
 * <p>
 * Measure internal jitter of JVM running MyProg, log into logFile and
 * logFile.hgrm, report in 5 seconds intervals (which is the default), start
 * measurements 60 seconds into the run, and run concurrent control process
 * that will record hiccups on an idle workload running at the same time,
 * logging those into c.logFile and c.logFile.hgrm:
 * <p>
 * java -javaagent:jHiccup.jar="-d 60000 -l testLog -c c.testlog" MyProg
 * <p>
 * Note: while HiccupMeter is typically executed as a javaagent, it can be
 * run as a standalone program, in which case it will execute for the
 * duration of time specified with the -t <runTimeMs> option, or if the
 * -terminateOnStdIn flag is used, it will terminate execution when standard
 * input is severed. This last option is useful for executing HiccupMeter as
 * a standalone control process launched from a javaagent HiccupMeter
 * instance.
 *
 * Note: HiccupMeter can be used to process data from an input file instead
 * of sampling it. When the [-f inputFileName] option is used, the input file
 * is expected to contain two white-space delimited values per line (in either
 * integer or real number format), representing a time stamp and a measured
 * latency, both in millisecond units. It's important to note that the default
 * "expected interval between samples" resolution in jHiccup and HiccupMeter
 * is 1 millisecond. When processing input files, it is imperative that the
 * appropriate value be supplied to the -r option, and that this value correctly
 * represent the expected interval between samples in the provided input file.
 * HiccupMeter will use this parameter to determine whether additional, artificial
 * values should be added to the histogram recording, between input samples that
 * are farther apart in time than the expected interval specified to the -r option.
 * This behavior corrects for "coordinated omission" situations (where long response
 * times lead to "skipped" requests that would have typically correlated with "bad"
 * response times). A "large" value (e.g. -r 100000) can easily be specified to
 * avoid any correction of this situation.
 *
 * Note: HiccupMeter makes systemic use of HdrHistogram to collected and report
 * on the statistical distribution of hiccups. HdrHistogram sources, documentation,
 * and a ready to use jar file can all be found on GitHub,
 * at http://giltene.github.com/HdrHistogram
 */


public class HiccupMeter extends Thread {

    private static final String versionString = "jHiccup version " + Version.version;

    static final String defaultHiccupLogFileName = "hiccup.%date.%pid.hlog";

    protected final PrintStream log;

    protected final HistogramLogWriter histogramLogWriter;

    protected final HiccupMeterConfiguration config;

    protected static class HiccupMeterConfiguration {
        public boolean terminateWithStdInput = false;
        public double resolutionMs = 1.0;
        public long runTimeMs = 0;
        public long reportingIntervalMs = 5000;
        public long startDelayMs = 0;
        public boolean startDelayMsExplicitlySpecified = false;

        public boolean verbose = false;
        public boolean allocateObjects = false;
        public String logFileName;
        public boolean logFileExplicitlySpecified = false;
        public String inputFileName = null;
        public boolean fillInZerosInInputFile = false;
        public boolean logFormatCsv = false;

        public boolean launchControlProcess = false;
        public long launchControlProcessHeapSizeMBFilter = 0;
        public String controlProcessLogFileName = null;
        public String controlProcessCommand = null;
        public boolean controlProcessJvmArgsExplicitlySpecified = false;
        public String controlProcessJvmArgs;

        public boolean attachToProcess = false;
        public String pidOfProcessToAttachTo = null;
        public String agentJarFileName = null;
        public String agentArgs = null;

        public boolean startTimeAtZero = false;

        public long lowestTrackableValue = 1000L * 20L; // default to ~20usec best-case resolution
        public long highestTrackableValue = 30 * 24 * 3600 * 1000L * 1000L * 1000L; // 1 Month
        public int numberOfSignificantValueDigits = 2;

        public boolean error = false;
        public String errorMessage = "";

        String fillInPidAndDate(String logFileName) {
            final String processName =
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            final String processID = processName.split("@")[0];
            final SimpleDateFormat formatter = new SimpleDateFormat("yyMMdd.HHmm");
            final String formattedDate = formatter.format(new Date());

            logFileName = logFileName.replaceAll("%pid", processID);
            logFileName = logFileName.replaceAll("%date", formattedDate);
            return logFileName;
        }

        public HiccupMeterConfiguration(final String[] args, String defaultLogFileName) {
            logFileName = defaultLogFileName;
            try {
                for (int i = 0; i < args.length; ++i) {
                    if (args[i].equals("-v")) {
                        verbose = true;
                    } else if (args[i].equals("-0")) {
                        startTimeAtZero = true;
                    } else if (args[i].equals("-a")) {
                        allocateObjects = true;
                    } else if (args[i].equals("-p")) {
                        attachToProcess = true;
                        pidOfProcessToAttachTo = args[++i];
                    } else if (args[i].equals("-j")) {
                        agentJarFileName = args[++i];
                    } else if (args[i].equals("-terminateWithStdInput")) {
                        terminateWithStdInput = true;
                    } else if (args[i].equals("-i")) {
                        reportingIntervalMs = Long.parseLong(args[++i]);
                    } else if (args[i].equals("-t")) {
                        runTimeMs = Long.parseLong(args[++i]);
                    } else if (args[i].equals("-d")) {
                        startDelayMs = Long.parseLong(args[++i]);
                        startDelayMsExplicitlySpecified = true;
                    } else if (args[i].equals("-r")) {
                        resolutionMs = Double.parseDouble(args[++i]);
                    } else if (args[i].equals("-s")) {
                        numberOfSignificantValueDigits = Integer.parseInt(args[++i]);
                    } else if (args[i].equals("-l")) {
                        logFileName = args[++i];
                        logFileExplicitlySpecified = true;
                    } else if (args[i].equals("-f")) {
                        inputFileName = args[++i];
                        lowestTrackableValue = 1L; // drop to ~1 nsec best-case resolution when processing files
                    } else if (args[i].equals("-fz")) {
                        fillInZerosInInputFile = true;
                    } else if (args[i].equals("-c")) {
                        launchControlProcess = true;
                    } else if (args[i].equals("-cfmb")) {
                        launchControlProcessHeapSizeMBFilter = Long.parseLong(args[++i]);;
                    } else if (args[i].equals("-x")) {
                        controlProcessJvmArgs = args[++i];
                        controlProcessJvmArgsExplicitlySpecified = true;
                    } else if (args[i].equals("-o")) {
                        logFormatCsv = true;
                    } else {
                        throw new Exception("Invalid args: " + args[i]);
                    }
                }

                logFileName = fillInPidAndDate(logFileName);

                if (attachToProcess) {
                    if (!startDelayMsExplicitlySpecified) {
                        startDelayMs = 0;
                    }

                    if (agentJarFileName == null) {
                        throw new Exception("Invalid args, missing agent jar file name, specify with -j option");
                    }
                    agentArgs = "-d " + startDelayMs +
                                " -i " + reportingIntervalMs +
                                ((startTimeAtZero) ? " -0" : "") +
                                " -s " + numberOfSignificantValueDigits +
                                " -r " + resolutionMs;

                    if (runTimeMs != 0) {
                        agentArgs += " -t " + runTimeMs;
                    }

                    if (logFileExplicitlySpecified) {
                        agentArgs += " -l " + logFileName;
                    }

                    if (launchControlProcess) {
                        agentArgs += " -c";
                    }

                    if (controlProcessJvmArgsExplicitlySpecified) {
                        agentArgs += " -x " + controlProcessJvmArgs;
                    }

                    if (verbose) {
                        agentArgs += " -v";
                    }

                    if (logFormatCsv) {
                        agentArgs += " -o";
                    }
                }

                if (launchControlProcess && (launchControlProcessHeapSizeMBFilter > 0)) {
                        MemoryMXBean mxbean = ManagementFactory.getMemoryMXBean();
                        MemoryUsage memoryUsage = mxbean.getHeapMemoryUsage();
                        long estimatedHeapMB = (memoryUsage.getMax() / (1024 * 1024));
                        if (estimatedHeapMB < launchControlProcessHeapSizeMBFilter) {
                            launchControlProcess = false;
                        }
                }

                if (launchControlProcess) {
                    File filePath = new File(logFileName);
                    String parentFileNamePart = filePath.getParent();

                    String childFileNamePart = filePath.getName();
                    // Derive control process log file name from logFileName:
                    File controlFilePath = new File(parentFileNamePart, childFileNamePart + ".c");
                    controlProcessLogFileName = controlFilePath.getPath();

                    // Compute path to agent's JAR file
                    CodeSource agentCodeSource = HiccupMeter.class.getProtectionDomain().getCodeSource();
                    String agentPath = new File(agentCodeSource.getLocation().toURI()).getPath();

                    // Derive controlProcessCommand from our java home, class name, and parsed
                    // options:
                    controlProcessCommand =
                            System.getProperty("java.home") +
                                    File.separator + "bin" + File.separator + "java" +
                                    (controlProcessJvmArgsExplicitlySpecified ? " " + controlProcessJvmArgs : "") +
                                    " -cp " + agentPath +
                                    " -Dorg.jhiccup.avoidRecursion=true" +
                                    " " + HiccupMeter.class.getCanonicalName() +
                                    " -l " + controlProcessLogFileName +
                                    " -i " + reportingIntervalMs +
                                    " -d " + startDelayMs +
                                    ((startTimeAtZero) ? " -0" : "") +
                                    ((logFormatCsv) ? " -o" : "") +
                                    " -s " + numberOfSignificantValueDigits +
                                    " -r " + resolutionMs +
                                    " -terminateWithStdInput";
                }
                if (resolutionMs < 0) {
                    System.err.println("resolutionMs must be positive.");
                    System.exit(1);
                }
            } catch (Exception e) {
                error = true;
                errorMessage = "Error: launched with the following args:\n";

                for (String arg : args) {
                    errorMessage += arg + " ";
                }
                errorMessage += "\nWhich was parsed as an error, indicated by the following exception:\n" + e;

                System.err.println(errorMessage);

                String validArgs =
                        "\"[-v] [-c] [-x controlProcessArgs] [-o] [-0] [-n] [-p pidOfProcessToAttachTo] [-j jHiccupJarFileName] " +
                        "[-i reportingIntervalMs] [-h] [-t runTimeMs] [-d startDelayMs] " +
                        "[-l logFileName] [-r resolutionMs] [-terminateWithStdInput] [-f inputFileName]\"\n";

                System.err.println("valid arguments = " + validArgs);

                System.err.println(
                " [-h]                        help\n" +
                " [-v]                        verbose\n" +
                " [-l logFileName]            Log hiccup information into logFileName and logFileName.hgrm\n" +
                "                             (will replace occurrences of %pid and %date with appropriate information)\n" +
                " [-o]                        Output log files in CSV format\n" +
                " [-c]                        Launch a control process in a separate JVM\n" +
                "                             logging hiccup data into logFileName.c and logFileName.c.hgrm\n" +
                " [-cfmb controlProcessArgs]  Control process filter heap size (in MB): only launch control proc if\n" +
                "                             this process's heap size is larger than the -cfmb parameter\n" +
                " [-x controlProcessArgs]     Pass additional args to the control process JVM\n" +
                " [-p pidOfProcessToAttachTo] Attach to the process with given pid and inject jHiccup as an agent\n" +
                " [-j jHiccupJarFileName]     File name for the jHiccup.jar file, and required with [-p] option above\n" +
                " [-d startDelayMs]           Delay the beginning of hiccup measurement by\n" +
                "                             startDelayMs milliseconds [default 0]\n" +
                " [-0]                        Start timestamps at 0 (as opposed to at JVM runtime at start point)\n" +
                " [-a]                        Allocate a throwaway object on every sample [default false]\n" +
                " [-i reportingIntervalMs]    Set reporting interval [default 5000]\n" +
                " [-r resolutionMs]           Set sampling resolution in milliseconds [default 1]\n" +
                " [-t runTimeMs]              Limit measurement time [default 0, for infinite]\n" +
                " [-terminateWithStdInput]    Take over standard input, and terminate process when\n" +
                "                             standard input is severed (useful for control\n" +
                "                             processes that wish to terminate when their launching\n" +
                "                             parent does).\n" +
                " [-f inputFileName]          Read timestamp and latency data from input file\n" +
                "                             instead of sampling it directly\n" +"" +
                " [-fz]                       (applies only in conjunction with -f) fill in blank time ranges" +
                "                             with zero values. Useful e.g. when processing GC-log derived input.\n" +
                " [-s numberOfSignificantValueDigits]\n");
            }
        }
    }

    public HiccupMeter(final String[] args, String defaultLogFileName) throws FileNotFoundException {
        this.setName("HiccupMeter");
        config = new HiccupMeterConfiguration(args, defaultLogFileName);
        log = new PrintStream(new FileOutputStream(config.logFileName), false);
        histogramLogWriter = new HistogramLogWriter(log);
        this.setDaemon(true);
    }

    public static class ExecProcess extends Thread {
        final String processName;
        final String command;
        final boolean verbose;
        final PrintStream log;

        public ExecProcess(final String command, final String processName,
                           final PrintStream log, final boolean verbose) {
            this.setDaemon(true);
            this.setName(processName + "ExecThread");
            this.command = command;
            this.processName = processName;
            this.log = log;
            this.verbose = verbose;
            this.start();
        }

        public void run() {
            try {
                if (verbose) {
                    log.println("# HiccupMeter Executing " + processName + " command: " + command);
                }
                final Process p = Runtime.getRuntime().exec(command);
                p.waitFor();
            } catch (Exception e) {
                System.err.println("HiccupMeter: " + processName + " terminated.");
            }
        }
    }

    class TerminateWithStdInputReader extends Thread {
        TerminateWithStdInputReader() {
            this.setDaemon(true);
            this.setName("terminateWithStdInputReader");
            this.start();
        }

        @Override
        public void run() {
            // Ensure exit when stdin is severed.
            try {
                while (System.in.read() >= 0) {
                }
                System.exit(1);
            } catch (Exception e) {
                System.exit(1);
            }

        }
    }

    public class HiccupRecorder extends Thread {
        public volatile boolean doRun;
        private final boolean allocateObjects;
        public volatile Long lastSleepTimeObj; // public volatile to make sure allocs are not optimized away...
        protected final SingleWriterRecorder recorder;

        public HiccupRecorder(final SingleWriterRecorder recorder, final boolean allocateObjects) {
            this.setDaemon(true);
            this.setName("HiccupRecorder");
            this.recorder = recorder;
            this.allocateObjects = allocateObjects;
            doRun = true;
        }

        public void terminate() {
            doRun = false;
        }

        public long getCurrentTimeMsecWithDelay(final long nextReportingTime) throws InterruptedException {
            final long now = System.currentTimeMillis();
            if (now < nextReportingTime)
                Thread.sleep(nextReportingTime - now);
            return now;
        }

        public void run() {
            final long resolutionNsec = (long)(config.resolutionMs * 1000L * 1000L);
            try {
                long shortestObservedDeltaTimeNsec = Long.MAX_VALUE;
                long timeBeforeMeasurement = Long.MAX_VALUE;
                while (doRun) {
                    if (config.resolutionMs != 0) {
                        TimeUnit.NANOSECONDS.sleep(resolutionNsec);
                        if (allocateObjects) {
                            // Allocate an object to make sure potential allocation stalls are measured.
                            lastSleepTimeObj = new Long(timeBeforeMeasurement);
                        }
                    }
                    final long timeAfterMeasurement = System.nanoTime();
                    final long deltaTimeNsec = timeAfterMeasurement - timeBeforeMeasurement;
                    timeBeforeMeasurement = timeAfterMeasurement;

                    if (deltaTimeNsec < 0) {
                        // On the very first iteration (which will not time the loop in it's entirety)
                        // the delta will be negative, and we'll skip recording.
                        continue;
                    }

                    if (deltaTimeNsec < shortestObservedDeltaTimeNsec) {
                        shortestObservedDeltaTimeNsec = deltaTimeNsec;
                    }

                    long hiccupTimeNsec = deltaTimeNsec - shortestObservedDeltaTimeNsec;

                    recorder.recordValueWithExpectedInterval(hiccupTimeNsec, resolutionNsec);
                }
            } catch (InterruptedException e) {
                if (config.verbose) {
                    log.println("# HiccupRecorder interrupted/terminating...");
                }
            }
        }
    }

    class InputRecorder extends HiccupRecorder {
        final Scanner scanner;
        long prevTimeMsec = 0;
        long inputLineTimeMsec = 0;
        long msecThatPrecedesInputLine = -1;
        double inputLineHiccupTimeMsec = -1;
        boolean reportedAfterTerminate = false;


        InputRecorder(final SingleWriterRecorder recorder, final String inputFileName) {
            super(recorder, false);
            Scanner newScanner = null;
            try {
                newScanner = new Scanner(new File(inputFileName));
            } catch (FileNotFoundException e) {
                System.err.println("HiccupMeter: Failed to open input file \"" + inputFileName + "\"");
                System.exit(-1);
            } finally {
                scanner = newScanner;
            }
        }

        long processInputLine(final Scanner scanner, final SingleWriterRecorder recorder) {
            if (scanner.hasNextLine()) {
                try {
                    inputLineTimeMsec = (long) scanner.nextDouble(); // Timestamp is expect to be in millis
                    inputLineHiccupTimeMsec = scanner.nextDouble(); // Latency is expected to be in millis
                    msecThatPrecedesInputLine = config.fillInZerosInInputFile ?
                            inputLineTimeMsec - (long)Math.ceil(inputLineHiccupTimeMsec) :
                            inputLineTimeMsec;
                    if (inputLineTimeMsec < prevTimeMsec) {
                        return -1; // Input time is going backwards. Can't have that. Terminate.
                    }
                    return inputLineTimeMsec;
                } catch (java.util.NoSuchElementException e) {
                    return -1;
                }
            }
            return -1;
        }

        @Override
        public long getCurrentTimeMsecWithDelay(final long nextReportingTime) throws InterruptedException {
            // The following loop will terminate either at the next reporting time, or when input is exhausted:
            do {
                if (nextReportingTime < msecThatPrecedesInputLine) {
                    // Nothing in the input before the nextReportingTime:

                    long numberOfTicksBeforeNextReportingTime =
                            (long) ((nextReportingTime - prevTimeMsec) / config.resolutionMs);
                    if (config.fillInZerosInInputFile && (numberOfTicksBeforeNextReportingTime > 0)) {
                        // fill in blank time between prevTimeMsec and nextReportingTime with zero values:
                        recorder.recordValueWithCount(0L, numberOfTicksBeforeNextReportingTime);
                    }
                    
                    // Indicate that we've processed input up to nextReportingTime:
                    prevTimeMsec = nextReportingTime;

                    return nextReportingTime;
                } else if (msecThatPrecedesInputLine >= prevTimeMsec) {
                    // Process previously read input:
                    long numberOfTicksBeforeInputHiccup =
                            (long) ((msecThatPrecedesInputLine - prevTimeMsec) / config.resolutionMs);
                    if (config.fillInZerosInInputFile && (numberOfTicksBeforeInputHiccup > 0)) {
                        // Fill in blank time between previously processed time and the hiccup with zero values:
                        recorder.recordValueWithCount(0L, numberOfTicksBeforeInputHiccup);
                    }

                    final long hiccupTimeNsec = (long) (inputLineHiccupTimeMsec * 1000000.0);
                    recorder.recordValueWithExpectedInterval(hiccupTimeNsec, (long) (config.resolutionMs * 1000000L));

                    // indicate that we've processed input up to the end of the previously read line:
                    prevTimeMsec = inputLineTimeMsec;
                }
                // Read next line:
            } while (processInputLine(scanner, recorder) >= 0);

            if (!reportedAfterTerminate) {
                // Fill last report with zeros if/as needed:
                long numberOfTicksBeforeNextReportingTime =
                        (long) ((nextReportingTime - prevTimeMsec) / config.resolutionMs);
                if (config.fillInZerosInInputFile && (numberOfTicksBeforeNextReportingTime > 0)) {
                    // fill in blank time between prevTimeMsec and nextReportingTime with zero values:
                    recorder.recordValueWithCount(0L, numberOfTicksBeforeNextReportingTime);
                }

                reportedAfterTerminate = true;
                return nextReportingTime;
            }
            // Input exhausted :
            return -1;
        }

        @Override
        public void run() {
            try {
                while (doRun) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                if (config.verbose) {
                    log.println("# HiccupRecorder interrupted/terminating...");
                }
            }
        }
    }

    public HiccupRecorder createHiccupRecorder(SingleWriterRecorder recorder) {
        return new HiccupRecorder(recorder, config.allocateObjects);
    }

    public String getVersionString() {
        return versionString;
    }

    @Override
    public void run() {
        final SingleWriterRecorder recorder =
                new SingleWriterRecorder(
                        config.lowestTrackableValue,
                        config.highestTrackableValue,
                        config.numberOfSignificantValueDigits
                );

        Histogram intervalHistogram = null;

        HiccupRecorder hiccupRecorder;

        final long uptimeAtInitialStartTime = ManagementFactory.getRuntimeMXBean().getUptime();
        long now = System.currentTimeMillis();
        long jvmStartTime = now - uptimeAtInitialStartTime;
        long reportingStartTime = jvmStartTime;

        if (config.inputFileName == null) {
            // Normal operating mode.
            // Launch a hiccup recorder, a process termination monitor, and an optional control process:
            hiccupRecorder = this.createHiccupRecorder(recorder);
            if (config.terminateWithStdInput) {
                new TerminateWithStdInputReader();
            }
            if (config.controlProcessCommand != null) {
                new ExecProcess(config.controlProcessCommand, "ControlProcess", log, config.verbose);
            }
        } else {
            // Take input from file instead of sampling it ourselves.
            // Launch an input hiccup recorder, but no termination monitoring or control process:
            hiccupRecorder = new InputRecorder(recorder, config.inputFileName);
        }

        histogramLogWriter.outputComment("[Logged with " + getVersionString() + "]");
        histogramLogWriter.outputLogFormatVersion();

        try {
            final long startTime;

            if (config.inputFileName == null) {
                // Normal operating mode:
                if (config.startDelayMs > 0) {
                    // Run hiccup recorder during startDelayMs time to let code warm up:
                    hiccupRecorder.start();
                    while (config.startDelayMs > System.currentTimeMillis() - jvmStartTime) {
                        Thread.sleep(100);
                    }
                    hiccupRecorder.terminate();
                    hiccupRecorder.join();

                    recorder.reset();
                    hiccupRecorder = new HiccupRecorder(recorder, config.allocateObjects);
                }
                hiccupRecorder.start();
                startTime = System.currentTimeMillis();
                if (config.startTimeAtZero) {
                    reportingStartTime = startTime;
                }

                histogramLogWriter.outputStartTime(reportingStartTime);
                histogramLogWriter.setBaseTime(reportingStartTime);

            } else {
                // Reading from input file, not sampling ourselves...:
                hiccupRecorder.start();
                now = reportingStartTime = hiccupRecorder.getCurrentTimeMsecWithDelay(0);

                while (config.startDelayMs > now - reportingStartTime) {
                    now = hiccupRecorder.getCurrentTimeMsecWithDelay(0);
                }

                startTime = now;

                histogramLogWriter.outputComment("[Data read from input file \"" + config.inputFileName + "\" at " + new Date() + "]");
            }

            histogramLogWriter.outputLegend();

            long nextReportingTime = startTime + config.reportingIntervalMs;
            long intervalStartTimeMsec = 0;

            while ((now >= 0) && ((config.runTimeMs == 0) || (config.runTimeMs >= now - startTime))) {
                now = hiccupRecorder.getCurrentTimeMsecWithDelay(nextReportingTime); // could return -1 to indicate termination
                if (now >= nextReportingTime) {
                    // Get the latest interval histogram and give the recorder a fresh Histogram for the next interval
                    intervalHistogram = recorder.getIntervalHistogram(intervalHistogram);

                    while (now >= nextReportingTime) {
                        nextReportingTime += config.reportingIntervalMs;
                    }

                    if (config.inputFileName != null) {
                        // When read from input file, use timestamps from file input for start/end of log intervals:
                        intervalHistogram.setStartTimeStamp(intervalStartTimeMsec);
                        intervalHistogram.setEndTimeStamp(now);
                        intervalStartTimeMsec = now;
                    }

                    if (intervalHistogram.getTotalCount() > 0) {
                        histogramLogWriter.outputIntervalHistogram(intervalHistogram);
                    }
                }
            }
        } catch (InterruptedException e) {
            if (config.verbose) {
                log.println("# HiccupMeter terminating...");
            }
        }

        try {
            hiccupRecorder.terminate();
            hiccupRecorder.join();
        } catch (InterruptedException e) {
            if (config.verbose) {
                log.println("# HiccupMeter terminate/join interrupted");
            }
        }
    }

    public static HiccupMeter commonMain(final String[] args, boolean exitOnError) {
        HiccupMeter hiccupMeter = null;
        try {
            hiccupMeter = new HiccupMeter(args, defaultHiccupLogFileName);

            if (hiccupMeter.config.attachToProcess) {
                String errorMessage = "Cannot use -p option with HiccupMeter (use HiccupMeterAttacher instead)";
                if (exitOnError) {
                    System.err.println(errorMessage);
                    System.exit(1);
                } else {
                    throw new RuntimeException("Error: " + errorMessage);
                }
            }

            if (hiccupMeter.config.error) {
                if (exitOnError) {
                    System.exit(1);
                } else {
                    throw new RuntimeException("Error: " + hiccupMeter.config.errorMessage);
                }
            }

            if (hiccupMeter.config.verbose) {
                hiccupMeter.log.print("# Executing: HiccupMeter");
                for (String arg : args) {
                    hiccupMeter.log.print(" " + arg);
                }
                hiccupMeter.log.println("");
            }

            hiccupMeter.start();

        } catch (FileNotFoundException e) {
            System.err.println("HiccupMeter: Failed to open log file.");
        }
        return hiccupMeter;
    }

    public static void agentmain(String argsString, java.lang.instrument.Instrumentation inst) {
        final String[] args = ((argsString != null) && !argsString.equals("")) ? argsString.split("[ ,;]+") : new String[0];
        final String avoidRecursion = System.getProperty("org.jhiccup.avoidRecursion");
        if (avoidRecursion != null) {
            return; // If this is a -c invocation, we do not want the agent to do anything...
        }
        commonMain(args, false);
    }

    public static void premain(String argsString, java.lang.instrument.Instrumentation inst) {
        final String[] args = ((argsString != null) && !argsString.equals("")) ? argsString.split("[ ,;]+") : new String[0];
        final String avoidRecursion = System.getProperty("org.jhiccup.avoidRecursion");
        if (avoidRecursion != null) {
            return; // If this is a -c invocation, we do not want the agent to do anything...
        }
        commonMain(args, true);
    }

    public static void main(final String[] args)  {
        final HiccupMeter hiccupMeter = commonMain(args, true);

        if (hiccupMeter != null) {
            // The HiccupMeter thread, on it's own, will not keep the JVM from exiting. If nothing else
            // is running (i.e. we we are the main class), then keep main thread from exiting
            // until the HiccupMeter thread does...
            try {
                hiccupMeter.join();
            } catch (InterruptedException e) {
                if (hiccupMeter.config.verbose) {
                    hiccupMeter.log.println("# HiccupMeter main() interrupted");
                }
            }
        }
    }
}

