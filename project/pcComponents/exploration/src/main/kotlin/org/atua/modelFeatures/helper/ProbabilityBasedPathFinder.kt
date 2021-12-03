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

package org.atua.modelFeatures.helper

import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.Rotation
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.Cardinality
import org.atua.modelFeatures.dstg.DSTG
import org.atua.modelFeatures.dstg.PredictedAbstractState
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.TransitionPath
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.mapping.EWTG_DSTGMapping
import org.droidmate.exploration.modelFeatures.graph.Edge
import org.droidmate.explorationModel.interaction.State
import java.util.*
import kotlin.collections.HashMap

class ProbabilityBasedPathFinder {
    companion object {
        val DEFAULT_MAX_COST: Double = 15.0
        val allAvailableTransitionPaths = HashMap<Pair<AbstractState, AbstractState>, ArrayList<TransitionPath>>()
        private val disableEdges = HashSet<Edge<AbstractState, AbstractTransition>>()
        private val disablePaths = HashSet<Pair<List<AbstractTransition>, DisablePathType>>()

        fun findPathToTargetComponent(
            autautMF: org.atua.modelFeatures.ATUAMF,
            currentState: State<*>,
            root: AbstractState,
            finalTargets: List<AbstractState>,
            foundPaths: ArrayList<TransitionPath>,
            shortest: Boolean = true,
            pathCountLimitation: Int = 3,
            windowAsTarget: Boolean,
            pathType: PathFindingHelper.PathType,
            goalsByTarget: Map<AbstractState,List<Input>>,
            maxCost: Double
        ) {
            val targetTraces = if (pathType == PathFindingHelper.PathType.PARTIAL_TRACE || pathType == PathFindingHelper.PathType.FULLTRACE) {
                val edgesToTarget = autautMF.dstg.edges().filter { finalTargets.contains(it.label.dest) }
                edgesToTarget.map { it.label.tracing }.flatten().map { it.first }.distinct()
            } else {
                emptyList()
            }
            if (pathType == PathFindingHelper.PathType.PARTIAL_TRACE || pathType == PathFindingHelper.PathType.FULLTRACE) {
                if (targetTraces.isEmpty())
                    return
            }
            if (pathType == PathFindingHelper.PathType.NORMAL && !windowAsTarget) {
                val exisitingEdgesToTarget = autautMF.dstg.edges().filter {  finalTargets.contains(it.destination?.data) }
                if (exisitingEdgesToTarget.isEmpty())
                    return
            }
            if (autautMF.dstg.edges().all {  !finalTargets.map{it.window }.contains(it.destination?.data?.window)}) {
                return
            }
           /* if (goalsByTarget.values.flatten().isNotEmpty()) {
                val possibleActions = EWTG_DSTGMapping.INSTANCE.inputsByAbstractActions.filter { it.value.intersect(goalsByTarget.values.flatten()).isNotEmpty() }
                if (possibleActions.isEmpty()) {
                    return
                }
            }*/
            val currentAbstractStateStack: List<AbstractState> = autautMF.getAbstractStateStack()
            when (pathType) {
                PathFindingHelper.PathType.NORMAL -> findPathToTargetComponentByBFS(
                    autautMF = autautMF,
                    currentState = currentState,
                    root = root,
                    finalTargets = finalTargets,
                    prevEdgeIds = emptyList(),
                    traversedEdges = HashMap(),
                    foundPaths = foundPaths,
                    pathTracking = HashMap(),
                    depth = 0,
                    includeWTG = false,
                    stopWhenHavingUnexercisedAction = false,
                    shortest = shortest,
                    pathCountLimitation = pathCountLimitation,
                    includeResetAction = true,
                    followTrace = false,
                    pathType = pathType,
                    targetTraces = targetTraces,
                    currentAbstractStateStack = currentAbstractStateStack,
                    goalsByTarget = goalsByTarget,
                    minCost = maxCost,
                    windowAsTarget = windowAsTarget
                )
                PathFindingHelper.PathType.WIDGET_AS_TARGET -> findPathToTargetComponentByBFS(
                    autautMF = autautMF,
                    currentState = currentState,
                    root = root,
                    finalTargets = finalTargets,
                    prevEdgeIds = emptyList(),
                    traversedEdges = HashMap(),
                    foundPaths = foundPaths,
                    pathTracking = HashMap(),
                    depth = 0,
                    includeWTG = false,
                    stopWhenHavingUnexercisedAction = false,
                    shortest = shortest,
                    pathCountLimitation = pathCountLimitation,
                    includeResetAction = true,
                    followTrace = false,
                    pathType = pathType,
                    targetTraces = targetTraces,
                    currentAbstractStateStack = currentAbstractStateStack,
                    goalsByTarget = goalsByTarget,
                    minCost = maxCost,
                    windowAsTarget = windowAsTarget
                )
                PathFindingHelper.PathType.WTG -> findPathToTargetComponentByBFS(
                    autautMF = autautMF,
                    currentState = currentState,
                    root = root,
                    prevEdgeIds = emptyList(),
                    traversedEdges = HashMap(),
                    finalTargets = finalTargets,
                    foundPaths = foundPaths,
                    pathTracking = HashMap(),
                    depth = 0,
                    includeWTG = true,
                    stopWhenHavingUnexercisedAction = false,
                    shortest = shortest,
                    pathCountLimitation = pathCountLimitation,
                    includeResetAction = true,
                    followTrace = false,
                    pathType = pathType,
                    targetTraces = targetTraces,
                    currentAbstractStateStack = currentAbstractStateStack,
                    goalsByTarget = goalsByTarget,
                    minCost = maxCost,
                    windowAsTarget = windowAsTarget
                )
            }

        }

        fun findPathToTargetComponentByBFS(
            autautMF: org.atua.modelFeatures.ATUAMF, currentState: State<*>, root: AbstractState,
            prevEdgeIds: List<Int>,
            finalTargets: List<AbstractState>,
            foundPaths: ArrayList<TransitionPath>,
            traversedEdges: HashMap<Int, Pair<AbstractTransition, Stack<AbstractState>>>,
            pathTracking: HashMap<Int, Int>,
            depth: Int,
            includeWTG: Boolean,
            windowAsTarget: Boolean,
            stopWhenHavingUnexercisedAction: Boolean = false,
            shortest: Boolean = true,
            pathCountLimitation: Int = 1,
            includeResetAction: Boolean,
            followTrace: Boolean,
            pathType: PathFindingHelper.PathType,
            targetTraces: List<Int>,
            currentAbstractStateStack: List<AbstractState>,
            goalsByTarget: Map<AbstractState, List<Input>>,
            minCost: Double
        ) {
            val graph = autautMF.dstg
            val nextTransitions = ArrayList<Int>()
            if (prevEdgeIds.isEmpty()) {
                if (depth == 0) {
                    val source = root
                    val windowStack = autautMF.getAbstractStateStack().clone() as Stack<AbstractState>
                    getNextTraversingNodes(
                        atuaMF = autautMF,
                        abstractStateStack = windowStack,
                        graph = graph,
                        source = source,
                        prevEdgeId = null,
                        depth = depth,
                        traversedEdges = traversedEdges,
                        pathTracking = pathTracking,
                        finalTargets = finalTargets,
                        root = root,
                        foundPaths = foundPaths,
                        nextTransitions = nextTransitions,
                        stopWhenHavingUnexercisedAction = stopWhenHavingUnexercisedAction,
                        includeWTG = includeWTG,
                        includeResetAction = includeResetAction,
                        followTrace = followTrace,
                        pathType = pathType,
                        targetTraces = targetTraces,
                        currentAbstractStateStack = currentAbstractStateStack,
                        goalsByTarget = goalsByTarget,
                        minCost = minCost,
                        windowAsTarget = windowAsTarget
                    )
                } else {
                    return
                }
            } else {
                foundPaths.removeIf { it.cost() > minCost }
                for (traversing in prevEdgeIds) {
                    val source = traversedEdges[traversing]!!.first.dest
                    if (source.window is Launcher)
                        continue
                    if (source is VirtualAbstractState && !includeWTG) {
                        continue
                    }
                    val windowStack = traversedEdges[traversing]!!.second
                    if (windowStack.isEmpty())
                        continue
                    getNextTraversingNodes(
                        atuaMF = autautMF,
                        abstractStateStack = windowStack,
                        graph = graph,
                        source = source,
                        prevEdgeId = traversing,
                        depth = depth,
                        traversedEdges = traversedEdges,
                        pathTracking = pathTracking,
                        finalTargets = finalTargets,
                        root = root,
                        foundPaths = foundPaths,
                        nextTransitions = nextTransitions,
                        stopWhenHavingUnexercisedAction = stopWhenHavingUnexercisedAction,
                        includeWTG = includeWTG,
                        includeResetAction = includeResetAction,
                        followTrace = followTrace,
                        pathType = pathType,
                        targetTraces = targetTraces,
                        currentAbstractStateStack = currentAbstractStateStack,
                        goalsByTarget = goalsByTarget,
                        minCost = minCost,
                        windowAsTarget = windowAsTarget
                    )
                }
            }
            if (nextTransitions.isEmpty())
                return
            if (foundPaths.isNotEmpty() && foundPaths.any { it.path.values.all { it.dest !is PredictedAbstractState } }) {
                return
            }
            val newMinCost = foundPaths.map { it.cost() }.min()?:minCost
            foundPaths.removeIf { it.cost() > newMinCost }
            findPathToTargetComponentByBFS(
                autautMF = autautMF,
                currentState = currentState,
                root = root,
                finalTargets = finalTargets,
                traversedEdges = traversedEdges,
                prevEdgeIds = nextTransitions,
                foundPaths = foundPaths,
                pathTracking = pathTracking,
                depth = depth + 1,
                includeWTG = includeWTG,
                stopWhenHavingUnexercisedAction = stopWhenHavingUnexercisedAction,
                shortest = shortest,
                pathCountLimitation = pathCountLimitation,
                includeResetAction = includeResetAction,
                followTrace = followTrace,
                pathType = pathType,
                targetTraces = targetTraces,
                currentAbstractStateStack = currentAbstractStateStack,
                goalsByTarget = goalsByTarget,
                minCost = newMinCost,
                windowAsTarget = windowAsTarget
            )
        }

        private fun getNextTraversingNodes(
            atuaMF: org.atua.modelFeatures.ATUAMF,
            abstractStateStack: Stack<AbstractState>, graph: DSTG, source: AbstractState,
            prevEdgeId: Int?,
            depth: Int,
            traversedEdges: HashMap<Int, Pair<AbstractTransition, Stack<AbstractState>>>,
            pathTracking: HashMap<Int, Int>,
            finalTargets: List<AbstractState>,
            root: AbstractState,
            foundPaths: ArrayList<TransitionPath>,
            nextTransitions: ArrayList<Int>,
            includeWTG: Boolean,
            windowAsTarget: Boolean,
            stopWhenHavingUnexercisedAction: Boolean,
            includeResetAction: Boolean,
            followTrace: Boolean,
            pathType: PathFindingHelper.PathType,
            targetTraces: List<Int>,
            currentAbstractStateStack: List<AbstractState>,
            goalsByTarget: Map<AbstractState,List<Input>>,
            minCost: Double
        ) {
            val prevAbstractTransitions = ArrayList<AbstractTransition>()
            var traverseEdgeId = prevEdgeId
            while (traverseEdgeId != null) {
                val transition = traversedEdges.get(traverseEdgeId)!!.first
                prevAbstractTransitions.add(transition)
                traverseEdgeId = pathTracking[traverseEdgeId]
            }
            val considerGuardedTransitions =
                if (prevAbstractTransitions.isEmpty() || prevAbstractTransitions.all { it.isExplicit() || it.guardEnabled }) {
                    true
                } else {
                    if (prevAbstractTransitions.last().abstractAction.isLaunchOrReset())
                        true
                    else
                        false
                }
            val traveredAbstractTransitions = traversedEdges.map { it.value.first }
            val allAvailableActions = source.getAvailableActions()
            val validAbstractActions = allAvailableActions.filter {
                (it.actionType != AbstractActionType.RESET_APP
                        || depth == 0) && it.actionType != AbstractActionType.ACTION_QUEUE
                        && it.actionType != AbstractActionType.UNKNOWN
            }
            validAbstractActions.forEach { abstractAction ->
                val abstractTransitions = source.abstractTransitions.filter {
                            it.abstractAction == abstractAction
                            && !traveredAbstractTransitions.map { it.source }.contains(it.dest)
                            && it.source != it.dest
                            && it.activated == true}
                val validAbstactTransitions = abstractTransitions.filter {
                    it.dest !is PredictedAbstractState
                            && (includeWTG || !it.fromWTG )
                            && (!considerGuardedTransitions || !it.guardEnabled ||
                            it.dependentAbstractStates.intersect(abstractStateStack.toList()).isNotEmpty())
                }
                var selectedExercisedAbstractTransition = false
                if (validAbstactTransitions.isNotEmpty()) {
                    validAbstactTransitions.forEach { abstractTransition ->
                        selectedExercisedAbstractTransition = processAbstractTransition(
                            abstractTransition= abstractTransition,
                            traversedEdges = traversedEdges,
                            pathType = pathType,
                            atuaMF = atuaMF,
                            prevEdgeId = prevEdgeId,
                            root = root,
                            pathTracking = pathTracking,
                            minCost = minCost,
                            foundPaths = foundPaths,
                            abstractStateStack = abstractStateStack,
                            source = source,
                            nextTransitions = nextTransitions,
                            finalTargets = finalTargets,
                            goalsByTarget = goalsByTarget,
                            windowAsTarget = windowAsTarget
                        )
                    }
                }
                if ((!selectedExercisedAbstractTransition || abstractAction.isItemAction())
                    && pathType != PathFindingHelper.PathType.NORMAL
                    && (goalsByTarget.isNotEmpty() || windowAsTarget)) {
                    var predictedAction = false
                    if (traveredAbstractTransitions.any { it.dest is PredictedAbstractState && it.abstractAction == abstractAction }) {
                        predictedAction = true
                    }
                    if (!predictedAction) {
                        val exisitingPredictedTransitions = abstractTransitions.filter { it.dest is PredictedAbstractState }
                        if (exisitingPredictedTransitions.isNotEmpty()) {
                            if (!traveredAbstractTransitions.any { it.abstractAction == abstractAction && it.dest is PredictedAbstractState }) {
                                exisitingPredictedTransitions.forEach {
                                    processAbstractTransition(
                                        abstractTransition = it,
                                        traversedEdges = traversedEdges,
                                        pathType = pathType,
                                        atuaMF = atuaMF,
                                        prevEdgeId = prevEdgeId,
                                        root = root,
                                        pathTracking = pathTracking,
                                        minCost = minCost,
                                        foundPaths = foundPaths,
                                        abstractStateStack = abstractStateStack,
                                        source = source,
                                        nextTransitions = nextTransitions,
                                        finalTargets = finalTargets,
                                        goalsByTarget = goalsByTarget,
                                        windowAsTarget = windowAsTarget
                                    )
                                }
                            }
                        } else {
                            // predict destination
                            if (!AbstractStateManager.INSTANCE
                                    .goBackAbstractActions.contains(abstractAction) || true
                            ) {
                                val reachableAbstractActions =
                                    atuaMF.dstg.abstractActionEnables[abstractAction]?.second?.filter { it.key.actionType != AbstractActionType.RESET_APP }
                                if (reachableAbstractActions != null) {
                                    val totalcnt = atuaMF.dstg.abstractActionEnables[abstractAction]!!.first
                                    val abstractActionsByWindow = reachableAbstractActions.keys.groupBy { it.window }
                                    abstractActionsByWindow.forEach { window, abstractActions ->
                                        val activity = window.classType
                                        val predictAbstractState = PredictedAbstractState(
                                            activity = activity,
                                            window = window,
                                            rotation = Rotation.PORTRAIT,
                                            isOutOfApplication = (window is OutOfApp),
                                            isOpeningMenus = false,
                                            isRequestRuntimePermissionDialogBox = false,
                                            isOpeningKeyboard = false,
                                            inputMappings = HashMap()
                                        )
                                        abstractActions.forEach { action ->
                                            val prob =
                                                reachableAbstractActions[action]!! * 1.0 / totalcnt
                                            if (prob > 0.4) {
                                                if (!action.isWidgetAction()) {
                                                    if (!predictAbstractState.containsActionCount(action))
                                                        predictAbstractState.setActionCount(action, 0)
                                                }
                                                if (action.attributeValuationMap != null && !predictAbstractState.attributeValuationMaps.contains(
                                                        action.attributeValuationMap
                                                    )
                                                ) {
                                                    predictAbstractState.attributeValuationMaps.add(action.attributeValuationMap)
                                                    predictAbstractState.avmCardinalities.putIfAbsent(
                                                        action.attributeValuationMap,
                                                        Cardinality.ONE
                                                    )
                                                }
                                                val inputs =
                                                    EWTG_DSTGMapping.INSTANCE.inputsByAbstractActions.get(action)
                                                inputs?.forEach {
                                                    predictAbstractState.associateAbstractActionWithInputs(action, it)
                                                }

                                                predictAbstractState.abstractActionsProbability.put(action, prob)
                                            } else {
                                                val faileur = 1.0-prob
                                            }
                                        }
                                        predictAbstractState.updateHashCode()
                                        val newAbstractTransition = AbstractTransition(
                                            abstractAction = abstractAction,
                                            source = source,
                                            dest = predictAbstractState,
                                            isImplicit = true
                                        )
                                        source.abstractTransitions.add(newAbstractTransition)
                                        atuaMF.dstg.add(source, predictAbstractState, newAbstractTransition)
                                        processAbstractTransition(
                                            abstractTransition = newAbstractTransition,
                                            traversedEdges = traversedEdges,
                                            pathType = pathType,
                                            prevEdgeId = prevEdgeId,
                                            root = root,
                                            pathTracking = pathTracking,
                                            foundPaths = foundPaths,
                                            abstractStateStack = abstractStateStack,
                                            source = source,
                                            nextTransitions = nextTransitions,
                                            finalTargets = finalTargets,
                                            minCost = minCost,
                                            goalsByTarget = goalsByTarget,
                                            atuaMF = atuaMF,
                                            windowAsTarget = windowAsTarget
                                        )
                                    }
                                }
                            }
                        }
                    }

                }
            }
        }

        private fun processAbstractTransition(
            abstractTransition: AbstractTransition,
            traversedEdges: HashMap<Int, Pair<AbstractTransition, Stack<AbstractState>>>,
            pathType: PathFindingHelper.PathType,
            atuaMF: ATUAMF,
            prevEdgeId: Int?,
            root: AbstractState,
            pathTracking: HashMap<Int, Int>,
            minCost: Double,
            foundPaths: ArrayList<TransitionPath>,
            abstractStateStack: Stack<AbstractState>,
            source: AbstractState,
            nextTransitions: ArrayList<Int>,
            finalTargets: List<AbstractState>,
            goalsByTarget: Map<AbstractState, List<Input>>,
            windowAsTarget: Boolean
        ): Boolean {
            var result: Boolean = false
            val nextState = abstractTransition.dest
            var isValid = false
            if (!abstractTransition.guardEnabled) {
                isValid = true
            } else if (abstractTransition.dependentAbstractStates.isEmpty()) {
                isValid = true
            } else if (abstractTransition.dependentAbstractStates.intersect(abstractStateStack)
                    .isNotEmpty()
            ) {
                isValid = true
            }
            if (isValid) {
                val fullGraph = PathFindingHelper.createTransitionPath(
                    atuaMF,
                    pathType,
                    nextState,
                    abstractTransition,
                    prevEdgeId,
                    root,
                    traversedEdges,
                    pathTracking
                )
                val cost:Double
                if (reachedTarget(abstractTransition.dest, finalTargets, goalsByTarget,windowAsTarget)) {
                    val targetInputs = goalsByTarget.filter { it.key.window == abstractTransition.dest.window }.values.flatten().distinct()
                    fullGraph.goal.addAll(targetInputs)
                    cost = fullGraph.cost()
                    if (cost <= minCost) {
                        foundPaths.add(fullGraph)
                    }
                } else {
                    cost = fullGraph.cost()
                }
                if (cost <= minCost) {
                    if (!isDisablePath(fullGraph, pathType)) {
                        result = true
                        val nextAbstateStack = if (abstractTransition.abstractAction.isLaunchOrReset()) {
                            Stack<AbstractState>().also { it.add(AbstractStateManager.INSTANCE.ABSTRACT_STATES.find { it.isHomeScreen }!!) }
                        } else {
                            createAbstractStackForNext(
                                abstractStateStack,
                                source,
                                nextState
                            )
                        }
                        val key = if (traversedEdges.isEmpty())
                            0
                        else
                            traversedEdges.keys.max()!! + 1
                        traversedEdges.put(key, Pair(abstractTransition, nextAbstateStack))
                        if (prevEdgeId != null)
                            pathTracking.put(key, prevEdgeId)
                        nextTransitions.add(key)
                    }
                } else {
                     val a = cost
                }
            }
            return result
        }

        private fun isDisablePath(fullGraph: TransitionPath, pathType: PathFindingHelper.PathType): Boolean {
            return false
        }

        private fun reachedTarget(destination: AbstractState, finalTargets: List<AbstractState>, goalsByTarget: Map<AbstractState, List<Input>>, windowAsTarget: Boolean): Boolean {
            if (finalTargets.contains(destination)) {
                return true
            }
            if (finalTargets.map { it.window }.contains(destination.window)) {
                val goals = goalsByTarget.filter { it.key.window == destination.window && it.value.isNotEmpty() }
                if (goals.isEmpty() || windowAsTarget) {
                    return true
                } else if (goals.isNotEmpty()) {
                    if (destination.getAvailableInputs().intersect(goals.values.flatten()).isNotEmpty()) {
                        return true
                    } else {
                        return false
                    }
                }
            }
            return false
        }

        private fun createAbstractStackForNext(
            AbstractStateStack: Stack<AbstractState>,
            prevState: AbstractState,
            nextState: AbstractState
        ): Stack<AbstractState> {
            val newWindowStack = AbstractStateStack.clone() as Stack<AbstractState>
            if (newWindowStack.map { it.window }.contains(nextState.window) && newWindowStack.size > 1) {
                // Return to the prev window
                // Pop the window
                while (newWindowStack.pop().window != nextState.window) {
                }
            } else {
                if (nextState.window != prevState.window) {
                    if (prevState.window !is Dialog && prevState.window !is OptionsMenu) {
                        newWindowStack.push(prevState)
                    }
                } else if (nextState.isOpeningKeyboard) {
                    newWindowStack.push(nextState)
                }
            }
            return newWindowStack
        }
    }
}