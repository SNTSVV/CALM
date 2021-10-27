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

package org.atua.modelFeatures.ewtg

import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.helper.PathFindingHelper
import kotlin.collections.HashMap

class TransitionPath(val root: AbstractState, val pathType: PathFindingHelper.PathType, val destination: AbstractState) {
    val path: HashMap<Int, AbstractTransition> = HashMap()

    fun getFinalDestination(): AbstractState{
        return destination
    }

    fun containsLoop(): Boolean {
        val orginalSize = path.values.map { it.source }.size
        val reducedSize = path.values.map { it.source }.distinct().size
        if (reducedSize<orginalSize)
            return true
        return false
    }

    fun cost(start: Int=0): Int {
        var cost = 0
        path.values.drop(start).forEach {
            if (it.abstractAction.actionType == AbstractActionType.RESET_APP)
                cost+=5
            else if (it.abstractAction.actionType == AbstractActionType.LAUNCH_APP)
                cost+=4
            else
                cost+=1
        }
        return cost
    }


}

class PathTraverser (val transitionPath: TransitionPath) {
    var latestEdgeId: Int? = null
    fun reset() {
        latestEdgeId = null
    }
    fun getCurrentTransition(): AbstractTransition? {
        if (latestEdgeId == null)
            return null
        return transitionPath.path[latestEdgeId!!]
    }
    fun next(): AbstractTransition? {
        if(finalStateAchieved())
            return null
        if (latestEdgeId == null)
            latestEdgeId = 0
        else
            latestEdgeId = latestEdgeId!! + 1
        val edge = transitionPath.path[latestEdgeId!!]
        return edge
    }

    fun finalStateAchieved(): Boolean {
        return latestEdgeId == transitionPath.path!!.size-1
    }

    fun canContinue(currentAppState: AbstractState): Boolean {
        val nextAbstractTransition = transitionPath.path[latestEdgeId!! + 1]
        val nextAction = nextAbstractTransition?.abstractAction
        if (nextAction == null)
            return false
        if (!nextAction.isWidgetAction())
            return true
        val targetAVM = nextAction!!.attributeValuationMap!!
        if (currentAppState.attributeValuationMaps.contains(targetAVM)) {
            return true
        }
        currentAppState.attributeValuationMaps.forEach {
            if (it.isDerivedFrom(targetAVM))
                return true
        }
        val nextInput = nextAbstractTransition!!.source.inputMappings[nextAction]
        if (nextInput!=null) {
            if (currentAppState.inputMappings.values.flatten().intersect(nextInput).isNotEmpty()) {
                return true
            }
        }
        return false
    }
}