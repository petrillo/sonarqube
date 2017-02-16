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
package org.sonar.server.component.index;

import java.util.Collection;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;

import static java.util.Arrays.asList;
import static org.sonar.api.resources.Qualifiers.FILE;
import static org.sonar.api.resources.Qualifiers.MODULE;
import static org.sonar.api.resources.Qualifiers.PROJECT;

public class ComponentIndexScoreOverShardsTest extends ComponentIndexTest {

  // TODO next things to try:
  // 1) enable BM25 EVERYWHERE (which will be a step in the direction of es 5 anyways)
  // 2) check, if manipulating the query helps

  @Test
  public void scoring_perfect_match_dispite_inserting_36_items_in_one_shard() {
    assertShardEffectNotToAppearFor("sonarqube", 36);
  }

  @Test
  public void scoring_perfect_match_dispite_inserting_37_items_in_one_shard() {
    assertShardEffectNotToAppearFor("sonarqube", 37);
  }

  @Test
  public void scoring_perfect_match_dispite_inserting_285_items_in_one_shard() {
    assertShardEffectNotToAppearFor("sonarqube", 285);
  }

  @Test
  public void scoring_perfect_match_dispite_inserting_286_items_in_one_shard() {
    assertShardEffectNotToAppearFor("sonarqube", 286);
  }

  @Test
  public void scoring_prefix_match_dispite_inserting_1_items_in_one_shard() {
    assertShardEffectNotToAppearFor("sonarqub", 1);
  }

  @Test
  public void scoring_prefix_match_dispite_inserting_2_items_in_one_shard() {
    assertShardEffectNotToAppearFor("sonarqub", 2);
  }

  private void assertShardEffectNotToAppearFor(String queryText, int numberOfExtraDocumentsInOneShard) {
    // project1 is a very good match, but we will insert more documents in the shard, to reduce the relevancy
    ComponentDto project1 = indexProject("org.sonarsource.sonarqube:sonarqube", "SonarQube");

    // project2 is not a good match, but the shard does not contain any other documents
    ComponentDto project2 = indexProject("org.sonarsource.scm.git:sonar-scm-git", "SonarQube SCM Git");

    // add documents to project1, until the search scoring gets wrong
    for (int i = 0; i < numberOfExtraDocumentsInOneShard; i++) {

      // check, that project1 is scored better than project2
      Optional<String> result = getTopHit(queryText);

      String caseDescription = ", after " + i + " document" + (i == 1 ? "" : "s") + " had been added to the other shard";
      if (result.isPresent()) {
        String uuid = result.get();
        if (project1.uuid().equals(uuid)) {
          // this is expected
        } else if (project2.uuid().equals(uuid)) {
          // search scoring is wrong
          Assert.fail("The worse project got scored higher" + caseDescription);
        } else {
          Assert.fail("Found unexpected document " + uuid + caseDescription);
        }
      } else {
        Assert.fail("Did not find any document" + caseDescription);
      }

      // add document to the shard of project1
      indexer.index(ComponentTesting.newFileDto(project1)
        .setName("SonarQube")
        .setKey("java/org/example/" + "SonarQube-" + i + ".txt")
        .setUuid("UUID-" + i));
    }
  }

  private Optional<String> getTopHit(String queryText) {
    ComponentIndexQuery query = new ComponentIndexQuery(queryText).setQualifiers(asList(PROJECT, MODULE, FILE)).setLimit(1);
    Optional<String> results = index.search(query, features.get())
      .stream().map(ComponentsPerQualifier::getComponentUuids)
      .flatMap(Collection::stream)
      .findFirst();
    return results;
  }
}
