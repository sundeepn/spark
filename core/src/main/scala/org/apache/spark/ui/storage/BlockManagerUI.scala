/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ui.storage

import javax.servlet.http.HttpServletRequest

import scala.collection.mutable

import org.eclipse.jetty.servlet.ServletContextHandler

import org.apache.spark.ui._
import org.apache.spark.ui.JettyUtils._
import org.apache.spark.scheduler._
import org.apache.spark.storage.{RDDInfo, StorageStatusListener, StorageUtils}

/** Web UI showing storage status of all RDD's in the given SparkContext. */
private[ui] class BlockManagerUI(parent: SparkUI) {
  val appName = parent.appName
  val basePath = parent.basePath

  private val indexPage = new IndexPage(this)
  private val rddPage = new RDDPage(this)
  private var _listener: Option[BlockManagerListener] = None

  lazy val listener = _listener.get

  def start() {
    _listener = Some(new BlockManagerListener(parent.storageStatusListener))
  }

  def getHandlers = Seq[ServletContextHandler](
    createServletHandler("/storage/rdd",
      (request: HttpServletRequest) => rddPage.render(request), parent.securityManager, basePath),
    createServletHandler("/storage",
      (request: HttpServletRequest) => indexPage.render(request), parent.securityManager, basePath)
  )
}

/**
 * A SparkListener that prepares information to be displayed on the BlockManagerUI
 */
private[ui] class BlockManagerListener(storageStatusListener: StorageStatusListener)
  extends SparkListener {

  private val _rddInfoMap = mutable.Map[Int, RDDInfo]()

  def storageStatusList = storageStatusListener.storageStatusList

  /** Filter RDD info to include only those with cached partitions */
  def rddInfoList = _rddInfoMap.values.filter(_.numCachedPartitions > 0).toSeq

  /** Update each RDD's info to reflect any updates to the RDD's storage status */
  private def updateRDDInfo() {
    val rddInfos = _rddInfoMap.values.toSeq
    val updatedRddInfos = StorageUtils.rddInfoFromStorageStatus(storageStatusList, rddInfos)
    updatedRddInfos.foreach { info => _rddInfoMap(info.id) = info }
  }

  /**
   * Assumes the storage status list is fully up-to-date. This implies the corresponding
   * StorageStatusSparkListener must process the SparkListenerTaskEnd event before this listener.
   */
  override def onTaskEnd(taskEnd: SparkListenerTaskEnd) = synchronized {
    val metrics = taskEnd.taskMetrics
    if (metrics != null && metrics.updatedBlocks.isDefined) {
      updateRDDInfo()
    }
  }

  override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted) = synchronized {
    val rddInfo = stageSubmitted.stageInfo.rddInfo
    _rddInfoMap(rddInfo.id) = rddInfo
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted) = synchronized {
    // Remove all partitions that are no longer cached
    _rddInfoMap.retain { case (_, info) => info.numCachedPartitions > 0 }
  }

  override def onUnpersistRDD(unpersistRDD: SparkListenerUnpersistRDD) = synchronized {
    updateRDDInfo()
  }
}
