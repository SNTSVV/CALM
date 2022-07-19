/*
 * ATUA is a test automation tool for mobile Apps, which focuses on testing methods updated in each software release.
 * Copyright (C) 2019 - 2021 University of Luxembourg
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package org.atua.strategy.task

import org.atua.modelFeatures.helper.PathConstraint
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.strategy.ATUATestingStrategy
import org.droidmate.explorationModel.interaction.State
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GoToTargetWindowTask (
    regressionWatcher: org.atua.modelFeatures.ATUAMF,
    atuaTestingStrategy: ATUATestingStrategy,
    delay: Long, useCoordinateClicks: Boolean) : GoToAnotherWindowTask(regressionWatcher, atuaTestingStrategy, delay, useCoordinateClicks) {

    var abstractAtionAsTarget: Boolean = false
    override fun increaseExecutedCount() {
        executedCount++
    }

    override fun identifyPossiblePaths(currentState: State<*>, continueMode: Boolean, pathType: PathFindingHelper.PathType) {
        possiblePaths.clear()
        var nextPathType = pathType
     /*   var nextPathType = if (currentPath == null)
            PathFindingHelper.PathType.NORMAL
        *//*else if (continueMode)
            PathFindingHelper.PathType.PARTIAL_TRACE*//*
        else
            computeNextPathType(currentPath!!.pathType,includeResetAction)*/
        val pathConstraints = HashMap<PathConstraint,Boolean>()
        pathConstraints.put(PathConstraint.TARGET_ABSTRACT_ACTION, abstractAtionAsTarget)
        if (!continueMode) {
            pathConstraints.put(PathConstraint.INCLUDE_RESET, includeResetAction)
            pathConstraints.put(PathConstraint.INCLUDE_LAUNCH, true)
        } else {
            pathConstraints.put(PathConstraint.INCLUDE_RESET, false)
            pathConstraints.put(PathConstraint.INCLUDE_LAUNCH, false)
        }
        pathConstraints.put(PathConstraint.MAXIMUM_DSTG,true)

        while (possiblePaths.isEmpty()) {
            if (nextPathType == PathFindingHelper.PathType.WTG) {
                pathConstraints.put(PathConstraint.INCLUDE_WTG,true)
            } else {
                pathConstraints.put(PathConstraint.INCLUDE_WTG,false)
            }
            if (pathConstraints[PathConstraint.INCLUDE_RESET] == true) {
                val notResetConstraints = java.util.HashMap<PathConstraint, Boolean>()
                notResetConstraints.putAll(pathConstraints)
                notResetConstraints[PathConstraint.INCLUDE_RESET] = false
                val paths = atuaStrategy.phaseStrategy.getPathsToTargetWindows(
                    currentState = currentState,
                    pathType = nextPathType,
                    maxCost = maxCost,
                    pathConstraint =notResetConstraints )
                if (paths.isNotEmpty()) {
                    possiblePaths.addAll( paths)
                }
            }
            if (possiblePaths.isEmpty()) {
                possiblePaths.addAll(atuaStrategy.phaseStrategy.getPathsToTargetWindows(
                    currentState = currentState,
                    pathType = nextPathType,
                    maxCost = maxCost,
                    pathConstraint =pathConstraints ))
            }

            nextPathType = computeNextPathType(nextPathType,includeResetAction)
            if (nextPathType ==PathFindingHelper.PathType.WIDGET_AS_TARGET)
                break
        }
        if (possiblePaths.isEmpty() && destWindow!=null) {
            log.debug("Cannot identify path to $destWindow")
        }
    }


    companion object {
        private val log: Logger by lazy { LoggerFactory.getLogger(this.javaClass.name) }
        var executedCount:Int = 0
        var instance: GoToTargetWindowTask? = null
        fun getInstance(regressionWatcher: org.atua.modelFeatures.ATUAMF,
                        atuaTestingStrategy: ATUATestingStrategy,
                        delay: Long,
                        useCoordinateClicks: Boolean): GoToTargetWindowTask {
            if (instance == null) {
                instance = GoToTargetWindowTask(regressionWatcher, atuaTestingStrategy, delay,useCoordinateClicks)
            }
            return instance!!
        }
    }
}