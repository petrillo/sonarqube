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

import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import javax.annotation.CheckForNull;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public class ComponentIndexQuery {

  public enum Sort {
    NAME, SCORE
  }

  private String query;
  private Collection<String> qualifiers = Collections.emptyList();
  private Set<String> componentUuids = Collections.emptySet();
  private Optional<Integer> limit = Optional.empty();
  private Sort sort = Sort.SCORE;
  private boolean asc = true;

  public ComponentIndexQuery setQuery(String query) {
    requireNonNull(query, "Query cannot be null");
    checkArgument(query.length() >= 2, "Query must be at least two characters long: %s", query);
    this.query = query;
    return this;
  }

  public ComponentIndexQuery setQualifiers(Collection<String> qualifiers) {
    this.qualifiers = Collections.unmodifiableCollection(qualifiers);
    return this;
  }

  public Collection<String> getQualifiers() {
    return qualifiers;
  }

  public Set<String> getComponentUuids() {
    return componentUuids;
  }

  public ComponentIndexQuery setComponentUuids(Set<String> componentUuids) {
    this.componentUuids = ImmutableSet.copyOf(requireNonNull(componentUuids, "Component uuids cannot be null"));
    return this;
  }

  @CheckForNull
  public String getQuery() {
    return query;
  }

  public Sort getSort() {
    return sort;
  }

  public ComponentIndexQuery setSort(Sort sort) {
    this.sort = requireNonNull(sort, "Sort cannot be null");
    return this;
  }

  public boolean isAsc() {
    return asc;
  }

  public ComponentIndexQuery setAsc(boolean asc) {
    this.asc = asc;
    return this;
  }

  /**
   * The number of search hits to return per Qualifier. Defaults to <tt>10</tt>.
   */
  public ComponentIndexQuery setLimit(int limit) {
    checkArgument(limit >= 1, "Limit has to be strictly positive: %s", limit);
    this.limit = Optional.of(limit);
    return this;
  }

  public Optional<Integer> getLimit() {
    return limit;
  }
}
