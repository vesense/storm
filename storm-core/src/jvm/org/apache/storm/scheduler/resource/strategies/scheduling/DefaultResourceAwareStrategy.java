/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.scheduler.resource.strategies.scheduling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Iterator;

import org.apache.storm.scheduler.resource.ClusterStateData.NodeDetails;
import org.apache.storm.scheduler.resource.ClusterStateData;
import org.apache.storm.scheduler.resource.SchedulingResult;
import org.apache.storm.scheduler.resource.SchedulingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.storm.scheduler.ExecutorDetails;
import org.apache.storm.scheduler.TopologyDetails;
import org.apache.storm.scheduler.WorkerSlot;
import org.apache.storm.scheduler.resource.Component;

public class DefaultResourceAwareStrategy implements IStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultResourceAwareStrategy.class);
    private ClusterStateData _clusterStateData;
    //Map key is the supervisor id and the value is the corresponding RAS_Node Object
    private Map<String, NodeDetails> _availNodes;
    private NodeDetails refNode = null;
    /**
     * supervisor id -> Node
     */
    private Map<String, NodeDetails> _nodes;
    private Map<String, List<String>> _clusterInfo;

    private final double CPU_WEIGHT = 1.0;
    private final double MEM_WEIGHT = 1.0;
    private final double NETWORK_WEIGHT = 1.0;

    public void prepare (ClusterStateData clusterStateData) {
        _clusterStateData = clusterStateData;
        _nodes = clusterStateData.nodes;
        _availNodes = this.getAvailNodes();
        _clusterInfo = _clusterStateData.getNetworkTopography();
        LOG.debug(this.getClusterInfo());
    }

    //the returned TreeMap keeps the Components sorted
    private TreeMap<Integer, List<ExecutorDetails>> getPriorityToExecutorDetailsListMap(
            Queue<Component> ordered__Component_list, Collection<ExecutorDetails> unassignedExecutors) {
        TreeMap<Integer, List<ExecutorDetails>> retMap = new TreeMap<>();
        Integer rank = 0;
        for (Component ras_comp : ordered__Component_list) {
            retMap.put(rank, new ArrayList<ExecutorDetails>());
            for(ExecutorDetails exec : ras_comp.execs) {
                if(unassignedExecutors.contains(exec)) {
                    retMap.get(rank).add(exec);
                }
            }
            rank++;
        }
        return retMap;
    }

    public SchedulingResult schedule(TopologyDetails td) {
        if (_availNodes.size() <= 0) {
            LOG.warn("No available nodes to schedule tasks on!");
            return SchedulingResult.failure(SchedulingStatus.FAIL_NOT_ENOUGH_RESOURCES, "No available nodes to schedule tasks on!");
        }
        Collection<ExecutorDetails> unassignedExecutors = _clusterStateData.getUnassignedExecutors(td.getId());
        Map<WorkerSlot, Collection<ExecutorDetails>> schedulerAssignmentMap = new HashMap<>();
        LOG.debug("ExecutorsNeedScheduling: {}", unassignedExecutors);
        Collection<ExecutorDetails> scheduledTasks = new ArrayList<>();
        List<Component> spouts = this.getSpouts(td);

        if (spouts.size() == 0) {
            LOG.error("Cannot find a Spout!");
            return SchedulingResult.failure(SchedulingStatus.FAIL_INVALID_TOPOLOGY, "Cannot find a Spout!");
        }

        Queue<Component> ordered__Component_list = bfs(td, spouts);

        Map<Integer, List<ExecutorDetails>> priorityToExecutorMap = getPriorityToExecutorDetailsListMap(ordered__Component_list, unassignedExecutors);
        Collection<ExecutorDetails> executorsNotScheduled = new HashSet<>(unassignedExecutors);
        Integer longestPriorityListSize = this.getLongestPriorityListSize(priorityToExecutorMap);
        //Pick the first executor with priority one, then the 1st exec with priority 2, so on an so forth. 
        //Once we reach the last priority, we go back to priority 1 and schedule the second task with priority 1.
        for (int i = 0; i < longestPriorityListSize; i++) {
            for (Entry<Integer, List<ExecutorDetails>> entry : priorityToExecutorMap.entrySet()) {
                Iterator<ExecutorDetails> it = entry.getValue().iterator();
                if (it.hasNext()) {
                    ExecutorDetails exec = it.next();
                    LOG.debug("\n\nAttempting to schedule: {} of component {}[avail {}] with rank {}",
                            new Object[] { exec, td.getExecutorToComponent().get(exec),
                    td.getTaskResourceReqList(exec), entry.getKey() });
                    scheduleExecutor(exec, td, schedulerAssignmentMap, scheduledTasks);
                    it.remove();
                }
            }
        }

        executorsNotScheduled.removeAll(scheduledTasks);
        LOG.debug("/* Scheduling left over task (most likely sys tasks) */");
        // schedule left over system tasks
        for (ExecutorDetails exec : executorsNotScheduled) {
            scheduleExecutor(exec, td, schedulerAssignmentMap, scheduledTasks);
        }

        SchedulingResult result;
        executorsNotScheduled.removeAll(scheduledTasks);
        if (executorsNotScheduled.size() > 0) {
            LOG.error("Not all executors successfully scheduled: {}",
                    executorsNotScheduled);
            schedulerAssignmentMap = null;
            result = SchedulingResult.failure(SchedulingStatus.FAIL_NOT_ENOUGH_RESOURCES,
                    (td.getExecutors().size() - unassignedExecutors.size()) + "/" + td.getExecutors().size() + " executors scheduled");
        } else {
            LOG.debug("All resources successfully scheduled!");
            result = SchedulingResult.successWithMsg(schedulerAssignmentMap, "Fully Scheduled by DefaultResourceAwareStrategy");
        }
        if (schedulerAssignmentMap == null) {
            LOG.error("Topology {} not successfully scheduled!", td.getId());
        }
        return result;
    }

    private void scheduleExecutor(ExecutorDetails exec, TopologyDetails td, Map<WorkerSlot,
            Collection<ExecutorDetails>> schedulerAssignmentMap, Collection<ExecutorDetails> scheduledTasks) {
        WorkerSlot targetSlot = this.findWorkerForExec(exec, td, schedulerAssignmentMap);
        if (targetSlot != null) {
            NodeDetails targetNode = this.idToNode(targetSlot.getNodeId());
            if (!schedulerAssignmentMap.containsKey(targetSlot)) {
                schedulerAssignmentMap.put(targetSlot, new LinkedList<ExecutorDetails>());
            }

            schedulerAssignmentMap.get(targetSlot).add(exec);
            targetNode.consumeResourcesforTask(exec, td);
            scheduledTasks.add(exec);
            LOG.debug("TASK {} assigned to Node: {} avail [mem: {} cpu: {}] total [mem: {} cpu: {}] on slot: {}", exec,
                    targetNode, targetNode.getAvailableMemoryResources(),
                    targetNode.getAvailableCpuResources(), targetNode.getTotalMemoryResources(),
                    targetNode.getTotalCpuResources(), targetSlot);
        } else {
            LOG.error("Not Enough Resources to schedule Task {}", exec);
        }
    }

    private WorkerSlot findWorkerForExec(ExecutorDetails exec, TopologyDetails td, Map<WorkerSlot, Collection<ExecutorDetails>> scheduleAssignmentMap) {
      WorkerSlot ws;
      // first scheduling
      if (this.refNode == null) {
          String clus = this.getBestClustering();
          ws = this.getBestWorker(exec, td, clus, scheduleAssignmentMap);
      } else {
          ws = this.getBestWorker(exec, td, scheduleAssignmentMap);
      }
      if(ws != null) {
          this.refNode = this.idToNode(ws.getNodeId());
      }
      LOG.debug("reference node for the resource aware scheduler is: {}", this.refNode);
      return ws;
    }

    private WorkerSlot getBestWorker(ExecutorDetails exec, TopologyDetails td, Map<WorkerSlot, Collection<ExecutorDetails>> scheduleAssignmentMap) {
        return this.getBestWorker(exec, td, null, scheduleAssignmentMap);
    }

    private WorkerSlot getBestWorker(ExecutorDetails exec, TopologyDetails td, String clusterId, Map<WorkerSlot, Collection<ExecutorDetails>> scheduleAssignmentMap) {
        double taskMem = td.getTotalMemReqTask(exec);
        double taskCPU = td.getTotalCpuReqTask(exec);
        List<NodeDetails> nodes;
        if(clusterId != null) {
            nodes = this.getAvailableNodesFromCluster(clusterId);
            
        } else {
            nodes = this.getAvailableNodes();
        }
        //First sort nodes by distance
        TreeMap<Double, NodeDetails> nodeRankMap = new TreeMap<>();
        for (NodeDetails n : nodes) {
            if(n.getFreeSlots().size()>0) {
                if (n.getAvailableMemoryResources() >= taskMem
                        && n.getAvailableCpuResources() >= taskCPU) {
                    double a = Math.pow(((taskCPU - n.getAvailableCpuResources())/(n.getAvailableCpuResources() + 1))
                            * this.CPU_WEIGHT, 2);
                    double b = Math.pow(((taskMem - n.getAvailableMemoryResources())/(n.getAvailableMemoryResources() + 1))
                            * this.MEM_WEIGHT, 2);
                    double c = 0.0;
                    if(this.refNode != null) {
                        c = Math.pow(this.distToNode(this.refNode, n)
                                * this.NETWORK_WEIGHT, 2);
                    }
                    double distance = Math.sqrt(a + b + c);
                    nodeRankMap.put(distance, n);
                }
            }
        }
        //Then, pick worker from closest node that satisfy constraints
        for(Map.Entry<Double, NodeDetails> entry : nodeRankMap.entrySet()) {
            NodeDetails n = entry.getValue();
            for(WorkerSlot ws : n.getFreeSlots()) {
                if(checkWorkerConstraints(exec, ws, td, scheduleAssignmentMap)) {
                    return ws;
                }
            }
        }
        return null;
    }

    private String getBestClustering() {
        String bestCluster = null;
        Double mostRes = 0.0;
        for (Entry<String, List<String>> cluster : _clusterInfo
                .entrySet()) {
            Double clusterTotalRes = this.getTotalClusterRes(cluster.getValue());
            if (clusterTotalRes > mostRes) {
                mostRes = clusterTotalRes;
                bestCluster = cluster.getKey();
            }
        }
        return bestCluster;
    }

    private Double getTotalClusterRes(List<String> cluster) {
        Double res = 0.0;
        for (String node : cluster) {
            res += _availNodes.get(this.NodeHostnameToId(node))
                    .getAvailableMemoryResources()
                    + _availNodes.get(this.NodeHostnameToId(node))
                    .getAvailableCpuResources();
        }
        return res;
    }

    private Double distToNode(NodeDetails src, NodeDetails dest) {
        if (src.getId().equals(dest.getId())) {
            return 0.0;
        } else if (this.NodeToCluster(src).equals(this.NodeToCluster(dest))) {
            return 0.5;
        } else {
            return 1.0;
        }
    }

    private String NodeToCluster(NodeDetails node) {
        for (Entry<String, List<String>> entry : _clusterInfo
                .entrySet()) {
            if (entry.getValue().contains(node.getHostname())) {
                return entry.getKey();
            }
        }
        LOG.error("Node: {} not found in any clusters", node.getHostname());
        return null;
    }
    
    private List<NodeDetails> getAvailableNodes() {
        LinkedList<NodeDetails> nodes = new LinkedList<>();
        for (String clusterId : _clusterInfo.keySet()) {
            nodes.addAll(this.getAvailableNodesFromCluster(clusterId));
        }
        return nodes;
    }

    private List<NodeDetails> getAvailableNodesFromCluster(String clus) {
        List<NodeDetails> retList = new ArrayList<>();
        for (String node_id : _clusterInfo.get(clus)) {
            retList.add(_availNodes.get(this
                    .NodeHostnameToId(node_id)));
        }
        return retList;
    }

    private List<WorkerSlot> getAvailableWorkersFromCluster(String clusterId) {
        List<NodeDetails> nodes = this.getAvailableNodesFromCluster(clusterId);
        List<WorkerSlot> workers = new LinkedList<>();
        for(NodeDetails node : nodes) {
            workers.addAll(node.getFreeSlots());
        }
        return workers;
    }

    private List<WorkerSlot> getAvailableWorker() {
        List<WorkerSlot> workers = new LinkedList<>();
        for (String clusterId : _clusterInfo.keySet()) {
            workers.addAll(this.getAvailableWorkersFromCluster(clusterId));
        }
        return workers;
    }

    /**
     * In case in the future RAS can only use a subset of nodes
     */
    private Map<String, NodeDetails> getAvailNodes() {
        return _nodes;
    }

    /**
     * Breadth first traversal of the topology DAG
     * @param td
     * @param spouts
     * @return A partial ordering of components
     */
    private Queue<Component> bfs(TopologyDetails td, List<Component> spouts) {
        // Since queue is a interface
        Queue<Component> ordered__Component_list = new LinkedList<Component>();
        HashMap<String, Component> visited = new HashMap<>();

        /* start from each spout that is not visited, each does a breadth-first traverse */
        for (Component spout : spouts) {
            if (!visited.containsKey(spout.id)) {
                Queue<Component> queue = new LinkedList<>();
                queue.offer(spout);
                while (!queue.isEmpty()) {
                    Component comp = queue.poll();
                    visited.put(comp.id, comp);
                    ordered__Component_list.add(comp);
                    List<String> neighbors = new ArrayList<>();
                    neighbors.addAll(comp.children);
                    neighbors.addAll(comp.parents);
                    for (String nbID : neighbors) {
                        if (!visited.containsKey(nbID)) {
                            Component child = td.getComponents().get(nbID);
                            queue.offer(child);
                        }
                    }
                }
            }
        }
        return ordered__Component_list;
    }

    private List<Component> getSpouts(TopologyDetails td) {
        List<Component> spouts = new ArrayList<>();

        for (Component c : td.getComponents().values()) {
            if (c.type == Component.ComponentType.SPOUT) {
                spouts.add(c);
            }
        }
        return spouts;
    }

    private Integer getLongestPriorityListSize(Map<Integer, List<ExecutorDetails>> priorityToExecutorMap) {
        Integer mostNum = 0;
        for (List<ExecutorDetails> execs : priorityToExecutorMap.values()) {
            Integer numExecs = execs.size();
            if (mostNum < numExecs) {
                mostNum = numExecs;
            }
        }
        return mostNum;
    }

    /**
     * Get the remaining amount memory that can be assigned to a worker given the set worker max heap size
     * @param ws
     * @param td
     * @param scheduleAssignmentMap
     * @return The remaining amount of memory
     */
    private Double getWorkerScheduledMemoryAvailable(WorkerSlot ws, TopologyDetails td, Map<WorkerSlot, Collection<ExecutorDetails>> scheduleAssignmentMap) {
        Double memScheduleUsed = this.getWorkerScheduledMemoryUse(ws, td, scheduleAssignmentMap);
        return td.getTopologyWorkerMaxHeapSize() - memScheduleUsed;
    }

    /**
     * Get the amount of memory already assigned to a worker
     * @param ws
     * @param td
     * @param scheduleAssignmentMap
     * @return the amount of memory
     */
    private Double getWorkerScheduledMemoryUse(WorkerSlot ws, TopologyDetails td, Map<WorkerSlot, Collection<ExecutorDetails>> scheduleAssignmentMap) {
        Double totalMem = 0.0;
        Collection<ExecutorDetails> execs = scheduleAssignmentMap.get(ws);
        if(execs != null) {
            for(ExecutorDetails exec : execs) {
                totalMem += td.getTotalMemReqTask(exec);
            }
        } 
        return totalMem;
    }

    /**
     * Checks whether we can schedule an Executor exec on the worker slot ws
     * Only considers memory currently.  May include CPU in the future
     * @param exec
     * @param ws
     * @param td
     * @param scheduleAssignmentMap
     * @return a boolean: True denoting the exec can be scheduled on ws and false if it cannot
     */
    private boolean checkWorkerConstraints(ExecutorDetails exec, WorkerSlot ws, TopologyDetails td, Map<WorkerSlot, Collection<ExecutorDetails>> scheduleAssignmentMap) {
        boolean retVal = false;
        if(this.getWorkerScheduledMemoryAvailable(ws, td, scheduleAssignmentMap) >= td.getTotalMemReqTask(exec)) {
            retVal = true;
        }
        return retVal;
    }

    /**
     * Get the amount of resources available and total for each node
     * @return a String with cluster resource info for debug
     */
    private String getClusterInfo() {
        String retVal = "Cluster info:\n";
        for(Entry<String, List<String>> clusterEntry : _clusterInfo.entrySet()) {
            String clusterId = clusterEntry.getKey();
            retVal += "Rack: " + clusterId + "\n";
            for(String nodeHostname : clusterEntry.getValue()) {
                NodeDetails node = this.idToNode(this.NodeHostnameToId(nodeHostname));
                retVal += "-> Node: " + node.getHostname() + " " + node.getId() + "\n";
                retVal += "--> Avail Resources: {Mem " + node.getAvailableMemoryResources() + ", CPU " + node.getAvailableCpuResources() + "}\n";
                retVal += "--> Total Resources: {Mem " + node.getTotalMemoryResources() + ", CPU " + node.getTotalCpuResources() + "}\n";
            }
        }
        return retVal;
    }

    /**
     * hostname to Id
     * @param hostname
     * @return the id of a node
     */
    public String NodeHostnameToId(String hostname) {
        for (NodeDetails n : _nodes.values()) {
            if (n.getHostname() == null) {
                continue;
            }
            if (n.getHostname().equals(hostname)) {
                return n.getId();
            }
        }
        LOG.error("Cannot find Node with hostname {}", hostname);
        return null;
    }

    /**
     * Find RAS_Node for specified node id
     * @param id
     * @return a RAS_Node object
     */
    public NodeDetails idToNode(String id) {
        if(_nodes.containsKey(id) == false) {
            LOG.error("Cannot find Node with Id: {}", id);
            return null;
        }
        return _nodes.get(id);
    }
}
