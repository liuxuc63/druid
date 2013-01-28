/*
* Druid - a distributed column store.
* Copyright (C) 2012  Metamarkets Group Inc.
*
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation; either version 2
* of the License, or (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/

package com.metamx.druid.master;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.MinMaxPriorityQueue;
import com.metamx.common.guava.Comparators;
import com.metamx.druid.client.DataSegment;
import com.metamx.druid.client.DruidServer;
import com.metamx.emitter.EmittingLogger;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 */
public class DruidMasterBalancer implements DruidMasterHelper
{
  public static final Comparator<ServerHolder> percentUsedComparator = Comparators.inverse(
      new Comparator<ServerHolder>()
      {
        @Override
        public int compare(ServerHolder lhs, ServerHolder rhs)
        {
          return lhs.getPercentUsed().compareTo(rhs.getPercentUsed());
        }
      }
  );
  private static final EmittingLogger log = new EmittingLogger(DruidMasterBalancer.class);

  private final DruidMaster master;

  private final Map<String, ConcurrentHashMap<String, BalancerSegmentHolder>> currentlyMovingSegments = Maps.newHashMap();

  public DruidMasterBalancer(
      DruidMaster master
  )
  {
    this.master = master;
  }

  private void reduceLifetimes(String tier)
  {
    for (BalancerSegmentHolder holder : currentlyMovingSegments.get(tier).values()) {
      holder.reduceLifetime();
      if (holder.getLifetime() <= 0) {
        log.makeAlert(
            "[%s]: Balancer move segments queue has a segment stuck",
            tier,
            ImmutableMap.<String, Object>builder()
                        .put("segment", holder.getSegment().getIdentifier())
                        .build()
        ).emit();
      }
    }
  }

  @Override
  public DruidMasterRuntimeParams run(DruidMasterRuntimeParams params)
  {
    final MasterStats stats = new MasterStats();
    final DateTime referenceTimestamp = params.getBalancerReferenceTimestamp();
    final BalancerCostAnalyzer analyzer = params.getBalancerCostAnalyzer(referenceTimestamp);
    final int maxSegmentsToMove = params.getMaxSegmentsToMove();

    for (Map.Entry<String, MinMaxPriorityQueue<ServerHolder>> entry :
        params.getDruidCluster().getCluster().entrySet()) {
      String tier = entry.getKey();

      if (currentlyMovingSegments.get(tier) == null) {
        currentlyMovingSegments.put(tier, new ConcurrentHashMap<String, BalancerSegmentHolder>());
      }

      if (!currentlyMovingSegments.get(tier).isEmpty()) {
        reduceLifetimes(tier);
        log.info(
            "[%s]: Still waiting on %,d segments to be moved",
            tier,
            currentlyMovingSegments.size()
        );
        continue;
      }

      final List<ServerHolder> serverHolderList = new ArrayList<ServerHolder>(entry.getValue());
      int numSegments = 0;
      for (ServerHolder server : serverHolderList) {
        numSegments += server.getServer().getSegments().size();
      }

      if (numSegments == 0) {
        log.info("No segments found.  Cannot balance.");
        continue;
      }

      final Set<DataSegment> segmentsBeingMoved = new HashSet<DataSegment>();
      int iter = 0;

      while (iter < maxSegmentsToMove) {
        BalancerSegmentHolder segmentToMove = analyzer.pickSegmentToMove(serverHolderList, numSegments);
        DruidServer toServer = analyzer.findNewSegmentHome(segmentToMove.getSegment(), serverHolderList).getServer();
        if (!segmentsBeingMoved.contains(segmentToMove.getSegment())) {
          moveSegment(segmentToMove, toServer, params);
          segmentsBeingMoved.add(segmentToMove.getSegment());
        }
        iter++;
      }

      final double initialTotalCost = analyzer.calculateInitialTotalCost(serverHolderList);
      final double normalization = analyzer.calculateNormalization(serverHolderList);
      final double normalizedInitialCost = initialTotalCost / normalization;

      stats.addToTieredStat("initialCost", tier, (long) initialTotalCost);
      stats.addToTieredStat("normalization", tier, (long) normalization);
      stats.addToTieredStat("movedCount", tier, segmentsBeingMoved.size());

      log.info(
          "Initial Total Cost: [%f], Initial Normalized Cost: [%f], Segments Moved: [%d]",
          initialTotalCost,
          normalizedInitialCost,
          segmentsBeingMoved.size()
      );

      if (serverHolderList.size() <= 1) {
        log.info(
            "[%s]: One or fewer servers found.  Cannot balance.",
            tier
        );
        continue;
      }

    }

    return params.buildFromExisting()
                 .withMasterStats(stats)
                 .build();
  }

  private void moveSegment(
      final BalancerSegmentHolder segment,
      final DruidServer toServer,
      final DruidMasterRuntimeParams params
  )
  {
    final String toServerName = toServer.getName();
    final LoadQueuePeon toPeon = params.getLoadManagementPeons().get(toServerName);

    final String fromServerName = segment.getFromServer().getName();
    final DataSegment segmentToMove = segment.getSegment();
    final String segmentName = segmentToMove.getIdentifier();

    if (!toPeon.getSegmentsToLoad().contains(segmentToMove) &&
        (toServer.getSegment(segmentName) == null) &&
        new ServerHolder(toServer, toPeon).getAvailableSize() > segmentToMove.getSize()) {
      log.info(
          "Moving [%s] from [%s] to [%s]",
          segmentName,
          fromServerName,
          toServerName
      );
      try {
        master.moveSegment(
            fromServerName,
            toServerName,
            segmentToMove.getIdentifier(),
            new LoadPeonCallback()
            {
              @Override
              protected void execute()
              {
                Map<String, BalancerSegmentHolder> movingSegments = currentlyMovingSegments.get(toServer.getTier());
                if (movingSegments != null) {
                  movingSegments.remove(segmentName);
                }
              }
            }
        );
        currentlyMovingSegments.get(toServer.getTier()).put(segmentName, segment);
      }
      catch (Exception e) {
        log.makeAlert(e, String.format("[%s] : Moving exception", segmentName)).emit();
      }
    } else {
      currentlyMovingSegments.get(toServer.getTier()).remove(segment);
    }

  }
}