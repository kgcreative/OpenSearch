/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.aggregations.bucket.geogrid;

import org.opensearch.geometry.utils.Geohash;
import org.opensearch.search.aggregations.InternalAggregations;

import java.util.List;
import java.util.Map;

public class GeoHashGridTests extends GeoGridTestCase<InternalGeoHashGridBucket, InternalGeoHashGrid> {

    @Override
    protected InternalGeoHashGrid createInternalGeoGrid(String name, int size, List<InternalGeoGridBucket> buckets,
                                                        Map<String, Object> metadata) {
        return new InternalGeoHashGrid(name, size, buckets, metadata);
    }

    @Override
    protected InternalGeoHashGridBucket createInternalGeoGridBucket(Long key, long docCount, InternalAggregations aggregations) {
        return new InternalGeoHashGridBucket(key, docCount, aggregations);
    }

    @Override
    protected long longEncode(double lng, double lat, int precision) {
        return Geohash.longEncode(lng, lat, precision);
    }

    @Override
    protected int randomPrecision() {
        return randomIntBetween(4, 12);
    }
}
