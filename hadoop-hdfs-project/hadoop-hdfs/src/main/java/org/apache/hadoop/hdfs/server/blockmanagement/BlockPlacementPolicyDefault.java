/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.blockmanagement;

import static org.apache.hadoop.util.Time.now;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.server.namenode.FSClusterStats;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;

import com.google.common.annotations.VisibleForTesting;

/**
 * The class is responsible for choosing the desired number of targets
 * for placing block replicas.
 * The replica placement strategy is that if the writer is on a datanode,
 * the 1st replica is placed on the local machine, 
 * otherwise a random datanode. The 2nd replica is placed on a datanode
 * that is on a different rack. The 3rd replica is placed on a datanode
 * which is on a different node of the rack as the second replica.
 */
@InterfaceAudience.Private
public class BlockPlacementPolicyDefault extends BlockPlacementPolicy {

  private static final Log LOG =
    LogFactory.getLog(BlockPlacementPolicyDefault.class.getName());

  private static final String enableDebugLogging =
    "For more information, please enable DEBUG log level on "
    + LOG.getClass().getName();

  private boolean considerLoad; 
  private boolean preferLocalNode = true;
  private NetworkTopology clusterMap;
  private FSClusterStats stats;
  private long heartbeatInterval;   // interval for DataNode heartbeats
  /**
   * A miss of that many heartbeats is tolerated for replica deletion policy.
   */
  private int tolerateHeartbeatMultiplier;

  BlockPlacementPolicyDefault(Configuration conf,  FSClusterStats stats,
                           NetworkTopology clusterMap) {
    initialize(conf, stats, clusterMap);
  }

  protected BlockPlacementPolicyDefault() {
  }
    
  @Override
  public void initialize(Configuration conf,  FSClusterStats stats,
                         NetworkTopology clusterMap) {
    this.considerLoad = conf.getBoolean(DFSConfigKeys.DFS_NAMENODE_REPLICATION_CONSIDERLOAD_KEY, true);
    this.stats = stats;
    this.clusterMap = clusterMap;
    this.heartbeatInterval = conf.getLong(
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY,
        DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_DEFAULT) * 1000;
    this.tolerateHeartbeatMultiplier = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_TOLERATE_HEARTBEAT_MULTIPLIER_KEY,
        DFSConfigKeys.DFS_NAMENODE_TOLERATE_HEARTBEAT_MULTIPLIER_DEFAULT);
  }

  private ThreadLocal<StringBuilder> threadLocalBuilder =
    new ThreadLocal<StringBuilder>() {
    @Override
    protected StringBuilder initialValue() {
      return new StringBuilder();
    }
  };

  @Override
  public DatanodeDescriptor[] chooseTarget(String srcPath,
                                    int numOfReplicas,
                                    DatanodeDescriptor writer,
                                    List<DatanodeDescriptor> chosenNodes,
                                    long blocksize, int bank) {
    return chooseTarget(numOfReplicas, writer, chosenNodes, false,
        null, blocksize, bank);
  }

  @Override
  public DatanodeDescriptor[] chooseTarget(String srcPath,
                                    int numOfReplicas,
                                    DatanodeDescriptor writer,
                                    List<DatanodeDescriptor> chosenNodes,
                                    boolean returnChosenNodes,
                                    HashMap<Node, Node> excludedNodes,
                                    long blocksize, int bank) {
    return chooseTarget(numOfReplicas, writer, chosenNodes, returnChosenNodes,
        excludedNodes, blocksize, bank);
  }

  private static void filterNodesByBank(List<DatanodeDescriptor> results, int bank) {
    // TODO: we should really be using a fallback here, so that if we run 
    // out of DNs that have our bank, we don't consider it a total failure.
    Iterator<DatanodeDescriptor> iter = results.iterator();
    while (iter.hasNext()) {
      DatanodeDescriptor d = iter.next();
      if (d.getBank() != bank) iter.remove();
    }
  }

  /**
   * This is just a callback that checks for a DN which has the same hostname as ours, 
   * but the correct bank.
   */
  class RebankingPredicate implements NetworkTopology.Predicate {
    private DatanodeDescriptor writer;
    private int bankNeeded;
    
    public RebankingPredicate(DatanodeDescriptor writer, int bankNeeded) {
      this.writer = writer;
    }
    
    @Override
    public boolean check(Node n) {
      DatanodeDescriptor dn = (DatanodeDescriptor)n;
      if (dn.getBank() != bankNeeded) {
        return false;
      }
      if (!dn.getHostName().equals(writer.getHostName())) {
        return false;
      }
      return true;
    }
  }
  
  /**
   * We use DatanodeDescriptors to represent the writers, even though 
   * the writers are DFSClients, not DataNodes at all.  I guess the idea is 
   * that both have a hostname.
   * 
   * When there are two or more DNs on the same hostname, the one we use to 
   * represent the writer is randomly chosen.  This normally works pretty well, 
   * since there is almost always one DN per cluster node.  However, in some cases, 
   * we really do care, since one of the DNs might have a different bank 
   * than the other.  If we have the DN with the 'wrong' bank for what this writer needs,
   * we do a full search of the topology to find the correct one.
   * 
   * TODO: this is very inefficient.  It might search every DN in the topology.
   */
  private DatanodeDescriptor rebankWriter(DatanodeDescriptor writer, int bank) {
    if (writer == null) return null;
    if (writer.getBank() == bank) return writer;
    return (DatanodeDescriptor)clusterMap.searchAll(
        new RebankingPredicate(writer, bank));
  }

  /** This is the implementation. */
  DatanodeDescriptor[] chooseTarget(int numOfReplicas,
                                    DatanodeDescriptor writer,
                                    List<DatanodeDescriptor> chosenNodes,
                                    boolean returnChosenNodes,
                                    HashMap<Node, Node> excludedNodes,
                                    long blocksize, int bank) {
    //filterNodesByBank(chosenNodes, bank);
    writer = rebankWriter(writer, bank);
    if (numOfReplicas == 0 || clusterMap.getNumOfLeaves()==0) {
      return new DatanodeDescriptor[0];
    }
      
    if (excludedNodes == null) {
      excludedNodes = new HashMap<Node, Node>();
    }
     
    int clusterSize = clusterMap.getNumOfLeaves();
    int totalNumOfReplicas = chosenNodes.size()+numOfReplicas;
    if (totalNumOfReplicas > clusterSize) {
      numOfReplicas -= (totalNumOfReplicas-clusterSize);
      totalNumOfReplicas = clusterSize;
    }
      
    int maxNodesPerRack = 
      (totalNumOfReplicas-1)/clusterMap.getNumOfRacks()+2;
      
    List<DatanodeDescriptor> results = 
      new ArrayList<DatanodeDescriptor>(chosenNodes);
    for (Node node:chosenNodes) {
      excludedNodes.put(node, node);
    }
      
    if (!clusterMap.contains(writer)) {
      writer=null;
    }
      
    DatanodeDescriptor localNode = chooseTarget(numOfReplicas, writer, 
                                                excludedNodes, blocksize, maxNodesPerRack, results, bank);
    if (!returnChosenNodes) {  
      results.removeAll(chosenNodes);
    }
      
    // sorting nodes to form a pipeline
    return getPipeline((writer==null)?localNode:writer,
                       results.toArray(new DatanodeDescriptor[results.size()]));
  }
    
  /* choose <i>numOfReplicas</i> from all data nodes */
  private DatanodeDescriptor chooseTarget(int numOfReplicas,
                                          DatanodeDescriptor writer,
                                          HashMap<Node, Node> excludedNodes,
                                          long blocksize,
                                          int maxNodesPerRack,
                                          List<DatanodeDescriptor> results, int bank) {
    //filterNodesByBank(results, bank);
    writer = rebankWriter(writer, bank);
    if (numOfReplicas == 0 || clusterMap.getNumOfLeaves()==0) {
      return writer;
    }
    int totalReplicasExpected = numOfReplicas;
      
    int numOfResults = results.size();
    boolean newBlock = (numOfResults==0);
    if (writer == null && !newBlock) {
      writer = results.get(0);
    }
      
    try {
      if (numOfResults == 0) {
        writer = chooseLocalNode(writer, excludedNodes, 
                                 blocksize, maxNodesPerRack, results, bank);
        if (--numOfReplicas == 0) {
          return writer;
        }
      }
      if (numOfResults <= 1) {
        chooseRemoteRack(1, results.get(0), excludedNodes, 
                         blocksize, maxNodesPerRack, results, bank);
        if (--numOfReplicas == 0) {
          return writer;
        }
      }
      if (numOfResults <= 2) {
        if (clusterMap.isOnSameRack(results.get(0), results.get(1))) {
          chooseRemoteRack(1, results.get(0), excludedNodes,
                           blocksize, maxNodesPerRack, results, bank);
        } else if (newBlock){
          chooseLocalRack(results.get(1), excludedNodes, blocksize, 
                          maxNodesPerRack, results, bank);
        } else {
          chooseLocalRack(writer, excludedNodes, blocksize,
                          maxNodesPerRack, results, bank);
        }
        if (--numOfReplicas == 0) {
          return writer;
        }
      }
      chooseRandom(numOfReplicas, NodeBase.ROOT, excludedNodes, 
                   blocksize, maxNodesPerRack, results, bank);
    } catch (NotEnoughReplicasException e) {
      LOG.warn("Not able to place enough replicas, still in need of "
               + numOfReplicas + " to reach " + totalReplicasExpected + "\n"
               + e.getMessage());
    }
    return writer;
  }
    
  /* choose <i>localMachine</i> as the target.
   * if <i>localMachine</i> is not available, 
   * choose a node on the same rack
   * @return the chosen node
   */
  private DatanodeDescriptor chooseLocalNode(
                                             DatanodeDescriptor localMachine,
                                             HashMap<Node, Node> excludedNodes,
                                             long blocksize,
                                             int maxNodesPerRack,
                                             List<DatanodeDescriptor> results, int bank)
    throws NotEnoughReplicasException {
    // if no local machine, randomly choose one node
    if (localMachine == null)
      return chooseRandom(NodeBase.ROOT, excludedNodes, 
                          blocksize, maxNodesPerRack, results, bank);
    if (preferLocalNode) {
      // otherwise try local machine first
      Node oldNode = excludedNodes.put(localMachine, localMachine);
      if (oldNode == null) { // was not in the excluded list
        if (isGoodTarget(localMachine, blocksize,
                         maxNodesPerRack, false, results, bank)) {
          results.add(localMachine);
          return localMachine;
        }
      } 
    }      
    // try a node on local rack
    return chooseLocalRack(localMachine, excludedNodes, 
                           blocksize, maxNodesPerRack, results, bank);
  }
    
  /* choose one node from the rack that <i>localMachine</i> is on.
   * if no such node is available, choose one node from the rack where
   * a second replica is on.
   * if still no such node is available, choose a random node 
   * in the cluster.
   * @return the chosen node
   */
  private DatanodeDescriptor chooseLocalRack(
                                             DatanodeDescriptor localMachine,
                                             HashMap<Node, Node> excludedNodes,
                                             long blocksize,
                                             int maxNodesPerRack,
                                             List<DatanodeDescriptor> results, int bank)
    throws NotEnoughReplicasException {
    // no local machine, so choose a random machine
    if (localMachine == null) {
      return chooseRandom(NodeBase.ROOT, excludedNodes, 
                          blocksize, maxNodesPerRack, results, bank);
    }
      
    // choose one from the local rack
    try {
      return chooseRandom(
                          localMachine.getNetworkLocation(),
                          excludedNodes, blocksize, maxNodesPerRack, results, bank);
    } catch (NotEnoughReplicasException e1) {
      // find the second replica
      DatanodeDescriptor newLocal=null;
      for(Iterator<DatanodeDescriptor> iter=results.iterator();
          iter.hasNext();) {
        DatanodeDescriptor nextNode = iter.next();
        if (nextNode != localMachine) {
          newLocal = nextNode;
          break;
        }
      }
      if (newLocal != null) {
        try {
          return chooseRandom(
                              newLocal.getNetworkLocation(),
                              excludedNodes, blocksize, maxNodesPerRack, results, bank);
        } catch(NotEnoughReplicasException e2) {
          //otherwise randomly choose one from the network
          return chooseRandom(NodeBase.ROOT, excludedNodes,
                              blocksize, maxNodesPerRack, results, bank);
        }
      } else {
        //otherwise randomly choose one from the network
        return chooseRandom(NodeBase.ROOT, excludedNodes,
                            blocksize, maxNodesPerRack, results, bank);
      }
    }
  }
    
  /* choose <i>numOfReplicas</i> nodes from the racks 
   * that <i>localMachine</i> is NOT on.
   * if not enough nodes are available, choose the remaining ones 
   * from the local rack
   */
    
  private void chooseRemoteRack(int numOfReplicas,
                                DatanodeDescriptor localMachine,
                                HashMap<Node, Node> excludedNodes,
                                long blocksize,
                                int maxReplicasPerRack,
                                List<DatanodeDescriptor> results, int bank)
    throws NotEnoughReplicasException {
    int oldNumOfReplicas = results.size();
    // randomly choose one node from remote racks
    try {
      chooseRandom(numOfReplicas, "~"+localMachine.getNetworkLocation(),
                   excludedNodes, blocksize, maxReplicasPerRack, results, bank);
    } catch (NotEnoughReplicasException e) {
      chooseRandom(numOfReplicas-(results.size()-oldNumOfReplicas),
                   localMachine.getNetworkLocation(), excludedNodes, blocksize, 
                   maxReplicasPerRack, results, bank);
    }
  }

  /* Randomly choose one target from <i>nodes</i>.
   * @return the chosen node
   */
  private DatanodeDescriptor chooseRandom(
                                          String nodes,
                                          HashMap<Node, Node> excludedNodes,
                                          long blocksize,
                                          int maxNodesPerRack,
                                          List<DatanodeDescriptor> results, int bank) 
    throws NotEnoughReplicasException {
    int numOfAvailableNodes =
      clusterMap.countNumOfAvailableNodes(nodes, excludedNodes.keySet());
    StringBuilder builder = null;
    if (LOG.isDebugEnabled()) {
      builder = threadLocalBuilder.get();
      builder.setLength(0);
      builder.append("[");
    }
    boolean badTarget = false;
    while(numOfAvailableNodes > 0) {
      DatanodeDescriptor chosenNode = 
        (DatanodeDescriptor)(clusterMap.chooseRandom(nodes));

      Node oldNode = excludedNodes.put(chosenNode, chosenNode);
      if (oldNode == null) { // choosendNode was not in the excluded list
        numOfAvailableNodes--;
        if (isGoodTarget(chosenNode, blocksize, maxNodesPerRack, results, bank)) {
          results.add(chosenNode);
          return chosenNode;
        } else {
          badTarget = true;
        }
      }
    }

    String detail = enableDebugLogging;
    if (LOG.isDebugEnabled()) {
      if (badTarget && builder != null) {
        detail = builder.append("]").toString();
        builder.setLength(0);
      } else detail = "";
    }
    throw new NotEnoughReplicasException(detail);
  }
    
  /* Randomly choose <i>numOfReplicas</i> targets from <i>nodes</i>.
   */
  private void chooseRandom(int numOfReplicas,
                            String nodes,
                            HashMap<Node, Node> excludedNodes,
                            long blocksize,
                            int maxNodesPerRack,
                            List<DatanodeDescriptor> results, int bank)
    throws NotEnoughReplicasException {
      
    int numOfAvailableNodes =
      clusterMap.countNumOfAvailableNodes(nodes, excludedNodes.keySet());
    StringBuilder builder = null;
    if (LOG.isDebugEnabled()) {
      builder = threadLocalBuilder.get();
      builder.setLength(0);
      builder.append("[");
    }
    boolean badTarget = false;
    while(numOfReplicas > 0 && numOfAvailableNodes > 0) {
      DatanodeDescriptor chosenNode = 
        (DatanodeDescriptor)(clusterMap.chooseRandom(nodes));
      Node oldNode = excludedNodes.put(chosenNode, chosenNode);
      if (oldNode == null) {
        numOfAvailableNodes--;

        if (isGoodTarget(chosenNode, blocksize, maxNodesPerRack, results, bank)) {
          numOfReplicas--;
          results.add(chosenNode);
        } else {
          badTarget = true;
        }
      }
    }
      
    if (numOfReplicas>0) {
      String detail = enableDebugLogging;
      if (LOG.isDebugEnabled()) {
        if (badTarget && builder != null) {
          detail = builder.append("]").toString();
          builder.setLength(0);
        } else detail = "";
      }
      throw new NotEnoughReplicasException(detail);
    }
  }
    
  /* judge if a node is a good target.
   * return true if <i>node</i> has enough space, 
   * does not have too much load, and the rack does not have too many nodes
   */
  private boolean isGoodTarget(DatanodeDescriptor node,
                               long blockSize, int maxTargetPerLoc,
                               List<DatanodeDescriptor> results, int bank) {
    return isGoodTarget(node, blockSize, maxTargetPerLoc,
                        this.considerLoad, results, bank);
  }
    
  private boolean isGoodTarget(DatanodeDescriptor node,
                               long blockSize, int maxTargetPerLoc,
                               boolean considerLoad,
                               List<DatanodeDescriptor> results, int bank) {
    if (node.getBank() != bank) {
      /*LOG.error("WATERMELON: rejecting node " + node + " because its bank is not " + bank + ": " +
        "node.ipAddr = " + node.getIpAddr() + ", node.hostName = " + node.getHostName() +
        ", node.storageID = "  + node.getStorageID() + ", node.xferPort = " + node.getXferPort() +
        ", node.bank = " + node.getBank()); */
      return false;
    }
    // check if the node is (being) decommissed
    if (node.isDecommissionInProgress() || node.isDecommissioned()) {
      if(LOG.isDebugEnabled()) {
        threadLocalBuilder.get().append(node.toString()).append(": ")
          .append("Node ").append(NodeBase.getPath(node))
          .append(" is not chosen because the node is (being) decommissioned ");
      }
      return false;
    }

    long remaining = node.getRemaining() - 
                     (node.getBlocksScheduled() * blockSize); 
    // check the remaining capacity of the target machine
    if (blockSize* HdfsConstants.MIN_BLOCKS_FOR_WRITE>remaining) {
      if(LOG.isDebugEnabled()) {
        threadLocalBuilder.get().append(node.toString()).append(": ")
          .append("Node ").append(NodeBase.getPath(node))
          .append(" is not chosen because the node does not have enough space ");
      }
      return false;
    }
      
    // check the communication traffic of the target machine
    if (considerLoad) {
      double avgLoad = 0;
      int size = clusterMap.getNumOfLeaves();
      if (size != 0 && stats != null) {
        avgLoad = (double)stats.getTotalLoad()/size;
      }
      if (node.getXceiverCount() > (2.0 * avgLoad)) {
        if(LOG.isDebugEnabled()) {
          threadLocalBuilder.get().append(node.toString()).append(": ")
            .append("Node ").append(NodeBase.getPath(node))
            .append(" is not chosen because the node is too busy ");
        }
        return false;
      }
    }
      
    // check if the target rack has chosen too many nodes
    String rackname = node.getNetworkLocation();
    int counter=1;
    for(Iterator<DatanodeDescriptor> iter = results.iterator();
        iter.hasNext();) {
      Node result = iter.next();
      if (rackname.equals(result.getNetworkLocation())) {
        counter++;
      }
    }
    if (counter>maxTargetPerLoc) {
      if(LOG.isDebugEnabled()) {
        threadLocalBuilder.get().append(node.toString()).append(": ")
          .append("Node ").append(NodeBase.getPath(node))
          .append(" is not chosen because the rack has too many chosen nodes ");
      }
      return false;
    }
    return true;
  }
    
  /* Return a pipeline of nodes.
   * The pipeline is formed finding a shortest path that 
   * starts from the writer and traverses all <i>nodes</i>
   * This is basically a traveling salesman problem.
   */
  private DatanodeDescriptor[] getPipeline(
                                           DatanodeDescriptor writer,
                                           DatanodeDescriptor[] nodes) {
    if (nodes.length==0) return nodes;
      
    synchronized(clusterMap) {
      int index=0;
      if (writer == null || !clusterMap.contains(writer)) {
        writer = nodes[0];
      }
      for(;index<nodes.length; index++) {
        DatanodeDescriptor shortestNode = nodes[index];
        int shortestDistance = clusterMap.getDistance(writer, shortestNode);
        int shortestIndex = index;
        for(int i=index+1; i<nodes.length; i++) {
          DatanodeDescriptor currentNode = nodes[i];
          int currentDistance = clusterMap.getDistance(writer, currentNode);
          if (shortestDistance>currentDistance) {
            shortestDistance = currentDistance;
            shortestNode = currentNode;
            shortestIndex = i;
          }
        }
        //switch position index & shortestIndex
        if (index != shortestIndex) {
          nodes[shortestIndex] = nodes[index];
          nodes[index] = shortestNode;
        }
        writer = shortestNode;
      }
    }
    return nodes;
  }

  @Override
  public int verifyBlockPlacement(String srcPath,
                                  LocatedBlock lBlk,
                                  int minRacks) {
    DatanodeInfo[] locs = lBlk.getLocations();
    if (locs == null)
      locs = new DatanodeInfo[0];
    int numRacks = clusterMap.getNumOfRacks();
    if(numRacks <= 1) // only one rack
      return 0;
    minRacks = Math.min(minRacks, numRacks);
    // 1. Check that all locations are different.
    // 2. Count locations on different racks.
    Set<String> racks = new TreeSet<String>();
    for (DatanodeInfo dn : locs)
      racks.add(dn.getNetworkLocation());
    return minRacks - racks.size();
  }

  @Override
  public DatanodeDescriptor chooseReplicaToDelete(BlockCollection bc,
                                                 Block block,
                                                 short replicationFactor,
                                                 Collection<DatanodeDescriptor> first, 
                                                 Collection<DatanodeDescriptor> second) {
    long oldestHeartbeat =
      now() - heartbeatInterval * tolerateHeartbeatMultiplier;
    DatanodeDescriptor oldestHeartbeatNode = null;
    long minSpace = Long.MAX_VALUE;
    DatanodeDescriptor minSpaceNode = null;

    // pick replica from the first Set. If first is empty, then pick replicas
    // from second set.
    Iterator<DatanodeDescriptor> iter =
          first.isEmpty() ? second.iterator() : first.iterator();

    // Pick the node with the oldest heartbeat or with the least free space,
    // if all hearbeats are within the tolerable heartbeat interval
    while (iter.hasNext() ) {
      DatanodeDescriptor node = iter.next();
      long free = node.getRemaining();
      long lastHeartbeat = node.getLastUpdate();
      if(lastHeartbeat < oldestHeartbeat) {
        oldestHeartbeat = lastHeartbeat;
        oldestHeartbeatNode = node;
      }
      if (minSpace > free) {
        minSpace = free;
        minSpaceNode = node;
      }
    }
    return oldestHeartbeatNode != null ? oldestHeartbeatNode : minSpaceNode;
  }
  
  @VisibleForTesting
  void setPreferLocalNode(boolean prefer) {
    this.preferLocalNode = prefer;
  }
}

