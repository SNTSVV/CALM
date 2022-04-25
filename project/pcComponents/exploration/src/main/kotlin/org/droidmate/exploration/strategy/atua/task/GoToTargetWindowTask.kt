package org.droidmate.exploration.strategy.atua.task

import org.atua.modelFeatures.helper.PathConstraint
import org.atua.modelFeatures.helper.PathFindingHelper
import org.droidmate.exploration.strategy.atua.ATUATestingStrategy
import org.droidmate.explorationModel.interaction.State
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class GoToTargetWindowTask (
    regressionWatcher: org.atua.modelFeatures.ATUAMF,
    atuaTestingStrategy: ATUATestingStrategy,
    delay: Long, useCoordinateClicks: Boolean) : GoToAnotherWindowTask(regressionWatcher, atuaTestingStrategy, delay, useCoordinateClicks) {

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
        if (!continueMode) {
            pathConstraints.put(PathConstraint.INCLUDE_RESET, includeResetAction)
            pathConstraints.put(PathConstraint.INCLUDE_LAUNCH, true)
        }
        pathConstraints.put(PathConstraint.MAXIMUM_DSTG,true)

        while (possiblePaths.isEmpty()) {
            if (nextPathType == PathFindingHelper.PathType.WTG) {
                pathConstraints.put(PathConstraint.INCLUDE_WTG,true)
            } else {
                pathConstraints.put(PathConstraint.INCLUDE_WTG,false)
            }
            possiblePaths.addAll(atuaStrategy.phaseStrategy.getPathsToTargetWindows(currentState,pathType = nextPathType,maxCost = maxCost,pathConstraint =pathConstraints ))
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