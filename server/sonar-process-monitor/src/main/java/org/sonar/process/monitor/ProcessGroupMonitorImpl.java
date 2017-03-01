/*
 *
 *  * SonarQube
 *  * Copyright (C) 2009-2017 SonarSource SA
 *  * mailto:info AT sonarsource DOT com
 *  *
 *  * This program is free software; you can redistribute it and/or
 *  * modify it under the terms of the GNU Lesser General Public
 *  * License as published by the Free Software Foundation; either
 *  * version 3 of the License, or (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  * Lesser General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU Lesser General Public License
 *  * along with this program; if not, write to the Free Software Foundation,
 *  * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */

package org.sonar.process.monitor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.CheckForNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.process.DefaultProcessCommands;
import org.sonar.process.Lifecycle;
import org.sonar.process.SystemExit;

import static java.util.Objects.requireNonNull;

public class ProcessGroupMonitorImpl implements ProcessGroupMonitor {
  private static final Logger LOG = LoggerFactory.getLogger(ProcessGroupMonitorImpl.class);
  private static final Timeouts TIMEOUTS = new Timeouts();
  private static final long WATCH_DELAY_MS = 500L;

  private static int restartorInstanceCounter = 0;

  private final int processNumber;
  private final FileSystem fileSystem;
  private final SystemExit systemExit;
  private final boolean watchForHardStop;
  private final Thread shutdownHook = new Thread(new MonitorShutdownHook(), "Monitor Shutdown Hook");
  private final boolean waitForOperational;

  private final List<WatcherThread> watcherThreads = new CopyOnWriteArrayList<>();
  private final Lifecycle lifecycle;

  private final TerminatorThread terminator = new TerminatorThread();
  private final RestartRequestWatcherThread restartWatcher = new RestartRequestWatcherThread();
  @CheckForNull
  private Supplier<List<JavaCommand>> javaCommandsSupplier;
  @CheckForNull
  private List<JavaCommand> javaCommands;
  @CheckForNull
  private JavaProcessLauncher launcher;
  @CheckForNull
  private RestartorThread restartor;
  @CheckForNull
  HardStopWatcherThread hardStopWatcher;

  private ProcessGroupMonitorImpl(Builder builder) {
    this.processNumber = builder.processNumber;
    this.fileSystem = requireNonNull(builder.fileSystem, "FileSystem can't be null²");
    this.systemExit = builder.exit == null ? new SystemExit() : builder.exit;
    this.watchForHardStop = builder.watchForHardStop;
    this.waitForOperational = builder.waitForOperational;
    this.lifecycle = builder.listeners == null ? new Lifecycle() : new Lifecycle(builder.listeners.stream().toArray(Lifecycle.LifecycleListener[]::new));
  }

  public static Builder newMonitorBuilder() {
    return new Builder();
  }

  public static class Builder {
    private int processNumber;
    private FileSystem fileSystem;
    private SystemExit exit;
    private boolean watchForHardStop;
    private boolean waitForOperational = false;
    private List<Lifecycle.LifecycleListener> listeners;

    private Builder() {
      // use static factory method
    }

    public Builder setProcessNumber(int processNumber) {
      this.processNumber = processNumber;
      return this;
    }

    public Builder setFileSystem(FileSystem fileSystem) {
      this.fileSystem = fileSystem;
      return this;
    }

    public Builder setExit(SystemExit exit) {
      this.exit = exit;
      return this;
    }

    public Builder setWatchForHardStop(boolean watchForHardStop) {
      this.watchForHardStop = watchForHardStop;
      return this;
    }

    public Builder setWaitForOperational() {
      this.waitForOperational = true;
      return this;
    }

    public Builder addListener(Lifecycle.LifecycleListener listener) {
      if (this.listeners == null) {
        this.listeners = new ArrayList<>(1);
      }
      this.listeners.add(requireNonNull(listener, "LifecycleListener can't be null"));
      return this;
    }

    public ProcessGroupMonitorImpl build() {
      return new ProcessGroupMonitorImpl(this);
    }
  }

  /**
   * Starts the processes defined by the JavaCommand in {@link #javaCommands}/
   */
  private void startProcesses(Supplier<List<JavaCommand>> javaCommandsSupplier) throws InterruptedException {
    // do no start any child process if not in state INIT or RESTARTING (a stop could be in progress too)
    if (lifecycle.tryToMoveTo(Lifecycle.State.STARTING)) {
      resetFileSystem();

      // start watching for stop requested by other process (eg. orchestrator) if enabled and not started yet
      if (watchForHardStop && hardStopWatcher == null) {
        hardStopWatcher = new HardStopWatcherThread();
        hardStopWatcher.start();
      }

      this.javaCommands = javaCommandsSupplier.get();
      startAndMonitorProcesses();
      stopIfAnyProcessDidNotStart();
      waitForOperationalProcesses();
    }
  }

  @Override
  public ProcessState launchAndMonitor(JavaCommand javaCommand, Consumer stateListener) {
    return null;
  }

  @Override
  public void stop() {

  }

  private void stopProcesses() {
    List<WatcherThread> watcherThreadsCopy = new ArrayList<>(this.watcherThreads);
    // create a copy and reverse it to terminate in reverse order of startup (dependency order)
    Collections.reverse(watcherThreadsCopy);

    for (WatcherThread watcherThread : watcherThreadsCopy) {
      ProcessRef ref = watcherThread.getProcessRef();
      if (!ref.isStopped()) {
        LOG.info("{} is stopping", ref);
        ref.askForGracefulAsyncStop();

        long killAt = System.currentTimeMillis() + TIMEOUTS.getTerminationTimeout();
        while (!ref.isStopped() && System.currentTimeMillis() < killAt) {
          try {
            Thread.sleep(10L);
          } catch (InterruptedException e) {
            // stop asking for graceful stops, Monitor will hardly kill all processes
            break;
          }
        }
        if (!ref.isStopped()) {
          LOG.info("{} failed to stop in a timely fashion. Killing it.", ref);
        }
        ref.stop();
        LOG.info("{} is stopped", ref);
      }
    }

    // all processes are stopped, no need to keep references to these WatcherThread anymore
    LOG.trace("all processes stopped, clean list of watcherThreads...");
    this.watcherThreads.clear();
  }

  /**
   * Asks for processes termination and returns without blocking until termination.
   * However, if a termination request is already under way (it's not supposed to happen, but, technically, it can occur),
   * this call will be blocking until the previous request finishes.
   */
  public void stopAsync() {
    stopAsync(Lifecycle.State.STOPPING);
  }

  private void stopAsync(Lifecycle.State stoppingState) {
    assert stoppingState == Lifecycle.State.STOPPING || stoppingState == Lifecycle.State.HARD_STOPPING;
    if (lifecycle.tryToMoveTo(stoppingState)) {
      terminator.start();
    }
  }

  public void restartAsync() {
    if (lifecycle.tryToMoveTo(Lifecycle.State.RESTARTING)) {
      restartor = new RestartorThread();
      restartor.start();
    }
  }

  /**
   * Runs every time a restart request is detected.
   */
  private class RestartorThread extends Thread {

    private RestartorThread() {
      super("Restartor " + (restartorInstanceCounter++));
    }

    @Override
    public void run() {
      stopProcesses();
      try {
        startProcesses(this::loadJavaCommands);
      } catch (InterruptedException e) {
        // Startup was interrupted. Processes are being stopped asynchronously.
        // Restoring the interruption state.
        Thread.currentThread().interrupt();
      } catch (Throwable t) {
        LOG.error("Restart failed", t);
        stopAsync(Lifecycle.State.HARD_STOPPING);
      }
    }
  }

  private class MonitorShutdownHook implements Runnable {
    @Override
    public void run() {
      systemExit.setInShutdownHook();
      LOG.trace("calling stop from MonitorShutdownHook...");
      // blocks until everything is corrected terminated
      stop();
    }
  }

  /**
   * Runs only once
   */
  private class TerminatorThread extends Thread {

    private TerminatorThread() {
      super("Terminator");
    }

    @Override
    public void run() {
      stopProcesses();
    }
  }

  /**
   * Watches for any child process requesting a restart of all children processes.
   * It runs once and as long as {@link #lifecycle} hasn't reached {@link Lifecycle.State#STOPPED} and holds its checks
   * when {@link #lifecycle} is not in state {@link Lifecycle.State#STARTED} to avoid taking the same request into account
   * twice.
   */
  public class RestartRequestWatcherThread extends Thread {
    public RestartRequestWatcherThread() {
      super("Restart watcher");
    }

    @Override
    public void run() {
      while (lifecycle.getState() != Lifecycle.State.STOPPED) {
        Lifecycle.State state = lifecycle.getState();
        if ((state == Lifecycle.State.STARTED || state == Lifecycle.State.OPERATIONAL) && didAnyProcessRequestRestart()) {
          restartAsync();
        }
        try {
          Thread.sleep(WATCH_DELAY_MS);
        } catch (InterruptedException ignored) {
          // keep watching
        }
      }
    }

    private boolean didAnyProcessRequestRestart() {
      for (WatcherThread watcherThread : watcherThreads) {
        ProcessRef processRef = watcherThread.getProcessRef();
        if (processRef.getCommands().askedForRestart()) {
          LOG.info("Process [{}] requested restart", processRef.getKey());
          return true;
        }
      }
      return false;
    }

  }

  public class HardStopWatcherThread extends Thread {

    public HardStopWatcherThread() {
      super("Hard stop watcher");
    }

    @Override
    public void run() {
      while (lifecycle.getState() != Lifecycle.State.STOPPED) {
        if (askedForStop()) {
          LOG.trace("Stopping process");
          ProcessGroupMonitorImpl.this.stop();
        } else {
          delay();
        }
      }
    }

    private boolean askedForStop() {
      File tempDir = fileSystem.getTempDir();
      try (DefaultProcessCommands processCommands = DefaultProcessCommands.secondary(tempDir, processNumber)) {
        if (processCommands.askedForStop()) {
          return true;
        }
      }
      return false;
    }

    private void delay() {
      try {
        Thread.sleep(WATCH_DELAY_MS);
      } catch (InterruptedException ignored) {
        // keep watching
      }
    }
  }
}
