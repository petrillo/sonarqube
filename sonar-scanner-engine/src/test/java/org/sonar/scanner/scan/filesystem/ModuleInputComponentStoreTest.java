/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scanner.scan.filesystem;

import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputModule;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.scanner.sensor.SensorStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ModuleInputComponentStoreTest {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private InputComponentStore componentStore;

  private final String moduleKey = "dummy key";

  @Before
  public void setUp() throws IOException {
    componentStore = new InputComponentStore(new PathResolver());
    componentStore.put(TestInputFileBuilder.newDefaultInputModule(moduleKey, temp.newFolder()));
  }

  @Test
  public void should_cache_files_by_filename() throws IOException {
    ModuleInputComponentStore store = newModuleInputComponentStore();

    String filename = "some name";
    InputFile inputFile1 = new TestInputFileBuilder(moduleKey, "some/path/" + filename).build();
    store.doAdd(inputFile1);

    InputFile inputFile2 = new TestInputFileBuilder(moduleKey, "other/path/" + filename).build();
    store.doAdd(inputFile2);

    InputFile dummyInputFile = new TestInputFileBuilder(moduleKey, "some/path/Dummy.java").build();
    store.doAdd(dummyInputFile);

    assertThat(store.getFilesByName(filename)).containsOnly(inputFile1, inputFile2);
  }

  @Test
  public void should_cache_files_by_extension() throws IOException {
    ModuleInputComponentStore store = newModuleInputComponentStore();

    InputFile inputFile1 = new TestInputFileBuilder(moduleKey, "some/path/Program.java").build();
    store.doAdd(inputFile1);

    InputFile inputFile2 = new TestInputFileBuilder(moduleKey, "other/path/Utils.java").build();
    store.doAdd(inputFile2);

    InputFile dummyInputFile = new TestInputFileBuilder(moduleKey, "some/path/NotJava.cpp").build();
    store.doAdd(dummyInputFile);

    assertThat(store.getFilesByExtension("java")).containsOnly(inputFile1, inputFile2);
  }

  @Test
  public void should_not_cache_duplicates() throws IOException {
    ModuleInputComponentStore store = newModuleInputComponentStore();

    String ext = "java";
    String filename = "Program." + ext;
    InputFile inputFile = new TestInputFileBuilder(moduleKey, "some/path/" + filename).build();
    store.doAdd(inputFile);
    store.doAdd(inputFile);
    store.doAdd(inputFile);

    assertThat(store.getFilesByName(filename)).containsOnly(inputFile);
    assertThat(store.getFilesByExtension(ext)).containsOnly(inputFile);
  }

  @Test
  public void should_get_empty_iterable_on_cache_miss() {
    ModuleInputComponentStore store = newModuleInputComponentStore();

    String ext = "java";
    String filename = "Program." + ext;
    InputFile inputFile = new TestInputFileBuilder(moduleKey, "some/path/" + filename).build();
    store.doAdd(inputFile);

    assertThat(store.getFilesByName("nonexistent")).isEmpty();
    assertThat(store.getFilesByExtension("nonexistent")).isEmpty();
  }

  private ModuleInputComponentStore newModuleInputComponentStore() {
    return new ModuleInputComponentStore(mock(InputModule.class), componentStore, mock(SensorStrategy.class));
  }
}
