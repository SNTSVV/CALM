package org.droidmate.exploration.strategy.atua

import org.atua.calm.ewtgdiff.EWTGDiff
import org.atua.calm.modelReuse.ModelHistoryInformation
import org.atua.calm.modelReuse.ModelVersion
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.Cardinality
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.EWTGWidget
import org.atua.modelFeatures.ewtg.EventType
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.TransitionPath
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.ewtg.window.OptionsMenu
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.helper.Goal
import org.atua.modelFeatures.helper.PathConstraint
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.isLaunchApp
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.resetApp
import org.droidmate.exploration.strategy.atua.task.ExerciseTargetComponentTask
import org.droidmate.exploration.strategy.atua.task.GoToAnotherWindowTask
import org.droidmate.exploration.strategy.atua.task.GoToTargetWindowTask
import org.droidmate.exploration.strategy.atua.task.OpenNavigationBarTask
import org.droidmate.exploration.strategy.atua.task.PrepareContextTask
import org.droidmate.exploration.strategy.atua.task.RandomExplorationTask
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RandomExplorationStrategy(
    atuaTestingStrategy: ATUATestingStrategy,
    budgetScale: Double,
    delay: Long,
    useCoordinateClicks: Boolean,
    val strategy: Int = 2
) : AbstractPhaseStrategy(
    atuaTestingStrategy = atuaTestingStrategy,
    scaleFactor = budgetScale,
    delay = delay,
    useCoordinateClicks = useCoordinateClicks,
    useVirtualAbstractState = true

) {
    override fun isTargetWindow(window: Window): Boolean {
        if (window == targetWindow)
            return true
        return false
    }

    var attemps: Int
    var targetWindow: Window? = null

    val targetWindowTryCount: HashMap<Window, Int> = HashMap()
    val windowRandomExplorationBudget: HashMap<Window, Int> = HashMap()
    val windowRandomExplorationBudgetUsed: HashMap<Window, Int> = HashMap()

    var delayCheckingBlockStates = 0
    var tryCount = 0
    var forceEnd = false
    var episodeCountDown = 0

    init {
        phaseState = PhaseState.P1_INITIAL
        atuaMF = atuaTestingStrategy.eContext.getOrCreateWatcher()
        attemps = atuaMF.modifiedMethodsByWindow.size
        atuaMF.modifiedMethodsByWindow.keys.forEach {
            targetWindowTryCount.put(it, 0)
        }

    }

    var recentTargetEvent: Input? = null


    override fun hasNextAction(currentState: State<*>): Boolean {

        //For debug, end phase one after 100 actions
        /*if (autAutTestingStrategy.eContext.explorationTrace.getActions().size > 100)
            return true*/

        if (atuaMF.lastUpdatedStatementCoverage == 1.0) {
            return false
        }


        return true
    }

    override fun registerTriggeredInputs(chosenAbstractAction: AbstractAction, currentState: State<*>) {
        TODO("Not yet implemented")
    }


    private fun isAvailableAbstractStatesExisting(currentState: State<*>): Boolean {
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        val availableAbstractStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES
            .filter {
                it !is VirtualAbstractState
                        && it.attributeValuationMaps.isNotEmpty()
                        && ((windowRandomExplorationBudget.containsKey(it.window)
                        && hasBudgetLeft(it.window)
                        && it.guiStates.isNotEmpty())
                        || (!windowRandomExplorationBudget.containsKey(it.window)
                        && it.modelVersion == ModelVersion.BASE))
            }
        if (availableAbstractStates.isEmpty())
            return false
        if (availableAbstractStates.any { !isBlocked(it, currentState) }) {
            return true
        }
        return false
    }



    override fun nextAction(eContext: ExplorationContext<*, *, *>): ExplorationAction {
        atuaMF.dstg.cleanPredictedAbstractStates()
        //TODO Update target windows
        episodeCountDown--
        /* val allowResetAction: Boolean
         runBlocking {
             val resetActionIndex = eContext.explorationTrace.P_getActions().indexOfLast { it.actionType == "ResetApp" }
             if (eContext.explorationTrace.P_getActions().size-1-resetActionIndex > 50) {

             }
         }*/

        val currentState = eContext.getCurrentState()
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        log.info("Current abstract state: $currentAppState")
        log.info("Current window: ${currentAppState.window}")
        /*if (phaseState != PhaseState.P1_EXERCISE_TARGET_NODE
                && phaseState != PhaseState.P1_GO_TO_EXPLORE_STATE
                && phaseState != PhaseState.P1_GO_TO_TARGET_NODE
                && needReset(currentState)) {
            return eContext.resetApp()
        }*/
        var chosenAction: ExplorationAction?

        /*else if (outofbudgetWindows.contains(targetWindow!!)  ) {
            //try select another target window
            selectTargetWindow(currentState, false).also {
                resetStrategyTask(currentState)
                log.info("Switch target window to $targetWindow")
            }
        } *//*else if (currentAppState.window == targetWindow && getCurrentTargetEvents(currentState).isEmpty()) {
            if (getPathsToTargetWindows(currentState,PathFindingHelper.PathType.ANY).isEmpty()) {
                //if current abstract state is a target but does not have target events
                //and their no path to the abstract states with target events
                //select another target window
                selectTargetNode(currentState, 0).also {
                    if (targetWindow != null) {
                        strategyTask = null
                        phaseState = PhaseState.P1_INITIAL
                    }
                }
            }
        }*/
        ExplorationTrace.widgetTargets.clear()

        log.info("Target window: $targetWindow")
        chooseTask_P1(eContext, currentState)
        if (needResetApp) {
            needResetApp = false
            return eContext.resetApp()
        }
        if (strategyTask != null) {
            log.debug(phaseState.name)
            chosenAction = strategyTask!!.chooseAction(currentState)
            if (chosenAction == null)
                chosenAction = ExplorationAction.pressBack()
            consumeTestBudget(chosenAction, currentAppState)
        } else {
            log.debug("No task seleted. It might be a bug.")
            chosenAction = eContext.resetApp()
        }
        actionCountSinceSelectTarget++
        return chosenAction
    }

    override fun isTargetState(currentState: State<*>): Boolean {
        TODO("Not yet implemented")
    }


    private fun consumeTestBudget(
        chosenAction: ExplorationAction,
        currentAppState: AbstractState
    ) {
        if (isCountAction(chosenAction)
            && windowRandomExplorationBudgetUsed.containsKey(currentAppState.window)
            && ((
                    strategyTask is RandomExplorationTask
                            && (strategyTask as RandomExplorationTask).fillingData == false
                            && (strategyTask as RandomExplorationTask).goToLockedWindowTask == null
                    )
                    ||
                    (strategyTask is ExerciseTargetComponentTask
                            && !(strategyTask as ExerciseTargetComponentTask).fillingData
                            && (strategyTask as ExerciseTargetComponentTask).isDoingRandomExplorationTask)
                    )
        ) {
            windowRandomExplorationBudgetUsed[currentAppState.window] =
                windowRandomExplorationBudgetUsed[currentAppState.window]!! + 1
        }
    }

    var leftTargetRandomTry = 0
    private fun resetStrategyTask(currentState: State<*>) {
        /*if (strategyTask != null && strategyTask is GoToAnotherWindowTask) {
            strategyTask!!.isTaskEnd(currentState)
        }*/
        if (strategyTask != null)
            strategyTask!!.reset()
        strategyTask = null
        phaseState = PhaseState.P1_INITIAL
        episodeCountDown = 5
        log.info("Reset the current task.")
    }

    private fun chooseTask_P1(eContext: ExplorationContext<*, *, *>, currentState: State<*>) {
        log.debug("Choosing Task")
        val fillDataTask = PrepareContextTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val exerciseTargetComponentTask =
            ExerciseTargetComponentTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val goToTargetNodeTask =
            GoToTargetWindowTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val goToAnotherNode = GoToAnotherWindowTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val randomExplorationTask =
            RandomExplorationTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val openNavigationBarTask =
            OpenNavigationBarTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val currentState = eContext.getCurrentState()
        val currentAppState = atuaMF.getAbstractState(currentState)!!

        if (strategyTask == null) {
            setRandomExploration(randomExplorationTask,currentState)
            phaseState = PhaseState.P4_RANDOM_EXPLORATION
        } else {
            if (phaseState == PhaseState.P4_RANDOM_EXPLORATION) {
                if (!strategyTask!!.isTaskEnd(currentState)) {
                    return
                }
                if (targetWindowTryCount.isEmpty()) {
                    setRandomExploration(randomExplorationTask, currentState)
                    return
                }
                if (targetedRandomExploration(
                        currentState,
                        currentAppState,
                        randomExplorationTask,
                        goToTargetNodeTask
                    )
                ) return
                setRandomExploration(randomExplorationTask, currentState)
                return
            }
            if (phaseState == PhaseState.P4_GO_TO_TARGET_WINDOW) {
                if (!strategyTask!!.isTaskEnd(currentState)) {
                    return
                }
                if (currentAppState.window == targetWindow) {
                    setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                    phaseState = PhaseState.P4_TARGET_RANDOM_EXPLORATION
                    targetWindowTryCount[targetWindow!!] = targetWindowTryCount[targetWindow!!]!! + 1
                    return
                }
                if (strategy == 2) {
                    if (leftTargetRandomTry>0 && targetedRandomExploration(
                            currentState,
                            currentAppState,
                            randomExplorationTask,
                            goToTargetNodeTask
                        )
                    ) return
                }
                setRandomExploration(randomExplorationTask, currentState)
                phaseState = PhaseState.P4_RANDOM_EXPLORATION
                return
            }
            if (phaseState == PhaseState.P4_TARGET_RANDOM_EXPLORATION) {
                if (!strategyTask!!.isTaskEnd(currentState)) {
                    return
                }
                if (strategy == 1) {
                    setRandomExploration(randomExplorationTask, currentState)
                    phaseState = PhaseState.P4_RANDOM_EXPLORATION
                } else {
                    if (leftTargetRandomTry>0 && targetedRandomExploration(
                            currentState,
                            currentAppState,
                            randomExplorationTask,
                            goToTargetNodeTask))
                        return
                    setRandomExploration(randomExplorationTask,currentState)
                    phaseState = PhaseState.P4_RANDOM_EXPLORATION
                    return
                }
                return
            }
        }
        setRandomExploration(randomExplorationTask,currentState)
        phaseState = PhaseState.P4_RANDOM_EXPLORATION
        return

    }

    private fun targetedRandomExploration(
        currentState: State<*>,
        currentAppState: AbstractState,
        randomExplorationTask: RandomExplorationTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ): Boolean {
        val targetWindowCountTmp = HashMap<Window, Int>()
        targetWindowCountTmp.putAll(targetWindowTryCount)

        while (targetWindowCountTmp.isNotEmpty()) {
            val minTry = targetWindowCountTmp.minByOrNull { it.value }!!.value
            val leastTryWindows = targetWindowCountTmp.filter { it.value == minTry }
            for (window in leastTryWindows.keys) {
                targetWindow = window
                if (currentAppState.window == targetWindow) {
                    setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                    targetWindowTryCount[window] = targetWindowTryCount[window]!! + 1
                    phaseState = PhaseState.P4_TARGET_RANDOM_EXPLORATION
                    if (leftTargetRandomTry <= 0)
                        leftTargetRandomTry = targetWindowTryCount.size
                    else
                        leftTargetRandomTry -= 1
                    return true
                }
            }
            for (window in leastTryWindows.keys) {
                targetWindow = window
                if (goToTargetNodeTask.isAvailable(
                        currentState = currentState,
                        destWindow = targetWindow,
                        isWindowAsTarget = true,
                        includeResetApp = false,
                        isExploration = false,
                        includePressback = true,
                        maxCost = 25.0
                    )
                ) {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    phaseState = PhaseState.P4_GO_TO_TARGET_WINDOW
                    if (leftTargetRandomTry <= 0)
                        leftTargetRandomTry = targetWindowTryCount.size
                    else
                        leftTargetRandomTry -= 1
                    return true
                }
            }
            leastTryWindows.keys.forEach {
                targetWindowCountTmp.remove(it)
            }
        }
        return false
    }


    private fun exploreApp(
        currentState: State<*>,
        goToAnotherWindow: GoToAnotherWindowTask,
        randomExplorationTask: RandomExplorationTask
    ): Boolean {
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        if (strategyTask != goToAnotherWindow && goToAnotherWindow.isAvailable(
                currentState = currentState,
                destWindow = null,
                includeResetApp = false,
                isExploration = true,
                includePressback = true,
                isWindowAsTarget = false
            )
        ) {
            setGoToExploreState(goToAnotherWindow, currentState)
            return true
        }
        if (strategyTask is GoToAnotherWindowTask) {
            if ((strategyTask as GoToAnotherWindowTask).includeResetAction) {
                if (goToAnotherWindow.isAvailable(
                        currentState = currentState,
                        destWindow = null,
                        includeResetApp = false,
                        isExploration = true,
                        includePressback = true,
                        isWindowAsTarget = true
                    )
                ) {
                    setGoToExploreState(goToAnotherWindow, currentState)
                    return true
                }
                if (hasBudgetLeft(currentAppState.window)) {
                    setRandomExploration(randomExplorationTask, currentState, false, true)
                    return true
                } else {
                    return false
                }
            }
        }
        if (goToAnotherWindow.isAvailable(
                currentState = currentState,
                destWindow = null,
                includeResetApp = true,
                isExploration = true,
                includePressback = true,
                isWindowAsTarget = false
            )
        ) {
            setGoToExploreState(goToAnotherWindow, currentState)
            return true
        }
        log.info("No available abstract states to explore.")
        return false
    }


    private fun randomExplorationInSpecialWindows(
        currentAppState: AbstractState,
        randomExplorationTask: RandomExplorationTask,
        currentState: State<*>
    ): Boolean {
        if (currentAppState.isRequireRandomExploration()) {
            setRandomExploration(randomExplorationTask, currentState, false, false)
            return true
        }
        if (Helper.isOptionsMenuLayout(currentState)) {
            setRandomExploration(randomExplorationTask, currentState, false, false)
            return true
        }

        return false
    }









    private fun goToUnexploitedAbstractStateOrRandomlyExplore(
        currentAppState: AbstractState,
        goToAnotherNode: GoToAnotherWindowTask,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask
    ): Boolean {
        if (hasBudgetLeft(currentAppState.window)
            || currentAppState.getUnExercisedActions(currentState, atuaMF)
                .isNotEmpty()
        ) {
            setRandomExploration(randomExplorationTask, currentState)
            return true
        }
        return false
    }


    private fun exerciseTargetIfAvailable(
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        currentState: State<*>
    ): Boolean {
        if (exerciseTargetComponentTask.isAvailable(currentState)) {
            setExerciseTarget(exerciseTargetComponentTask, currentState)
            return true
        }
        return false
    }

    private fun continueRandomExplorationIfIsFillingData(randomExplorationTask: RandomExplorationTask): Boolean {
        if (randomExplorationTask.fillingData || randomExplorationTask.goToLockedWindowTask != null || randomExplorationTask.attemptCount == 0) {
            // if random can be still run, keep running
            log.info("Continue ${strategyTask!!}")
            return true
        }
        return false
    }

    private fun continueOrEndCurrentTask(currentState: State<*>): Boolean {
        if (!strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue ${strategyTask!!}")
            return true
        }
        return false
    }


    private fun setGoToExploreState(goToAnotherNode: GoToAnotherWindowTask, currentState: State<*>) {
        strategyTask = goToAnotherNode.also {
            it.initialize(currentState)
            it.retryTimes = 0
        }
        log.info("Explore App.")
        phaseState = PhaseState.P1_GO_TO_EXPLORE_STATE
    }

    private fun setGoToTarget(
        goToTargetNodeTask: GoToTargetWindowTask,
        currentState: State<*>,
        noRetry: Boolean = false
    ) {
        strategyTask = goToTargetNodeTask.also {
            it.initialize(currentState)
            it.retryTimes = 0
            it.saveBudget = noRetry
        }
        log.info("Go to target window: ${targetWindow.toString()}")
        phaseState = PhaseState.P1_GO_TO_TARGET_NODE
    }



    override fun getPathsToExploreStates(
        currentState: State<*>,
        pathType: PathFindingHelper.PathType,
        maxCost: Double,
        pathConstraints: Map<PathConstraint, Boolean>
    ): List<TransitionPath> {
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        if (currentAbstractState == null)
            return transitionPaths
        val goalByAbstractState = HashMap<AbstractState, List<Goal>>()
        val runtimeAbstractStates = ArrayList(getUnexhaustedExploredAbstractState())
        runtimeAbstractStates.groupBy { it.window }.forEach { window, appStates ->
            var virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window)
            if (virtualAbstractState == null) {
                virtualAbstractState = AbstractStateManager.INSTANCE.createVirtualAbstractState(
                    window,
                    appStates.first().activity,
                    appStates.first().isHomeScreen
                )
            }
            val toExploreInputs = ArrayList<Goal>()
            appStates.forEach { appState ->
                appState.getUnExercisedActions(null, atuaMF).filter { action ->
                    !action.isCheckableOrTextInput(appState)
                            && appState.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                            && !ProbabilityBasedPathFinder.disableAbstractActions.contains(action)
                            && ProbabilityBasedPathFinder.disableInputs.intersect(
                        appState.getInputsByAbstractAction(
                            action
                        )
                    ).isEmpty()
                }.forEach { action ->
                    toExploreInputs.add(Goal(input = null, abstractAction = action))
                }
            }
            goalByAbstractState.put(virtualAbstractState, toExploreInputs.distinct())
        }
        if (goalByAbstractState.values.flatten().isEmpty()) {
            return emptyList()
        }
        goalByAbstractState.entries.removeIf { it.value.isEmpty() }
        val abstratStateCandidates = goalByAbstractState.keys
        val stateByActionCount = HashMap<AbstractState, Double>()
        abstratStateCandidates.forEach {
            val weight = goalByAbstractState[it]!!.size
            if (weight > 0.0) {
                stateByActionCount.put(it, 1.0)
            }
        }
        getPathToStatesBasedOnPathType(
            pathType,
            transitionPaths,
            stateByActionCount,
            currentAbstractState,
            currentState,
            true,
            windowAsTarget = false,
            goalByAbstractState = goalByAbstractState,
            maxCost = maxCost,
            abandonedAppStates = emptyList(),
            pathConstraints = pathConstraints
        )
        if (transitionPaths.isEmpty()) {
            goalByAbstractState.clear()
            val unexhaustedTestedWindows =
                runtimeAbstractStates.map { it.window }.distinct()
            unexhaustedTestedWindows.forEach { window ->
                val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window)
                if (virtualAbstractState != null) {
                    goalByAbstractState.put(virtualAbstractState, emptyList())
                }
            }
            val stateByActionCount = HashMap<AbstractState, Double>()
            goalByAbstractState.forEach {
                stateByActionCount.put(it.key, 1.0)
            }
            getPathToStatesBasedOnPathType(
                pathType,
                transitionPaths,
                stateByActionCount,
                currentAbstractState,
                currentState,
                true,
                windowAsTarget = true,
                goalByAbstractState = goalByAbstractState,
                maxCost = maxCost,
                abandonedAppStates = emptyList(),
                pathConstraints = pathConstraints
            )
        }
        return transitionPaths
    }

    override fun getPathsToTargetWindows(
        currentState: State<*>,
        pathType: PathFindingHelper.PathType,
        maxCost: Double,
        pathConstraints: Map<PathConstraint, Boolean>
    ): List<TransitionPath> {
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        if (currentAbstractState == null)
            return transitionPaths
        val stateScores: HashMap<AbstractState, Double> = HashMap<AbstractState, Double>()
        val goalByAbstractState: HashMap<AbstractState, List<Goal>> = HashMap()
        val targetInputs = ArrayList<Input>()


        val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(targetWindow!!)!!
        stateScores.put(virtualAbstractState, 1.0)
        goalByAbstractState.put(
            virtualAbstractState,
            targetInputs.distinct().map { Goal(input = it, abstractAction = null) })
        var windowAsTarget = false
        if (targetInputs.isEmpty()) {
            return getPathsToWindowToExplore(currentState, targetWindow!!, pathType, true, maxCost, pathConstraints)
        }
        getPathToStatesBasedOnPathType(
            pathType,
            transitionPaths,
            stateScores,
            currentAbstractState,
            currentState,
            true,
            windowAsTarget,
            goalByAbstractState,
            maxCost,
            abandonedAppStates = emptyList(),
            pathConstraints = pathConstraints
        )
        if (transitionPaths.isEmpty()) {
            return getPathsToWindowToExplore(currentState, targetWindow!!, pathType, true, maxCost, pathConstraints)
        }
        /*if (transitionPaths.isEmpty() && currentAbstractState.window != targetWindow) {
            log.debug("No path from $currentAbstractState to $targetWindow!!")
            val virtualAbstractStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it.window == targetWindow }
            stateScores.clear()
            stateScores.putAll(virtualAbstractStates.associateWith { 1.0 })
            getPathToStatesBasedOnPathType(pathType, transitionPaths, stateScores, currentAbstractState, currentState,true,true)
        }*/
        return transitionPaths
    }

    override fun getCurrentTargetInputs(currentState: State<*>): Set<Input> {
        val result = ArrayList<Input>()
        val availableTargetInputsWithActions = HashMap<Input, List<AbstractAction>>()

        val abstractState = atuaMF.getAbstractState(currentState)!!

        return result.distinct().toSet()
        /*val currentAppState = regressionTestingMF.getAbstractState(currentState)!!
        val targetActions = currentAppState.targetActions
        val untriggerTargetActions = targetActions.filter {
            if (it.widgetGroup==null) {
                currentAppState.actionCount[it] == 0
            } else {
                it.widgetGroup.actionCount[it] == 0
            }
        }
        return untriggerTargetActions*/
    }

    var clickedOnKeyboard = false
    var needResetApp = true
    var actionCountSinceSelectTarget: Int = 0
    private fun isCountAction(chosenAction: ExplorationAction) =
        !chosenAction.isFetch()
                && chosenAction.name != "CloseKeyboard"
                && !chosenAction.name.isLaunchApp()
                && (chosenAction.name != "Swipe" || !chosenAction.hasWidgetTarget)
                && !(
                chosenAction.hasWidgetTarget
                        && ExplorationTrace.widgetTargets.any { it.isKeyboard }
                )



    var numOfContinousTry = 0

    private fun shouldChangeTargetWindow() = atuaMF.updateMethodCovFromLastChangeCount > 25 * scaleFactor

    private fun setFullyRandomExplorationInTargetWindow(
        randomExplorationTask: RandomExplorationTask,
        currentState: State<*>,
        currentAppState: AbstractState
    ) {
        setFullyRandomExploration(randomExplorationTask, currentState, currentAppState)
        randomExplorationTask.lockTargetWindow(currentAppState.window)
        phaseState = PhaseState.P1_RANDOM_IN_EXERCISE_TARGET_NODE
    }

    private fun setExerciseTarget(exerciseTargetComponentTask: ExerciseTargetComponentTask, currentState: State<*>) {
        strategyTask = exerciseTargetComponentTask.also {
            it.initialize(currentState)
        }
        log.info("This window has target events.")
        log.info("Exercise target component task chosen")
        phaseState = PhaseState.P1_EXERCISE_TARGET_NODE
    }

    private fun hasBudgetLeft(window: Window): Boolean {
        return true
    }

    private fun getAbstractStateExecutedActionsCount(abstractState: AbstractState) =
        abstractState.getActionCountMap().filter { it.key.isWidgetAction() }.map { it.value }.sum()

    private fun isLoginWindow(currentAppState: AbstractState): Boolean {
        val activity = currentAppState.window.classType.toLowerCase()
        return activity.contains("login") || activity.contains("signin")
    }

    private fun setRandomExploration(
        randomExplorationTask: RandomExplorationTask,
        currentState: State<*>,
        stopWhenTestPathIdentified: Boolean = false,
        lockWindow: Boolean = false
    ) {
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.stopWhenHavingTestPath = stopWhenTestPathIdentified
            /*val randomBudgetLeft = if (windowRandomExplorationBudget.containsKey(currentAbstractState.window))
                windowRandomExplorationBudget[currentAbstractState.window]!! - windowRandomExplorationBudgetUsed[currentAbstractState.window]!!
            else
                (1 * scaleFactor).toInt()*/
            val randomBudgetLeft =
                (50 * scaleFactor).toInt()
            val minRandomBudget = (2 * scaleFactor).toInt()
            if (randomBudgetLeft <= minRandomBudget) {
                it.setMaxiumAttempt(minRandomBudget)
            } else {
                it.setMaxiumAttempt(randomBudgetLeft)
            }
            if (lockWindow && currentAbstractState.belongToAUT())
                it.lockTargetWindow(currentAbstractState.window)
        }
        log.info("Random exploration")

    }

    private fun setFullyRandomExploration(
        randomExplorationTask: RandomExplorationTask,
        currentState: State<*>,
        currentAbstractState: AbstractState
    ) {
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.isPureRandom = true
            it.stopWhenHavingTestPath = false
            val randomBudgetLeft =
                windowRandomExplorationBudget[currentAbstractState.window]!! - windowRandomExplorationBudgetUsed[currentAbstractState.window]!!
            val minRandomBudget = (5 * scaleFactor).toInt()
            if (randomBudgetLeft <= minRandomBudget) {
                it.setMaxiumAttempt(minRandomBudget)
            } else {
                it.setMaxiumAttempt(randomBudgetLeft)
            }
        }
        log.info("Cannot find path the target node.")
        log.info("Fully Random exploration")
        phaseState = PhaseState.P1_RANDOM_EXPLORATION
/*        if (Random.nextBoolean()) {
            needResetApp = true
        }*/
    }

    private fun setRandomExplorationInTargetWindow(
        randomExplorationTask: RandomExplorationTask,
        currentState: State<*>
    ) {
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.lockTargetWindow(targetWindow!!)
            val randomBudgetLeft =
                (10 * scaleFactor).toInt()
            val minRandomBudget = (10 * scaleFactor).toInt()
            if (randomBudgetLeft <= minRandomBudget) {
                it.setMaxiumAttempt(minRandomBudget)
            } else {
                it.setMaxiumAttempt(randomBudgetLeft)
            }
        }
        log.info("Random exploration in current window")
    }





    companion object {

        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(RandomExplorationStrategy::class.java) }


    }
}