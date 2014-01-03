/**
 * Written by Gil Tene of Azul Systems, and released to the public domain,
 * as explained at http://creativecommons.org/publicdomain/zero/1.0/
 *
 * @author Gil Tene
 * @version 1.3.6
 */

package org.jhiccup;

import org.HdrHistogram.*;

import java.io.*;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.lang.management.*;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.Semaphore;

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
 * (using the -d <startDelayMs> flag, defaults to 30000 msec). It can also be
 * configured to terminate measurement after a given length of time (using the
 * -t <runTimeMs> flag). If the -t flag is not used, HiccupMeter will continue
 * to run until the Class executed with via the -exec parameter (see below)
 * exits, or indefnitely (if no -exec is used).
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
 * and a ready to use jar file can all be found on GitGub,
 * at http://giltene.github.com/HdrHistogram
 */


public class HiccupMeter extends Thread {

    final String versionString = "jHiccup version 1.3.6";

    final PrintStream log;

    final HiccupMeterConfiguration config;

    static class HiccupMeterConfiguration {
        public boolean terminateWithStdInput = false;
        public long resolutionMs = 1;
        public long runTimeMs = 0;
        public long reportingIntervalMs = 5000;
        public long startDelayMs = 30000;
        public boolean startDelayMsExplicitlySpecified = false;
        public long timeDelayMsBeforeThrowingHistogramFileExceptions = 60000;

        public boolean verbose = false;
        public boolean allocateObjects = false;
        public String logFileName = "hiccup.%date.%pid";
        public boolean logFileExplicitlySpecified = false;
        public String inputFileName = null;

        public boolean launchControlProcess = false;
        public String controlProcessLogFileName = null;
        public String controlProcessCommand = null;

        public boolean attachToProcess = false;
        public String pidOfProcessToAttachTo = null;
        public String agentJarFileName = null;
        public String agentArgs = null;

        public boolean startTimeAtZero = false;

        public long highestTrackableValue = 3600 * 1000L * 1000L;
        public int numberOfSignificantValueDigits = 2;
        public int histogramDumpTicksPerHalf = 5;
        public Double outputValueUnitRatio = 1000.0;

        public boolean error = false;
        public String errorMessage = "";

//        String deriveLogFileName() {
//            final String processName =
//                    java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
//            final String processID = processName.split("@")[0];
//            final SimpleDateFormat formatter = new SimpleDateFormat("yyMMdd.HHmm");
//            final String formattedDate = formatter.format(new Date());
//            return "hiccup." + formattedDate + "." + processID;
//        }

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

        public HiccupMeterConfiguration(final String[] args) {
            try {
                for (int i = 0; i < args.length; ++i) {
                    if (args[i].equals("-v")) {
                        verbose = true;
                    } else if (args[i].equals("-0")) {
                        startTimeAtZero = true;
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
                        resolutionMs = Long.parseLong(args[++i]);
                    } else if (args[i].equals("-l")) {
                        logFileName = args[++i];
                        logFileExplicitlySpecified = true;
                    } else if (args[i].equals("-f")) {
                        inputFileName = args[++i];
                    } else if (args[i].equals("-c")) {
                        launchControlProcess = true;
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
                                " -d " + startDelayMs +
                                ((startTimeAtZero) ? " -0" : "") +
                                " -r " + resolutionMs;

                    if (logFileExplicitlySpecified) {
                        agentArgs += " -l " + logFileName;
                    }

                    if (launchControlProcess) {
                        agentArgs += " -c";
                    }

                    if (verbose) {
                        agentArgs += " -v";
                    }
                }

                if (launchControlProcess) {
                    File filePath = new File(logFileName);
                    String parentFileNamePart = filePath.getParent();
                    if (parentFileNamePart == null) {
                        parentFileNamePart = "";
                    }
                    String childFileNamePart = filePath.getName();
                    // Derive control process log file name from logFileName:
                    File controlFilePath = new File(parentFileNamePart + childFileNamePart + ".c");
                    controlProcessLogFileName = controlFilePath.getPath();

                    // Derive controlProcessCommand from our java home, class name, and parsed
                    // options:
                    controlProcessCommand =
                            System.getProperty("java.home") +
                                    File.separator + "bin" + File.separator + "java" +
                                    " -cp " + System.getProperty("java.class.path") +
                                    " -Dorg.jhiccup.avoidRecursion=true" +
                                    " " + HiccupMeter.class.getCanonicalName() +
                                    " -l " + controlProcessLogFileName +
                                    " -i " + reportingIntervalMs +
                                    " -d " + startDelayMs +
                                    ((startTimeAtZero) ? " -0" : "") +
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
                        "\"[-v] [-c] [-0] [-p pidOfProcessToAttachTo] [-j jHiccupJarFileName] " +
                        "[-i reportingIntervalMs] [-h] [-t runTimeMs] [-d startDelayMs] " +
                        "[-l logFileName] [-r resolutionMs] [-terminateWithStdInput] [-f inputFileName]\"\n";

                System.err.println("valid arguments = " + validArgs);

                System.err.println(
                " [-h]                        help\n" +
                " [-v]                        verbose\n" +
                " [-l logFileName]            Log hiccup information into logFileName and logFileName.hgrm\n" +
                "                             (will replace occurrences of %pid and %date with appropriate information)\n" +
                " [-c]                        Launch a control process in a separate JVM\n" +
                "                             logging hiccup data into logFileName.c and logFileName.c.hgrm\n" +
                " [-p pidOfProcessToAttachTo] Attach to the process with given pid and inject jHiccup as an agent\n" +
                " [-j jHiccupJarFileName]     File name for the jHiccup.jar file, and required with [-p] option above\n" +
                " [-d startDelayMs]           Delay the beginning of hiccup measurement by\n" +
                "                             startDelayMs milliseconds [default 30000]\n" +
                " [-0]                        Start timestamps at 0 (as opposed to at JVM runtime at start point)" +
                " [-i reportingIntervalMs]    Set reporting interval [default 50000]\n" +
                " [-r resolutionMs]           Set sampling resolution in milliseconds [default 1]\n" +
                " [-t runTimeMs]              Limit measurement time [default 0, for infinite]\n" +
                " [-terminateWithStdInput]    Take over standard input, and terminate process when\n" +
                "                             standard input is severed (useful for control\n" +
                "                             processes that wish to terminate when their launching\n" +
                "                             parent does).\n" +
                " [-f inputFileName]          Read timestamp and latency data from input file\n" +
                "                             instead of sampling it directly\n");
            }
        }
    }

    /**
     * Argument usage:
     * <pre>
     *    [-h]                           help
     *    [-v]                           verbose
     *    [-l logFileName]               Log hiccup information into <i>logFileName</i> and
     *                                   <i>logFileName.hgrm</i>
     *    [-c controlProcessLogFileName] Launch a control process in a separate JVM
     *                                   logging hiccup data into <i>controlProcessLogFileName</i>
     *                                   and <i>controlProcessLogFileName.hgrm</i>
     *    [-d startDelayMs]              Delay the beginning of hiccup measurement by
     *                                   <i>startDelayMs</i> milliseconds [default 30000]
     *    [-i reportingIntervalMs]       Set reporting interval [default 50000]
     *    [-r resolutionMs]              Set sampling resolution in milliseconds [default 1]
     *    [-t runTimeMs]                 Limit measurement time [default 0, for infinite]
     *    [-terminateWithStdInput]       Take over standard input, and terminate process when
     *                                   standard input is severed (useful for control
     *                                   processes that wish to terminate when their launching
     *                                   parent does).
     * </pre>
     */

    public HiccupMeter(final String[] args) throws FileNotFoundException {
        this.setName("HiccupMeter");
        config = new HiccupMeterConfiguration(args);
        log = new PrintStream(new FileOutputStream(config.logFileName), false);
        this.setDaemon(true);
    }

    class ControlProcess extends Thread {
        final String controlProcessCommand;

        ControlProcess(final String command) {
            this.setDaemon(true);
            this.setName("ControlProcessExecThread");
            controlProcessCommand = command;
            this.start();
        }

        public void run() {
            try {
                if (config.verbose) {
                    log.println("# Executing Control Process command: " + controlProcessCommand);
                }
                final Process p = Runtime.getRuntime().exec(controlProcessCommand);
                p.waitFor();
            } catch (Exception e) {
                System.err.println("HiccupMeter: Control process terminated.");
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

    class HiccupRecorder extends Thread {
        Histogram histogram;
        volatile Histogram newHistogram;
        volatile Histogram oldHistogram;
        volatile boolean doRun;
        final boolean allocateObjects;
        public volatile Long lastSleepTimeObj; // public volatile to make sure allocs are not optimized away...
        private Semaphore histogramReplacedSemaphore = new Semaphore(0);


        HiccupRecorder(final Histogram histogram, final boolean allocateObjects) {
            this.setDaemon(true);
            this.setName("HiccupRecorder");
            this.histogram = histogram;
            this.allocateObjects = allocateObjects;
            doRun = true;
        }

        public void terminate() {
            doRun = false;
        }


        public long getCurrentTimeMsec(final long nextReportingTime) throws InterruptedException {
            final long now = System.currentTimeMillis();
            if (now < nextReportingTime)
                Thread.sleep(100);
            return now;
        }

        public synchronized Histogram swapHistogram(final Histogram replacementHistogram) {
            // Ask the running thread to replace the running histogram and
            // hand us the current one in oldHistogram:

            // We only want to return the current histogram when we know it will
            // no longer have results logged into it by the logging/sampling thread.
            // We use a counting semaphore for signaling here, to avoid potential
            // blocking in the sampling thread.

            // Indicate that we want to swap histogram:
            newHistogram = replacementHistogram;

            // Wait for an indication that the histogram as replaced:
            histogramReplacedSemaphore.acquireUninterruptibly();

            final Histogram returnedHistogram = oldHistogram;
            oldHistogram = null;
            return returnedHistogram;
        }

        public Histogram getHistogram() {
            return histogram;
        }

        public void run() {
            final long resolutionUsec = config.resolutionMs * 1000L;
            try {
                while (doRun) {
                    final long measurementStartTime, deltaTimeNs;
                    if (config.resolutionMs != 0) {
                        measurementStartTime = System.nanoTime();
                        Thread.sleep(config.resolutionMs);
                        if (allocateObjects) {
                            // Allocate an object to make sure potential allocation stalls are measured.
                            lastSleepTimeObj = new Long(measurementStartTime);
                        }
                        deltaTimeNs = System.nanoTime() - measurementStartTime;
                    } else {
                        measurementStartTime = System.nanoTime();
                        deltaTimeNs = System.nanoTime() - measurementStartTime;
                    }
                    long hiccupTimeUsec = (deltaTimeNs/1000) - resolutionUsec;
                    hiccupTimeUsec = (hiccupTimeUsec < 0) ? 0 : hiccupTimeUsec;
                    histogram.recordValueWithExpectedInterval(hiccupTimeUsec, resolutionUsec);

                    if (newHistogram != null) {
                        // Someone wants to replace the running histogram with a new one.
                        // Do wait-free swapping. The recording loop stays wait-free, while the other side polls:
                        final Histogram tempHistogram = histogram;
                        histogram = newHistogram;
                        newHistogram = null;
                        // The requesting thread will observe oldHistogram through polling:
                        oldHistogram = tempHistogram;
                        // Signal that histogram was replaced:
                        histogramReplacedSemaphore.release();
                    }
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

        InputRecorder(final Histogram histogram, final String inputFileName) {
            super(histogram, false);
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

        long processInputLine(final Scanner scanner, final Histogram histogram) {
            if (scanner.hasNextLine()) {
                try {
                    final long timeMsec = (long) scanner.nextDouble(); // Timestamp is expect to be in millis
                    final long hiccupTimeUsec = (long) (scanner.nextDouble() * 1000); // Latency is expected to be in millis
                    histogram.recordValueWithExpectedInterval(hiccupTimeUsec, config.resolutionMs * 1000);
                    return timeMsec;
                } catch (java.util.NoSuchElementException e) {
                    return -1;
                }
            }
            return -1;
        }

        @Override
        public long getCurrentTimeMsec(final long nextReportingTime) throws InterruptedException {
            return processInputLine(scanner, histogram);
        }

        @Override
        public Histogram swapHistogram(final Histogram replacementHistogram) {
            final Histogram tmpHistogram  = histogram;
            histogram = replacementHistogram;
            return tmpHistogram;
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

    static boolean canDoOverwritingRenames = true; // Assume we can do overwriting renames, will auto-correct if wrong

    void outputHistogramFile(final Histogram histogram, final boolean suppressRunTimeExceptions) {
        try {
            if (config.logFileName != null) {
                final PrintStream histogramLog =
                        new PrintStream(new FileOutputStream(config.logFileName + ".hgrm.tmp"),false);
                histogramLog.println("jHiccup histogram report, " + new Date() + " :\n--------------------\n");
                histogram.getHistogramData().outputPercentileDistribution(histogramLog,
                        config.histogramDumpTicksPerHalf, config.outputValueUnitRatio);
                histogramLog.close();
                final File fromFile = new File(config.logFileName + ".hgrm.tmp");
                final File toFile = new File(config.logFileName + ".hgrm");
                if (!canDoOverwritingRenames) // Only delete toFile if platform can't do overwriting renames.
                    toFile.delete();
                boolean renameSucceess = fromFile.renameTo(toFile);
                if (!renameSucceess) {
                    if (canDoOverwritingRenames) {
                        toFile.delete();
                        renameSucceess = fromFile.renameTo(toFile);
                        if (renameSucceess) {
                            canDoOverwritingRenames = false; // Figured out that we can't do overwriting renames...
                        }
                    }
                    if (!renameSucceess) {
                        log.println("Failed to rename histogram file from " + fromFile.getName() +
                                " to " + toFile.getName());
                    }
                }
            }
        } catch (FileNotFoundException e) {
            log.println("Could not open histogram file(s): " + e);
        } catch (RuntimeException e) {
            if (!suppressRunTimeExceptions) {
                log.println("Encountered exception when trying to output histogram file: " + e);
                throw e;
            }
        }
    }

    @Override
    public void run() {
        final Histogram accumulatedHistogram =
                new Histogram(config.highestTrackableValue, config.numberOfSignificantValueDigits);
        HiccupRecorder hiccupRecorder;

        final long uptimeAtInitialStartTime = ManagementFactory.getRuntimeMXBean().getUptime();
        long now = System.currentTimeMillis();
        long jvmStartTime = now - uptimeAtInitialStartTime;
        long reportingStartTime = jvmStartTime;


        final Histogram initialHistogram =
                new Histogram(config.highestTrackableValue, config.numberOfSignificantValueDigits);

        if (config.inputFileName == null) {
            // Normal operating mode.
            // Launch a hiccup recorder, a process termination monitor, and an optional control process:
            hiccupRecorder = new HiccupRecorder(initialHistogram, config.allocateObjects);
            if (config.terminateWithStdInput) {
                new TerminateWithStdInputReader();
            }
            if (config.controlProcessCommand != null) {
                new ControlProcess(config.controlProcessCommand);
            }
        } else {
            // Take input from file instead of sampling it ourselves.
            // Launch an input hiccup recorder, but no termination monitoring or control process:
            hiccupRecorder = new InputRecorder(initialHistogram, config.inputFileName);
        }

        try {
            final long startTime;
            log.println("#[Logged with " + versionString + "]");
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

                    initialHistogram.reset();
                    hiccupRecorder = new HiccupRecorder(initialHistogram, config.allocateObjects);
                }
                hiccupRecorder.start();
                startTime = System.currentTimeMillis();
                log.println("#[Sampling start time: " + new Date() + ", (uptime at sampling start: " +
                        (ManagementFactory.getRuntimeMXBean().getUptime()/1000.0) + " seconds)]");
                if (config.startTimeAtZero) {
                    reportingStartTime = startTime;
                }
            } else {
                // Reading from input file, not sampling ourselves...:
                hiccupRecorder.start();
                reportingStartTime = startTime = hiccupRecorder.getCurrentTimeMsec(0);
                log.println("#[Data read from input file \"" + config.inputFileName + "\" at " + new Date() + "]");
            }

            log.println("Time: IntervalPercentiles:count ( 50% 90% Max ) TotalPercentiles:count ( 50% 90% 99% 99.9% 99.99% Max )");

            long nextReportingTime = startTime + config.reportingIntervalMs;

            Histogram latestHistogram =
                    new Histogram(config.highestTrackableValue, config.numberOfSignificantValueDigits);

            while ((now > 0) && ((config.runTimeMs == 0) || (config.runTimeMs > now - startTime))) {
                now = hiccupRecorder.getCurrentTimeMsec(nextReportingTime); // could return -1 to indicate termination
                if (now > nextReportingTime) {
                    // Get the latest interval histogram and give the recorder a fresh Histogram for the next interval
                    latestHistogram.reset();
                    latestHistogram = hiccupRecorder.swapHistogram(latestHistogram);
                    accumulatedHistogram.add(latestHistogram);
                    while (now > nextReportingTime) {
                        nextReportingTime += config.reportingIntervalMs;
                    }
                    if (latestHistogram.getHistogramData().getTotalCount() > 0) {
                        final HistogramData latestHistogramData = latestHistogram.getHistogramData();
                        final HistogramData accumulatedHistogramData = accumulatedHistogram.getHistogramData();
                        log.format(Locale.US,
                                "%4.3f: I:%d ( %7.3f %7.3f %7.3f ) T:%d ( %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f )\n",
                                (now - reportingStartTime)/1000.0,
                                // values recorded during the last reporting interval
                                latestHistogramData.getTotalCount(),
                                latestHistogramData.getValueAtPercentile(50.0)/1000.0,
                                latestHistogramData.getValueAtPercentile(90.0)/1000.0,
                                latestHistogramData.getMaxValue()/1000.0,
                                // values recorded from the beginning until now
                                accumulatedHistogramData.getTotalCount(),
                                accumulatedHistogramData.getValueAtPercentile(50.0)/1000.0,
                                accumulatedHistogramData.getValueAtPercentile(90.0)/1000.0,
                                accumulatedHistogramData.getValueAtPercentile(99.0)/1000.0,
                                accumulatedHistogramData.getValueAtPercentile(99.9)/1000.0,
                                accumulatedHistogramData.getValueAtPercentile(99.99)/1000.0,
                                accumulatedHistogramData.getMaxValue()/1000.0
                        );

                        // Output histogram file:
                        // Note: Some platforms (e.g. Websphere) turn on temporary security managers at start
                        // time, which may prevent new histogram file output. We suppress exceptions for
                        // the first timeDelayMsBeforeThrowingHistogramFileExceptions msecs to allow
                        // recording to continue if failure is only temporary:

                        outputHistogramFile(accumulatedHistogram,
                                ((now - startTime) < config.timeDelayMsBeforeThrowingHistogramFileExceptions));
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

        accumulatedHistogram.add(hiccupRecorder.getHistogram());
        outputHistogramFile(accumulatedHistogram, false);
    }

    public static HiccupMeter commonMain(final String[] args, boolean exitOnError) {
        HiccupMeter hiccupMeter = null;
        Thread.currentThread().setName("HiccupBookkeeper ");
        try {
            hiccupMeter = new HiccupMeter(args);

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
        final String[] args = (argsString != null) ? argsString.split("[ ,;]+") : new String[0];
        final String avoidRecursion = System.getProperty("org.jhiccup.avoidRecursion");
        if (avoidRecursion != null) {
            return; // If this is a -c invocation, we do not want the agent to do anything...
        }
        commonMain(args, false);
    }

    public static void premain(String argsString, java.lang.instrument.Instrumentation inst) {
        final String[] args = (argsString != null) ? argsString.split("[ ,;]+") : new String[0];
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

