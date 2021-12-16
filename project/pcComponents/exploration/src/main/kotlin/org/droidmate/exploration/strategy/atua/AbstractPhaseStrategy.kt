package org.droidmate.exploration.strategy.atua

import kotlinx.coroutines.runBlocking
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.TransitionPath
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.strategy.atua.task.AbstractStrategyTask
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
        maxCost: Double
    ): List<TransitionPath>

    abstract fun getPathsToTargetWindows(
        currentState: State<*>,
        pathType: PathFindingHelper.PathType,
        maxCost: Double
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

    fun getUnexhaustedExploredAbstractState(currentState: State<*>): List<AbstractState> {
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        if (currentAbstractState == null)
            return emptyList()
        val runtimeAbstractStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES
            .filterNot {
                it is VirtualAbstractState
                        || it.ignored
                        || it == currentAbstractState
                        || it.window is Launcher
                        || it.window is OutOfApp
                        || (it.window is Dialog && (it.window as Dialog).ownerActivitys.all { it is OutOfApp })
                        || it.isRequestRuntimePermissionDialogBox
                        || it.isAppHasStoppedDialogBox
                        || it.attributeValuationMaps.isEmpty()
                        || it.guiStates.isEmpty()
                        || it.guiStates.all { atuaMF.actionCount.getUnexploredWidget(it).isEmpty() }

            }
        return runtimeAbstractStates
    }

    fun hasUnexploreWidgets(currentState: State<*>): Boolean {
        return atuaMF.actionCount.getUnexploredWidget(currentState).isNotEmpty()
    }

    open fun getPathsToWindowToExplore(
        currentState: State<*>,
        targetWindow: Window,
        pathType: PathFindingHelper.PathType,
        explore: Boolean,
        maxCost: Double
    ): List<TransitionPath> {
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        if (currentAbstractState == null)
            return transitionPaths
        val goalByAbstractState = HashMap<AbstractState, List<Input>>()
        val stateWithScores = HashMap<AbstractState, Double>()
        var targetStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
            it.window == targetWindow
                    && it.ignored == false
                    && (pathType == PathFindingHelper.PathType.FULLTRACE
                    || pathType == PathFindingHelper.PathType.PARTIAL_TRACE
                    || !AbstractStateManager.INSTANCE.unreachableAbstractState.contains(it))
                    && it != currentAbstractState
                    && (it is VirtualAbstractState ||
                    (it.attributeValuationMaps.isNotEmpty() && it.guiStates.isNotEmpty()))
        }.toHashSet()
        if (explore) {
            targetStates.removeIf {
                it is VirtualAbstractState
            }
            val unexercisedInputs = ArrayList<Input>()
            val canExploreAppStates1 = targetStates.associateWith { it.getUnExercisedActions(null, atuaMF)}.filter { it.value.isNotEmpty() }
            if (canExploreAppStates1.isNotEmpty()) {
                canExploreAppStates1.forEach { s, actions ->
                    val inputs = actions.map { s.getInputsByAbstractAction(it) }.flatten().distinct()
                    unexercisedInputs.addAll(inputs)
                }
            } else {
                val canExploreAppStates2 = targetStates.associateWith { it.getUnExercisedActions(null, atuaMF)}.filter { it.value.isNotEmpty() }
                canExploreAppStates2.forEach { s, actions ->
                    val inputs = actions.map { s.getInputsByAbstractAction(it) }.flatten().distinct()
                    unexercisedInputs.addAll(inputs)
                }
            }
            if (unexercisedInputs.isEmpty())
                return emptyList()
            val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window = targetWindow)!!
            goalByAbstractState.put(virtualAbstractState, unexercisedInputs.distinct())
            stateWithScores.put(virtualAbstractState, 1.0)
            /*  if (targetStates.isEmpty()) {
                  targetStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
                      it.window == targetWindow
                              && it != currentAbstractState
                              && (it.guiStates.any { atuaMF.actionCount.getUnexploredWidget(it).isNotEmpty() })
                              && it !is VirtualAbstractState
                  }.toHashSet()
              }*/
        } else {
            val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window = targetWindow)!!
            stateWithScores.put(virtualAbstractState, 1.0)
        }
        getPathToStatesBasedOnPathType(
            pathType,
            transitionPaths,
            stateWithScores,
            currentAbstractState,
            currentState,
            true,
            !explore,
            goalByAbstractState,
            maxCost
        )
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
        goalByAbstractState: Map<AbstractState, List<Input>>,
        maxCost: Double
    ) {
        if (pathType != PathFindingHelper.PathType.ANY) {
            getPathToStates(
                transitionPaths = transitionPaths,
                stateByScore = statesWithScore,
                currentAbstractState = currentAbstractState,
                currentState = currentState,
                pathType = pathType,
                shortest = shortest,
                windowAsTarget = windowAsTarget,
                goalByAbstractState = goalByAbstractState,
                maxCost = maxCost
            )
        } else {
            getPathToStates(
                transitionPaths = transitionPaths,
                stateByScore = statesWithScore,
                currentAbstractState = currentAbstractState,
                currentState = currentState,
                pathType = PathFindingHelper.PathType.NORMAL,
                shortest = shortest,
                windowAsTarget = windowAsTarget,
                goalByAbstractState = goalByAbstractState,
                maxCost = maxCost
            )
            if (transitionPaths.isEmpty() &&
                (windowAsTarget || goalByAbstractState.isNotEmpty())
            ) {
                getPathToStates(
                    transitionPaths = transitionPaths,
                    stateByScore = statesWithScore,
                    currentAbstractState = currentAbstractState,
                    currentState = currentState,
                    pathType = PathFindingHelper.PathType.WIDGET_AS_TARGET,
                    shortest = shortest,
                    windowAsTarget = windowAsTarget,
                    goalByAbstractState = goalByAbstractState,
                    maxCost = maxCost
                )
            }
            if (transitionPaths.isEmpty() && windowAsTarget) {
                getPathToStates(
                    transitionPaths = transitionPaths,
                    stateByScore = statesWithScore,
                    currentAbstractState = currentAbstractState,
                    currentState = currentState,
                    pathType = PathFindingHelper.PathType.WTG,
                    shortest = shortest,
                    windowAsTarget = windowAsTarget,
                    goalByAbstractState = goalByAbstractState,
                    maxCost = maxCost
                )
            }
            if (transitionPaths.isEmpty()) {
                getPathToStates(
                    transitionPaths = transitionPaths,
                    stateByScore = statesWithScore,
                    currentAbstractState = currentAbstractState,
                    currentState = currentState,
                    pathType = PathFindingHelper.PathType.PARTIAL_TRACE,
                    shortest = shortest,
                    windowAsTarget = windowAsTarget,
                    goalByAbstractState = goalByAbstractState,
                    maxCost = maxCost
                )
            }
            if (transitionPaths.isEmpty()) {
                getPathToStates(
                    transitionPaths = transitionPaths,
                    stateByScore = statesWithScore,
                    currentAbstractState = currentAbstractState,
                    currentState = currentState,
                    pathType = PathFindingHelper.PathType.FULLTRACE,
                    shortest = shortest,
                    windowAsTarget = windowAsTarget,
                    goalByAbstractState = goalByAbstractState,
                    maxCost = maxCost
                )
            }
        }
    }

    abstract fun getCurrentTargetInputs(currentState: State<*>): Set<Input>

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
        goalByAbstractState: Map<AbstractState, List<Input>>,
        maxCost: Double
    ) {

        val candidateStates = HashMap(stateByScore)
        while (candidateStates.isNotEmpty()) {
            if (transitionPaths.isNotEmpty())
                break
            val maxValue = candidateStates.maxBy { it.value }!!.value
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
                maxCost = maxCost)
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
        getPathToStates(
            transitionPaths = transitionPath,
            stateByScore = abstractStates,
            currentState = currentState,
            currentAbstractState = currentAbstractState,
            shortest = true,
            pathCountLimitation = 1,
            pathType = PathFindingHelper.PathType.NORMAL,
            goalByAbstractState = mapOf(),
            maxCost = ProbabilityBasedPathFinder.DEFAULT_MAX_COST
        )
        if (transitionPath.isNotEmpty())
            return false
        return true
    }
}