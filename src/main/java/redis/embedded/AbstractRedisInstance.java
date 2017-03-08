package redis.embedded;

import org.apache.commons.io.IOUtils;
import redis.embedded.exceptions.EmbeddedRedisException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

abstract class AbstractRedisInstance implements Redis {
    protected List<String> args = Collections.emptyList();
    private volatile boolean active = false;
	private Process redisProcess;
    private final int port;
    private final File logfile;


    private ExecutorService executor;

    protected AbstractRedisInstance(int port) {
        this.port = port;
        this.logfile = null;
    }
    protected AbstractRedisInstance(int port, File logfile) {
        this.port = port;
        this.logfile = logfile;
    }

    @Override
    public boolean isActive() {
        return active;
    }

	@Override
    public synchronized void start() throws EmbeddedRedisException {
        if (active) {
            throw new EmbeddedRedisException("This redis server instance is already running...");
        }
        try {
            redisProcess = createRedisProcessBuilder().start();
            logErrors();
            awaitRedisServerReady();
            active = true;
        } catch (IOException e) {
            throw new EmbeddedRedisException("Failed to start Redis instance", e);
        }
    }

    private void logErrors() {
        final InputStream errorStream = redisProcess.getErrorStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream));
        Runnable printReaderTask = new PrintReaderRunnable(reader);
        executor = Executors.newSingleThreadExecutor();
        executor.submit(printReaderTask);
    }

    private void awaitRedisServerReady() throws IOException {
        BufferedReader reader = getBufferedInputStreamReader();
        try {
            String outputLine;
            while (!reader.ready());
            do {
                outputLine = reader.readLine();
                if (outputLine == null) {
                    //Something goes wrong. Stream is ended before server was activated.
                    throw new RuntimeException("Can't start redis server. Check logs for details.");
                }
            } while (!outputLine.matches(redisReadyPattern()));
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    private BufferedReader getBufferedInputStreamReader() {
        if(logfile != null) {
            while (!logfile.exists()){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException("Could not put the thread to sleep.");
                }
            }
            try {
                return new BufferedReader(new FileReader(logfile));
            } catch (FileNotFoundException e) {
                throw new RuntimeException("Could not locate Redis logfile: " + logfile.getAbsolutePath());
            }
        } else {
            return new BufferedReader(new InputStreamReader(redisProcess.getInputStream()));
        }
    }

    protected abstract String redisReadyPattern();

    private ProcessBuilder createRedisProcessBuilder() {
        File executable = new File(args.get(0));
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(executable.getParentFile());
        return pb;
    }

    @Override
    public synchronized void stop() throws EmbeddedRedisException {
        if (active) {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
            }
            redisProcess.destroy();
            tryWaitFor();
            active = false;
        }
    }

    private void tryWaitFor() {
        try {
            redisProcess.waitFor();
        } catch (InterruptedException e) {
            throw new EmbeddedRedisException("Failed to stop redis instance", e);
        }
    }

    @Override
    public List<Integer> ports() {
        return Arrays.asList(port);
    }

    private static class PrintReaderRunnable implements Runnable {
        private final BufferedReader reader;

        private PrintReaderRunnable(BufferedReader reader) {
            this.reader = reader;
        }

        public void run() {
            try {
                readLines();
            } finally {
                IOUtils.closeQuietly(reader);
            }
        }

        public void readLines() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
