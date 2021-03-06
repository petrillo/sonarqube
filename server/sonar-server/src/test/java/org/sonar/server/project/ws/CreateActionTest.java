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
package org.sonar.server.project.ws;

import com.google.common.base.Throwables;
import java.io.IOException;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.System2;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.server.component.ComponentUpdater;
import org.sonar.server.component.NewComponent;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.MediaTypes;
import org.sonarqube.ws.WsProjects.CreateWsResponse;
import org.sonarqube.ws.client.project.CreateRequest;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.core.util.Protobuf.setNullable;
import static org.sonar.db.permission.OrganizationPermission.PROVISION_PROJECTS;
import static org.sonar.server.project.ws.ProjectsWsSupport.PARAM_ORGANIZATION;
import static org.sonar.test.JsonAssert.assertJson;
import static org.sonarqube.ws.client.WsRequest.Method.POST;
import static org.sonarqube.ws.client.project.ProjectsWsParameters.PARAM_NAME;

public class CreateActionTest {

  private static final String DEFAULT_PROJECT_KEY = "project-key";
  private static final String DEFAULT_PROJECT_NAME = "project-name";

  private System2 system2 = System2.INSTANCE;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();
  @Rule
  public DbTester db = DbTester.create(system2);
  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();

  private DefaultOrganizationProvider defaultOrganizationProvider = TestDefaultOrganizationProvider.from(db);
  private ComponentUpdater componentUpdater = mock(ComponentUpdater.class, Mockito.RETURNS_MOCKS);

  private WsActionTester ws = new WsActionTester(
    new CreateAction(
      new ProjectsWsSupport(db.getDbClient()),
      db.getDbClient(), userSession,
      componentUpdater,
      defaultOrganizationProvider));

  @Test
  public void create_project() throws Exception {
    userSession.addPermission(PROVISION_PROJECTS, db.getDefaultOrganization());
    expectSuccessfulCallToComponentUpdater();

    CreateWsResponse response = call(CreateRequest.builder()
      .setKey(DEFAULT_PROJECT_KEY)
      .setName(DEFAULT_PROJECT_NAME)
      .build());

    assertThat(response.getProject().getKey()).isEqualTo(DEFAULT_PROJECT_KEY);
    assertThat(response.getProject().getName()).isEqualTo(DEFAULT_PROJECT_NAME);
    assertThat(response.getProject().getQualifier()).isEqualTo("TRK");
  }

  @Test
  public void create_project_with_branch() throws Exception {
    userSession.addPermission(PROVISION_PROJECTS, db.getDefaultOrganization());

    call(CreateRequest.builder()
      .setKey(DEFAULT_PROJECT_KEY)
      .setName(DEFAULT_PROJECT_NAME)
      .setBranch("origin/master")
      .build());

    NewComponent called = verifyCallToComponentUpdater();
    assertThat(called.key()).isEqualTo(DEFAULT_PROJECT_KEY);
    assertThat(called.branch()).isEqualTo("origin/master");
  }

  @Test
  public void create_project_with_deprecated_parameter() throws Exception {
    OrganizationDto organization = db.organizations().insert();
    userSession.addPermission(PROVISION_PROJECTS, organization);

    ws.newRequest()
      .setMethod(POST.name())
      .setParam("organization", organization.getKey())
      .setParam("key", DEFAULT_PROJECT_KEY)
      .setParam(PARAM_NAME, DEFAULT_PROJECT_NAME)
      .execute();

    NewComponent called = verifyCallToComponentUpdater();
    assertThat(called.key()).isEqualTo(DEFAULT_PROJECT_KEY);
    assertThat(called.branch()).isNull();
  }

  @Test
  public void fail_when_project_already_exists() throws Exception {
    OrganizationDto organization = db.organizations().insert();
    when(componentUpdater.create(any(DbSession.class), any(NewComponent.class), anyInt())).thenThrow(new BadRequestException("already exists"));
    userSession.addPermission(PROVISION_PROJECTS, organization);

    expectedException.expect(BadRequestException.class);

    call(CreateRequest.builder()
      .setOrganization(organization.getKey())
      .setKey(DEFAULT_PROJECT_KEY)
      .setName(DEFAULT_PROJECT_NAME)
      .build());
  }

  @Test
  public void fail_when_missing_project_parameter() throws Exception {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("The 'project' parameter is missing");

    call(CreateRequest.builder().setName(DEFAULT_PROJECT_NAME).build());
  }

  @Test
  public void fail_when_missing_name_parameter() throws Exception {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("The 'name' parameter is missing");

    call(CreateRequest.builder().setKey(DEFAULT_PROJECT_KEY).build());
  }

  @Test
  public void fail_when_missing_create_project_permission() throws Exception {
    expectedException.expect(ForbiddenException.class);

    call(CreateRequest.builder().setKey(DEFAULT_PROJECT_KEY).setName(DEFAULT_PROJECT_NAME).build());
  }

  @Test
  public void test_example() {
    userSession.addPermission(PROVISION_PROJECTS, db.getDefaultOrganization());
    expectSuccessfulCallToComponentUpdater();

    String result = ws.newRequest()
      .setParam("key", DEFAULT_PROJECT_KEY)
      .setParam("name", DEFAULT_PROJECT_NAME)
      .execute().getInput();

    assertJson(result).isSimilarTo(getClass().getResource("create-example.json"));
  }

  @Test
  public void definition() {
    WebService.Action definition = ws.getDef();

    Assertions.assertThat(definition.key()).isEqualTo("create");
    Assertions.assertThat(definition.since()).isEqualTo("4.0");
    Assertions.assertThat(definition.isInternal()).isFalse();
    Assertions.assertThat(definition.responseExampleAsString()).isNotEmpty();

    Assertions.assertThat(definition.params()).hasSize(4);

    WebService.Param organization = definition.param(PARAM_ORGANIZATION);
    Assertions.assertThat(organization.description()).isEqualTo("The key of the organization");
    Assertions.assertThat(organization.isInternal()).isTrue();
    Assertions.assertThat(organization.isRequired()).isFalse();
    Assertions.assertThat(organization.since()).isEqualTo("6.3");
  }

  private CreateWsResponse call(CreateRequest request) {
    TestRequest httpRequest = ws.newRequest()
      .setMethod(POST.name())
      .setMediaType(MediaTypes.PROTOBUF);
    setNullable(request.getOrganization(), e -> httpRequest.setParam("organization", e));
    setNullable(request.getKey(), e -> httpRequest.setParam("project", e));
    setNullable(request.getName(), e -> httpRequest.setParam("name", e));
    setNullable(request.getBranch(), e -> httpRequest.setParam("branch", e));
    try {
      return CreateWsResponse.parseFrom(httpRequest.execute().getInputStream());
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private NewComponent verifyCallToComponentUpdater() {
    ArgumentCaptor<NewComponent> argument = ArgumentCaptor.forClass(NewComponent.class);
    verify(componentUpdater).create(any(DbSession.class), argument.capture(), anyInt());
    return argument.getValue();
  }

  private void expectSuccessfulCallToComponentUpdater() {
    when(componentUpdater.create(any(DbSession.class), any(NewComponent.class), anyInt())).thenAnswer(invocation -> {
      NewComponent newC = invocation.getArgumentAt(1, NewComponent.class);
      return new ComponentDto().setKey(newC.key()).setQualifier(newC.qualifier()).setName(newC.name());
    });
  }
}
