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

import org.calm.ModelBackwardAdapter
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.Rotation
import org.atua.modelFeatures.dstg.AbstractAction
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
import org.atua.modelFeatures.ewtg.window.FakeWindow
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.mapping.EWTG_DSTGMapping
import org.droidmate.exploration.modelFeatures.graph.Edge
import org.droidmate.explorationModel.interaction.State
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet

class ProbabilityBasedPathFinder {
    companion object {


        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(this::class.java) }

        val DEFAULT_MAX_COST: Double = 25.0
        val allAvailableTransitionPaths = HashMap<Pair<AbstractState, AbstractState>, ArrayList<TransitionPath>>()
        private val disableEdges = HashSet<Edge<AbstractState, AbstractTransition>>()
        private val disablePaths = HashSet<Pair<List<AbstractTransition>, DisablePathType>>()
        val disableActionSequences = HashMap<AbstractState, ArrayList<LinkedList<AbstractAction>>>()
        val disableInputs1 = HashSet<Input>()
        val disableAbstractActions1 = HashSet<AbstractAction>()
        val disableWindows1: HashSet<Window> = HashSet()
        val disableInputs2 = HashSet<Input>()
        val disableAbstractActions2 = HashSet<AbstractAction>()
        val disableWindows2: HashSet<Window> = HashSet()
        val unavailableActions = HashMap<AbstractState, ArrayList<AbstractAction>>()
        val disableActionsTriggeredByActions = HashMap<AbstractAction, HashMap<Window,ArrayList<AbstractAction>>>()

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
            goalsByTarget: Map<AbstractState, List<Goal>>,
            maxCost: Double,
            abandonedAppStates: List<AbstractState>,
            constraints: Map<PathConstraint, Boolean>
        ) {
            val targetTraces =
                if (pathType == PathFindingHelper.PathType.PARTIAL_TRACE || pathType == PathFindingHelper.PathType.FULLTRACE) {
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
                val exisitingEdgesToTarget =
                    autautMF.dstg.edges().filter { finalTargets.contains(it.destination?.data) }
                if (exisitingEdgesToTarget.isEmpty())
                    return

            }
            val existingEdgesToTargets =
                autautMF.dstg.edges().filter { finalTargets.map { it.window }.contains(it.destination?.data?.window) }
                    .map { it.label }
            if (existingEdgesToTargets.isEmpty()) {
                return
            } else {
                val allTargetInputs = goalsByTarget.values.flatten()
                if (allTargetInputs.isNotEmpty()) {
                    val reachingTargetInputsEdges = existingEdgesToTargets.filter {
                        it.dest.getAvailableInputs().intersect(allTargetInputs).isNotEmpty()
                    }
                    if (allTargetInputs.isEmpty() && !windowAsTarget) {
                        return
                    }
                }
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
                    stopWhenHavingUnexercisedAction = false,
                    shortest = shortest,
                    pathCountLimitation = pathCountLimitation,
                    followTrace = false,
                    pathType = pathType,
                    targetTraces = targetTraces,
                    currentAbstractStateStack = currentAbstractStateStack,
                    goalsByTarget = goalsByTarget,
                    maxCost = maxCost,
                    windowAsTarget = windowAsTarget,
                    abandonedAppStates = abandonedAppStates,
                    pathContraints = constraints
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
                    stopWhenHavingUnexercisedAction = false,
                    shortest = shortest,
                    pathCountLimitation = pathCountLimitation,
                    followTrace = false,
                    pathType = pathType,
                    targetTraces = targetTraces,
                    currentAbstractStateStack = currentAbstractStateStack,
                    goalsByTarget = goalsByTarget,
                    maxCost = maxCost,
                    windowAsTarget = windowAsTarget,
                    abandonedAppStates = abandonedAppStates,
                    pathContraints = constraints
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
                    stopWhenHavingUnexercisedAction = false,
                    shortest = shortest,
                    pathCountLimitation = pathCountLimitation,
                    followTrace = false,
                    pathType = pathType,
                    targetTraces = targetTraces,
                    currentAbstractStateStack = currentAbstractStateStack,
                    goalsByTarget = goalsByTarget,
                    maxCost = maxCost,
                    windowAsTarget = windowAsTarget,
                    abandonedAppStates = abandonedAppStates,
                    pathContraints = constraints
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
            windowAsTarget: Boolean,
            stopWhenHavingUnexercisedAction: Boolean = false,
            shortest: Boolean = true,
            pathCountLimitation: Int = 1,
            followTrace: Boolean,
            pathType: PathFindingHelper.PathType,
            targetTraces: List<Int>,
            currentAbstractStateStack: List<AbstractState>,
            goalsByTarget: Map<AbstractState, List<Goal>>,
            maxCost: Double,
            abandonedAppStates: List<AbstractState>,
            pathContraints: Map<PathConstraint, Boolean>
        ) {
//            log.debug("Depth: $depth")
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
                        followTrace = followTrace,
                        pathType = pathType,
                        targetTraces = targetTraces,
                        currentAbstractStateStack = currentAbstractStateStack,
                        goalsByTarget = goalsByTarget,
                        maxCost = maxCost,
                        windowAsTarget = windowAsTarget,
                        abandonedAppStates = abandonedAppStates,
                        pathContraints = pathContraints
                    )
                } else {
                    return
                }
            } else {
//                foundPaths.removeIf { it.cost(final = true) > maxCost }
                for (traversing in prevEdgeIds) {
                    val source = traversedEdges[traversing]!!.first.dest
                    if (source.window is Launcher)
                        continue
                    if (source is VirtualAbstractState && !(pathContraints[PathConstraint.INCLUDE_WTG]?:false)) {
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
                        followTrace = followTrace,
                        pathType = pathType,
                        targetTraces = targetTraces,
                        currentAbstractStateStack = currentAbstractStateStack,
                        goalsByTarget = goalsByTarget,
                        maxCost = maxCost,
                        windowAsTarget = windowAsTarget,
                        abandonedAppStates = abandonedAppStates,
                        pathContraints = pathContraints
                    )
                }
            }
            if (nextTransitions.isEmpty())
                return
            if (foundPaths.isNotEmpty()
                && foundPaths.any { it.path.values.all {
                    it.dest.guiStates.isNotEmpty() } }) {
                return
            }
            if (depth>15) {
                return
            }
            val newMinCost = foundPaths.map { it.cost(final = false) }.minOrNull() ?: maxCost
//            foundPaths.removeIf { it.cost() > newMinCost }
//            val newMinCost = minCost
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
                stopWhenHavingUnexercisedAction = stopWhenHavingUnexercisedAction,
                shortest = shortest,
                pathCountLimitation = pathCountLimitation,
                followTrace = followTrace,
                pathType = pathType,
                targetTraces = targetTraces,
                currentAbstractStateStack = currentAbstractStateStack,
                goalsByTarget = goalsByTarget,
                maxCost = newMinCost,
                windowAsTarget = windowAsTarget,
                abandonedAppStates = abandonedAppStates,
                pathContraints = pathContraints
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
            windowAsTarget: Boolean,
            stopWhenHavingUnexercisedAction: Boolean,
            followTrace: Boolean,
            pathType: PathFindingHelper.PathType,
            targetTraces: List<Int>,
            currentAbstractStateStack: List<AbstractState>,
            goalsByTarget: Map<AbstractState, List<Goal>>,
            abandonedAppStates: List<AbstractState>,
            maxCost: Double,
            pathContraints: Map<PathConstraint,Boolean>
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
//            val traveredAbstractTransitions = prevAbstractTransitions
            val traveredAppStates = traveredAbstractTransitions.map { it.dest }
            val allAvailableActions = source.getAvailableActions()
            val validAbstractActions = allAvailableActions.filter {
                (it.actionType != AbstractActionType.RESET_APP
                        || (depth == 0 && pathContraints[PathConstraint.INCLUDE_RESET]?:false ))
                        && it.actionType != AbstractActionType.ACTION_QUEUE
                        && it.actionType != AbstractActionType.UNKNOWN
                        && (!(pathContraints[PathConstraint.FORCING_LAUNCH]?:false)
                        || depth!=0 || it.actionType == AbstractActionType.LAUNCH_APP)
                        /*&& (it.actionType != AbstractActionType.SWIPE || !it.isWebViewAction())*/

            }

            validAbstractActions.forEach { abstractAction ->
                val abstractTransitions = source.abstractTransitions.filter { absTransition ->
                    (!absTransition.abstractAction.isItemAction() || absTransition.abstractAction.isWebViewAction())
                    && absTransition.abstractAction == abstractAction
                            && absTransition.activated == true
                            && absTransition.ignored == false
                            && (absTransition.interactions.isNotEmpty()
                                || (pathContraints[PathConstraint.INCLUDE_WTG]?:false
                                    || !absTransition.fromWTG || absTransition.dest is PredictedAbstractState )
                                /*&& (!absTransition.fromWTG || !source.abstractTransitions.any {
                                !it.fromWTG && it.abstractAction == abstractAction
                            } )*/)
                            && absTransition.dest.window !is Launcher
//                            && absTransition.dest !is PredictedAbstractState
                }
                if (true || !abstractTransitions.any {
                        it.source == it.dest && it.interactions.isNotEmpty()
                    }) {
                    val filterAbstractStateStack = Stack<AbstractState>()
                    if (atuaMF.disablePrevAbstractStates.containsKey(abstractAction)) {
                        val ignoreAbstractStates = HashSet<AbstractState>()
                        for (abstractState in abstractStateStack.reversed()) {
                            if (!ignoreAbstractStates.contains(abstractState) && atuaMF.disablePrevAbstractStates[abstractAction]!!.containsKey(abstractState)) {
                                ignoreAbstractStates.addAll(atuaMF.disablePrevAbstractStates[abstractAction]!![abstractState]!!)
                            }
                        }
                        for (abstractState in abstractStateStack) {
                            if (!ignoreAbstractStates.contains(abstractState)) {
                                filterAbstractStateStack.push(abstractState)
                            }
                        }
                    } else {
                        filterAbstractStateStack.addAll(abstractStateStack)
                    }

                    var mostPertinentAbstractTransitions = ArrayList<AbstractTransition>()
                    mostPertinentAbstractTransitions.addAll(abstractTransitions.filter {
                        !it.guardEnabled || !considerGuardedTransitions }.filter{it.dest !is PredictedAbstractState})
                    val guardedAbstractTransitions = abstractTransitions.subtract(mostPertinentAbstractTransitions)
                    for (abstractState in filterAbstractStateStack.reversed()) {
                        val selectedGuaredAbstractTransitions = guardedAbstractTransitions.filter{
                            it.dependentAbstractStates.any { s1->
                                s1 == abstractState || s1.hashCode == abstractState.hashCode
                                        || ModelBackwardAdapter.instance.backwardEquivalentAbstractStateMapping[abstractState]?.contains(s1)?:false
                                        || (it.fromWTG && s1.window == abstractState.window)
                            }
                        }
                        if (selectedGuaredAbstractTransitions.isNotEmpty()) {
                            mostPertinentAbstractTransitions.addAll(selectedGuaredAbstractTransitions)
                            break
                        }
                    }
                    /*if (mostPertinentAbstractTransitions.isEmpty()) {
                        mostPertinentAbstractTransitions.addAll(goodAbstactTransitions)
                    }*/
                    var selectedExercisedAbstractTransition = false
                    if (mostPertinentAbstractTransitions.isNotEmpty()) {
                        mostPertinentAbstractTransitions.forEach { abstractTransition ->
                            if (!traveredAbstractTransitions.contains(abstractTransition)
                                && abstractTransition.source != abstractTransition.dest) {
                                selectedExercisedAbstractTransition = processAbstractTransition(
                                    abstractTransition = abstractTransition,
                                    traversedEdges = traversedEdges,
                                    pathType = pathType,
                                    atuaMF = atuaMF,
                                    prevEdgeId = prevEdgeId,
                                    root = root,
                                    pathTracking = pathTracking,
                                    maxCost = maxCost,
                                    foundPaths = foundPaths,
                                    abstractStateStack = abstractStateStack,
                                    source = source,
                                    nextTransitions = nextTransitions,
                                    finalTargets = finalTargets,
                                    goalsByTarget = goalsByTarget,
                                    abandonedAppStates = abandonedAppStates,
                                    windowAsTarget = windowAsTarget,
                                    maximumDSTG = pathContraints[PathConstraint.MAXIMUM_DSTG]?:false
                                )
                            }
                        }
                    }
                    val isAllImplicitTransitions = abstractTransitions.all { it.interactions.isEmpty() }
                    val isNonDeterministic = mostPertinentAbstractTransitions.all {it.nondeterministic}
                    /*val isManyCardinalityAVM = if (abstractAction.isWidgetAction()) {
                        if (source.avmCardinalities[abstractAction.attributeValuationMap!!] == Cardinality.MANY) {
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }*/
                    val isManyCardinalityAVM = false
                    val isTransitionsEmpty = abstractTransitions.isEmpty()
                    val similarAbstractAction = if (atuaMF.dstg.abstractActionEnables.containsKey(abstractAction)) {
                        abstractAction
                    } /*else{
                        atuaMF.dstg.abstractActionEnables.entries.find {
                            it.key.isWidgetAction() && it.key.window == abstractAction.window
                                    && it.key.actionType == abstractAction.actionType
                                    && it.key.extra == abstractAction.extra
                                    && (abstractAction.attributeValuationMap?.isDerivedFrom(it.key.attributeValuationMap!!,abstractAction.window)?:false
                                    || (abstractAction.isWidgetAction() && ModelBackwardAdapter.instance.matchingAVMs[abstractAction.attributeValuationMap!!]?.contains(it.key.attributeValuationMap!!)?:false)) }?.key
                    }*/
                    else {
                        null
                    }
                    if (similarAbstractAction!=null
                        && isConsideredForPredicting(similarAbstractAction!!)
                        && (mostPertinentAbstractTransitions.isEmpty() || isNonDeterministic
                                || isTransitionsEmpty || isAllImplicitTransitions || isManyCardinalityAVM )
                        && (pathType == PathFindingHelper.PathType.WIDGET_AS_TARGET || pathType == PathFindingHelper.PathType.WTG)
                        && (goalsByTarget.isNotEmpty() || windowAsTarget)) {
                        var predictedAction = false
                        if (traveredAbstractTransitions.any {
                                it.dest is PredictedAbstractState
                                        && it.abstractAction == abstractAction }) {
                            predictedAction = true
                        }
//                    predictedAction = false
                        if (!predictedAction) {
                            val exisitingPredictedTransitions =
                                abstractTransitions.filter { it.dest is PredictedAbstractState }
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
                                            maxCost = maxCost,
                                            foundPaths = foundPaths,
                                            abstractStateStack = abstractStateStack,
                                            source = source,
                                            nextTransitions = nextTransitions,
                                            finalTargets = finalTargets,
                                            goalsByTarget = goalsByTarget,
                                            abandonedAppStates = abandonedAppStates,
                                            windowAsTarget = windowAsTarget,
                                            maximumDSTG = pathContraints[PathConstraint.MAXIMUM_DSTG]?:false
                                        )
                                    }
                                }
                            } else {
                                // predict destination
                                if ( similarAbstractAction.isWidgetAction() || similarAbstractAction.actionType == AbstractActionType.PRESS_BACK || true
                                ) {
                                    var reachableAbstractActionsByWindow: HashMap<Window,HashMap<AbstractAction, Int>> = HashMap()
                                    val totalcntByWindow = HashMap<Window, Int>()
                                    val enabledAbstractActions =  atuaMF.dstg.abstractActionEnables[similarAbstractAction]!!

                                    for (abstractState in filterAbstractStateStack.reversed()) {
                                        val dependentWindow = abstractState.window

                                        if (enabledAbstractActions.contains(dependentWindow)
                                        ) {
                                            reachableAbstractActionsByWindow.put(dependentWindow, HashMap())
                                            reachableAbstractActionsByWindow[dependentWindow]!!.putAll(
                                                enabledAbstractActions[dependentWindow]!!.filter {
                                                    it.key.actionType != AbstractActionType.RESET_APP
                                                            && it.key.isWidgetAction()
                                                })
                                            val count1 =  atuaMF.dstg.abstractActionCounts[dependentWindow]!![similarAbstractAction]
                                            if (count1!=null) {
                                                totalcntByWindow.put(
                                                    dependentWindow,
                                                    count1
                                                )
                                            }
                                            break
                                        }
                                    }

                                    if(enabledAbstractActions.any { it.key is FakeWindow }) {
                                        val fakeWindow = FakeWindow.getOrCreateNode(false)
                                        reachableAbstractActionsByWindow.put(fakeWindow,HashMap())
                                        reachableAbstractActionsByWindow[fakeWindow]!!.putAll(enabledAbstractActions[fakeWindow]!!.filter {
                                            it.key.actionType != AbstractActionType.RESET_APP
                                                    && it.key.isWidgetAction()
                                        })
                                        val count2 = atuaMF.dstg.abstractActionCounts[fakeWindow]!![similarAbstractAction]
                                        if (count2!=null)
                                            totalcntByWindow.put(fakeWindow, count2)
                                    }

                                    reachableAbstractActionsByWindow.keys.forEach { dependentWindow->
                                        val reachableAbstractActions = reachableAbstractActionsByWindow[dependentWindow]!!
                                        val disableActions = disableActionsTriggeredByActions[similarAbstractAction]?.get(dependentWindow)?:emptyList<AbstractAction>()
                                        val abstractActionsByWindow = reachableAbstractActions.keys.groupBy { it.window }
                                        abstractActionsByWindow.filter { it.key !is Launcher }. forEach { window, abstractActions ->
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
                                                val totalCnt = totalcntByWindow[dependentWindow]
                                                if (totalCnt!=null) {
                                                    val prob =
                                                        reachableAbstractActions[action]!! * 1.0 / totalCnt
                                                    if (prob>=0.75 && !disableActions.contains(action)){
                                                        if (!action.isWidgetAction()) {
                                                            if (!predictAbstractState.containsActionCount(action,atuaMF))
                                                                predictAbstractState.setActionCount(action, 0,atuaMF)
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


                                                        val effectiveness = if (totalCnt == 1) 0.0
                                                        else  1.0
                                                        predictAbstractState.abstractActionsEffectivenss.put(action,effectiveness)
                                                    }
                                                }
//                                            AbstractStateManager.INSTANCE.initAbstractInteractions(predictAbstractState)

                                            }
                                            if (predictAbstractState.attributeValuationMaps.isNotEmpty()) {
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
                                                    maxCost = maxCost,
                                                    goalsByTarget = goalsByTarget,
                                                    atuaMF = atuaMF,
                                                    abandonedAppStates = abandonedAppStates,
                                                    windowAsTarget = windowAsTarget,
                                                    maximumDSTG = pathContraints[PathConstraint.MAXIMUM_DSTG]?:false
                                                )
                                            }

                                        }
                                    }

                                }
                            }
                        }

                    }
                }

            }
        }

        private fun isConsideredForPredicting(abstractAction: AbstractAction) =
            (abstractAction.actionType != AbstractActionType.RESET_APP)

        private fun processAbstractTransition(
            abstractTransition: AbstractTransition,
            traversedEdges: HashMap<Int, Pair<AbstractTransition, Stack<AbstractState>>>,
            pathType: PathFindingHelper.PathType,
            atuaMF: ATUAMF,
            prevEdgeId: Int?,
            root: AbstractState,
            pathTracking: HashMap<Int, Int>,
            maxCost: Double,
            foundPaths: ArrayList<TransitionPath>,
            abstractStateStack: Stack<AbstractState>,
            source: AbstractState,
            nextTransitions: ArrayList<Int>,
            finalTargets: List<AbstractState>,
            goalsByTarget: Map<AbstractState, List<Goal>>,
            abandonedAppStates: List<AbstractState>,
            windowAsTarget: Boolean,
            maximumDSTG: Boolean
        ): Boolean {
            var result: Boolean = false
            val nextState = abstractTransition.dest
            var isValid = true
            if (abstractTransition.dest is PredictedAbstractState
                && abstractTransition.dest.abstractActionsProbability.isEmpty()) {
                isValid = false
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
                    pathTracking,
                    abstractStateStack
                )
                val cost: Double
                if (reachedTarget(abstractTransition.dest, finalTargets, goalsByTarget, abandonedAppStates, windowAsTarget)) {
                    val targetInputs =
                        goalsByTarget.filter {
                            it.key.window == abstractTransition.dest.window }.values.flatten()
                            .distinct().filter {
                                if (it.abstractAction!=null) {
                                    abstractTransition.dest.getAvailableActions(null).contains(it.abstractAction)
                                } else {
                                    abstractTransition.dest.getAvailableInputs().contains(it.input)
                                }
                            }
                    fullGraph.goal.addAll(targetInputs)
                    cost = fullGraph.cost(final = true)
                    if (foundPaths.isNotEmpty()) {
                        val finalmaxCost = foundPaths.map { it.cost(final = true) }.minOrNull()!!
                        if (cost <= finalmaxCost) {
                            foundPaths.add(fullGraph)
                        }
                    } else
                        foundPaths.add(fullGraph)
                    /*if (cost <= finalmaxCost || (maximumDSTG
                                && fullGraph.path.values.all {
                            it.dest.guiStates.isNotEmpty()
                                   })) {
                    }*/
                } else {
                    cost = fullGraph.cost(final = false)
                }
                if (cost < maxCost /*|| (maximumDSTG
                            && fullGraph.path.values.all {
                        it.dest.guiStates.isNotEmpty() })*/) {
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
                            traversedEdges.keys.maxOrNull()!! + 1
                        traversedEdges.put(key, Pair(abstractTransition, nextAbstateStack))
                        if (prevEdgeId != null)
                            pathTracking.put(key, prevEdgeId)
                        nextTransitions.add(key)
                    }
                }
            }
            return result
        }

        private fun isDisablePath(fullGraph: TransitionPath, pathType: PathFindingHelper.PathType): Boolean {
            // get action sequences from predicted abstract states
 /*           if (pathType == PathFindingHelper.PathType.WIDGET_AS_TARGET) {
                var startAbstractState: AbstractState? = null
                val predictedSequence = LinkedList<AbstractAction>()
                var action: AbstractAction? = null
                var actionId = 0
                action = fullGraph.path[actionId]?.abstractAction
                while (action != null) {
                    val transition = fullGraph.path[actionId]!!
                    if (transition.dest is PredictedAbstractState) {
                        if (predictedSequence.isEmpty()) {
                            startAbstractState = transition.source
                        }
                        predictedSequence.add(transition.abstractAction)
                    }
                    actionId++
                    action = fullGraph.path[actionId]?.abstractAction
                }
                val corruptedSequences = disableActionSequences[startAbstractState]
                if (corruptedSequences == null) {
                    return false
                }
                if (corruptedSequences.find { it == predictedSequence } != null) {
                    return true
                }
            }*/
            return false
        }

        private fun reachedTarget(
            destination: AbstractState,
            finalTargets: List<AbstractState>,
            goalsByTarget: Map<AbstractState, List<Goal>>,
            abandonedAppStates: List<AbstractState>,
            windowAsTarget: Boolean
        ): Boolean {
            if (finalTargets.contains(destination)
                && destination !is VirtualAbstractState) {
                return true
            }
            if (abandonedAppStates.contains(destination))
                return false
            if (destination.ignored)
                return false
            if (finalTargets.map { it.window }.contains(destination.window)) {
                val goals = goalsByTarget.filter { it.key.window == destination.window && it.value.isNotEmpty() }.values.flatten()
                if ((goals.isEmpty()
                            && windowAsTarget)
                    /*|| goals.any {
                            if (it.abstractAction != null) {
                                !it.abstractAction.isWidgetAction()
                            } else {
                        !it.input!!.witnessed || !it.input!!.eventType.isWidgetEvent() }  }*/) {
                    /*if (destination.getUnExercisedActions2(null).isNotEmpty()
                        || destination is VirtualAbstractState)

                    else
                        return false*/
                    return true
                } else if (goals.isNotEmpty()) {
                    goals.forEach {
                        if (it.abstractAction != null) {
                            if (destination.getAvailableActions(null).contains(it.abstractAction)) {
                                return true
                            }
                        } else {
                            if (destination.getAvailableInputs().contains(it.input!!))
                                return true
                        }
                    }
                    return false
                }
            }
            return false
        }

        private fun createAbstractStackForNext(
            AbstractStateStack: Stack<AbstractState>,
            prevState: AbstractState,
            nextState: AbstractState
        ): Stack<AbstractState> {
            val appStateStack = AbstractStateStack.clone() as Stack<AbstractState>
            if (appStateStack.isNotEmpty()) {
                val topWindow = appStateStack.peek().window
                if (topWindow == nextState.window){
                    // Stay in the same window
                    // Just remove any state similar to the current state
                    appStateStack.removeIf {
                        it.isSimlarAbstractState(nextState,0.8)
                    }
                } else {
                    if (appStateStack.map { it.window }.contains(nextState.window)
                        && appStateStack.size > 1 ) {
                        // Return to one of the previous windows
                        while(appStateStack.peek().window!=nextState.window) {
                            appStateStack.pop()
                            if (appStateStack.isEmpty())
                                break
                        }
                        if (appStateStack.isNotEmpty()) {
                            appStateStack.removeIf {
                                it.isSimlarAbstractState(nextState,0.8)
                            }
                        }
                    }
                }
            }
            appStateStack.push(nextState)
            return appStateStack
        }
    }
}

enum class PathConstraint {
    INCLUDE_RESET,
    FORCING_LAUNCH,
    FORCING_RESET,
    INCLUDE_LAUNCH,
    INCLUDE_WTG,
    MAXIMUM_DSTG,
    TARGET_ABSTRACT_ACTION
}
