package org.droidmate.exploration.strategy.atua

import org.atua.calm.ewtgdiff.EWTGDiff
import org.atua.calm.modelReuse.ModelHistoryInformation
import org.atua.calm.modelReuse.ModelVersion
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.Swipe
import org.droidmate.deviceInterface.exploration.isLaunchApp
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.resetApp
import org.droidmate.exploration.modelFeatures.graph.Edge
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractTransition
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.PredictedAbstractState
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.helper.ProbabilityDistribution
import org.atua.modelFeatures.ewtg.*
import org.atua.modelFeatures.ewtg.window.FakeWindow
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.helper.Goal
import org.atua.modelFeatures.helper.PathConstraint
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.droidmate.exploration.modelFeatures.reporter.StatementCoverageMF
import org.droidmate.exploration.strategy.atua.task.*
import org.droidmate.explorationModel.interaction.State
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.max

class PhaseTwoStrategy(
        atuaTestingStrategy: ATUATestingStrategy,
        budgetScale: Double,
        delay: Long,
        useCoordinateClicks: Boolean,
        val unreachableWindow: Set<Window>
) : AbstractPhaseStrategy(
        atuaTestingStrategy = atuaTestingStrategy,
        scaleFactor = budgetScale,
        delay = delay,
        useCoordinateClicks = useCoordinateClicks,
        useVirtualAbstractState = false
) {
    override fun isTargetWindow(window: Window): Boolean {
        if (window == targetWindow)
            return true
        return false
    }

    override fun isTargetState(currentState: State<*>): Boolean {
        return true
    }

    private var reachedTarget: Boolean = false
    var initialCoverage: Double = 0.0
    private var numOfContinousTry: Int = 0
    val statementMF: StatementCoverageMF

    var remainPhaseStateCount: Int = 0

    var targetWindow: Window? = null
    var phase2TargetEvents: HashMap<Input, Int> = HashMap()

    var targetWindowsCount: HashMap<Window, Int> = HashMap()

    val abstractStatesScores = HashMap<AbstractState, Double>()
    val abstractStateProbabilityByWindow = HashMap<Window, ArrayList<Pair<AbstractState, Double>>>()

    val modifiedMethodWeights = HashMap<String, Double>()
    val modifiedMethodMissingStatements = HashMap<String, HashSet<String>>()
    val appStateModifiedMethodMap = HashMap<AbstractState, HashSet<String>>()
    val modifiedMethodTriggerCount = HashMap<String, Int>()
    val windowScores = HashMap<Window, Double>()
    val windowsProbability = HashMap<Window, Double>()
    var attempt: Int = 0
    var budgetLeft: Int = 0
    var randomBudgetLeft: Int = 0
    var budgetType: BudgetType = BudgetType.UNSET
    var needResetApp = false
    val currentTargetInputs = ArrayList<Input>()
    var gamma = 1.0


    init {
        phaseState = PhaseState.P2_INITIAL
        atuaMF = atuaTestingStrategy.eContext.getOrCreateWatcher()
        statementMF = atuaTestingStrategy.eContext.getOrCreateWatcher()
        atuaMF.updateMethodCovFromLastChangeCount = 0
        val witnessedTargetInputs = atuaMF.notFullyExercisedTargetInputs.filter { it.witnessed }
        val witnessedTargetWindows = witnessedTargetInputs.map { it.sourceWindow }.distinct()
        val allTargetWindows = atuaMF.notFullyExercisedTargetInputs.map { it.sourceWindow }.distinct()
        val seenWindows = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it !is VirtualAbstractState
                && it !is PredictedAbstractState
                && it.ignored == false }.map { it.window }.distinct()
        atuaMF.modifiedMethodsByWindow.keys
            .filter{ it !is Launcher
                    && it !is FakeWindow
                    && it !is OptionsMenu
                    && ( allTargetWindows.contains(it) || seenWindows.contains(it))}.forEach { window ->
            targetWindowsCount.put(window, 0)
            /*val abstractStates = AbstractStateManager.INSTANCE.getPotentialAbstractStates().filter { it.window == window }
            if (abstractStates.isNotEmpty()) {
                targetWindowsCount.put(window, 0)
                *//*val targetInputs = atuaMF.allTargetInputs.filter { it.sourceWindow == window }

                val realisticInputs = abstractStates.map { it.inputMappings.values }.flatten().flatten().distinct()
                val realisticTargetInputs = targetInputs.intersect(realisticInputs)
                if (realisticTargetInputs.isNotEmpty()) {
                    targetWindowsCount.put(window, 0)
                }*//*
            }*/
        }
        witnessedTargetWindows.filter {it !is OptionsMenu}.forEach {
            targetWindowsCount.putIfAbsent(it,0)
        }
        attempt = (targetWindowsCount.size * budgetScale*gamma).toInt()+1
        initialCoverage = atuaMF.statementMF!!.getCurrentModifiedMethodStatementCoverage()
    }

    override fun registerTriggeredInputs(abstractAction: AbstractAction, currentState: State<*>) {
        val abstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)!!
        //val abstractInteractions = regressionTestingMF.abstractTransitionGraph.edges(abstractState).filter { it.label.abstractAction.equals(abstractAction) }.map { it.label }
        val inputs = abstractState.getInputsByAbstractAction(abstractAction)
        inputs.forEach {
            if (phase2TargetEvents.containsKey(it)) {
                phase2TargetEvents[it] = phase2TargetEvents[it]!! + 1
            }
            currentTargetInputs.remove(it)
        }
    }

    override fun hasNextAction(currentState: State<*>): Boolean {
        if (atuaMF.lastUpdatedStatementCoverage == 1.0)
            return false
        targetWindowsCount.entries.removeIf { !atuaMF.modifiedMethodsByWindow.containsKey(it.key) }
        phase2TargetEvents.entries.removeIf { !atuaMF.notFullyExercisedTargetInputs.contains(it.key) }
        atuaMF.modifiedMethodsByWindow.keys.filter { it !is Launcher
                && it !is OptionsMenu
                && !targetWindowsCount.containsKey(it)}.forEach {window ->
            val abstractStates = AbstractStateManager.INSTANCE.getPotentialAbstractStates().filter { it.window == window }
            if (abstractStates.isNotEmpty()) {
                targetWindowsCount.put(window,0)
            }
        }
        if (attempt < 0) {
            if (atuaMF.statementMF!!.getCurrentModifiedMethodStatementCoverage() > (initialCoverage+0.05)) {
                targetWindowsCount.entries.removeIf {
                    !atuaMF.modifiedMethodsByWindow.containsKey(it.key)
                }
                gamma = gamma*0.5
                attempt = (targetWindowsCount.size * gamma * scaleFactor).toInt()
                if (attempt < 1)
                    attempt = 1
                initialCoverage = atuaMF.statementMF!!.getCurrentModifiedMethodStatementCoverage()
                return true
            } else
                return false
        }

        return true
    }

    override fun nextAction(eContext: ExplorationContext<*, *, *>): ExplorationAction {
        if (atuaMF == null) {
            atuaMF = eContext.findWatcher { it is org.atua.modelFeatures.ATUAMF } as org.atua.modelFeatures.ATUAMF
        }
        atuaMF.dstg.cleanPredictedAbstractStates()
        val currentState = eContext.getCurrentState()
        /*if (phaseState != PhaseState.P2_EXERCISE_TARGET_NODE
                && phaseState != PhaseState.P2_GO_TO_TARGET_NODE
                && phaseState != PhaseState.P2_GO_TO_EXPLORE_STATE
                && needReset(currentState)) {
            return eContext.resetApp()
        }*/
        if (targetWindow!=null && !targetWindowsCount.containsKey(targetWindow!!)) {
            targetWindow = null
        }

        var chosenAction: ExplorationAction?


        val currentAppState = atuaMF.getAbstractState(currentState)

        if (currentAppState == null) {
            return eContext.resetApp()
        }
        /* if (targetWindow != null) {
             if (targetWindowsCount.containsKey(currentAppState.window)
                     && targetWindow != currentAppState.window) {
                 if (targetWindowsCount[targetWindow!!]!! > targetWindowsCount[currentAppState.window]!!) {
                     targetWindow = currentAppState.window
                     targetWindowsCount[targetWindow!!] = targetWindowsCount[targetWindow!!]!!+1
                 }
             }
         }*/

        if (targetWindow == null) {
            selectTargetWindow(currentState, 0)
            phaseState = PhaseState.P2_INITIAL
        }
        if (targetWindow != currentAppState.window) {
            val targetInputs = atuaMF.notFullyExercisedTargetInputs.intersect(currentAppState.getAvailableInputs())
                .filter { it.exerciseCount == 0 }
            if (targetInputs.isNotEmpty()) {
                computeAppStatesScore()
                targetWindow = currentAppState.window
                budgetLeft = -1
                budgetType = BudgetType.UNSET
                phaseState = PhaseState.P2_INITIAL
                establishTargetInputsForWindow()
            }
        }


        log.info("Current abstract state: $currentAppState")
        log.info("Current window: ${currentAppState.window}")
        log.info("Target window: $targetWindow")
        chooseTask(eContext, currentState)
        /*if (needResetApp) {
            needResetApp = false
            return eContext.resetApp()
        }*/
        if (strategyTask != null) {
            chosenAction = strategyTask!!.chooseAction(currentState)
        } else {
            log.debug("No task seleted. It might be a bug.")
            chosenAction = eContext.resetApp()
        }
        if (chosenAction == null)
            return ExplorationAction.pressBack()
        budgetConsume(chosenAction,currentAppState)
/*        if (strategyTask is RandomExplorationTask && (strategyTask as RandomExplorationTask).fillingData == false) {
            budgetLeft--
        }*/
        return chosenAction
    }
    var eContext: ExplorationContext<*,*,*>? = null
    private fun chooseTask(eContext: ExplorationContext<*, *, *>, currentState: State<*>) {
        log.debug("Choosing Task")
        this.eContext = eContext
        //val fillDataTask = FillTextInputTask.getInstance(regressionTestingMF,this,delay, useCoordinateClicks)
        val exerciseTargetComponentTask = ExerciseTargetComponentTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val goToTargetNodeTask = GoToTargetWindowTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val goToAnotherNode = GoToAnotherWindowTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val randomExplorationTask = RandomExplorationTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val openNavigationBarTask = OpenNavigationBarTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val currentState = eContext.getCurrentState()
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        /*if (!setTestBudget && currentAppState.window == targetWindow)
        {
            budgetLeft = (currentAppState.widgets.map { it.getPossibleActions() }.sum()*budgetScale).toInt()
            setTestBudget = true
        }*/
        log.info("Phase budget left: $attempt")
        if (isBudgetAvailable()) {
            if (budgetType != BudgetType.RANDOM_EXPLORATION)
                log.info("Exercise budget left: $budgetLeft")
            else
                log.info("Random budget left: $randomBudgetLeft")
            if (strategyTask !is RandomExplorationTask && targetWindowsCount.keys.contains(currentAppState.window)) {
                val unexercisedInputs = currentAppState.getAvailableInputs().filter {
                    it.eventType.isWidgetEvent()
                            && (it.widget?.isUserLikeInput?:false == false
                            || it.widget?.verifiedNotUserlikeInput?:false == true)
                        && it.exerciseCount == 0 }
                if (unexercisedInputs.isNotEmpty()) {
                    setRandomExploration(randomExplorationTask, currentState, currentAppState,budget=unexercisedInputs.size)
                    return
                }
            }
            if (phaseState == PhaseState.P2_INITIAL) {
                nextActionOnInitial(currentAppState, exerciseTargetComponentTask, currentState, randomExplorationTask, goToTargetNodeTask, goToAnotherNode)
                return
            }
            if (phaseState == PhaseState.P2_EXERCISE_TARGET_NODE) {
                nextActionOnExerciseTargetWindow(currentState, currentAppState, randomExplorationTask, goToTargetNodeTask, goToAnotherNode, exerciseTargetComponentTask)
                return
            }
            if (phaseState == PhaseState.P2_RANDOM_IN_EXERCISE_TARGET_NODE) {
                nextActionOnRandomInTargetWindow(randomExplorationTask, currentAppState, currentState, exerciseTargetComponentTask, goToTargetNodeTask, goToAnotherNode)
                return
            }
            if (phaseState == PhaseState.P2_GO_TO_TARGET_NODE) {
                nextActionOnGoToTargetWindow(currentState, currentAppState, exerciseTargetComponentTask, randomExplorationTask, goToAnotherNode, goToTargetNodeTask)
                return
            }
            if (phaseState == PhaseState.P2_GO_TO_EXPLORE_STATE) {
                nextActionOnGoToExploreState(currentAppState, exerciseTargetComponentTask, currentState, randomExplorationTask, goToTargetNodeTask,goToAnotherNode)
                return
            }
            if (phaseState == PhaseState.P2_RANDOM_EXPLORATION) {
                nextActionOnRandomExploration(currentAppState, exerciseTargetComponentTask, currentState, randomExplorationTask, goToTargetNodeTask, goToAnotherNode,eContext)
                return

            }
        } else {
            if (phaseState != PhaseState.P2_RANDOM_EXPLORATION && false) {
                targetWindow = null
                setRandomExplorationBudget(currentState)
                val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
                    .filter {
                        !it.isCheckableOrTextInput(currentAppState)
                                && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
                    }
                if (meaningfulAbstractActions.isNotEmpty()
                ) {
                    setRandomExploration(randomExplorationTask, currentState, currentAppState)
                    return
                }
                if (goToAnotherNode.isAvailable(currentState)) {
                    strategyTask = goToAnotherNode.also {
                        it.initialize(currentState)
                        it.retryTimes = 0
                    }
                    log.info("Go to target window by visiting another window: ${targetWindow.toString()}")
                    numOfContinousTry = 0
                    phaseState = PhaseState.P2_GO_TO_EXPLORE_STATE
                    return
                }
            } else {
                selectTargetWindow(currentState, 0)

                //setTestBudget = false
                //needResetApp = true
                phaseState = PhaseState.P2_INITIAL
                chooseTask(eContext, currentState)
            }
        }

        return
    }

    private fun budgetConsume(choosenAction: ExplorationAction,currentAppState: AbstractState) {
        if (budgetType == BudgetType.EXERCISE_TARGET) {
            if (strategyTask is ExerciseTargetComponentTask
                    && !(strategyTask as ExerciseTargetComponentTask).fillingData
                    && !(strategyTask as ExerciseTargetComponentTask).isDoingRandomExplorationTask) {
                budgetLeft--
            }
            if (phaseState == PhaseState.P2_RANDOM_IN_EXERCISE_TARGET_NODE
                && strategyTask is RandomExplorationTask && !(strategyTask as RandomExplorationTask).fillingData) {
                budgetLeft--
            }
            /*if (phaseState == PhaseState.P2_RANDOM_IN_EXERCISE_TARGET_NODE) {
                if (strategyTask is RandomExplorationTask
                        && !(strategyTask as RandomExplorationTask).fillingData
                        && (strategyTask as RandomExplorationTask).goToLockedWindowTask == null
                        && (strategyTask as RandomExplorationTask).lockedWindow == currentAppState.window) {
                    // check current Window is also target Window
                    if (isCountAction(choosenAction))
                        budgetLeft--
                }
            }*/
        }
        if (budgetType == BudgetType.RANDOM_EXPLORATION) {
            if (
                (strategyTask is RandomExplorationTask
                    && !(strategyTask as RandomExplorationTask).fillingData)
                ||
                (strategyTask is ExerciseTargetComponentTask
                        && !(strategyTask as ExerciseTargetComponentTask).fillingData
                        && !(strategyTask as ExerciseTargetComponentTask).isDoingRandomExplorationTask))
                    randomBudgetLeft--
        }

    }

    private fun isCountAction(chosenAction: ExplorationAction) =
            !chosenAction.isFetch()
                    && chosenAction.name != "CloseKeyboard"
                    && !chosenAction.name.isLaunchApp()
                    && chosenAction !is Swipe

    override fun getPathsToTargetWindows(currentState: State<*>,
                                         pathType: PathFindingHelper.PathType,
                                         maxCost:Double,
                                         pathConstraints: Map<PathConstraint, Boolean>): List<TransitionPath> {
        val currentAbState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        val prevAbstractState = AbstractStateManager.INSTANCE.getAbstractState(atuaMF.appPrevState!!)
        if (currentAbState == null)
            return emptyList()
        if (currentTargetInputs.isEmpty())
            establishTargetInputsForWindow()
        val windowTargetInputs = currentTargetInputs.distinct()
//        val windowTargetInputs = phase2TargetEvents.filter { it.key.sourceWindow == targetWindow }.keys
        val inputScore = HashMap<Input,Double>()
        windowTargetInputs.forEach { input ->
            val score = computeInputScore(input)
            if (score > 0.0)
                inputScore.put(input,score)
        }
        val targetAbstractStatesPbMap = HashMap<AbstractState, Double>()
        val targetAbstractStateWithGoals = HashMap<AbstractState,List<Goal>> ()
        val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(targetWindow!!)
        if (virtualAbstractState == null)
            return emptyList()
        targetAbstractStatesPbMap.put(virtualAbstractState,1.0)
        targetAbstractStateWithGoals.put(virtualAbstractState,inputScore.keys.map { Goal(input = it,abstractAction = null) })

        val transitionPaths = ArrayList<TransitionPath>()

        getPathToStatesBasedOnPathType(pathType, transitionPaths, targetAbstractStatesPbMap, currentAbState, currentState,false,inputScore.isEmpty(),
           targetAbstractStateWithGoals,maxCost,
            emptyList(),pathConstraints)
        if (transitionPaths.isEmpty()) {
            getPathToStatesBasedOnPathType(pathType, transitionPaths, targetAbstractStatesPbMap, currentAbState, currentState,false,true,
                targetAbstractStateWithGoals,maxCost,
                emptyList(),pathConstraints)
        }
        return transitionPaths
    }

    override fun getPathsToExploreStates(currentState: State<*>,
                                         pathType: PathFindingHelper.PathType,
                                         maxCost: Double,
                                         pathConstraints: Map<PathConstraint,Boolean>): List<TransitionPath> {
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)!!
        val toExploreAppStates = getUnexhaustedExploredAbstractState()
        val stateByActionCount = HashMap<AbstractState, Double>()
        val goalByAbstractState = HashMap<AbstractState, List<Goal>>()
        toExploreAppStates.groupBy { it.window }.forEach { window, appStates ->
            var virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window)
            if (virtualAbstractState == null) {
                virtualAbstractState = AbstractStateManager.INSTANCE.createVirtualAbstractState(window,appStates.first().activity,appStates.first().isHomeScreen)
            }
            val toExploreInputs = ArrayList<Goal>()
            appStates.forEach { appState ->
                val meaningfulAbstractActions = appState.getUnExercisedActions(currentState, atuaMF)
                    .filter { action->
                        !ProbabilityBasedPathFinder.disableAbstractActions.contains(action)
                                && ProbabilityBasedPathFinder.disableInputs.intersect(appState.getInputsByAbstractAction(action)).isEmpty()
                                && !action.isCheckableOrTextInput(appState)
                                && appState.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                    }
                meaningfulAbstractActions.forEach { action ->
                    toExploreInputs.add(Goal(input = null,abstractAction = action))
                }
            }

            goalByAbstractState.put(virtualAbstractState, toExploreInputs.distinct())
        }
        val abstratStateCandidates = goalByAbstractState.keys
        abstratStateCandidates.forEach {
            val weight = goalByAbstractState[it]!!.size
            if (weight > 0.0) {
                stateByActionCount.put(it, 1.0)
            }
        }
        getPathToStatesBasedOnPathType(
            pathType = pathType,
            transitionPaths =  transitionPaths,
            statesWithScore =  stateByActionCount,
            currentAbstractState= currentAbstractState,
            currentState =  currentState,
            shortest =  true,
            goalByAbstractState = goalByAbstractState,
            maxCost = maxCost,
            abandonedAppStates = emptyList(),
            pathConstraints = pathConstraints
        )
        return transitionPaths
    }

    override fun getCurrentTargetInputs(currentState: State<*>): Set<Input> {
        val targetEvents = ArrayList<Input>()

        val abstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)!!
        if (currentTargetInputs.isEmpty()) {
            establishTargetInputsForWindow()
        }
        if (abstractState.window == targetWindow) {
            val availableInputs = abstractState.getAvailableInputs()
            targetEvents.addAll(currentTargetInputs.intersect(availableInputs))
            val potentialAbstractActionS = abstractState.abstractTransitions
                .filter { it.interactions.isEmpty()
                        && it.modelVersion == ModelVersion.BASE
                        && it.modifiedMethods.isNotEmpty()
                        && it.modifiedMethods.keys.any { !atuaMF.statementMF!!.executedMethodsMap.containsKey(it) }}
                .map { it.abstractAction }
            val potentialInputs = potentialAbstractActionS.map { abstractState.getInputsByAbstractAction(it)}.flatten().distinct()
            return targetEvents.union(potentialInputs).distinct().toSet()
        }
        return targetEvents.toSet()
    }

    private fun establishTargetInputsForWindow() {
        currentTargetInputs.clear()
        val windowTargetInputs = phase2TargetEvents.filter { it.key.sourceWindow == targetWindow }.keys
        val inputScore = HashMap<Input, Double>()
        windowTargetInputs
//            .filter { availableInputs.contains(it) }
            .forEach { input ->
                val score = computeInputScore(input)
                if (score > 0.0)
                    inputScore.put(input, score)
            }
        if (inputScore.isNotEmpty()) {
            /*val exerciseCnt = if (budgetLeft > 0)
                budgetLeft
            else
                computeExerciseTestBudget(targetWindow!!)*/
            val targetInputSetCnt = 10
            val pb = ProbabilityDistribution<Input>(inputScore)
            while (currentTargetInputs.size < targetInputSetCnt) {
                val selectedInput = pb.getRandomVariable()
                currentTargetInputs.add(selectedInput)
            }
        }
    }

    private fun computeInputScore(input: Input): Double {
        val notFullyCoveredMethods =
            input.modifiedMethods.filter { !atuaMF.statementMF!!.fullyCoveredMethods.contains(it.key) }
        val uncoveredMethods =
            input.modifiedMethods.filter { !atuaMF.statementMF!!.executedMethodsMap.contains(it.key) }
        val usefulness = ModelHistoryInformation.INSTANCE.inputUsefulness[input]
        val score = if (usefulness != null) {
            (usefulness.second * 1.0 / (usefulness.first + 1)) * notFullyCoveredMethods.size
        } else if (input.widget!=null && !EWTGDiff.instance.getAddedWidgets().contains(input.widget!!)) {
            0.0
        }
        else {
            1.0 * notFullyCoveredMethods.size
        }
        return score
    }

    var alreadyRandomInputInTarget = false


    private fun isBudgetAvailable(): Boolean {
        if (budgetType == BudgetType.UNSET)
            return true
        if (budgetType == BudgetType.EXERCISE_TARGET)
            return budgetLeft > 0
        if (budgetType == BudgetType.RANDOM_EXPLORATION)
            return randomBudgetLeft > 0
        return true
    }

    private fun nextActionOnInitial(currentAppState: AbstractState, exerciseTargetComponentTask: ExerciseTargetComponentTask, currentState: State<*>, randomExplorationTask: RandomExplorationTask, goToTargetNodeTask: GoToTargetWindowTask, goToAnotherNode: GoToAnotherWindowTask) {
        alreadyRandomInputInTarget = true
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetComponentTask.isAvailable(currentState)) {
                setExerciseTarget(exerciseTargetComponentTask, currentState)
                return
            }
            if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,false, true, true, false)) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,false, true, true, false)) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }

        if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,true, true, true, false)) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState, currentAppState)
            return
        }
        if (goToAnotherNode.isAvailable(currentState)) {
            strategyTask = goToAnotherNode.also {
                it.initialize(currentState)
                it.retryTimes = 0
            }
            log.info("Go to target window by visiting another window: ${targetWindow.toString()}")
            numOfContinousTry = 0
            phaseState = PhaseState.P2_GO_TO_EXPLORE_STATE
            return
        }
        setRandomExploration(randomExplorationTask, currentState, currentAppState)
//        selectTargetWindow(currentState,0)
//        attempt++
//        setFullyRandomExploration(randomExplorationTask, currentState)
        return
    }

    private fun setRandomExplorationBudget(currentState: State<*>) {
        if (budgetType == BudgetType.RANDOM_EXPLORATION)
            return
        if (budgetType == BudgetType.EXERCISE_TARGET) {
            budgetType == BudgetType.RANDOM_EXPLORATION
            return
        }
        budgetType = BudgetType.RANDOM_EXPLORATION
        if (randomBudgetLeft > 0)
            return
        randomBudgetLeft = (20 * scaleFactor).toInt()
        return
        /*val inputWidgetCount = Helper.getUserInputFields(currentState).size
        val rawInputCount = (Helper.getActionableWidgetsWithoutKeyboard(currentState).size - inputWidgetCount)
        val baseActionCount = if (rawInputCount < 0)
            5.0
        else
            log2(rawInputCount*2.toDouble())
        randomBudgetLeft = (baseActionCount * scaleFactor).toInt()*/
    }

    private fun setExerciseBudget(currentState: State<*>) {
        if (budgetType == BudgetType.EXERCISE_TARGET)
            return
        if (budgetType == BudgetType.RANDOM_EXPLORATION) {
            budgetType = BudgetType.EXERCISE_TARGET
            return
        }
        budgetType = BudgetType.EXERCISE_TARGET
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        val exerciseTestBudget =
            computeExerciseTestBudget(currentState)
        budgetLeft = max((20*scaleFactor).toInt(),exerciseTestBudget.toInt())
    }

    private fun computeExerciseTestBudget(currentState: State<*>): Int {
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        //val inputWidgetCount = 1
        val targetEvents =
            phase2TargetEvents.filter { it.key.sourceWindow == targetWindow && it.key.verifiedEventHandlers.isNotEmpty() }
        var targetEventCount = targetEvents.keys.sumBy {
            if (it.widget == null)
                1
            else if (it.widget!!.isItemWidget) {
                5
            } else
                1
        }
        val targetWidgets = targetEvents.map { it.key.widget }
        val inputWidget = Helper.getUserInputFields(currentState).filter {
            val avm =  currentAppState.getAttributeValuationSet(it,currentState,atuaMF)
            if (avm == null)
                true
            else {
                val ewtgWidget = currentAppState.EWTGWidgetMapping[avm]
                if (ewtgWidget == null)
                    true
                else {
                    !targetWidgets.contains(ewtgWidget)
                }
            }
        }
        val inputWidgetCount = inputWidget.size
        if (targetEventCount == 0)
            targetEventCount = 1
        /*val abstractStateCnt = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it.window == targetWindow
                && it.guiStates.isNotEmpty()}.filter{ it.getAvailableInputs().intersect(targetEvents.keys).isNotEmpty() }. size*/

        val undiscoverdTargetHiddenHandlers = atuaMF.untriggeredTargetHiddenHandlers.filter {
            atuaMF.windowHandlersHashMap.get(targetWindow!!)?.contains(it) ?: false
        }
        //        if (undiscoverdTargetHiddenHandlers.isNotEmpty())
        //            budgetLeft = (targetEventCount * (inputWidgetCount+1)+ log2(undiscoverdTargetHiddenHandlers.size.toDouble()) * scaleFactor).toInt()
        //        else
        val exerciseTestBudget =
            ((targetEventCount * (inputWidgetCount + 1)) * scaleFactor).toInt()
        return exerciseTestBudget
    }

    private fun computeExerciseTestBudget(window: Window): Int {
        val userlikeInputsCnt = window.widgets.filter { it.isUserLikeInput && it.witnessed && !it.verifiedNotUserlikeInput }.size
        val targetEvents =
            phase2TargetEvents.filter { it.key.sourceWindow == targetWindow && it.key.verifiedEventHandlers.isNotEmpty() }
        var targetEventCount = targetEvents.keys.sumBy {
            if (it.widget == null)
                1
            else if (it.widget!!.isItemWidget) {
                5
            } else
                1
        }
        if (targetEventCount == 0)
            targetEventCount = 1
//        val abstractStateCnt = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it.window == window
//                && it.guiStates.isNotEmpty()}.filter{ it.getAvailableInputs().intersect(targetEvents.keys).isNotEmpty() }. size
        val undiscoverdTargetHiddenHandlers = atuaMF.untriggeredTargetHiddenHandlers.filter {
            atuaMF.windowHandlersHashMap.get(targetWindow!!)?.contains(it) ?: false
        }
        //        if (undiscoverdTargetHiddenHandlers.isNotEmpty())
        //            budgetLeft = (targetEventCount * (inputWidgetCount+1)+ log2(undiscoverdTargetHiddenHandlers.size.toDouble()) * scaleFactor).toInt()
        //        else
        val exerciseTestBudget =
            ((targetEventCount * (userlikeInputsCnt + 1) + undiscoverdTargetHiddenHandlers.size) * scaleFactor).toInt()

        return exerciseTestBudget
    }

    private fun nextActionOnExerciseTargetWindow(currentState: State<*>, currentAppState: AbstractState, randomExplorationTask: RandomExplorationTask, goToTargetNodeTask: GoToTargetWindowTask, goToAnotherNode: GoToAnotherWindowTask, exerciseTargetComponentTask: ExerciseTargetComponentTask) {
        if (!windowEffectiveScore.containsKey(targetWindow))
            windowEffectiveScore.put(targetWindow!!,0)
        val updatedCoverageIncrease = statementMF.actionUpdatedCoverageTracking[eContext!!.getLastAction().actionId.toString()]?.size
        if (updatedCoverageIncrease!= null && updatedCoverageIncrease>0) {
            windowEffectiveScore[targetWindow!!] = windowEffectiveScore[targetWindow!!]!! + 1
        }
        if (!strategyTask!!.isTaskEnd(currentState)) {
            //Keep current task
            log.info("Continue exercise target window")

            return
        }
        if (currentAppState.isRequireRandomExploration() || Helper.isOptionsMenuLayout(currentState) ) {
            setRandomExploration(randomExplorationTask, currentState, currentAppState, true, lockWindow = false)
        }
        if (currentAppState.window == targetWindow) {
            if (currentAppState.getUnExercisedActions(currentState, atuaMF).isNotEmpty()) {
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return
            }
        }
        if (goToTargetNodeTask.isAvailable(currentState,
                destWindow = targetWindow!!,
                isWindowAsTarget = false,
                includePressback =  true,
                includeResetApp =  true,
                isExploration =  false)) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetComponentTask.isAvailable(currentState)) {
                setExerciseTarget(exerciseTargetComponentTask, currentState)
                return
            }
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(currentState,
                destWindow = targetWindow!!,
                isWindowAsTarget = true,
                includePressback =  true,
                includeResetApp =  true,
                isExploration =  false)) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()
            ) {
            setRandomExploration(randomExplorationTask, currentState, currentAppState)
            return
        }
        if (goToAnotherNode.isAvailable(currentState)) {
            setGoToExploreState(goToAnotherNode, currentState)
            return
        }
        setFullyRandomExploration(randomExplorationTask, currentState)
        return
    }

    private fun nextActionOnRandomInTargetWindow(randomExplorationTask: RandomExplorationTask, currentAppState: AbstractState, currentState: State<*>, exerciseTargetComponentTask: ExerciseTargetComponentTask, goToTargetNodeTask: GoToTargetWindowTask, goToAnotherNode: GoToAnotherWindowTask) {
        if (!windowEffectiveScore.containsKey(targetWindow))
            windowEffectiveScore.put(targetWindow!!,0)
        val updatedCoverageIncrease = statementMF.actionUpdatedCoverageTracking[eContext!!.getLastAction().actionId.toString()]?.size
        if (updatedCoverageIncrease!= null && updatedCoverageIncrease>0) {
            windowEffectiveScore[targetWindow!!] = windowEffectiveScore[targetWindow!!]!! + 1
        }
        if (!strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue random in target window")
            return
        }
        /*phase2TargetEvents.filter { it.key.sourceWindow == targetWindow }.keys.also {
            currentTargetInputs.clear()
            currentTargetInputs.addAll(it) }*/
        alreadyRandomInputInTarget = true
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetComponentTask.isAvailable(currentState)) {
                setExerciseTarget(exerciseTargetComponentTask, currentState)
                return
            }
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            /*if (Random.nextDouble() >= 0.2) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
            }*/
            /*val targetWindowEvents = phase2TargetEvents.filter {
                it.key.sourceWindow == targetWindow!!
            }
            if (targetWindowEvents.isEmpty())
                setRandomExplorationBudget(currentState)
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)*/
        }
        if (currentAppState.isRequireRandomExploration() || Helper.isOptionsMenuLayout(currentState) ) {
            setRandomExploration(randomExplorationTask, currentState, currentAppState, true, lockWindow = false)
            return
        }
        if (goToTargetNodeTask.isAvailable(currentState,
                destWindow = targetWindow!!,
                isWindowAsTarget = false,
                includePressback =  true,
                includeResetApp =  true,
                isExploration =  false))  {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(currentState,
                destWindow = targetWindow!!,
                isWindowAsTarget = true,
                includePressback =  true,
                includeResetApp =  true,
                isExploration =  false))  {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()
          ) {
            setRandomExploration(randomExplorationTask, currentState, currentAppState)
            return
        }
        if (goToAnotherNode.isAvailable(currentState)) {
            strategyTask = goToAnotherNode.also {
                it.initialize(currentState)
                it.retryTimes = 0
            }
            log.info("Go to target window by visiting another window: ${targetWindow.toString()}")
            numOfContinousTry = 0
            phaseState = PhaseState.P2_GO_TO_EXPLORE_STATE
            return
        }
        /*if (goToAnotherNode.isAvailable(currentState)) {
            setGoToExploreState(goToAnotherNode, currentState)
            return
        }*/
        setFullyRandomExploration(randomExplorationTask, currentState)
        return
    }

    private fun nextActionOnGoToTargetWindow(currentState: State<*>, currentAppState: AbstractState, exerciseTargetComponentTask: ExerciseTargetComponentTask, randomExplorationTask: RandomExplorationTask, goToAnotherNode: GoToAnotherWindowTask, goToTargetNodeTask: GoToTargetWindowTask) {
        if (!strategyTask!!.isTaskEnd(currentState)) {
            //Keep current task
            log.info("Continue go to target window")
            return
        }
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetComponentTask.isAvailable(currentState)) {
                reachedTarget = true
                setExerciseTarget(exerciseTargetComponentTask, currentState)
                return
            }
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        if (strategyTask is GoToAnotherWindowTask) {
            if ((strategyTask as GoToAnotherWindowTask).isWindowAsTarget == false) {
                if (goToTargetNodeTask.isAvailable(currentState,
                        destWindow = targetWindow!!,
                        isWindowAsTarget = true,
                        includePressback =  true,
                        includeResetApp =  true,
                        isExploration =  false))  {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
            }
        }
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()
           ) {
            setRandomExploration(randomExplorationTask, currentState, currentAppState)
            return
        }
        if (goToAnotherNode.isAvailable(currentState)) {
            strategyTask = goToAnotherNode.also {
                it.initialize(currentState)
                it.retryTimes = 0
            }
            log.info("Go to target window by visiting another window: ${targetWindow.toString()}")
            numOfContinousTry = 0
            phaseState = PhaseState.P2_GO_TO_EXPLORE_STATE
            return
        }
        setRandomExploration(randomExplorationTask, currentState, currentAppState, true, false)
        return
    }

    private fun nextActionOnGoToExploreState(currentAppState: AbstractState, exerciseTargetComponentTask: ExerciseTargetComponentTask, currentState: State<*>, randomExplorationTask: RandomExplorationTask, goToTargetNodeTask: GoToTargetWindowTask, goToAnotherNode: GoToAnotherWindowTask) {
        if (targetWindow!=null) {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                /*setExerciseBudget(currentState)
            if (Random.nextDouble() >= 0.2) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
            } else {
                val targetWindowEvents = phase2TargetEvents.filter {
                    it.key.sourceWindow == targetWindow!!
                }
                if (targetWindowEvents.isEmpty())
                    setRandomExplorationBudget(currentState)
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return
            }*/
            }
        }
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState, currentAppState)
            return
        }
        if (!strategyTask!!.isTaskEnd(currentState)) {
            //Keep current task
            log.info("Continue go to the window")
            return
        }
        if (strategyTask is GoToAnotherWindowTask) {
            if ((strategyTask as GoToAnotherWindowTask).reachedDestination) {
                setRandomExploration(randomExplorationTask, currentState,currentAppState)
                return
            }
        }
        if (targetWindow != null) {
            if (goToTargetNodeTask.isAvailable(
                    currentState,
                    destWindow = targetWindow!!,
                    isWindowAsTarget = false,
                    includePressback = true,
                    includeResetApp = true,
                    isExploration = false
                )
            ) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
        }
        if (goToAnotherNode.isAvailable(currentState)) {
            strategyTask = goToAnotherNode.also {
                it.initialize(currentState)
                it.retryTimes = 0
            }
            log.info("Go to target window by visiting another window: ${targetWindow.toString()}")
            numOfContinousTry++
            phaseState = PhaseState.P2_GO_TO_EXPLORE_STATE
            return
        }
        setRandomExploration(randomExplorationTask, currentState,currentAppState)
        return
    }

    private fun nextActionOnRandomExploration(currentAppState: AbstractState, exerciseTargetComponentTask: ExerciseTargetComponentTask, currentState: State<*>, randomExplorationTask: RandomExplorationTask, goToTargetNodeTask: GoToTargetWindowTask, goToAnotherNode: GoToAnotherWindowTask, eContext: ExplorationContext<*, *, *>) {
        if (!strategyTask!!.isTaskEnd(currentState)) {
            //Keep current task
            log.info("Continue doing random exploration")
            return
        }
        if (targetWindow != null) {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                /*setExerciseBudget(currentState)
            if (Random.nextDouble() >= 0.2) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return true
                }
            } else {
                val targetWindowEvents = phase2TargetEvents.filter {
                    it.key.sourceWindow == targetWindow!!
                }
                if (targetWindowEvents.isEmpty())
                    setRandomExplorationBudget(currentState)
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return true
            }*/
            }
        }

        if (currentAppState.isRequireRandomExploration() || Helper.isOptionsMenuLayout(currentState) ) {
            log.info("Continue doing random exploration")
            return
        }
        if (targetWindow != null) {
            if (goToTargetNodeTask.isAvailable(
                    currentState,
                    destWindow = targetWindow!!,
                    isWindowAsTarget = false,
                    includePressback = true,
                    includeResetApp = true,
                    isExploration = false
                )
            ) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            if (goToTargetNodeTask.isAvailable(
                    currentState,
                    destWindow = targetWindow!!,
                    isWindowAsTarget = true,
                    includePressback = true,
                    includeResetApp = true,
                    isExploration = false
                )
            ) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
        }
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()
            ) {
            setRandomExploration(randomExplorationTask, currentState, currentAppState)
            return
        }
        if (goToAnotherNode.isAvailable(currentState)) {
            strategyTask = goToAnotherNode.also {
                it.initialize(currentState)
                it.retryTimes = 0
            }
            log.info("Go to target window by visiting another window: ${targetWindow.toString()}")
            numOfContinousTry = 0
            phaseState = PhaseState.P2_GO_TO_EXPLORE_STATE
            return
        }
        setFullyRandomExploration(randomExplorationTask, currentState)
        /*selectTargetWindow(currentState, 0)
       *//* currentTargetInputs.clear()
        currentTargetInputs.addAll(phase2TargetEvents.filter { it.key.sourceWindow == targetWindow}.keys)*//*
        log.info("Phase budget left: $attempt")
        //setTestBudget = false
        //needResetApp = true
        phaseState = PhaseState.P2_INITIAL
        chooseTask(eContext, currentState)*/
        return
    }

    private fun setGoToExploreState(goToAnotherNode: GoToAnotherWindowTask, currentState: State<*>) {
        strategyTask = goToAnotherNode.also {
            it.initialize(currentState)
            it.retryTimes = 0
        }
        log.info("Go to target window by visiting another window: ${targetWindow.toString()}")
        phaseState = PhaseState.P2_GO_TO_EXPLORE_STATE
    }

    private fun setGoToTarget(goToTargetNodeTask: GoToAnotherWindowTask, currentState: State<*>) {
        log.info("Task chosen: Go to target node .")
        remainPhaseStateCount += 1
        strategyTask = goToTargetNodeTask.also {
            it.initialize(currentState)
            it.retryTimes = 0
        }
        phaseState = PhaseState.P2_GO_TO_TARGET_NODE
    }

    private fun setExerciseTarget(exerciseTargetComponentTask: ExerciseTargetComponentTask, currentState: State<*>) {
        log.info("Task chosen: Exercise Target Node .")
        setExerciseBudget(currentState)
        phaseState = PhaseState.P2_EXERCISE_TARGET_NODE
        remainPhaseStateCount = 0
        recentlyRandom = false
        strategyTask = exerciseTargetComponentTask.also {
            it.initialize(currentState)
            it.randomRefillingData = true
            it.environmentChange = true
            it.alwaysUseRandomInput = true
        }
    }

    private fun setFullyRandomExploration(randomExplorationTask: RandomExplorationTask, currentState: State<*>) {
        log.info("Task chosen: Fully Random Exploration")
        phaseState = PhaseState.P2_RANDOM_EXPLORATION
        setRandomExplorationBudget(currentState)
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.setMaxiumAttempt((5 * scaleFactor).toInt())
            it.isPureRandom = true
            it.environmentChange = true
            it.alwaysUseRandomInput = true
            it.stopWhenHavingTestPath = false
        }
    }

    private fun setRandomExploration(randomExplorationTask: RandomExplorationTask,
                                     currentState: State<*>,
                                     currentAbstractState: AbstractState,
                                     stopWhenTestPathIdentified: Boolean = false,
                                     lockWindow: Boolean = false,
                                    budget: Int =-1) {
        setRandomExplorationBudget(currentState)
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.environmentChange = true
            it.alwaysUseRandomInput = true
            if (budget != -1)
                it.setMaxiumAttempt((10 * scaleFactor).toInt())
            else
                it.setMaxiumAttempt(budget)
            it.stopWhenHavingTestPath = stopWhenTestPathIdentified
        }
//        log.info("Cannot find path the target node.")
        log.info("Random exploration")
        phaseState = PhaseState.P2_RANDOM_EXPLORATION
    }

    private fun setRandomExplorationInTargetWindow(randomExplorationTask: RandomExplorationTask, currentState: State<*>) {
        val inputWidgetCount = Helper.getUserInputFields(currentState).size
        setRandomExplorationBudget(currentState)
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.setMaxiumAttempt((10 * scaleFactor).toInt())
            it.environmentChange = true
            it.lockTargetWindow(targetWindow!!)
            it.alwaysUseRandomInput = true
        }
        recentlyRandom = true
        log.info("Random exploration in target window")
        phaseState = PhaseState.P2_RANDOM_IN_EXERCISE_TARGET_NODE

    }
    var recentlyRandom: Boolean = false
    var setTestBudget = false

    val windowEffectiveScore = HashMap<Window,Int>()
    fun selectTargetWindow(currentState: State<*>, numberOfTried: Int, in_maxTried: Int = 0) {
        log.info("Select a target Window.")
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        if (targetWindowsCount.isEmpty()) {
            val currentAppState = currentAbstractState
            targetWindow = currentAppState.window
        } else {
            computeAppStatesScore()
            /*var leastExercise = targetWindowsCount.values.min()
            var leastTriedWindows = targetWindowsCount.filter { windowScores.containsKey(it.key) }
                .map { Pair<Window, Int>(first = it.key, second = it.value) }*//*.filter { it.second == leastExercise }*//*

            if (leastTriedWindows.isEmpty()) {
                leastTriedWindows = targetWindowsCount.map { Pair<Window, Int>(first = it.key, second = it.value) }
                    .filter { it.second == leastExercise }
            }

            val leastTriedWindowScore =
                leastTriedWindows.associate { Pair(it.first, windowScores.get(it.first) ?: 1.0) }
            val maxTried =
                if (in_maxTried == 0) {
                    leastTriedWindowScore.size / 2 + 1
                } else {
                    in_maxTried
                }*/
            val pathConstraints = HashMap<PathConstraint,Boolean>()
            pathConstraints.put(PathConstraint.INCLUDE_RESET,true)
            pathConstraints.put(PathConstraint.INCLUDE_LAUNCH,true)
            pathConstraints.put(PathConstraint.MAXIMUM_DSTG,true)
            val leastTriedWindowScore = HashMap(windowScores.filter {
                val window = it.key
                val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window)!!
                val statesWithScore = HashMap<AbstractState,Double>()
                statesWithScore.put(virtualAbstractState,1.0)
                val transitionPath = ArrayList<TransitionPath>()
                getPathToStatesBasedOnPathType(
                    currentState = currentState,
                    pathType = PathFindingHelper.PathType.WIDGET_AS_TARGET,
                    statesWithScore = statesWithScore,
                    goalByAbstractState = emptyMap(),
                    maxCost = 25.0*scaleFactor,
                    windowAsTarget = true,
                    shortest = true,
                    transitionPaths = transitionPath,
                    currentAbstractState = currentAbstractState,
                    abandonedAppStates = emptyList(),
                    pathConstraints = pathConstraints
                )
                transitionPath.isNotEmpty()
            })

            leastTriedWindowScore.keys.forEach {
                if (windowEffectiveScore.containsKey(it)) {
                    val score = windowEffectiveScore[it]!!
                    if (score>0)
                        leastTriedWindowScore[it] = leastTriedWindowScore[it]!!*(score+1)
                }
            }
            if (leastTriedWindowScore.isNotEmpty()) {
                val pb = ProbabilityDistribution<Window>(leastTriedWindowScore)
                val targetNode = pb.getRandomVariable()
                targetWindow = targetNode
            } else {
                targetWindow = targetWindowsCount.map { it.key }.random()
            }
            targetWindowsCount[targetWindow!!] = targetWindowsCount[targetWindow!!]!! + 1
            windowEffectiveScore.put(targetWindow!!,0)
        }
        budgetLeft = -1
        budgetType = BudgetType.UNSET
        //setTestBudget = false
        attempt--
        atuaMF.updateMethodCovFromLastChangeCount = 0
        establishTargetInputsForWindow()
    }

    fun computeAppStatesScore() {
        //Initiate reachable modified methods list
        modifiedMethodMissingStatements.clear()
        modifiedMethodTriggerCount.clear()
        appStateModifiedMethodMap.clear()
        modifiedMethodWeights.clear()
        phase2TargetEvents.clear()
        val usefulTargets = atuaMF.notFullyExercisedTargetInputs.filter {
            it.eventType != EventType.resetApp
                    && it.eventType != EventType.implicit_launch_event
                    && ModelHistoryInformation.INSTANCE.inputUsefulness.containsKey(it)
                    && ModelHistoryInformation.INSTANCE.inputUsefulness[it]!!.second>0
        }
        val notExercisedYetTargets = atuaMF.notFullyExercisedTargetInputs.filter {
            it.eventType != EventType.resetApp
                    && it.eventType != EventType.implicit_launch_event
                    && ( it.exerciseCount == 0)
        }
        val allTargetInputs = ArrayList(usefulTargets.union(notExercisedYetTargets).distinct())

        val triggeredStatements = statementMF.getAllExecutedStatements()
        statementMF.getAllModifiedMethodsId().forEach {
            val methodName = statementMF!!.getMethodName(it)
            if (!atuaMF.unreachableModifiedMethods.contains(methodName)) {
                modifiedMethodTriggerCount.put(it, 0)
                val statements = statementMF!!.getMethodStatements(it)
                val missingStatements = statements.filter { !triggeredStatements.contains(it) }
                modifiedMethodMissingStatements.put(it, HashSet(missingStatements))
            }
        }
        allTargetInputs.removeIf {
            it.modifiedMethods.map { it.key }.all {
                modifiedMethodMissingStatements.containsKey(it) && modifiedMethodMissingStatements[it]!!.size == 0
            }
        }
        //get all AppState
        val appStateList = ArrayList<AbstractState>()
        AbstractStateManager.INSTANCE.getPotentialAbstractStates().forEach { appStateList.add(it) }

        //get all AppState's edges and appState's modified method
        val edges = ArrayList<Edge<AbstractState, AbstractTransition>>()
        appStateList.forEach { appState ->
            edges.addAll(atuaMF.dstg.edges(appState).filter { it.label.isExplicit() || it.label.fromWTG })
            appStateModifiedMethodMap.put(appState, HashSet())
            appState.abstractTransitions.map { it.modifiedMethods }.forEach { hmap ->
                hmap.forEach { m, v ->
                    if (!appStateModifiedMethodMap[appState]!!.contains(m)) {
                        appStateModifiedMethodMap[appState]!!.add(m)
                    }
                }
            }
        }
        //for each edge, count modified method appearing
        edges.forEach { edge ->
            val coveredMethods = edge.label.methodCoverage
            if (coveredMethods != null)
                coveredMethods.forEach {
                    if (atuaMF.statementMF!!.isModifiedMethod(it)) {
                        if (modifiedMethodTriggerCount.containsKey(it)) {
                            modifiedMethodTriggerCount[it] = modifiedMethodTriggerCount[it]!! + edge.label.interactions.size
                        }
                    }
                }
        }
        //calculate modified method score
        val totalAbstractInteractionCount = edges.size
        modifiedMethodTriggerCount.forEach { m, c ->
            val score = 1 - c / totalAbstractInteractionCount.toDouble()
            modifiedMethodWeights.put(m, score)
        }

        //calculate appState score
        appStateList.forEach {
            var appStateScore: Double = 0.0
            val frequency = atuaMF.abstractStateVisitCount.get(it)?:1
            // an abstract state has higher score if there are more unexercised target abstract transitions
            val unexercisedTargetAbstractActions = it.abstractTransitions.filter { t->
                t.interactions.isNotEmpty()
            }.map { it.abstractAction }.distinct().filter { action -> it.getInputsByAbstractAction(action).any { it.modifiedMethods.isNotEmpty() } }
            unexercisedTargetAbstractActions.forEach { action ->
                val inputs = it.getInputsByAbstractAction(action)
                for (item in inputs.map { it.modifiedMethods.entries }.flatten()) {
                    if (!modifiedMethodWeights.containsKey(item.key))
                        modifiedMethodWeights.put(item.key, 1.0)
                    val methodWeight = modifiedMethodWeights[item.key]!!
                    if (modifiedMethodMissingStatements.containsKey(item.key)) {
                        val missingStatementNumber = modifiedMethodMissingStatements[item.key]!!.size
                        if (action.meaningfulScore>0)
                            appStateScore += (methodWeight * missingStatementNumber/frequency*2)
                    }
                }
            }
            abstractStatesScores.put(it, appStateScore)
           /* if (appStateModifiedMethodMap.containsKey(it)) {
                appStateModifiedMethodMap[it]!!.forEach {
                    if (!modifiedMethodWeights.containsKey(it))
                        modifiedMethodWeights.put(it, 1.0)
                    val methodWeight = modifiedMethodWeights[it]!!
                    if (modifiedMethodMissingStatements.containsKey(it)) {
                        val missingStatementNumber = modifiedMethodMissingStatements[it]!!.size
                        appStateScore += (methodWeight * missingStatementNumber/frequency)
                    }
                }
                //appStateScore += 1
                abstractStatesScores.put(it, appStateScore)
            }*/
        }

        //calculate appState probability
        appStateList.groupBy { it.window }.forEach { window, abstractStateList ->
            var totalScore = 0.0
            abstractStateList.forEach {
                totalScore += abstractStatesScores[it]!!
            }
            val appStatesProbab = ArrayList<Pair<AbstractState, Double>>()
            abstractStateProbabilityByWindow.put(window, appStatesProbab)
            if (totalScore == 0.0) {
                abstractStateList.forEach {
                    appStatesProbab.add(Pair(it,0.0))
                }
            } else {
                abstractStateList.forEach {
                    val pb = abstractStatesScores[it]!! / totalScore
                    appStatesProbab.add(Pair(it, pb))
                }
            }
        }

        //calculate staticNode score
        var staticNodeTotalScore = 0.0
        windowScores.clear()
        targetWindowsCount.filter { abstractStateProbabilityByWindow.containsKey(it.key) }.forEach { n, _ ->
            var weight: Double = 0.0
            val modifiedMethods = HashSet<String>()
/*            appStateModifiedMethodMap.filter { it.key.staticNode == n}.map { it.value }.forEach {
                it.forEach {
                    if (!modifiedMethods.contains(it))
                    {
                        modifiedMethods.add(it)
                    }
                }
            }*/
            val windowTargetInputs = allTargetInputs.filter { it.sourceWindow == n }
            /*windowTargetInputs.forEach {
                modifiedMethods.addAll(it.modifiedMethods.map { it.key })
            }*/
            var inputEffectiveness = 0.0
            val usefulTargetInputs = windowTargetInputs.filter {
                !ModelHistoryInformation.INSTANCE.inputUsefulness.containsKey(it)
                        || ModelHistoryInformation.INSTANCE.inputUsefulness[it]!!.second>1
            }
            usefulTargetInputs.forEach { input ->
                modifiedMethods.addAll(input.modifiedMethods.map { it -> it.key })
                val notFullyCoveredMethods =
                    input.modifiedMethods.filter { !atuaMF.statementMF!!.fullyCoveredMethods.contains(it.key) }
                val uncoveredMethods =  input.modifiedMethods.filter { !atuaMF.statementMF!!.executedMethodsMap.contains(it.key) }
                val usefulness = ModelHistoryInformation.INSTANCE.inputUsefulness[input]
                val score = if (usefulness!=null) {
                    (usefulness.second*1.0/(usefulness.first+1))* notFullyCoveredMethods.size
                } else {
                    notFullyCoveredMethods.size*1.0
                }
                inputEffectiveness+=score
            }
/*            if (atuaMF.windowHandlersHashMap.containsKey(n)) {
                atuaMF.windowHandlersHashMap[n]!!.forEach { handler ->
                    val methods = atuaMF.modifiedMethodWithTopCallers.filter { it.value.contains(handler) }.map { it.key }
                    modifiedMethods.addAll(methods)
                }
            }*/

            modifiedMethods.filter { modifiedMethodWeights.containsKey(it) }.forEach {
                val methodWeight = modifiedMethodWeights[it]!!
                val missingStatementsNumber = modifiedMethodMissingStatements[it]?.size ?: 0
                weight += (methodWeight * missingStatementsNumber)
            }
            weight+=inputEffectiveness
            if (weight > 0.0) {
                windowScores.put(n, weight)
                staticNodeTotalScore += weight
            }
        }
        allTargetInputs.forEach {
            if (it.eventType != EventType.resetApp && (!it.usefullOnce || it.exerciseCount == 0)) {
                phase2TargetEvents.put(it, 0)
            }
        }
        windowsProbability.clear()
        //calculate staticNode probability
        windowScores.forEach { n, s ->
            val pb = s / staticNodeTotalScore
            windowsProbability.put(n, pb)
        }
    }


    companion object {
        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(PhaseTwoStrategy::class.java) }

        val TEST_BUDGET: Int = 25
    }
}

enum class BudgetType {
    UNSET,
    EXERCISE_TARGET,
    RANDOM_EXPLORATION
}
