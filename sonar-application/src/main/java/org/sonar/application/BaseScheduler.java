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

package org.sonar.application;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.process.Lifecycle;
import org.sonar.process.ProcessProperties;
import org.sonar.process.Props;
import org.sonar.process.Stoppable;
import org.sonar.process.monitor.JavaCommand;
import org.sonar.process.monitor.Monitor;

import static org.sonar.process.ProcessId.APP;

public abstract class BaseScheduler implements Scheduler, Stoppable {
  protected final Monitor monitor;
  protected final JavaCommandFactory javaCommandFactory;
  protected final Supplier<List<JavaCommand>> javaCommandSupplier;

  public BaseScheduler(Props props) {
    AppFileSystem appFileSystem = new AppFileSystem(props);
    appFileSystem.verifyProps();
    this.javaCommandFactory = new JavaCommandFactoryImpl();
    this.javaCommandSupplier = new ReloadableCommandSupplier(props, appFileSystem::ensureUnchangedConfiguration);

    boolean watchForHardStop = props.valueAsBoolean(ProcessProperties.ENABLE_STOP_COMMAND, false);
    this.monitor = Monitor.newMonitorBuilder()
      .setProcessNumber(APP.getIpcIndex())
      .setFileSystem(appFileSystem)
      .setWatchForHardStop(watchForHardStop)
      .setWaitForOperational()
      .addListener(new AppLifecycleListener())
      .build();
  }

  private static class AppLifecycleListener implements Lifecycle.LifecycleListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    @Override
    public void successfulTransition(Lifecycle.State from, Lifecycle.State to) {
      if (to == Lifecycle.State.OPERATIONAL) {
        LOGGER.info("SonarQube is up");
      }
    }
  }

  @Override
  public void stopAsync() {
    if (monitor != null) {
      monitor.stop();
    }
  }

  private class ReloadableCommandSupplier implements Supplier<List<JavaCommand>> {
    private final Props initialProps;
    private final CheckFSConfigOnReload checkFsConfigOnReload;
    private boolean initialPropsConsumed = false;
    private final Function<Properties, Props> propsFunction;

    ReloadableCommandSupplier(Props initialProps, CheckFSConfigOnReload checkFsConfigOnReload) {
      this.initialProps = initialProps;
      this.checkFsConfigOnReload = checkFsConfigOnReload;
      this.propsFunction = properties -> new PropsBuilder(properties, new JdbcSettings()).build();
    }

    @Override
    public List<JavaCommand> get() {
      if (!initialPropsConsumed) {
        initialPropsConsumed = true;
        return createCommands(this.initialProps);
      }
      return recreateCommands();
    }

    private List<JavaCommand> recreateCommands() {
      Props reloadedProps = propsFunction.apply(initialProps.rawProperties());
      AppFileSystem appFileSystem = new AppFileSystem(reloadedProps);
      appFileSystem.verifyProps();
      checkFsConfigOnReload.accept(reloadedProps);
      AppLogging logging = new AppLogging();
      logging.configure(reloadedProps);

      return createCommands(reloadedProps);
    }

    private List<JavaCommand> createCommands(Props props) {
      File homeDir = props.nonNullValueAsFile(ProcessProperties.PATH_HOME);
      List<JavaCommand> commands = new ArrayList<>(3);
      if (isProcessEnabled(props, ProcessProperties.CLUSTER_SEARCH_DISABLED)) {
        commands.add(javaCommandFactory.createESCommand(props, homeDir));
      }

      if (isProcessEnabled(props, ProcessProperties.CLUSTER_WEB_DISABLED)) {
        commands.add(javaCommandFactory.createWebCommand(props, homeDir));
      }

      if (isProcessEnabled(props, ProcessProperties.CLUSTER_CE_DISABLED)) {
        commands.add(javaCommandFactory.createCeCommand(props, homeDir));
      }

      return commands;
    }

    private boolean isProcessEnabled(Props props, String disabledPropertyKey) {
      return !props.valueAsBoolean(ProcessProperties.CLUSTER_ENABLED) ||
        !props.valueAsBoolean(disabledPropertyKey);
    }
  }

  @FunctionalInterface
  interface CheckFSConfigOnReload extends Consumer<Props> {

  }
}
