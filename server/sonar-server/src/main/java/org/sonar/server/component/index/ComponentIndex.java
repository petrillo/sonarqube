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

import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.filters.FiltersAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filters.InternalFilters;
import org.elasticsearch.search.aggregations.bucket.filters.InternalFilters.Bucket;
import org.elasticsearch.search.aggregations.metrics.tophits.InternalTopHits;
import org.elasticsearch.search.aggregations.metrics.tophits.TopHitsBuilder;
import org.sonar.core.util.stream.Collectors;
import org.sonar.server.es.DefaultIndexSettingsElement;
import org.sonar.server.es.EsClient;
import org.sonar.server.es.textsearch.ComponentTextSearchFeature;
import org.sonar.server.es.textsearch.ComponentTextSearchQueryFactory;
import org.sonar.server.es.textsearch.ComponentTextSearchQueryFactory.ComponentTextSearchQuery;
import org.sonar.server.permission.index.AuthorizationTypeSupport;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.index.query.QueryBuilders.termsQuery;
import static org.elasticsearch.search.sort.SortOrder.ASC;
import static org.elasticsearch.search.sort.SortOrder.DESC;
import static org.sonar.server.component.index.ComponentIndexDefinition.FIELD_KEY;
import static org.sonar.server.component.index.ComponentIndexDefinition.FIELD_NAME;
import static org.sonar.server.component.index.ComponentIndexDefinition.FIELD_QUALIFIER;
import static org.sonar.server.component.index.ComponentIndexDefinition.INDEX_TYPE_COMPONENT;

public class ComponentIndex {

  private static final String FILTERS_AGGREGATION_NAME = "filters";
  private static final String DOCS_AGGREGATION_NAME = "docs";

  private final EsClient client;
  private final AuthorizationTypeSupport authorizationTypeSupport;

  public ComponentIndex(EsClient client, AuthorizationTypeSupport authorizationTypeSupport) {
    this.client = client;
    this.authorizationTypeSupport = authorizationTypeSupport;
  }

  public List<ComponentsPerQualifier> search(ComponentIndexQuery query) {
    return search(query, ComponentTextSearchFeature.values());
  }

  @VisibleForTesting
  List<ComponentsPerQualifier> search(ComponentIndexQuery query, ComponentTextSearchFeature... features) {
    Collection<String> qualifiers = query.getQualifiers();
    if (qualifiers.isEmpty()) {
      return Collections.emptyList();
    }

    SearchRequestBuilder request = client
      .prepareSearch(INDEX_TYPE_COMPONENT)
      .setQuery(createQuery(query, features))
      .addAggregation(createAggregation(query))

      // the search hits are part of the aggregations
      .setSize(0);

    SearchResponse response = request.get();
    return aggregationsToQualifiers(response);
  }

  private static FiltersAggregationBuilder createAggregation(ComponentIndexQuery query) {
    FiltersAggregationBuilder filtersAggregation = AggregationBuilders.filters(FILTERS_AGGREGATION_NAME)
      .subAggregation(createSubAggregation(query));
    query.getQualifiers().forEach(q -> filtersAggregation.filter(q, termQuery(FIELD_QUALIFIER, q)));
    return filtersAggregation;
  }

  private static TopHitsBuilder createSubAggregation(ComponentIndexQuery query) {
    TopHitsBuilder sub = AggregationBuilders.topHits(DOCS_AGGREGATION_NAME);
    addSort(query, sub);
    query.getLimit().ifPresent(sub::setSize);
    return sub.setFetchSource(false);
  }

  private QueryBuilder createQuery(ComponentIndexQuery query, ComponentTextSearchFeature... features) {
    BoolQueryBuilder esQuery = boolQuery();
    esQuery.filter(authorizationTypeSupport.createQueryFilter());
    Set<String> componentUuids = query.getComponentUuids();
    if (!componentUuids.isEmpty()) {
      esQuery.must(termsQuery("_id", componentUuids));
    }
    String textQuery = query.getQuery();
    if (textQuery == null) {
      return esQuery.must(QueryBuilders.matchAllQuery());
    }
    ComponentTextSearchQuery componentTextSearchQuery = ComponentTextSearchQuery.builder()
      .setQueryText(textQuery)
      .setFieldKey(FIELD_KEY)
      .setFieldName(FIELD_NAME)
      .build();
    return esQuery.must(ComponentTextSearchQueryFactory.createQuery(componentTextSearchQuery, features));
  }

  private static List<ComponentsPerQualifier> aggregationsToQualifiers(SearchResponse response) {
    InternalFilters filtersAgg = response.getAggregations().get(FILTERS_AGGREGATION_NAME);
    List<Bucket> buckets = filtersAgg.getBuckets();
    return buckets.stream()
      .map(ComponentIndex::bucketToQualifier)
      .collect(Collectors.toList(buckets.size()));
  }

  private static ComponentsPerQualifier bucketToQualifier(Bucket bucket) {
    InternalTopHits docs = bucket.getAggregations().get(DOCS_AGGREGATION_NAME);

    SearchHits hitList = docs.getHits();
    SearchHit[] hits = hitList.getHits();

    List<String> componentUuids = Arrays.stream(hits).map(SearchHit::getId)
      .collect(Collectors.toList(hits.length));

    return new ComponentsPerQualifier(bucket.getKey(), componentUuids, hitList.totalHits());
  }

  private static void addSort(ComponentIndexQuery query, TopHitsBuilder topHitsBuilder) {
    if (query.getSort().equals(ComponentIndexQuery.Sort.NAME)) {
      topHitsBuilder.addSort(DefaultIndexSettingsElement.SORTABLE_ANALYZER.subField(FIELD_NAME), query.isAsc() ? ASC : DESC);
      // last sort is by key in order to be deterministic when same value
      topHitsBuilder.addSort(FIELD_KEY, ASC);
    }
    // Default sort is done by score
  }
}
