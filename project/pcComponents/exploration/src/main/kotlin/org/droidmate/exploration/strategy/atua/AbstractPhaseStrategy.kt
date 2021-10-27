package org.droidmate.exploration.strategy.atua

import kotlinx.coroutines.runBlocking
import org.atua.calm.modelReuse.ModelVersion
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.exploration.ExplorationContext
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.ewtg.*
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.droidmate.exploration.strategy.atua.task.AbstractStrategyTask
import org.droidmate.explorationModel.interaction.State
import org.slf4j.LoggerFactory
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

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
    val windowsCorrelation = HashMap<Window, HashMap<Window,Double>>()
    abstract fun nextAction(eContext: ExplorationContext<*,*,*>): ExplorationAction
    abstract fun isTargetState(currentState: State<*>): Boolean
    abstract fun isTargetWindow(window: Window): Boolean

    abstract fun getPathsToExploreStates(currentState: State<*>, pathType: PathFindingHelper.PathType): List<TransitionPath>
    abstract fun getPathsToTargetWindows(currentState: State<*> , pathType: PathFindingHelper.PathType): List<TransitionPath>

    fun needReset(currentState: State<*>): Boolean {
        val interval = 100 * scaleFactor
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
        if (currentAbstractState==null)
            return emptyList()
        val runtimeAbstractStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES
                .filterNot { it is VirtualAbstractState
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

    open fun getPathsToWindowToExplore(currentState: State<*>, targetWindow: Window, pathType: PathFindingHelper.PathType, explore: Boolean): List<TransitionPath> {
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        if (currentAbstractState==null)
            return transitionPaths
        val goalByAbstractState = HashMap<AbstractState, List<Input>>()
        var targetStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
            it.window == targetWindow
                    && (   pathType == PathFindingHelper.PathType.FULLTRACE
                    || pathType == PathFindingHelper.PathType.PARTIAL_TRACE
                    || !AbstractStateManager.INSTANCE.unreachableAbstractState.contains(it))
                    && it != currentAbstractState
                    && (it is VirtualAbstractState ||
                    (it.attributeValuationMaps.isNotEmpty() && it.guiStates.isNotEmpty()))
        }.toHashSet()
        if (explore) {
            targetStates.removeIf {
                it is VirtualAbstractState || it.getUnExercisedActions(null,atuaMF).isEmpty() }
            targetStates.forEach {
                val inputs = ArrayList<Input>()
                it.getUnExercisedActions(null,atuaMF).forEach { action ->
                    inputs.addAll(it.inputMappings[action]?: emptyList())
                }
                goalByAbstractState.put(it,inputs)
            }
          /*  if (targetStates.isEmpty()) {
                targetStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter {
                    it.window == targetWindow
                            && it != currentAbstractState
                            && (it.guiStates.any { atuaMF.actionCount.getUnexploredWidget(it).isNotEmpty() })
                            && it !is VirtualAbstractState
                }.toHashSet()
            }*/
        }
        val stateByActionCount = HashMap<AbstractState,Double>()
        targetStates.forEach {
//            val unExercisedActionsSize = it.getUnExercisedActions(null).filter { it.widgetGroup!=null }.size
//            if (unExercisedActionsSize > 0 )
//                stateByActionCount.put(it,it.getUnExercisedActions(null).size.toDouble())
            stateByActionCount.put(it, 1.0)
        }
        if (stateByActionCount.isEmpty()) {
            targetStates.forEach {
                if (stateByActionCount.get(it)!!>0)
                    stateByActionCount.put(it,1.0)
            }
        }
        getPathToStatesBasedOnPathType(pathType, transitionPaths, stateByActionCount, currentAbstractState, currentState,true,!explore, goalByAbstractState)
        return transitionPaths
    }

     fun getPathToStatesBasedOnPathType(pathType: PathFindingHelper.PathType
                                        , transitionPaths: ArrayList<TransitionPath>
                                        , stateByActionCount: HashMap<AbstractState, Double>
                                        , currentAbstractState: AbstractState
                                        , currentState: State<*>
                                        , shortest: Boolean = true
                                        , windowAsTarget: Boolean = false
     , goalByAbstractState: Map<AbstractState, List<Input>>) {
        if (pathType != PathFindingHelper.PathType.ANY) {
            getPathToStates(
                transitionPaths = transitionPaths,
                stateByScore = stateByActionCount,
                currentAbstractState = currentAbstractState,
                currentState = currentState,
                pathType = pathType,
                shortest = shortest, windowAsTarget = windowAsTarget, goalByAbstractState = goalByAbstractState
            )
        }
        else {
            getPathToStates(
                    transitionPaths = transitionPaths,
                    stateByScore = stateByActionCount,
                    currentAbstractState = currentAbstractState,
                    currentState = currentState,
                    pathType = PathFindingHelper.PathType.NORMAL,
                    shortest = shortest
                , windowAsTarget = windowAsTarget,
            goalByAbstractState = goalByAbstractState)
            if (transitionPaths.isEmpty() &&
                (windowAsTarget || goalByAbstractState.isNotEmpty())) {
                getPathToStates(
                    transitionPaths = transitionPaths,
                    stateByScore = stateByActionCount,
                    currentAbstractState = currentAbstractState,
                    currentState = currentState,
                    pathType = PathFindingHelper.PathType.WIDGET_AS_TARGET,
                    shortest = shortest
                    , windowAsTarget = windowAsTarget
                ,goalByAbstractState = goalByAbstractState)
            }
            if (transitionPaths.isEmpty() && windowAsTarget) {
                getPathToStates(
                        transitionPaths = transitionPaths,
                        stateByScore = stateByActionCount,
                        currentAbstractState = currentAbstractState,
                        currentState = currentState,
                        pathType = PathFindingHelper.PathType.WTG,
                        shortest = shortest
                    , windowAsTarget = windowAsTarget
                , goalByAbstractState = goalByAbstractState)
            }
            if (transitionPaths.isEmpty()) {
                getPathToStates(
                    transitionPaths = transitionPaths
                    , stateByScore = stateByActionCount
                    , currentAbstractState = currentAbstractState
                    , currentState = currentState
                    , pathType = PathFindingHelper.PathType.PARTIAL_TRACE
                    , shortest = shortest
                    , windowAsTarget = windowAsTarget
                    , goalByAbstractState = goalByAbstractState)
            }
            if (transitionPaths.isEmpty()) {
                getPathToStates(
                    transitionPaths = transitionPaths,
                        stateByScore = stateByActionCount,
                        currentAbstractState = currentAbstractState,
                        currentState = currentState,
                        pathType = PathFindingHelper.PathType.FULLTRACE,
                        shortest = shortest,
                    windowAsTarget = windowAsTarget,
                    goalByAbstractState = goalByAbstractState)
            }
        }
    }

    abstract fun getCurrentTargetInputs(currentState: State<*>):  Set<AbstractAction>

    abstract fun hasNextAction(currentState: State<*>): Boolean


    abstract fun registerTriggeredEvents(chosenAbstractAction: AbstractAction, currentState: State<*>)

    fun getPathToStates(transitionPaths: ArrayList<TransitionPath>, stateByScore: Map<AbstractState, Double>
                        , currentAbstractState: AbstractState, currentState: State<*>
                        , shortest: Boolean=true
                        , windowAsTarget: Boolean = false
                        , pathCountLimitation: Int = 1
                        , pathType: PathFindingHelper.PathType
                        , goalByAbstractState: Map<AbstractState, List<Input>>) {
        val candidateStates = HashMap(stateByScore)
        while (candidateStates.isNotEmpty()) {
            if (!windowAsTarget && transitionPaths.isNotEmpty())
                break
            val maxValue = candidateStates.maxBy { it.value }!!.value
            val abstractStates = candidateStates.filter { it.value == maxValue }
            abstractStates.keys.forEach { abstractState->
                PathFindingHelper.findPathToTargetComponent(currentState = currentState
                    , root = currentAbstractState
                    , finalTarget = abstractState
                    , allPaths = transitionPaths
                    , shortest = true
                    , pathCountLimitation = pathCountLimitation
                    , autautMF = atuaMF
                    , pathType = pathType
                    , goal = goalByAbstractState[abstractState]?: emptyList())
                //windowStates.remove(abstractState)
                candidateStates.remove(abstractState)
            }
            if (windowAsTarget && transitionPaths.isNotEmpty()) {
                val minSequenceLength = transitionPaths.map { it.cost()}.min()!!
                transitionPaths.removeIf { it.cost() > minSequenceLength }
                if (minSequenceLength == 1)
                    break
            }
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
        if (abstractState.guiStates.isNotEmpty())
            getPathToStates(
                transitionPaths = transitionPath,
                stateByScore = abstractStates,
                currentState = currentState,
                currentAbstractState = currentAbstractState,
                shortest = true,
                pathCountLimitation = 1,
                pathType = PathFindingHelper.PathType.FULLTRACE,
                goalByAbstractState = mapOf()
            )
        else if (abstractState.modelVersion == ModelVersion.BASE) {
            getPathToStates(
                transitionPaths = transitionPath,
                stateByScore = abstractStates,
                currentState = currentState,
                currentAbstractState = currentAbstractState,
                shortest = true,
                pathCountLimitation = 1,
                pathType = PathFindingHelper.PathType.NORMAL,
                goalByAbstractState = mapOf()
            )
        }
        if (transitionPath.isNotEmpty())
            return false
        return true
    }
}