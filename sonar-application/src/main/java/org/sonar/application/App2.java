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

import java.util.Properties;
import java.util.function.Function;
import org.sonar.process.Props;

/**
 * Entry-point of process that starts and monitors ElasticSearch, the Web Server and the Compute Engine.
 */
public class App2 {
  private final Function<Properties, Props> propsSupplier;
  private final Cluster cluster;

  App2(Properties commandLineArguments) {
    this.propsSupplier = properties -> new PropsBuilder(properties, new JdbcSettings()).build();
    Props props = propsSupplier.apply(commandLineArguments);

    ClusterProperties clusterProperties = new ClusterProperties(props);
    clusterProperties.populateProps(props);
    AppLogging logging = new AppLogging();
    logging.configure(props);
    clusterProperties.validate();
    this.cluster = new Cluster(clusterProperties);

    Scheduler scheduler = clusterProperties.isEnabled() ?
      new ClusterSchedulerImpl(props, cluster) :
      new NoClusterSchedulerImpl(props);

    scheduler.start();
  }

  public void main(String... args) {
    CommandLineParser cli = new CommandLineParser();
    Properties commandLineArguments = cli.parseArguments(args);

    new App2(commandLineArguments);
  }
}
