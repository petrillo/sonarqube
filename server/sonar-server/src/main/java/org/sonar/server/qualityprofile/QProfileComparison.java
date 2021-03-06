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
package org.sonar.server.qualityprofile;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.ce.ComputeEngineSide;
import org.sonar.api.server.ServerSide;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.qualityprofile.ActiveRuleDto;
import org.sonar.db.qualityprofile.ActiveRuleParamDto;
import org.sonar.db.qualityprofile.QualityProfileDto;

@ServerSide
@ComputeEngineSide
public class QProfileComparison {

  private final DbClient dbClient;

  public QProfileComparison(DbClient dbClient) {
    this.dbClient = dbClient;
  }

  public QProfileComparisonResult compare(String leftKey, String rightKey) {
    try (DbSession dbSession = dbClient.openSession(false)) {
      QProfileComparisonResult result = new QProfileComparisonResult();
      compare(dbSession, leftKey, rightKey, result);
      return result;
    }
  }

  private void compare(DbSession session, String leftKey, String rightKey, QProfileComparisonResult result) {
    result.left = dbClient.qualityProfileDao().selectByKey(session, leftKey);
    Preconditions.checkArgument(result.left != null, String.format("Could not find left profile '%s'", leftKey));
    result.right = dbClient.qualityProfileDao().selectByKey(session, rightKey);
    Preconditions.checkArgument(result.right != null, String.format("Could not find right profile '%s'", leftKey));

    Map<RuleKey, ActiveRuleDto> leftActiveRulesByRuleKey = loadActiveRules(session, leftKey);
    Map<RuleKey, ActiveRuleDto> rightActiveRulesByRuleKey = loadActiveRules(session, rightKey);

    Set<RuleKey> allRules = Sets.newHashSet();
    allRules.addAll(leftActiveRulesByRuleKey.keySet());
    allRules.addAll(rightActiveRulesByRuleKey.keySet());

    for (RuleKey ruleKey : allRules) {
      if (!leftActiveRulesByRuleKey.containsKey(ruleKey)) {
        result.inRight.put(ruleKey, rightActiveRulesByRuleKey.get(ruleKey));
      } else if (!rightActiveRulesByRuleKey.containsKey(ruleKey)) {
        result.inLeft.put(ruleKey, leftActiveRulesByRuleKey.get(ruleKey));
      } else {
        compareActivationParams(session, leftActiveRulesByRuleKey.get(ruleKey), rightActiveRulesByRuleKey.get(ruleKey), result);
      }
    }
  }

  private void compareActivationParams(DbSession session, ActiveRuleDto leftRule, ActiveRuleDto rightRule, QProfileComparisonResult result) {
    RuleKey key = leftRule.getKey().ruleKey();
    Map<String, String> leftParams = paramDtoToMap(dbClient.activeRuleDao().selectParamsByActiveRuleId(session, leftRule.getId()));
    Map<String, String> rightParams = paramDtoToMap(dbClient.activeRuleDao().selectParamsByActiveRuleId(session, rightRule.getId()));
    if (leftParams.equals(rightParams) && leftRule.getSeverityString().equals(rightRule.getSeverityString())) {
      result.same.put(key, leftRule);
    } else {
      ActiveRuleDiff diff = new ActiveRuleDiff();

      diff.leftSeverity = leftRule.getSeverityString();
      diff.rightSeverity = rightRule.getSeverityString();

      diff.paramDifference = Maps.difference(leftParams, rightParams);
      result.modified.put(key, diff);
    }
  }

  private Map<RuleKey, ActiveRuleDto> loadActiveRules(DbSession session, String profileKey) {
    return Maps.uniqueIndex(dbClient.activeRuleDao().selectByProfileKey(session, profileKey), ActiveRuleToRuleKey.INSTANCE);
  }

  public static class QProfileComparisonResult {

    private QualityProfileDto left;
    private QualityProfileDto right;
    private Map<RuleKey, ActiveRuleDto> inLeft = Maps.newHashMap();
    private Map<RuleKey, ActiveRuleDto> inRight = Maps.newHashMap();
    private Map<RuleKey, ActiveRuleDiff> modified = Maps.newHashMap();
    private Map<RuleKey, ActiveRuleDto> same = Maps.newHashMap();

    public QualityProfileDto left() {
      return left;
    }

    public QualityProfileDto right() {
      return right;
    }

    public Map<RuleKey, ActiveRuleDto> inLeft() {
      return inLeft;
    }

    public Map<RuleKey, ActiveRuleDto> inRight() {
      return inRight;
    }

    public Map<RuleKey, ActiveRuleDiff> modified() {
      return modified;
    }

    public Map<RuleKey, ActiveRuleDto> same() {
      return same;
    }

    public Collection<RuleKey> collectRuleKeys() {
      Set<RuleKey> keys = Sets.newHashSet();
      keys.addAll(inLeft.keySet());
      keys.addAll(inRight.keySet());
      keys.addAll(modified.keySet());
      keys.addAll(same.keySet());
      return keys;
    }
  }

  public static class ActiveRuleDiff {
    private String leftSeverity;
    private String rightSeverity;
    private MapDifference<String, String> paramDifference;

    public String leftSeverity() {
      return leftSeverity;
    }

    public String rightSeverity() {
      return rightSeverity;
    }

    public MapDifference<String, String> paramDifference() {
      return paramDifference;
    }
  }

  private enum ActiveRuleToRuleKey implements Function<ActiveRuleDto, RuleKey> {
    INSTANCE;

    @Override
    public RuleKey apply(@Nonnull ActiveRuleDto input) {
      return input.getKey().ruleKey();
    }
  }

  private static Map<String, String> paramDtoToMap(List<ActiveRuleParamDto> params) {
    Map<String, String> map = new HashMap<>();
    for (ActiveRuleParamDto dto : params) {
      map.put(dto.getKey(), dto.getValue());
    }
    return map;
  }

}
