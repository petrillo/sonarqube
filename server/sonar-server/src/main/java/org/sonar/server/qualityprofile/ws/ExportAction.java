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
package org.sonar.server.qualityprofile.ws;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nullable;
import org.sonar.api.profiles.ProfileExporter;
import org.sonar.api.resources.Languages;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.Response.Stream;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.NewAction;
import org.sonar.core.util.stream.Collectors;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.qualityprofile.QualityProfileDto;
import org.sonar.server.qualityprofile.QProfileBackuper;
import org.sonar.server.qualityprofile.QProfileExporters;
import org.sonar.server.util.LanguageParamUtils;
import org.sonarqube.ws.MediaTypes;

import static org.sonar.server.ws.WsUtils.checkFound;

public class ExportAction implements QProfileWsAction {

  private static final String PARAM_PROFILE_NAME = "name";
  private static final String PARAM_LANGUAGE = "language";
  private static final String PARAM_FORMAT = "exporterKey";

  private final DbClient dbClient;

  private final QProfileBackuper backuper;

  private final QProfileExporters exporters;

  private final Languages languages;

  public ExportAction(DbClient dbClient, QProfileBackuper backuper, QProfileExporters exporters, Languages languages) {
    this.dbClient = dbClient;
    this.backuper = backuper;
    this.exporters = exporters;
    this.languages = languages;
  }

  @Override
  public void define(WebService.NewController controller) {
    NewAction action = controller.createAction("export")
      .setSince("5.2")
      .setDescription("Export a quality profile.")
      .setResponseExample(getClass().getResource("export-example.xml"))
      .setHandler(this);

    action.createParam(PARAM_PROFILE_NAME)
      .setDescription("The name of the quality profile to export. If left empty, will export the default profile for the language.")
      .setExampleValue("My Sonar way");

    action.createParam(PARAM_LANGUAGE)
      .setDescription("The language for the quality profile.")
      .setExampleValue(LanguageParamUtils.getExampleValue(languages))
      .setPossibleValues(LanguageParamUtils.getLanguageKeys(languages))
      .setRequired(true);

    Set<String> exporterKeys = Arrays.stream(languages.all())
      .map(language -> exporters.exportersForLanguage(language.getKey()))
      .flatMap(Collection::stream)
      .map(ProfileExporter::getKey)
      .collect(Collectors.toSet());
    if (!exporterKeys.isEmpty()) {
      action.createParam(PARAM_FORMAT)
        .setDescription("Output format. If left empty, the same format as api/qualityprofiles/backup is used. " +
          "Possible values are described by api/qualityprofiles/exporters.")
        .setPossibleValues(exporterKeys)
        // This deprecated key is only there to be able to deal with redirection from /profiles/export
        .setDeprecatedKey("format", "6.3");
    }
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    String name = request.param(PARAM_PROFILE_NAME);
    String language = request.mandatoryParam(PARAM_LANGUAGE);
    String exporterKey = exporters.exportersForLanguage(language).isEmpty() ? null : request.param(PARAM_FORMAT);
    Stream stream = response.stream();
    try (DbSession dbSession = dbClient.openSession(false);
      OutputStream output = stream.output();
      Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
      QualityProfileDto profile = getProfile(dbSession, name, language);
      if (exporterKey == null) {
        stream.setMediaType(MediaTypes.XML);
        backuper.backup(dbSession, profile, writer);
      } else {
        stream.setMediaType(exporters.mimeType(exporterKey));
        exporters.export(profile, exporterKey, writer);
      }
    }
  }

  private QualityProfileDto getProfile(DbSession dbSession, @Nullable String name, String language) {
    QualityProfileDto profile = name == null ? dbClient.qualityProfileDao().selectDefaultProfile(dbSession, language)
      : dbClient.qualityProfileDao().selectByNameAndLanguage(name, language, dbSession);
    return checkFound(profile, "Could not find profile with name '%s' for language '%s'", name, language);
  }

}
