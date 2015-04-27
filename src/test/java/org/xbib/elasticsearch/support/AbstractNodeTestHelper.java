/*
 * Copyright (C) 2015 Jörg Prante
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.xbib.elasticsearch.support;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.node.Node;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.transport.TransportInfo;
import org.testng.Assert;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.common.collect.Maps.newHashMap;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.node.NodeBuilder.nodeBuilder;

public abstract class AbstractNodeTestHelper extends Assert {

    protected final static Logger logger = LogManager.getLogger("test");

    private final static AtomicInteger clusterCount = new AtomicInteger();

    private String cluster;

    // note, this must be same name as in json river specs
    protected final String index = "my_index";

    protected final String type = "my_type";

    private Map<String, Node> nodes = newHashMap();

    private Map<String, Client> clients = newHashMap();

    protected void setClusterName() {
        this.cluster = "test-jdbc-cluster-" + NetworkUtils.getLocalAddress().getHostName() + "-" + clusterCount.incrementAndGet();
    }

    protected String getClusterName() {
        return cluster;
    }

    private List<String> hosts;

    protected String[] getHosts() {
        return hosts != null ? hosts.toArray(new String[hosts.size()]) : new String[]{};
    }

    protected Settings getNodeSettings() {
        return ImmutableSettings
                .settingsBuilder()
                .put("cluster.name", getClusterName())
                .put("index.number_of_shards", 1)
                .put("index.number_of_replica", 0)
                .put("cluster.routing.schedule", "50ms")
                .put("gateway.type", "none")
                .put("index.store.type", "ram")
                .put("http.enabled", false)
                .put("discovery.zen.multicast.enabled", true)
                .build();
    }

    public void startNodes() throws Exception {
        setClusterName();

        // we need more than one node, for better resilience testing
        startNode("1");
        startNode("2");

        // find node address
        NodesInfoRequest nodesInfoRequest = new NodesInfoRequest().transport(true);
        NodesInfoResponse response = client("1").admin().cluster().nodesInfo(nodesInfoRequest).actionGet();
        Iterator<NodeInfo> it = response.iterator();
        hosts = new LinkedList<>();
        while (it.hasNext()) {
            NodeInfo nodeInfo = it.next();
            TransportInfo transportInfo = nodeInfo.getTransport();
            TransportAddress address = transportInfo.getAddress().publishAddress();
            if (address instanceof InetSocketTransportAddress) {
                InetSocketTransportAddress inetSocketTransportAddress = (InetSocketTransportAddress) address;
                hosts.add(inetSocketTransportAddress.address().getHostName() + ":" + inetSocketTransportAddress.address().getPort());
            }
        }
    }

    public Node startNode(String id) {
        return buildNode(id).start();
    }

    public Node buildNode(String id) {
        String settingsSource = getClass().getName().replace('.', '/') + ".yml";
        Settings finalSettings = settingsBuilder()
                .loadFromClasspath(settingsSource)
                .put(getNodeSettings())
                .put("name", id)
                .build();
        Node node = nodeBuilder().settings(finalSettings).build();
        Client client = node.client();
        nodes.put(id, node);
        clients.put(id, client);
        return node;
    }

    public void waitForYellow(String id) throws IOException {
        logger.info("wait for healthy cluster...");
        ClusterHealthResponse clusterHealthResponse = client(id).admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet();
        if (clusterHealthResponse.isTimedOut()) {
            throw new IOException("error, cluster health is " + clusterHealthResponse.getStatus().name());
        }
        logger.info("cluster health is {}", clusterHealthResponse.getStatus().name());
    }

    public void assertHits(String id, int expectedHits) {
        client(id).admin().indices().prepareRefresh(index).execute().actionGet();
        long hitsFound = client(id).prepareSearch(index).setTypes(type).execute().actionGet().getHits().getTotalHits();
        logger.info("{}/{} = {} hits", index, type, hitsFound);
        assertEquals(hitsFound, expectedHits);
    }

    public void assertTimestampSort(String id, int expectedHits) {
        client(id).admin().indices().prepareRefresh(index).execute().actionGet();
        QueryBuilder queryBuilder = QueryBuilders.matchAllQuery();
        SortBuilder sortBuilder = SortBuilders.fieldSort("_timestamp").order(SortOrder.DESC);
        SearchHits hits = client(id).prepareSearch(index).setTypes(type)
                .setQuery(queryBuilder)
                .addSort(sortBuilder)
                .addFields("_source", "_timestamp")
                .setSize(expectedHits)
                .execute().actionGet().getHits();
        Long prev = Long.MAX_VALUE;
        for (SearchHit hit : hits) {
            if (hit.getFields().get("_timestamp") == null) {
                logger.warn("type mapping was not correctly applied for _timestamp field");
            }
            Long curr = hit.getFields().get("_timestamp").getValue();
            logger.info("timestamp = {}", curr);
            assertTrue(curr <= prev);
            prev = curr;
        }
        logger.info("{}/{} = {} hits", index, type, hits.getTotalHits());
        assertEquals(hits.getTotalHits(), expectedHits);
    }

    public Client client(String id) {
        Client client = clients.get(id);
        if (client == null) {
            client = nodes.get(id).client();
            clients.put(id, client);
        }
        return client;
    }

    public void stopNodes() {
        for (Client client : clients.values()) {
            client.close();
        }
        clients.clear();
        for (Node node : nodes.values()) {
            node.stop();
            node.close();
        }
        nodes.clear();
    }

}
