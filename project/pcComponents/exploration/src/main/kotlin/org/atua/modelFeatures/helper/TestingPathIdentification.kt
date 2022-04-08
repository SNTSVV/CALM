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

import org.atua.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.ATUAMF
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.DSTG
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.PathTraverser
import org.atua.modelFeatures.ewtg.TransitionPath
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.FakeWindow
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.Window
import org.droidmate.exploration.modelFeatures.graph.Edge
import org.droidmate.explorationModel.interaction.State
import java.util.*

class PathFindingHelper {
    companion object {
        val allAvailableTransitionPaths = HashMap<Pair<AbstractState, AbstractState>, ArrayList<TransitionPath>>()
        private val disableEdges = HashSet<Edge<AbstractState, AbstractTransition>>()
        private val disablePaths = HashSet<Pair<List<AbstractTransition>, DisablePathType>>()


        private fun satisfyResetPathType(it: TransitionPath): Boolean {
            if (it.path.get(0)!!.abstractAction.actionType != AbstractActionType.RESET_APP) {
                return false
            }
            return true
        }



        private fun includingBackwardEquivalentAT(transition: AbstractTransition, depth: Int): Boolean {
            return true
            /*if (depth<=5) {
                    return true
                }
                val isBackwardEquivalent = (
                        transition.source.modelVersion != ModelVersion.BASE
                                && transition.interactions.isEmpty()
                                && transition.modelVersion == ModelVersion.BASE)
                return !isBackwardEquivalent*/
        }

        private fun IsCurrentPathStartingWithLaunchOrReset(
            ancestorEdgeId: Int?,
            traversedEdges: HashMap<Int, Pair<AbstractTransition, Stack<AbstractState>>>,
            ancestorAbstractStates: Stack<AbstractState>,
            pathTracking: HashMap<Int, Int>
        ): Boolean {
            var ancestorEdgeId1 = ancestorEdgeId
            var startedWithLaunchOrResetAction = false
            while (ancestorEdgeId1 != null) {
                val ancestorEdge = traversedEdges.get(ancestorEdgeId1)!!.first
                if (ancestorEdge.abstractAction.isLaunchOrReset()) {
                    startedWithLaunchOrResetAction = true
                    break
                }
                val sourceAbstractState = ancestorEdge.source
                if (!ancestorAbstractStates.any { it.window == sourceAbstractState.window })
                    ancestorAbstractStates.add(sourceAbstractState)
                ancestorEdgeId1 = pathTracking.get(ancestorEdgeId1)
            }
            return startedWithLaunchOrResetAction
        }

        private fun isTheSamePrevWinAbstractState(
            prevWinAbstractState1: AbstractState?,
            prevWinAbstractState2: AbstractState?
        ): Boolean {
            return (
                    (prevWinAbstractState1 != null && prevWinAbstractState2 == prevWinAbstractState1)
                            || prevWinAbstractState1 == null || prevWinAbstractState2 == null)
        }

        private fun getPrevWinAbstractState(
            prevWindow: Window?,
            pathTracking: HashMap<Int, Int>,
            prevEdgeId: Int?,
            traversedEdges: HashMap<Int, Pair<AbstractTransition, Stack<AbstractState>>>
        ): AbstractState? {
            if (prevWindow == null)
                return null
            if (prevEdgeId == null)
                return null
            var prevEdgeId2 = prevEdgeId
            var prevWinAbstractState = traversedEdges.get(prevEdgeId2)!!.first.source
            while (prevWinAbstractState.window != prevWindow) {
                prevEdgeId2 = pathTracking.get(prevEdgeId2)
                if (prevEdgeId2 == null) {
                    return null
                }
                prevWinAbstractState = traversedEdges.get(prevEdgeId2)!!.first.source
            }
            return prevWinAbstractState
        }

        private fun followTrace(
            edge: Edge<AbstractState, AbstractTransition>,
            depth: Int,
            targetTraces: List<Int>,
            traversedEdges: HashMap<Int, Pair<AbstractTransition, Stack<AbstractState>>>,
            prevEdgeId: Int?,
            pathTracking: HashMap<Int, Int>,
            pathType: PathType
        ): Boolean {
            if (pathType != PathType.PARTIAL_TRACE && pathType != PathType.FULLTRACE) {
                return true
            }
            if (prevEdgeId == null)
                return true
            if (edge.label.isImplicit)
                return false
            if (edge.label.abstractAction.actionType == AbstractActionType.RESET_APP)
                return true
            if (!edge.label.tracing.any { targetTraces.contains(it.first) })
                return false
            var prevTransitionId: Int? = prevEdgeId
            var prevTransition: AbstractTransition? = traversedEdges.get(prevTransitionId)!!.first
            var currentTransition = edge.label
            val validCurrentTracing = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
            // initiate valideNextTracing as prevTransition's tracing
            prevTransition!!.tracing.forEach {
                validCurrentTracing.put(it, it)
            }
            if (prevTransition!!.abstractAction.actionType == AbstractActionType.RESET_APP) {
                if (edge.label.tracing.any { it.second == 1 }) {
                    return true
                }
                return true
            }
            if (pathType == PathType.PARTIAL_TRACE && prevTransition!!.abstractAction.actionType == AbstractActionType.LAUNCH_APP) {
                return true
            }
            // trace backward to remove nonValidTracing
            while (prevTransitionId != null && validCurrentTracing.isNotEmpty()) {
                prevTransitionId = pathTracking.get(prevTransitionId)
                if (prevTransitionId == null)
                    break
                prevTransition = traversedEdges.get(prevTransitionId)!!.first
                if (prevTransition!!.abstractAction.actionType == AbstractActionType.RESET_APP) {
                    break
                }
                if (prevTransition!!.abstractAction.actionType == AbstractActionType.LAUNCH_APP
                    && pathType == PathType.PARTIAL_TRACE
                ) {
                    break
                }
                val kill = HashSet<Pair<Int, Int>>()
                val new = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
                validCurrentTracing.forEach { currentTrace, backwardTrace ->
                    val backwardCompatible = prevTransition!!.tracing.filter {
                        it.first == currentTrace.first
                                && it.second == backwardTrace.second - 1
                    }
                    if (backwardCompatible.isEmpty())
                        kill.add(currentTrace)
                    else {
                        new.put(currentTrace, backwardCompatible.single())
                    }
                }
                validCurrentTracing.clear()
                validCurrentTracing.putAll(new)
            }

            if (edge.label.tracing.any {
                    validCurrentTracing.keys.map { it.first }.contains(it.first)
                            && validCurrentTracing.keys.map { it.second + 1 }.contains(it.second)
                }) {
                return true
            } else {
                return false
            }

            val traceIds = HashSet<Int>()
            traceIds.addAll(edge.label.tracing.map { it.first })
            while (prevTransitionId != null && traceIds.isNotEmpty()) {
                val prevTransition = traversedEdges.get(prevTransitionId)?.first
                if (prevTransition == null)
                    throw Exception("Prev transition is null")
                if (prevTransition.abstractAction.actionType == AbstractActionType.RESET_APP) {
                    if (pathType == PathType.PARTIAL_TRACE || currentTransition.tracing.any { it.second == 1 })
                        return true
                    else
                        return false
                }
                if (isTransitionFollowingTrace(currentTransition, targetTraces, prevTransition, traceIds)) {
                    val validTraces = currentTransition.tracing.filter { t1 ->
                        traceIds.contains(t1.first) &&
                                prevTransition.tracing.any { t2 ->
                                    t2.first == t1.first
                                            && t2.second + 1 == t1.second
                                }
                    }
                    if (validTraces.isEmpty()) {
                        return false
                    }
                    traceIds.clear()
                    traceIds.addAll(validTraces.map { it.first })
                    prevTransitionId = pathTracking.get(prevTransitionId)
                    currentTransition = prevTransition
                    continue
                }
                return false
            }
            if (prevTransitionId == null)
                return true
            return false
        }

        private fun isTransitionFollowingTrace(
            currentTransition: AbstractTransition,
            targetTraces: List<Int>,
            prevTransition: AbstractTransition,
            traceIds: HashSet<Int>
        ): Boolean {
            if (currentTransition.tracing.all { t ->
                    !targetTraces.contains(t.first)
                            || !traceIds.contains(t.first)
                }) {
                return false
            }
            return currentTransition.tracing.any { t ->
                traceIds.contains(t.first) &&
                        prevTransition.tracing.any {
                            it.first == t.first
                                    && it.second + 1 == t.second
                        }
            }
        }

        private fun includingBackEventOrNot(
            it: Edge<AbstractState, AbstractTransition>,
            includeImplicitBackEvent: Boolean
        ): Boolean {
            if (includeImplicitBackEvent)
                return true
            if ((it.label.abstractAction.actionType == AbstractActionType.PRESS_BACK || AbstractStateManager.INSTANCE.goBackAbstractActions.contains(
                    it.label.abstractAction
                ))
                && it.label.isImplicit
            )
                return false
            return true
        }

        private fun includingWTGOrNot(edge: Edge<AbstractState, AbstractTransition>, includeWTG: Boolean): Boolean {
            if (includeWTG)
                return true
            if (edge.label.fromWTG || edge.label.dest is VirtualAbstractState)
                return false
            return true
        }


        private fun includingResetOrNot(
            it: Edge<AbstractState, AbstractTransition>,
            includeReset: Boolean,
            depth: Int,
            onlyStartWithReset: Boolean
        ): Boolean {
            if (includeReset && depth == 0) {
                if (onlyStartWithReset)
                    return it.label.abstractAction.actionType == AbstractActionType.RESET_APP && it.label.isImplicit
                else
                    return true
            }
            return it.label.abstractAction.actionType != AbstractActionType.RESET_APP
        }

        private fun includingLaunchOrNot(
            it: Edge<AbstractState, AbstractTransition>,
            includeLaunch: Boolean,
            depth: Int
        ): Boolean {
            return it.label.abstractAction.actionType != AbstractActionType.LAUNCH_APP
            if (includeLaunch && depth == 0) {
                return true
            } else
                return it.label.abstractAction.actionType != AbstractActionType.LAUNCH_APP
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

        private fun backToLauncherOrNot(it: Edge<AbstractState, AbstractTransition>) =
            it.destination!!.data.window !is Launcher

        private fun includingRotateUIOrNot(
            autautMF: org.atua.modelFeatures.ATUAMF,
            it: Edge<AbstractState, AbstractTransition>
        ): Boolean {
            if (autautMF.appRotationSupport)
                return true
            return it.label.abstractAction.actionType != AbstractActionType.ROTATE_UI
        }

        private fun isTheSamePrevWindow(prevWindow1: Window?, prevWindow2: Window?): Boolean {
            return (
                    (prevWindow1 != null && prevWindow2 == prevWindow1)
                            || prevWindow1 == null || prevWindow2 == null)
        }

        fun createTransitionPath(
            autautMF: org.atua.modelFeatures.ATUAMF,
            pathType: PathType,
            destination: AbstractState,
            lastTransition: AbstractTransition,
            prevEdgeId: Int?,
            startingNode: AbstractState,
            traversedEdges: HashMap<Int, Pair<AbstractTransition, Stack<AbstractState>>>,
            pathTracking: HashMap<Int, Int>,
            abstractStateStack: Stack<AbstractState>
        ): TransitionPath {
            val fullPath = TransitionPath(startingNode, pathType, destination)
            fullPath.abstractStateStack.addAll(abstractStateStack)
            val path = LinkedList<AbstractTransition>()
            path.add(lastTransition)

            var traceBackEdgeId: Int? = prevEdgeId
            //construct path as a linkedlist

            while (traceBackEdgeId != null) {
                val transition = traversedEdges[traceBackEdgeId]!!.first
                path.addFirst(transition)

                traceBackEdgeId = pathTracking[traceBackEdgeId]
                if (traceBackEdgeId == null)
                    break
            }
            var transitionId = 0
            while (path.isNotEmpty()) {
                val transition = path.first
                val source = transition.source
                val destination = transition.dest
                fullPath.path.put(transitionId, transition)
                //fullPath.edgeConditions[edge] = pathTracking[backwardNode]!!.third
//                val graphEdge = autautMF.dstg.edge(source, destination, transition)
                path.removeFirst()
                transitionId++
            }
            return fullPath
        }

        fun registerTransitionPath(source: AbstractState, destination: AbstractState, fullPath: TransitionPath) {
            if (!allAvailableTransitionPaths.containsKey(Pair(source, destination)))
                allAvailableTransitionPaths.put(Pair(source, destination), ArrayList())
            allAvailableTransitionPaths[Pair(source, destination)]!!.add(fullPath)
        }

        val unsuccessfulInteractions = HashMap<AbstractTransition, Int>()
        val requireTraceAbstractInteractions = ArrayList<AbstractTransition>()
        fun addDisablePathFromState(
            transitionPath: TransitionPath,
            corruptedTransition: AbstractTransition?,
            lastTransition: AbstractTransition
        ) {
            requireTraceAbstractInteractions.add(lastTransition)
            if (lastTransition.modelVersion == ModelVersion.RUNNING
                && (lastTransition.isImplicit || lastTransition.interactions.isEmpty())
            ) {
                lastTransition.source.abstractTransitions.remove(lastTransition)
            }
            /*if (corruptedTransition!=null)
                requireTraceAbstractInteractions.add(corruptedTransition)
            else
                requireTraceAbstractInteractions.add(transitionPath.path.getValue(transitionPath.path.keys.max()!!))*/
            /* val abstractInteraction = corruptedTransition.label
            if (abstractInteraction.abstractAction.isItemAction())
            {
                if (!unsuccessfulInteractions.containsKey(abstractInteraction)) {
                    unsuccessfulInteractions.put(abstractInteraction,0)
                }
                if (unsuccessfulInteractions[abstractInteraction]!! < 5) {
                    unsuccessfulInteractions[abstractInteraction] = unsuccessfulInteractions[abstractInteraction]!!+1
                    return
                }
            }*/
            //abstractTransitionGraph.remove(corruptedTransition)

            val corruptedEdge = corruptedTransition

            if (corruptedEdge != null && corruptedEdge.abstractAction.isLaunchOrReset())
                return
            /* if (corruptedEdge.label.fromWTG) {
                 disableEdges.add(corruptedEdge)
             }*/

            val disablePath = LinkedList<AbstractTransition>()
            var pathTraverser = PathTraverser(transitionPath)
            // Move to after reset action
            while (!pathTraverser.isEnded()) {
                val edge = pathTraverser.next()
                if (edge == null)
                    break
                if (edge.abstractAction.isLaunchOrReset()) {
                    break
                }
            }
            if (pathTraverser.isEnded()) {
                if (pathTraverser.getCurrentTransition()!!.abstractAction.isLaunchOrReset()) {
                    // This incorrect transition will be automatically removed
                    return
                }
                // No reset action
                pathTraverser = PathTraverser(transitionPath)
            }

            while (!pathTraverser.isEnded()) {
                val edge = pathTraverser.next()
                if (edge == null)
                    break
                disablePath.add(edge)
                if (edge.abstractAction.isLaunchOrReset()) {
                    disablePath.clear()
                }
                if (corruptedEdge != null && edge == corruptedEdge) {
                    break
                }
            }

            if (disablePath.isNotEmpty()) {
                if (corruptedEdge == null)
                    disablePaths.add(Pair(disablePath, DisablePathType.UNACHIEVABLE_FINAL_STATE))
                else
                    disablePaths.add(Pair(disablePath, DisablePathType.UNAVAILABLE_ACTION))
            }
            val root = transitionPath.root
            val destination = transitionPath.getFinalDestination()
            unregisteredTransitionPath(root, destination, transitionPath)
            //log.debug("Disable edges count: ${disableEdges.size}")

        }

        private fun unregisteredTransitionPath(
            root: AbstractState,
            destination: AbstractState,
            transitionPath: TransitionPath
        ) {
            if (allAvailableTransitionPaths.containsKey(Pair(root, destination))) {
                val existingPaths = allAvailableTransitionPaths[Pair(root, destination)]!!
                if (existingPaths.contains(transitionPath)) {
                    existingPaths.remove(transitionPath)
                }
            }
        }

        fun checkIsDisableEdge(edge: Edge<AbstractState, AbstractTransition>): Boolean {
            if (disableEdges.contains(edge)) {
                return true
            } else
                return false
        }

        fun isDisablePath(path: TransitionPath, pathType: PathType): Boolean {
            if (pathType == PathType.FULLTRACE) {
                return false
            }
            if (pathType != PathType.PARTIAL_TRACE) {
                path.path.values.forEach {
                    if (requireTraceAbstractInteractions.any { t ->
                            t.source.hashCode == it.source.hashCode
                                    && t.dest.hashCode == it.dest.hashCode
                                    && t.abstractAction == it.abstractAction
                                    && (
                                    t.userInputs.intersect(it.userInputs).isNotEmpty()
                                            || t.userInputs.isEmpty()
                                            || it.userInputs.isEmpty())
                        })
                        return true
                }
            }
            disablePaths.forEach {
                var matched = samePrefix(it.first, it.second, path)
                if (matched)
                    return true
            }

            /*if (path.edges().any { disableEdges.contains(it) }) {
                return true
            }*/
            return false
        }

        private fun isFollowingTrace(path: TransitionPath): Boolean {
            val pathTraverser = PathTraverser(path)
            //move current cursor to the node after the RESET action
            while (!pathTraverser.isEnded()) {
                val nextTransition = pathTraverser.next()
                if (nextTransition == null)
                    break
                val actionType = nextTransition.abstractAction.actionType
                if (actionType == AbstractActionType.RESET_APP || actionType == AbstractActionType.LAUNCH_APP) {
                    break
                }
            }
            if (pathTraverser.isEnded()) {
                val prevTransition = pathTraverser.getCurrentTransition()
                if (prevTransition != null && prevTransition.abstractAction.isLaunchOrReset())
                    return true
                else {
                    //No reset or launch action
                    pathTraverser.reset()
                }
            }

            val tracing = arrayListOf<Pair<Int, Int>>()
            while (!pathTraverser.isEnded()) {
                val nextTransition = pathTraverser.next()
                if (nextTransition == null)
                    break
                val edgeTracing = nextTransition.tracing
                if (edgeTracing.isEmpty())
                    break
                if (tracing.isEmpty()) {
                    tracing.addAll(edgeTracing)
                } else {
                    val nextTraces = ArrayList<Pair<Int, Int>>()
                    edgeTracing.forEach { trace ->
                        if (tracing.any { trace.first == it.first && trace.second == it.second + 1 }) {
                            nextTraces.add(trace)
                        }
                    }
                    tracing.clear()
                    tracing.addAll(nextTraces)
                }
            }
            if (tracing.isNotEmpty())
                return true
            return false
        }

        private fun samePrefix(edges: List<AbstractTransition>, type: DisablePathType, path: TransitionPath): Boolean {
            val iterator = edges.iterator()
            //Fixme

            val pathTraverser = PathTraverser(path)

            while (!pathTraverser.isEnded()) {
                val nextTransition = pathTraverser.next()
                if (nextTransition == null)
                    break
                val actionType = nextTransition.abstractAction.actionType
                if (actionType == AbstractActionType.RESET_APP || actionType == AbstractActionType.LAUNCH_APP) {
                    break
                }
            }
            val prevTransition = pathTraverser.getCurrentTransition()
            if (prevTransition != null && !prevTransition.abstractAction.isLaunchOrReset()) {
                //No reset or launch action
                pathTraverser.reset()
            }

            var samePrefix = true
            var initial = true
            while (iterator.hasNext()) {
                val edge1 = iterator.next()
                if (pathTraverser.isEnded()) {
                    samePrefix = false
                    break
                }
                val edge2 = pathTraverser.next()
                if (edge2 == null) {
                    samePrefix = false
                    break
                }
                if (initial) {
                    initial = false
                    val startState1 = edge1.source
                    val startState2 = edge2.source
                    if (startState1 != startState2) {
                        return false
                    }

                }
                if (edge1.abstractAction == edge2.abstractAction &&
                    (edge1.dependentAbstractStates.intersect(edge2.dependentAbstractStates).isNotEmpty()
                            || edge1.dependentAbstractStates.isEmpty()
                            || edge2.dependentAbstractStates.isEmpty())
                ) {
                    continue
                } else {
                    samePrefix = false
                    break
                }
            }
            /* if (samePrefix && pathTraverser.finalStateAchieved()) {
                 if (type == DisablePathType.UNAVAILABLE_ACTION) {
                     samePrefix = false
                 }
             }*/
            return samePrefix
        }
    }

    enum class PathType {
        INCLUDE_INFERED,
        NORMAL,
        WIDGET_AS_TARGET,
        FULLTRACE,
        PARTIAL_TRACE,
        WTG
    }
}

enum class DisablePathType {
    UNAVAILABLE_ACTION,
    UNACHIEVABLE_FINAL_STATE
}
