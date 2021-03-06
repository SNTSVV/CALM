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

package org.atua.strategy

import kotlinx.coroutines.runBlocking
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.TransitionPath
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.FakeWindow
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.helper.Goal
import org.atua.modelFeatures.helper.PathConstraint
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.exploration.ExplorationContext
import org.atua.strategy.task.AbstractStrategyTask
import org.droidmate.explorationModel.interaction.State

abstract class AbstractPhaseStrategy(
    val atuaTestingStrategy: ATUATestingStrategy,
    val scaleFactor: Double,
    val useVirtualAbstractState: Boolean,
    val delay: Long,
    val useCoordinateClicks: Boolean
) {
    lateinit var phaseState: PhaseState
    lateinit var atuaMF: org.atua.modelFeatures.ATUAMF

    var strategyTask: AbstractStrategyTask? = null
    var fullControl: Boolean = false
    val windowsCorrelation = HashMap<Window, HashMap<Window, Double>>()
    abstract fun nextAction(eContext: ExplorationContext<*, *, *>): ExplorationAction
    abstract fun isTargetState(currentState: State<*>): Boolean
    abstract fun isTargetWindow(window: Window): Boolean

    abstract fun getPathsToExploreStates(
        currentState: State<*>,
        pathType: PathFindingHelper.PathType,
        maxCost: Double,
        pathConstraints: Map<PathConstraint, Boolean>
    ): List<TransitionPath>

    abstract fun getPathsToTargetWindows(
        currentState: State<*>,
        pathType: PathFindingHelper.PathType,
        maxCost: Double,
        pathConstraint: Map<PathConstraint,Boolean>
    ): List<TransitionPath>

    fun needReset(currentState: State<*>): Boolean {
        val interval = 50 * scaleFactor
        val lastReset = runBlocking {
            atuaTestingStrategy.eContext.explorationTrace.P_getActions()
                .indexOfLast { it.actionType == "ResetApp" }
        }
        val currAction = atuaTestingStrategy.eContext.explorationTrace.size
        val diff = currAction - lastReset
        return diff > interval
    }

    open fun getUnexhaustedExploredAbstractState(includeReset: Boolean): List<AbstractState> {
        val runtimeAbstractStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES
            .filterNot {
                it.window is FakeWindow
                        || it.window is Launcher
                        || it.window is OutOfApp
                        || ProbabilityBasedPathFinder.disableWindows1.contains(it.window)
                        || (!includeReset && ProbabilityBasedPathFinder.disableWindows2.contains(it.window))
                        || it is VirtualAbstractState
                        || it.ignored
//                        || (it.window is Dialog && (it.window as Dialog).ownerActivitys.all { it is OutOfApp })
                        || it.window is Dialog
                        || it.isRequestRuntimePermissionDialogBox
                        || it.isAppHasStoppedDialogBox
                        || it.attributeValuationMaps.isEmpty()
                        || it.guiStates.isEmpty()
                        || it.getUnExercisedActions2(null).filter { action->
                            !action.isCheckableOrTextInput(it)
                                    && it.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                                    && !ProbabilityBasedPathFinder.disableAbstractActions1.contains(action)
                                    && ProbabilityBasedPathFinder.disableInputs1.intersect(it.getInputsByAbstractAction(action)).isEmpty()
                                    && (includeReset || (
                                    !ProbabilityBasedPathFinder.disableAbstractActions2.contains(action)
                                            && ProbabilityBasedPathFinder.disableInputs2.intersect(it.getInputsByAbstractAction(action)).isEmpty()
                                            )  )
                        }.isEmpty()
            }
        return runtimeAbstractStates
    }

    open fun getPathsToWindowToExplore(
        currentState: State<*>,
        targetWindow: Window,
        pathType: PathFindingHelper.PathType,
        explore: Boolean,
        maxCost: Double,
        pathConstraints: Map<PathConstraint, Boolean>
    ): List<TransitionPath> {
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        if (currentAbstractState == null)
            return transitionPaths
        val includeReset = pathConstraints[PathConstraint.INCLUDE_RESET]!!
        val goalByAbstractState = HashMap<AbstractState, List<Goal>>()
        val stateWithScores = HashMap<AbstractState, Double>()
        var targetStates = getUnexhaustedExploredAbstractState(includeReset).filter{it.window == targetWindow}.toHashSet()
        if (explore) {
            targetStates.removeIf {
                it is VirtualAbstractState
            }
            var unexercisedInputs = ArrayList<Goal>()
            val canExploreAppStatesWithAbstractActions1 = targetStates.associateWith {  it.getUnExercisedActions(null, atuaMF)
                .filter { action ->
                    !action.isCheckableOrTextInput(it)
                            && it.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                            && !ProbabilityBasedPathFinder.disableAbstractActions1.contains(action)
                            && ProbabilityBasedPathFinder.disableInputs1.intersect(it.getInputsByAbstractAction(action)).isEmpty()
                            && (includeReset ||
                                (!ProbabilityBasedPathFinder.disableAbstractActions2.contains(action)
                                    && ProbabilityBasedPathFinder.disableInputs2.intersect(it.getInputsByAbstractAction(action)).isEmpty()))
                }}.filter { it.value.isNotEmpty() }
            if (canExploreAppStatesWithAbstractActions1.isNotEmpty()) {
                canExploreAppStatesWithAbstractActions1.forEach { s, actions ->
//                    unexercisedInputs1.addAll(inputs.filter { it.exerciseCount==0 })
                    actions.forEach {
                            unexercisedInputs.add(Goal(input = null,abstractAction = it))
                    }


                }
            }
            unexercisedInputs = ArrayList(unexercisedInputs.distinct())
            if (unexercisedInputs.isNotEmpty()) {
                val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window = targetWindow)!!
                goalByAbstractState.put(virtualAbstractState, unexercisedInputs)
                stateWithScores.put(virtualAbstractState, 1.0)
                getPathToStatesBasedOnPathType(
                    pathType,
                    transitionPaths,
                    stateWithScores,
                    currentAbstractState,
                    currentState,
                    true,
                    false,
                    goalByAbstractState,
                    maxCost,
                    emptyList(),
                    pathConstraints
                )

            }

            return transitionPaths

        }
        if (transitionPaths.isEmpty()) {
            val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window = targetWindow)!!
            stateWithScores.put(virtualAbstractState, 1.0)
            getPathToStatesBasedOnPathType(
                pathType,
                transitionPaths,
                stateWithScores,
                currentAbstractState,
                currentState,
                true,
                true,
                goalByAbstractState,
                maxCost,
                emptyList(),
                pathConstraints
            )
        }
        return transitionPaths

    }

    fun getPathToStatesBasedOnPathType(
        pathType: PathFindingHelper.PathType,
        transitionPaths: ArrayList<TransitionPath>,
        statesWithScore: HashMap<AbstractState, Double>,
        currentAbstractState: AbstractState,
        currentState: State<*>,
        shortest: Boolean = true,
        windowAsTarget: Boolean = false,
        goalByAbstractState: Map<AbstractState, List<Goal>>,
        maxCost: Double,
        abandonedAppStates: List<AbstractState>,
        pathConstraints: Map<PathConstraint,Boolean>
    ) {
  /*      if (pathConstraints[PathConstraint.INCLUDE_RESET] == true) {
            val notResetConstraints = HashMap<PathConstraint,Boolean>()
            notResetConstraints.putAll(pathConstraints)
            notResetConstraints[PathConstraint.INCLUDE_RESET] = false
            getPathToStates(
                transitionPaths = transitionPaths,
                stateByScore = statesWithScore,
                currentAbstractState = currentAbstractState,
                currentState = currentState,
                pathType = pathType,
                shortest = shortest,
                windowAsTarget = windowAsTarget,
                goalByAbstractState = goalByAbstractState,
                maxCost = maxCost,
                abandonedAppStates = abandonedAppStates,
                pathConstraints = notResetConstraints
            )
            if (transitionPaths.isNotEmpty()) {
                return
            }
        }*/
        getPathToStates(
            transitionPaths = transitionPaths,
            stateByScore = statesWithScore,
            currentAbstractState = currentAbstractState,
            currentState = currentState,
            pathType = pathType,
            shortest = shortest,
            windowAsTarget = windowAsTarget,
            goalByAbstractState = goalByAbstractState,
            maxCost = maxCost,
            abandonedAppStates = abandonedAppStates,
            pathConstraints = pathConstraints
        )
    }

    abstract fun getCurrentTargetInputs(currentState: State<*>): Set<Goal>

    abstract fun hasNextAction(currentState: State<*>): Boolean


    abstract fun registerTriggeredInputs(chosenAbstractAction: AbstractAction, currentState: State<*>)

    fun getPathToStates(
        transitionPaths: ArrayList<TransitionPath>,
        stateByScore: Map<AbstractState, Double>,
        currentAbstractState: AbstractState,
        currentState: State<*>,
        shortest: Boolean = true,
        windowAsTarget: Boolean = false,
        pathCountLimitation: Int = 1,
        pathType: PathFindingHelper.PathType,
        goalByAbstractState: Map<AbstractState, List<Goal>>,
        maxCost: Double,
        abandonedAppStates: List<AbstractState>,
        pathConstraints: Map<PathConstraint,Boolean>
    ) {

        val candidateStates = HashMap(stateByScore)
        while (candidateStates.isNotEmpty()) {
            if (transitionPaths.isNotEmpty())
                break
            val maxValue = candidateStates.maxByOrNull { it.value }!!.value
            val abstractStates = candidateStates.filter { it.value == maxValue }.keys
            ProbabilityBasedPathFinder.findPathToTargetComponent(currentState = currentState,
                root = currentAbstractState,
                finalTargets = abstractStates.toList(),
                foundPaths = transitionPaths,
                shortest = true,
                pathCountLimitation = pathCountLimitation,
                autautMF = atuaMF,
                pathType = pathType,
                goalsByTarget = goalByAbstractState.filter { abstractStates.contains(it.key) },
                windowAsTarget = windowAsTarget,
                maxCost = maxCost,
                abandonedAppStates = abandonedAppStates,
                constraints = pathConstraints)
            abstractStates.forEach { candidateStates.remove(it) }
        }
//        LoggerFactory.getLogger(this::class.simpleName).debug("Paths count: ${transitionPaths.size}")
    }

    protected fun isBlocked(abstractState: AbstractState, currentState: State<*>): Boolean {
        val transitionPath = ArrayList<TransitionPath>()
        val abstractStates = HashMap<AbstractState, Double>()
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        if (abstractState == currentAbstractState)
            return false
        abstractStates.put(abstractState, 1.0)
        val pathConstraints = HashMap<PathConstraint,Boolean>()
        pathConstraints.put(PathConstraint.INCLUDE_RESET,true)
        getPathToStates(
            transitionPaths = transitionPath,
            stateByScore = abstractStates,
            currentState = currentState,
            currentAbstractState = currentAbstractState,
            shortest = true,
            pathCountLimitation = 1,
            pathType = PathFindingHelper.PathType.NORMAL,
            goalByAbstractState = mapOf(),
            maxCost = ProbabilityBasedPathFinder.DEFAULT_MAX_COST,
            abandonedAppStates = emptyList(),
            pathConstraints = pathConstraints
        )
        if (transitionPath.isNotEmpty())
            return false
        return true
    }
}