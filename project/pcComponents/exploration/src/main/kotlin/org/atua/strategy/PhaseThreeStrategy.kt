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

import org.calm.modelReuse.ModelHistoryInformation
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
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.EventType
import org.atua.modelFeatures.helper.ProbabilityDistribution
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.TransitionPath
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Launcher
import org.atua.modelFeatures.helper.Goal
import org.atua.modelFeatures.helper.PathConstraint
import org.atua.modelFeatures.helper.PathFindingHelper
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.atua.modelFeatures.informationRetrieval.InformationRetrieval
import org.atua.strategy.task.ExerciseTargetComponentTask
import org.atua.strategy.task.GoToAnotherWindowTask
import org.atua.strategy.task.GoToTargetWindowTask
import org.atua.strategy.task.RandomExplorationTask
import org.droidmate.exploration.modelFeatures.reporter.StatementCoverageMF

import org.droidmate.explorationModel.interaction.State
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.log2

class PhaseThreeStrategy(
    atuaTestingStrategy: ATUATestingStrategy,
    budgetScale: Double,
    delay: Long,
    useCoordinateClicks: Boolean
): AbstractPhaseStrategy(
        atuaTestingStrategy = atuaTestingStrategy,
        scaleFactor = budgetScale,
        delay = delay,
        useCoordinateClicks = useCoordinateClicks,
        useVirtualAbstractState = false
) {


    val statementMF: StatementCoverageMF
    var remainPhaseStateCount: Int = 0

    var targetWindow: Window? = null
    var allTargetInputs: HashMap<Input, Int> = HashMap()
    var targetEvent: Input? = null
    var targetWindowsCount: HashMap<Window, Int> = HashMap()

    val abstractStatesScores = HashMap<AbstractState, Double>()
    val abstractStateProbability = HashMap<Window, ArrayList<Pair<AbstractState, Double>>>()

    val modifiedMethodWeights = HashMap<String, Double>()
    val modifiedMethodMissingStatements = HashMap<String, HashSet<String>>()
    val appStateModifiedMethodMap = HashMap<AbstractState, HashSet<String>>()
    val windowModifiedMethodMap = HashMap<Window, HashSet<String>>()
    val modifiedMethodTriggerCount = HashMap<String, Int>()
    val windowScores = HashMap<Window, Double> ()
    val windowsProbability = HashMap<Window, Double> ()

    var randomBudgetLeft: Int = 0
    var needResetApp = false
    init {
        phaseState = PhaseState.P3_INITIAL
        atuaMF = atuaTestingStrategy.eContext.getOrCreateWatcher()
        statementMF = atuaTestingStrategy.eContext.getOrCreateWatcher()
        atuaMF.updateMethodCovFromLastChangeCount = 0
        atuaMF.notFullyExercisedTargetInputs.forEach {
            if (it.witnessed)
                allTargetInputs.put(it,0)
        }
        val seenWindows = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it !is VirtualAbstractState
                && it.guiStates.isNotEmpty()
                && it.ignored == false }.map { it.window }.distinct()
        atuaMF.modifiedMethodsByWindow.keys.filter { it !is Launcher && seenWindows.contains(it) }.forEach { window ->
            val abstractStates = AbstractStateManager.INSTANCE.getPotentialAbstractStates().filter { it.window == window }
            if (abstractStates.isNotEmpty()) {
                /*targetWindowsCount.put(window, 0)*/
                val targetInputs = atuaMF.notFullyExercisedTargetInputs.filter {it.sourceWindow == window}
                val realisticInputs = abstractStates.map { it.getAvailableInputs() }.flatten().distinct()
                val realisticTargetInputs = targetInputs.intersect(realisticInputs)
                if (realisticTargetInputs.isNotEmpty()) {
                    targetWindowsCount.put(window, 0)
                }
            }
        }
        computeWindowsCorrelation()
    }
    override fun isTargetWindow(window: Window): Boolean {
        if (window == targetWindow)
            return true
        return false
    }

    override fun isTargetState(currentState: State<*>): Boolean {
        return true
    }

    override fun registerTriggeredInputs(chosenAbstractAction: AbstractAction, currentState: State<*>) {
        val abstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)!!
        //val abstractInteractions = regressionTestingMF.abstractTransitionGraph.edges(abstractState).filter { it.label.abstractAction.equals(abstractAction) }.map { it.label }

        val inputs = abstractState.getInputsByAbstractAction(chosenAbstractAction)
        inputs.forEach {
            if (it == targetEvent) {
                targetEvent = null
            }
            allTargetInputs.putIfAbsent(it,0)
            allTargetInputs[it] = allTargetInputs[it]!! + 1
        }

    }

    override fun hasNextAction(currentState: State<*>): Boolean {

        if (atuaMF.lastUpdatedStatementCoverage == 1.0) {
            return false
        }
        val seenWindows = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it !is VirtualAbstractState
                && it.guiStates.isNotEmpty()
                && it.ignored == false }.map { it.window }.distinct()
        atuaMF.modifiedMethodsByWindow.keys.filter { it !is Launcher
                && !targetWindowsCount.containsKey(it) && seenWindows.contains(it)}.forEach {window ->
            val abstractStates = AbstractStateManager.INSTANCE.getPotentialAbstractStates().filter { it.window == window }
            if (abstractStates.isNotEmpty()) {
                val targetInputs = atuaMF.notFullyExercisedTargetInputs.filter {it.sourceWindow == window}
                val realisticInputs = abstractStates.map { it.getAvailableInputs() }.flatten().distinct()
                val realisticTargetInputs = targetInputs.intersect(realisticInputs)
                if (realisticTargetInputs.isNotEmpty()) {
                    targetWindowsCount.put(window, 0)
                }
            }
        }
        targetWindowsCount.entries.removeIf {
            !atuaMF.modifiedMethodsByWindow.containsKey(it.key)
        }
        atuaMF.notFullyExercisedTargetInputs.forEach {
            if (it.witnessed)
                allTargetInputs.putIfAbsent(it,0)
        }
        return true
    }

    override fun nextAction(eContext: ExplorationContext<*, *, *>): ExplorationAction {
        var chosenAction:ExplorationAction?

        val currentState = eContext.getCurrentState()
        if (phaseState == PhaseState.P3_INITIAL
                && needReset(currentState)) {
            return eContext.resetApp()
        }
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        if (targetWindow == null) {
            selectTargetWindow(currentState, 0, 0)
            selectTargetStaticEvent(currentState)
        }
        log.info("Current abstract state: $currentAppState")
        log.info("Current window: ${currentAppState.window}")
        log.debug("Target: $targetWindow")
        log.debug("To test Window transition: $targetEvent")
        log.debug("Related window: $relatedWindow")
        chooseTask(eContext, currentState)
        if (needResetApp) {
            needResetApp = false
            return eContext.resetApp()
        }
      /*  if (strategyTask is RandomExplorationTask) {
            if (!(strategyTask as RandomExplorationTask).fillingData)   {
              budgetLeft--
            }
        } else if (strategyTask is ExerciseTargetComponentTask) {
            if (!(strategyTask as ExerciseTargetComponentTask).fillingData) {
                budgetLeft--
            }
        }
        else {
            budgetLeft--
        }*/

        if (strategyTask != null) {
            chosenAction = strategyTask!!.chooseAction(currentState)
        } else {
            log.debug("No task seleted. It might be a bug.")
            chosenAction = eContext.resetApp()
        }
        if (chosenAction == null)
            return ExplorationAction.pressBack()
        if (strategyTask is RandomExplorationTask
                && (strategyTask as RandomExplorationTask).fillingData != true
                && (strategyTask as RandomExplorationTask).goToLockedWindowTask == null
                && (relatedWindow == null || currentAppState.window == relatedWindow)
                && phaseState == PhaseState.P3_EXPLORATION_IN_RELATED_WINDOW
                && isCountAction(chosenAction)) {
            randomBudgetLeft--
        }
        return chosenAction
    }

    private fun isCountAction(chosenAction: ExplorationAction) =
            !chosenAction.isFetch()
                    && chosenAction.name!="CloseKeyboard"
                    && !chosenAction.name.isLaunchApp()
                    && chosenAction !is Swipe

    override fun getPathsToExploreStates(currentState: State<*>,
                                         pathType: PathFindingHelper.PathType,
                                         maxCost: Double,
                                        pathConstraints: Map<PathConstraint,Boolean>): List<TransitionPath> {
        if (targetWindow==null)
            return emptyList()
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAppState = atuaMF.getAbstractState(currentState)
        if (currentAppState==null)
            return transitionPaths
        val includeReset = pathConstraints[PathConstraint.INCLUDE_RESET]!!
        val runtimeAbstractStates = getUnexhaustedExploredAbstractState(includeReset)
        val goalByAbstractState = HashMap<AbstractState, List<Goal>>()
        runtimeAbstractStates.groupBy { it.window }.forEach { window, appStates ->
            var virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window)
            if (virtualAbstractState == null) {
                virtualAbstractState = AbstractStateManager.INSTANCE.createVirtualAbstractState(window,appStates.first().activity,appStates.first().isHomeScreen)
            }
            val toExploreInputs = ArrayList<Goal>()
            appStates.forEach { appState ->
                val meaningfulAbstractActions = appState.getUnExercisedActions(currentState, atuaMF)
                    .filter { action->
                        !action.isCheckableOrTextInput(appState)
                                && appState.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                                && !ProbabilityBasedPathFinder.disableAbstractActions1.contains(action)
                                && ProbabilityBasedPathFinder.disableInputs1.intersect(appState.getInputsByAbstractAction(action)).isEmpty()
                                && (includeReset || (
                                !ProbabilityBasedPathFinder.disableAbstractActions2.contains(action)
                                        && ProbabilityBasedPathFinder.disableInputs2.intersect(appState.getInputsByAbstractAction(action)).isEmpty()
                                        ))

                    }
                meaningfulAbstractActions.forEach { action ->
                    toExploreInputs.add(Goal(input = null,abstractAction = action))
                }
            }
            goalByAbstractState.put(virtualAbstractState, toExploreInputs.distinct())
        }
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
            currentAppState,
            currentState,
            true,
            goalByAbstractState = goalByAbstractState,
            maxCost = maxCost,
            abandonedAppStates = emptyList(),
            pathConstraints = pathConstraints
        )
        return transitionPaths
    }

    override fun getPathsToTargetWindows(currentState: State<*>,
                                         pathType: PathFindingHelper.PathType,
                                         maxCost: Double,
                                         pathConstraints: Map<PathConstraint, Boolean>): List<TransitionPath> {
        val currentAbState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        val prevAbstractState = AbstractStateManager.INSTANCE.getAbstractState(atuaMF.appPrevState!!)
        if (currentAbState==null)
            return emptyList()

        if (!abstractStateProbability.containsKey(targetWindow))
            return emptyList()
        val targetScores = HashMap<AbstractState,Double>()

/*        abstractStateProbability[targetWindow]!!.filter {
            it.first.inputMappings.filter { it.value.contains(targetEvent) }.isNotEmpty()
        }.forEach {
            targetScores.put(it.first,it.second)
        }
        if (targetScores.isEmpty()) {
            abstractStatceProbability[targetWindow]!!.forEach {
                targetScores.put(it.first,it.second)
            }
        }*/
        val goalByAbstractState = HashMap<AbstractState, List<Goal>>()
        val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(targetWindow!!)!!

        targetScores.put(virtualAbstractState,1.0)

        val newPathConstraints = HashMap(pathConstraints)
        newPathConstraints.put(PathConstraint.INCLUDE_LAUNCH,false)
        newPathConstraints.put(PathConstraint.INCLUDE_RESET,false)
        val transitionPaths = ArrayList<TransitionPath>()
        if (targetEvent != null) {
            goalByAbstractState.put(virtualAbstractState, listOf(targetEvent!!).map { Goal(input = it,abstractAction = null) })
            getPathToStatesBasedOnPathType(
                pathType,
                transitionPaths,
                targetScores,
                currentAbState,
                currentState,
                false,
                false,
                goalByAbstractState,
                maxCost,
                emptyList(),
                newPathConstraints
            )
        } else {
            getPathToStatesBasedOnPathType(
                pathType,
                transitionPaths,
                targetScores,
                currentAbState,
                currentState,
                false,
                true,
                goalByAbstractState,
                maxCost,
                emptyList(),
                newPathConstraints
            )
        }
        return transitionPaths
    }

    override fun getCurrentTargetInputs(currentState: State<*>): Set<Goal> {
        val targetEvents = ArrayList<Goal>()
        targetEvents.clear()

        val abstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        if (abstractState!!.window == targetWindow )
        {
            if (targetEvent!=null) {
                val abstractActions = atuaMF.validateEvent(targetEvent!!, currentState)
                if (abstractActions.isNotEmpty()) {
                    targetEvents.add(Goal(input=targetEvent,abstractAction = null))
                }
            }/* else {
                val availableEvents = abstractState.getAvailableInputs()
                val windowTargetInputs = atuaMF.notFullyExercisedTargetInputs.filter {
                    it.sourceWindow == targetWindow!!
                }
                val inputScore = HashMap<Input,Double>()
                windowTargetInputs.filter { availableEvents.contains(it) }.forEach { input ->
                    val usefulness = ModelHistoryInformation.INSTANCE.inputUsefulness[input]
                    val score = if (usefulness!=null) {
                        (usefulness.second*1.0/(usefulness.first+1))*(1.0/(usefulness.first+1))
                    } else {
                        1.0
                    }
                    inputScore.put(input,score)
                }
                if (inputScore.isNotEmpty()) {
                    val pb = ProbabilityDistribution<Input>(inputScore)
                    val selectedInput = pb.getRandomVariable()
                    val abstractActions = atuaMF.validateEvent(selectedInput, currentState)
                    if (abstractActions.isNotEmpty()) {
                        targetEvents.add(selectedInput)
                    }
                }
            }*/
        }
        return targetEvents.toSet()
    }

    var relatedWindow: Window? = null
    var exercisedRelatedWindow: Boolean = false
    fun chooseTask(eContext: ExplorationContext<*, *, *>, currentState: State<*>){
        log.debug("Choosing Task")
        val exerciseTargetComponentTask = ExerciseTargetComponentTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val goToTargetNodeTask = GoToTargetWindowTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val goToAnotherNode = GoToAnotherWindowTask.getInstance(atuaMF, atuaTestingStrategy, delay, useCoordinateClicks)
        val randomExplorationTask = RandomExplorationTask.getInstance(atuaMF, atuaTestingStrategy,delay, useCoordinateClicks)
        val currentState = eContext.getCurrentState()
        val currentAppState = atuaMF!!.getAbstractState(currentState)!!
        if (windowBudgetLeft < 0) {
            targetEvent = null
            if (targetEvent==null) {
                selectTargetWindow(currentState, 0, 0)
                selectTargetStaticEvent(currentState)
            }
            phaseState = PhaseState.P3_INITIAL
        }
        if (phaseState == PhaseState.P3_INITIAL) {
            nextActionOnInitial(currentAppState, randomExplorationTask, currentState, goToAnotherNode)
            return
        }
        if (phaseState == PhaseState.P3_GO_TO_RELATED_NODE) {
            nextActionOnGoToRelatedWindow(currentState, currentAppState, randomExplorationTask, goToAnotherNode)
            return
        }
        if (phaseState == PhaseState.P3_EXPLORATION_IN_RELATED_WINDOW) {
            nextActionOnRandomExplorationInRelatedWindow(randomExplorationTask, currentState, currentAppState, exerciseTargetComponentTask, goToAnotherNode, goToTargetNodeTask)
            return
        }
        if (phaseState == PhaseState.P3_GO_TO_TARGET_NODE) {
            nextActionOnGoToTargetWindow(currentAppState, exerciseTargetComponentTask, currentState, goToTargetNodeTask, randomExplorationTask, goToAnotherNode)
            return
        }
        if (phaseState == PhaseState.P3_EXERCISE_TARGET_NODE) {
            nextActionOnExerciseTargetWindow(currentState, currentAppState, randomExplorationTask, eContext)
            return
        }
    }

    private fun isRandomBudgetAvailable() = randomBudgetLeft > 0 || randomBudgetLeft == -1

    private fun nextActionOnInitial(currentAppState: AbstractState, randomExplorationTask: RandomExplorationTask, currentState: State<*>, goToAnotherNode: GoToAnotherWindowTask) {
        if (relatedWindow == null) {
            log.warn("Related Window is null.")
            setRandomExplorationBudget(currentState)
            setRandomExploration(randomExplorationTask, currentState)
            phaseState = PhaseState.P3_EXPLORATION_IN_RELATED_WINDOW
            return
        }
        if (currentAppState.window == relatedWindow) {
            setRandomExplorationBudget(currentState)
            setRandomExplorationInRelatedWindow(randomExplorationTask, currentState)
            return
        }
        phaseState = PhaseState.P3_GO_TO_RELATED_NODE
        if (goToAnotherNode.isAvailable(currentState = currentState,
                destWindow = relatedWindow!!,
                isWindowAsTarget = true,
                isExploration =  false,
                includePressback = true,
                includeResetApp =  false)) {
            setGoToRelatedWindow(goToAnotherNode, currentState)
            return
        }
        if (goToAnotherNode.isAvailable(currentState = currentState,
                destWindow = relatedWindow!!,
                isWindowAsTarget = true,
                isExploration =  false,
                includePressback = true,
                includeResetApp =  true)) {
            setGoToRelatedWindow(goToAnotherNode, currentState)
            return
        }
        if (currentAppState.getUnExercisedActions(atuaMF = atuaMF,currentState = currentState)
                .filter { it.isWidgetAction() && !it.isCheckableOrTextInput(currentAppState)}.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState)
        }
        if (goToAnotherNode.isAvailable(currentState)) {
            setGoToExploreState(goToAnotherNode, currentState)
            return
        }
        setRandomExploration(randomExplorationTask, currentState)
        return
    }

    private fun nextActionOnGoToRelatedWindow(currentState: State<*>, currentAppState: AbstractState, randomExplorationTask: RandomExplorationTask, goToAnotherNode: GoToAnotherWindowTask) {
        if (relatedWindow == null) {
            log.warn("Related Window is null.")
            setRandomExplorationBudget(currentState)
            setRandomExploration(randomExplorationTask, currentState)
            return
        }
        if (strategyTask is GoToAnotherWindowTask && !strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue ${strategyTask!!.javaClass.name}")
            return
        }
        if (currentAppState.window == relatedWindow!!) {
            setRandomExplorationBudget(currentState)
            setRandomExplorationInRelatedWindow(randomExplorationTask, currentState)
            return
        }
        if (strategyTask is GoToAnotherWindowTask) {
            if ((strategyTask!! as GoToAnotherWindowTask).includeResetAction == false) {
                if (goToAnotherNode.isAvailable(
                        currentState= currentState,
                        destWindow= relatedWindow!!,
                         isWindowAsTarget = true,
                        includePressback = true,
                        includeResetApp =  true,
                        isExploration = false)) {
                    setGoToRelatedWindow(goToAnotherNode, currentState)
                    return
                }
            }

        }
        if (strategyTask is RandomExplorationTask
                && (strategyTask as RandomExplorationTask).stopWhenHavingTestPath
                && !currentAppState.isRequireRandomExploration()) {
            if (goToAnotherNode.isAvailable(currentState, relatedWindow!!,true, true, true,false)) {
                setGoToRelatedWindow(goToAnotherNode, currentState)
                return
            }
        }
        if (!strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue ${strategyTask!!.javaClass.name}")
            return
        }
        if (strategyTask !is GoToAnotherWindowTask &&
            goToAnotherNode.isAvailable(currentState, relatedWindow!!,true, true, true,false)) {
            setGoToRelatedWindow(goToAnotherNode, currentState)
            return
        }
       /* if (currentAppState.getUnExercisedActions(atuaMF = atuaMF,currentState = currentState)
                .filter { it.isWidgetAction()
                        && !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore>0 }}.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState)
            phaseState = PhaseState.P3_GO_TO_RELATED_NODE
            return
        }*/
        selectTargetStaticEvent(currentState)
        //setRandomExploration(randomExplorationTask, currentState)
        phaseState = PhaseState.P3_INITIAL
        nextActionOnInitial(currentAppState, randomExplorationTask, currentState, goToAnotherNode)
        return
    }

    private fun nextActionOnRandomExplorationInRelatedWindow(randomExplorationTask: RandomExplorationTask, currentState: State<*>, currentAppState: AbstractState, exerciseTargetComponentTask: ExerciseTargetComponentTask, goToAnotherNode: GoToAnotherWindowTask, goToTargetNodeTask: GoToTargetWindowTask) {
        if (relatedWindow == null) {
            log.warn("Related Window is null.")
            if (isRandomBudgetAvailable()) {
                if (!strategyTask!!.isTaskEnd(currentState)) {
                    log.info("Continue ${strategyTask!!.javaClass.name}")
                    return
                }
                setRandomExploration(randomExplorationTask, currentState)
                return
            } else {
                if (currentAppState.window == targetWindow) {
                    if (exerciseTargetComponentTask.isAvailable(currentState)) {
                        setExerciseTarget(exerciseTargetComponentTask, currentState)
                        phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                        return
                    }
                    if (currentAppState.isRequireRandomExploration() || Helper.isOptionsMenuLayout(currentState) ) {
                        setRandomExploration(randomExplorationTask, currentState)
                        phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                        return
                    }
                    if (goToTargetNodeTask.isAvailable(currentState,
                            destWindow = targetWindow!!,
                            isWindowAsTarget = false,
                            includePressback =  true,
                            includeResetApp =  false,
                            isExploration =  false)) {
                        setGoToTarget(goToTargetNodeTask, currentState)
                        return
                    }
                    setRandomExploration(randomExplorationTask, currentState)
                    phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                    return
                } else {
                    if (goToTargetNodeTask.isAvailable(currentState,
                            destWindow = targetWindow!!,
                            isWindowAsTarget = false,
                            includePressback =  true,
                            includeResetApp =  false,
                            isExploration =  false))  {
                        setGoToTarget(goToTargetNodeTask, currentState)
                        return
                    }
                    setRandomExploration(randomExplorationTask, currentState)
                    phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                    return
                }
            }
        }
        if (isRandomBudgetAvailable()) {
            if (randomExplorationTask.fillingData || randomExplorationTask.attemptCount == 0) {
                // if random can be still run, keep running
                log.info("Continue filling data")
                return
            }
            if (currentAppState.window == targetWindow
                && targetWindow != relatedWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                    return
                }
            }
            if (!strategyTask!!.isTaskEnd(currentState)) {
                log.info("Continue ${strategyTask!!.javaClass.name}")
                return
            }
            if (currentAppState.isRequireRandomExploration() || Helper.isOptionsMenuLayout(currentState) ) {
                setRandomExploration(randomExplorationTask, currentState)
                randomExplorationTask.isPureRandom = true
                return
            }
            if (currentAppState.window == relatedWindow) {
                setRandomExplorationBudget(currentState)
                setRandomExplorationInRelatedWindow(randomExplorationTask, currentState)
                return
            }
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                    return
                }
                if (currentAppState.isRequireRandomExploration() || Helper.isOptionsMenuLayout(currentState) ) {
                    setRandomExploration(randomExplorationTask, currentState)
                    phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                    return
                }
                if (goToTargetNodeTask.isAvailable(currentState,
                        destWindow = targetWindow!!,
                        isWindowAsTarget = false,
                        includePressback =  true,
                        includeResetApp =  false,
                        isExploration =  false))  {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
            }
            if (goToAnotherNode.isAvailable(currentState = currentState,
                    destWindow = relatedWindow!!,
                    isWindowAsTarget = false,
                    isExploration =  false,
                    includePressback = true,
                    includeResetApp =  false))  {
                setGoToRelatedWindow(goToAnotherNode, currentState)
                return
            }
            if (goToAnotherNode.isAvailable(currentState = currentState,
                    destWindow = relatedWindow!!,
                    isWindowAsTarget = false,
                    isExploration =  false,
                    includePressback = true,
                    includeResetApp =  true))  {
                setGoToRelatedWindow(goToAnotherNode, currentState)
                return
            }
            setFullyRandomExploration(randomExplorationTask, currentState)
            phaseState = PhaseState.P3_EXPLORATION_IN_RELATED_WINDOW
            return
        } else {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                    return
                }
                if (currentAppState.isRequireRandomExploration() || Helper.isOptionsMenuLayout(currentState) ) {
                    setRandomExploration(randomExplorationTask, currentState)
                    phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                    return
                }
                if (goToTargetNodeTask.isAvailable(currentState,
                        destWindow = targetWindow!!,
                        isWindowAsTarget = false,
                        includePressback =  true,
                        includeResetApp =  false,
                        isExploration =  false))  {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)

                return
            } else {
                if (goToTargetNodeTask.isAvailable(currentState,
                        destWindow = targetWindow!!,
                        isWindowAsTarget = false,
                        includePressback =  true,
                        includeResetApp =  false,
                        isExploration =  false))  {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
                setRandomExploration(randomExplorationTask, currentState)
                phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                return
            }
        }
    }

    private fun nextActionOnGoToTargetWindow(currentAppState: AbstractState, exerciseTargetComponentTask: ExerciseTargetComponentTask, currentState: State<*>, goToTargetNodeTask: GoToTargetWindowTask, randomExplorationTask: RandomExplorationTask, goToAnotherNode: GoToAnotherWindowTask) {
        if (strategyTask is GoToAnotherWindowTask && !strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue ${strategyTask!!.javaClass.name}")
            return
        }
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetComponentTask.isAvailable(currentState)) {
                setExerciseTarget(exerciseTargetComponentTask, currentState)
                phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
                return
            }
            if (targetEvent == null) {
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            }
        }

        if (strategyTask is RandomExplorationTask
                && (strategyTask as RandomExplorationTask).stopWhenHavingTestPath
                && !currentAppState.isRequireRandomExploration()) {
            if (goToTargetNodeTask.isAvailable(currentState,
                    destWindow = targetWindow!!,
                    isWindowAsTarget = false,
                    includePressback =  true,
                    includeResetApp =  false,
                    isExploration =  false))  {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
        }
        if (!strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue ${strategyTask!!.javaClass.name}")
            return
        }

        targetEvent = null
        if (targetEvent==null) {
            selectTargetWindow(currentState, 0, 0)
            selectTargetStaticEvent(currentState)
        }
        phaseState = PhaseState.P3_INITIAL
        nextActionOnInitial(currentAppState, randomExplorationTask, currentState, goToAnotherNode)
        return
    }

    private fun nextActionOnExerciseTargetWindow(currentState: State<*>, currentAppState: AbstractState, randomExplorationTask: RandomExplorationTask, eContext: ExplorationContext<*, *, *>) {
        if (!strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue ${strategyTask!!.javaClass.name}")
            return
        }
        if (currentAppState.isRequireRandomExploration() || Helper.isOptionsMenuLayout(currentState) ) {
            setRandomExploration(randomExplorationTask, currentState)
            phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
            return
        }
        phaseState = PhaseState.P3_INITIAL
        selectTargetStaticEvent(currentState)
        chooseTask(eContext, currentState)
        return
    }

    @Suppress
    private fun nextActionOnGoToExploreState(currentState: State<*>, currentAppState: AbstractState, randomExplorationTask: RandomExplorationTask, exerciseTargetComponentTask: ExerciseTargetComponentTask, goToAnotherNode: GoToAnotherWindowTask, goToTargetNodeTask: GoToTargetWindowTask) {
        if (!strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue ${strategyTask!!.javaClass.name}")
            return
        }
        if (isRandomBudgetAvailable()) {
            if (currentAppState.window == relatedWindow) {
                setRandomExplorationBudget(currentState)
                setRandomExplorationInRelatedWindow(randomExplorationTask, currentState)
                return
            }
            if (goToAnotherNode.isAvailable(currentState, relatedWindow!!, true, true, false,false)) {
                setGoToRelatedWindow(goToAnotherNode, currentState)
                return
            }
            setFullyRandomExploration(randomExplorationTask, currentState)
        } else {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                if (goToTargetNodeTask.isAvailable(currentState,
                        destWindow = targetWindow!!,
                        isWindowAsTarget = false,
                        includePressback =  true,
                        includeResetApp =  false,
                        isExploration =  false))  {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
                phaseState = PhaseState.P3_INITIAL
                selectTargetStaticEvent(currentState)
                setRandomExploration(randomExplorationTask, currentState)
                return
            }

        }
    }

    @Suppress
    private fun nextActionOnRandomExploration(randomExplorationTask: RandomExplorationTask, currentAppState: AbstractState, currentState: State<*>, goToAnotherNode: GoToAnotherWindowTask, exerciseTargetComponentTask: ExerciseTargetComponentTask, goToTargetNodeTask: GoToTargetWindowTask) {

        if (!strategyTask!!.isTaskEnd(currentState)) {
            log.info("Continue ${strategyTask!!.javaClass.name}")
            return
        }
        if (isRandomBudgetAvailable()) {
            if (currentAppState.window == relatedWindow) {
                setRandomExplorationBudget(currentState)
                setRandomExplorationInRelatedWindow(randomExplorationTask, currentState)
                return
            }
            if (goToAnotherNode.isAvailable(currentState, relatedWindow!!,true, true, false,false)) {
                setGoToRelatedWindow(goToAnotherNode, currentState)
                return
            }
            setFullyRandomExploration(randomExplorationTask, currentState)
            return
        } else {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
            }
            if (goToTargetNodeTask.isAvailable(currentState,
                    destWindow = targetWindow!!,
                    isWindowAsTarget = false,
                    includePressback =  true,
                    includeResetApp =  false,
                    isExploration =  false)) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            phaseState = PhaseState.P3_INITIAL
            selectTargetStaticEvent(currentState)
            setRandomExploration(randomExplorationTask, currentState)
            return
        }
    }

    private fun setGoToExploreState(goToAnotherNode: GoToAnotherWindowTask, currentState: State<*>) {
        strategyTask = goToAnotherNode.also {
            it.initialize(currentState)
            it.retryTimes = 0
        }
        phaseState = PhaseState.P3_GO_TO_RELATED_NODE
        log.info("Go to explore another window: ${targetWindow.toString()}")

    }

    private fun setGoToRelatedWindow(goToAnotherNode: GoToAnotherWindowTask, currentState: State<*>) {
        log.info("Task chosen: Go to related window: $relatedWindow")
        phaseState = PhaseState.P3_GO_TO_RELATED_NODE
        remainPhaseStateCount = 0
        strategyTask = goToAnotherNode.also {
            it.initialize(currentState)
            it.retryTimes = 0
        }
    }

    private fun setGoToTarget(goToTargetNodeTask: GoToTargetWindowTask, currentState: State<*>) {
        log.info("Task chosen: Go to target window: $targetWindow .")
        phaseState = PhaseState.P3_GO_TO_TARGET_NODE
        remainPhaseStateCount = 0
        strategyTask = goToTargetNodeTask.also {
            it.initialize(currentState)
            it.retryTimes = 0
        }
    }

    private fun setExerciseTarget(exerciseTargetComponentTask: ExerciseTargetComponentTask, currentState: State<*>) {
        log.info("Task chosen: Exercise Target Node .")
        phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
        remainPhaseStateCount = 0
        strategyTask = exerciseTargetComponentTask.also {
            it.initialize(currentState)
            it.alwaysUseRandomInput = true
            it.environmentChange = true
        }
    }

    private fun setFullyRandomExploration(randomExplorationTask: RandomExplorationTask, currentState: State<*>) {
        log.info("Cannot go to target Window. Random Exploration with 10 actions")
        remainPhaseStateCount = 0
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.isPureRandom = true
            it.backAction = true
            it.setMaxiumAttempt((5*scaleFactor).toInt())
            it.environmentChange = true
            it.alwaysUseRandomInput = true
        }
    }


    private fun setRandomExplorationInTargetWindow(randomExplorationTask: RandomExplorationTask, currentState: State<*>) {
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.lockTargetWindow(targetWindow!!)
            it.setMaxiumAttempt(currentState,(5*scaleFactor).toInt())
            it.backAction = false
            it.isPureRandom = true
            it.environmentChange = true
            it.alwaysUseRandomInput = true
        }
        log.info("This window is a target window but cannot find any target window transition")
        log.info("Random exploration in current window")
        phaseState = PhaseState.P3_EXERCISE_TARGET_NODE
    }

    private fun setRandomExplorationInRelatedWindow(randomExplorationTask: RandomExplorationTask, currentState: State<*>) {
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.lockTargetWindow(relatedWindow!!)
            it.environmentChange = true
            it.alwaysUseRandomInput = true
            it.isPureRandom = true
            it.setMaxiumAttempt((5*scaleFactor).toInt())
        }
        log.info("In related window $relatedWindow.")
        log.info("Random exploration in current window")
        phaseState = PhaseState.P3_EXPLORATION_IN_RELATED_WINDOW
    }

    var windowBudgetLeft: Int = 0

    fun selectTargetWindow(currentState: State<*>, numTried: Int, maxTry: Int) {
        computeAppStatesScore()
        selectLeastTriedTargetWindow(maxTry, numTried, currentState)
        computeEventWindowCorrelation()
        if (targetWindow!=null)
            computeTargetEventScores()
        /*if (targetEventScores.isEmpty()) {
            eliminateWindows.add(targetWindow!!)
            selectTargetWindow(currentState, numTried+1, maxTry)
        }*/
        if (targetInputScores.size>0)
            windowBudgetLeft = targetInputScores.size
        else
        {
            val undiscoverdTargetHiddenHandlers = atuaMF.untriggeredTargetHiddenHandlers.filter {
                atuaMF.windowHandlersHashMap.get(targetWindow!!)?.contains(it) ?: false
            }
            windowBudgetLeft = undiscoverdTargetHiddenHandlers.size
        }
    }

    private fun computeTargetEventScores() {
        targetInputScores.clear()
        allTargetInputs.filter {
            it.key.sourceWindow == targetWindow
        }.forEach { it1->
            var score: Double = 0.0
            it1.key.modifiedMethods.filter { it.value }.forEach {it2->
                val method = it2.key
                if (modifiedMethodMissingStatements.containsKey(method)) {
                    val totalStmt = statementMF.getMethodStatements(method).size
                    val missingStmt = modifiedMethodMissingStatements[method]!!.size
                    val coveredStmt = totalStmt - missingStmt
                    score += (modifiedMethodWeights[method]!! * coveredStmt)
                }
                val usefulness = ModelHistoryInformation.INSTANCE.inputUsefulness[it1.key]
                val usefullScore = if (usefulness!=null) {
                    (usefulness.second*1.0/(usefulness.first+1))
                } else {
                    1.0
                }
                score *=usefullScore
            }
            if (score > 0.0)
                targetInputScores.put(it1.key, score)
        }

       /*if (targetEventScores.isEmpty()) {
           allTargetInputs.forEach {
                targetEventScores.put(it.key, 1.0)
            }
        }*/
    }

    private fun selectLeastTriedTargetWindow(maxTry: Int, numTried: Int, currentState: State<*>): Boolean {
//        var leastExercise = targetWindowsCount.values.min()
        val seenWindows = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it !is VirtualAbstractState
                && it.guiStates.isNotEmpty()
                && it.ignored == false }.map { it.window }.distinct()

        var leastTriedWindows = targetWindowsCount.filter {
            windowScores.containsKey(it.key)
                    && seenWindows.contains(it.key)
                    && !ProbabilityBasedPathFinder.disableWindows1.contains(it.key)}.map { Pair<Window, Int>(first = it.key, second = it.value) }

        /*if (leastTriedWindows.isEmpty()) {
            leastTriedWindows = targetWindowsCount.map { Pair<Window, Int>(first = it.key, second = it.value) }.filter { it.second == leastExercise }
        }*/
        val leastTriedWindowScore = leastTriedWindows.associate { Pair(it.first, windowScores.get(it.first) ?: 1.0) }
        if (leastTriedWindowScore.isNotEmpty()) {
            val pb = ProbabilityDistribution<Window>(leastTriedWindowScore)
            val targetNode = pb.getRandomVariable()
            targetWindow = targetNode
        } else {
            targetWindow = WindowManager.instance.allMeaningWindows.random()
        }
/*        if (tarqetWindowCandidates.isNotEmpty()) {
            leastTryTargetWindows = tarqetWindowCandidates.map { Pair<Window, Int>(first = it.key, second = it.value) }
                    .groupBy { it.second }.entries
                    .sortedBy { it.key }
                    .first().value
                    .map { it.first }
            if (Random.nextBoolean()) {
                targetWindow = leastTryTargetWindows.random()
            } else if (windowScores.filter { leastTryTargetWindows.contains(it.key) }.isNotEmpty()) {
                val pb = ProbabilityDistribution<Window>(windowsProbability.filter { leastTryTargetWindows.contains(it.key) })
                val targetNode = pb.getRandomVariable()
                targetWindow = targetNode
            } else {
                targetWindow = leastTryTargetWindows.random()
            }
        } else {
            return false
        }*/
//        targetWindowsCount[targetWindow!!] = targetWindowsCount[targetWindow!!]!! + 1
        /*var max = if (maxTry == 0)
            leastTryTargetWindows.size / 2 + 1
        else
            maxTry
        if (numTried < max && getPathsToWindowToExplore(currentState, targetWindow!!, PathFindingHelper.PathType.ANY,false).isEmpty()) {
            selectTargetWindow(currentState, numTried + 1, max)
            return true
        }*/

        return true
    }


    val eliminateWindows = ArrayList<Window>()
    private fun containTargetEvents(window: Window): Boolean {
        return allTargetInputs.any {
            it.key.sourceWindow == window &&
                    eventWindowCorrelation.containsKey(it.key)}
    }

    val targetModifiedMethods = ArrayList<String>()
    val targetInputScores = HashMap<Input, Double>()

    fun selectTargetStaticEvent(currentState: State<*>){
        val windowTargetEvents = getWindowAvailableTargetInputs()
        if (windowTargetEvents.isEmpty()) {
            if (targetInputScores.isNotEmpty()) {
                val pdForTargetEvents = ProbabilityDistribution<Input>(targetInputScores)
                targetEvent = pdForTargetEvents.getRandomVariable()
            } else {
                targetEvent = null
            }
        } else {
            /*val leastExerciseEventsCount = windowTargetEvents
                    .minBy { it.value }?.value ?: emptyMap<Input, Int>()
            val leastExerciseEvents = windowTargetEvents.filter { it.value == leastExerciseEventsCount }
            val leastExerciseEventScores = targetInputScores.filter { leastExerciseEvents.containsKey(it.key) }*/
            val targetEventWithScores = targetInputScores.filter { windowTargetEvents.containsKey(it.key) }
            if (targetEventWithScores.isNotEmpty()) {
                val pdForTargetEvents = ProbabilityDistribution<Input>(targetEventWithScores)
                targetEvent = pdForTargetEvents.getRandomVariable()
            } else {
                targetEvent = windowTargetEvents.keys.random()
            }
            allTargetInputs[targetEvent!!] = allTargetInputs[targetEvent!!]!! + 1
        }
        //select related window
        selectRelatedWindow(currentState,0,0)
        budgetCalculated = false
        randomBudgetLeft = -1
        windowBudgetLeft--
        atuaMF.updateMethodCovFromLastChangeCount = 0

    }

    var budgetCalculated = false
    private fun setRandomExplorationBudget(currentState: State<*>) {
        if (budgetCalculated)
            return
        val abstractState = atuaMF.getAbstractState(currentState)!!
        val inputCount = abstractState.window.inputs.filter { it.witnessed }
        var budget = 0
        inputCount.forEach {
            if (it.widget == null) {
                budget += 1
            } else {
                if (it.eventType.isItemEvent) {
                    budget+=3
                } else {
                    budget+=1
                }
            }
        }
        randomBudgetLeft = (log2(budget.toDouble()) * scaleFactor).toInt()
        budgetCalculated = true
    }

    private fun getWindowAvailableTargetInputs() : Map<Input,Int> {
        val events = targetInputScores.filter { eventWindowCorrelation.containsKey(it.key) }
        if (events.isNotEmpty())
            return allTargetInputs.filter { events.containsKey(it.key) }
        val scoredEvents  =  allTargetInputs.filter { targetInputScores.containsKey(it.key) }
        if (scoredEvents.isNotEmpty())
            return scoredEvents
        val targetEvents = allTargetInputs.filter { it.key.sourceWindow == targetWindow }
        return targetEvents
    }

    private fun selectRelatedWindow(currentState: State<*>, numTried: Int, maxTry: Int ) {
        relatedWindow = null
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        var max = if (maxTry == 0) {
            if (eventWindowCorrelation.containsKey(targetEvent)) {
                eventWindowCorrelation[targetEvent!!]!!.size / 2 + 1
            } else {
                1
            }
        } else {
            maxTry
        }
        val reachableWindows = ArrayList<Window>()
        val seenWindows = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { it !is VirtualAbstractState
                && it.guiStates.isNotEmpty()
                && it.ignored == false }.map { it.window }.distinct()
        val windows = WindowManager.instance.allMeaningWindows.toList().filter {
            !ProbabilityBasedPathFinder.disableWindows1.contains(it)
                    && seenWindows.contains(it)
        }
//        getReachableWindows(windows, currentAppState, currentState, reachableWindows)
        reachableWindows.addAll(windows)
        if (targetEvent!=null
                && eventWindowCorrelation.containsKey(targetEvent!!)
                && eventWindowCorrelation[targetEvent!!]!!.isNotEmpty()) {
            val currentEventWindowCorrelation = eventWindowCorrelation[targetEvent!!]!!.filter {  it.key != targetWindow!! && it.key !is Dialog }
            if (currentEventWindowCorrelation.isNotEmpty()) {
                val reachableRelatedWindows = currentEventWindowCorrelation.keys.intersect(reachableWindows)
                if (reachableRelatedWindows.isNotEmpty()) {
                    val pdForRelatedWindows = ProbabilityDistribution<Window>(currentEventWindowCorrelation.filter { reachableRelatedWindows.contains(it.key) })
                    relatedWindow = pdForRelatedWindows.getRandomVariable()
                }
            } 
        }
        if (relatedWindow == null) {
            val relatedWindows = windowsCorrelation.get(targetWindow)
            val reachableRelatedWindows = relatedWindows?.keys?: emptyList<Window>().intersect(reachableWindows)
            if (reachableRelatedWindows.isNotEmpty()) {
                val pdForRelatedWindows = ProbabilityDistribution<Window>(relatedWindows!!.filter { reachableRelatedWindows.contains(it.key) })
                relatedWindow = pdForRelatedWindows.getRandomVariable()
            }
        }
        if (relatedWindow == null) {
            if (reachableWindows.isNotEmpty()) {
                val pdForRelatedWindows = ProbabilityDistribution<Window>(reachableWindows.associateWith { 1.0 })
                relatedWindow = pdForRelatedWindows.getRandomVariable()
            }
        }

       /* if (numTried< max && getPathsToWindowToExplore(currentState, relatedWindow!!,PathFindingHelper.PathType.ANY,false).isEmpty()) {
            selectRelatedWindow(currentState, numTried+1,max)
        }*/

    }

    private fun getReachableWindows(
        windows: List<Window>,
        currentAppState: AbstractState,
        currentState: State<*>,
        reachableWindows: ArrayList<Window>
    ) {
        val pathConstraints = HashMap<PathConstraint,Boolean>()
        pathConstraints.put(PathConstraint.INCLUDE_RESET,true)
        pathConstraints.put(PathConstraint.INCLUDE_LAUNCH,true)
        windows.forEach {
            val paths = ArrayList<TransitionPath>()
            val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(it)!!
            val stateWithScore = HashMap<AbstractState, Double>()
            stateWithScore.put(virtualAbstractState, 1.0)

            getPathToStatesBasedOnPathType(
                pathType = PathFindingHelper.PathType.WIDGET_AS_TARGET,
                transitionPaths = paths,
                statesWithScore = stateWithScore,
                currentAbstractState = currentAppState,
                currentState = currentState,
                shortest = true,
                windowAsTarget = true,
                goalByAbstractState = emptyMap(),
                maxCost = ProbabilityBasedPathFinder.DEFAULT_MAX_COST,
                abandonedAppStates = emptyList(),
                pathConstraints = pathConstraints
            )
            if (paths.isNotEmpty()) {
                reachableWindows.add(it)
            }
        }
    }

    private fun setRandomExploration(randomExplorationTask: RandomExplorationTask, currentState: State<*>) {
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.stopWhenHavingTestPath = true
        }
        log.info("Random exploration")
    }
    val eventWindowCorrelation = HashMap<Input, HashMap<Window,Double>>()

    fun computeEventWindowCorrelation() {
        eventWindowCorrelation.clear()
        val eventsTerms = atuaMF!!.accumulateTargetEventsDependency()
        val ir = InformationRetrieval<Window,String>(atuaMF!!.windowTermsHashMap)
        eventsTerms.forEach {
            val result = ir.searchSimilarDocuments(it.value,10)
            val correlation = HashMap<Window, Double>()
            result.forEach {
                correlation.put(it.first,it.second)
            }
            eventWindowCorrelation.put(it.key,correlation)
        }
    }


    fun computeWindowsCorrelation() {
        val windows = WindowManager.instance.allMeaningWindows
        val ir = InformationRetrieval<Window,String>(atuaMF!!.windowTermsHashMap)
        windows.forEach {
            val windowTerm = atuaMF!!.windowTermsHashMap.get(it)
            if (windowTerm != null) {
                val result = ir.searchSimilarDocuments(windowTerm, 10)
                val correlation = HashMap<Window, Double>()
                result.forEach {
                    correlation.put(it.first, it.second)
                }
                windowsCorrelation.put(it, correlation)
            }
        }
    }

    fun computeAppStatesScore(){
        //Initiate reachable modified methods list
        modifiedMethodMissingStatements.clear()
        modifiedMethodTriggerCount.clear()
        appStateModifiedMethodMap.clear()
        modifiedMethodWeights.clear()
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
                    && it.witnessed

        }
        val allTargetInputs = ArrayList(usefulTargets.union(notExercisedYetTargets).distinct())

        val triggeredStatements = statementMF.getAllExecutedStatements()
        statementMF.getAllModifiedMethodsId().forEach {
            val methodName = statementMF!!.getMethodName(it)
            if (!atuaMF.unreachableModifiedMethods.contains(methodName))
            {
                modifiedMethodTriggerCount.put(it,0)
                val statements = statementMF!!.getMethodStatements(it)
                val missingStatements = statements.filter { !triggeredStatements.contains(it) }
                modifiedMethodMissingStatements.put(it,HashSet(missingStatements))
            }
        }
        allTargetInputs.removeIf {
            it.modifiedMethods.map { it.key }.all {
                modifiedMethodMissingStatements.containsKey(it) && modifiedMethodMissingStatements[it]!!.size==0
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
            appState.abstractTransitions.map { it.modifiedMethods}.forEach { hmap ->
                hmap.forEach { m, v ->
                    if (!appStateModifiedMethodMap[appState]!!.contains(m)) {
                        appStateModifiedMethodMap[appState]!!.add(m)
                    }
                }
            }
        }

        //for each edge, count modified method appearing
        edges.forEach {edge ->
            val coveredMethods = edge.label.methodCoverage
            if (coveredMethods!=null)
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
            val score = 1-c/totalAbstractInteractionCount.toDouble()
            modifiedMethodWeights.put(m,score)
        }

        //calculate appState score
        appStateList.forEach {
            var appStateScore: Double = 0.0
            val frequency = atuaMF.abstractStateVisitCount.get(it)?:1
            // an abstract state has higher score if there are more unexercised target abstract transitions
            val unexercisedTargetAbstractActions = it.abstractTransitions.filter { t->
                t.interactions.isNotEmpty()
            }.map { it.abstractAction }.distinct().filter { action -> it.getInputsByAbstractAction(action).any { it.modifiedMethods.isNotEmpty() }?:false }
            unexercisedTargetAbstractActions.forEach { action ->
                val inputs = it.getInputsByAbstractAction(action)
                for (item in inputs.map { it.modifiedMethods.entries }.flatten()) {
                    if (!modifiedMethodWeights.containsKey(item.key))
                        modifiedMethodWeights.put(item.key, 1.0)
                    val methodWeight = modifiedMethodWeights[item.key]!!
                    if (modifiedMethodMissingStatements.containsKey(item.key)) {
                        val missingStatementNumber = modifiedMethodMissingStatements[item.key]!!.size
                        appStateScore += (methodWeight * missingStatementNumber/frequency)
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
        appStateList.groupBy { it.window }. forEach { window, abstractStateList ->
            var totalScore = 0.0
            abstractStateList.forEach {
                totalScore += abstractStatesScores[it]!!
            }

            val appStatesProbab = ArrayList<Pair<AbstractState,Double>>()
            abstractStateProbability.put(window, appStatesProbab )
            abstractStateList.forEach {
                val pb = abstractStatesScores[it]!!/totalScore
                appStatesProbab.add(Pair(it,pb))
            }
        }

        //calculate staticNode score
        var staticNodeTotalScore = 0.0
        windowScores.clear()
        targetWindowsCount.forEach { n, _ ->
            var weight: Double = 0.0
            val modifiedMethods = HashSet<String>()
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
                    (usefulness.second*1.0/(usefulness.first+1))* uncoveredMethods.size* notFullyCoveredMethods.size
                } else {
                    uncoveredMethods.size*notFullyCoveredMethods.size*1.0
                }
                inputEffectiveness+=score
            }
            weight+=inputEffectiveness
            modifiedMethods.filter { modifiedMethodWeights.containsKey(it) }. forEach {
                val methodWeight = modifiedMethodWeights[it]!!
                val missingStatementsNumber = modifiedMethodMissingStatements[it]?.size?:0
                weight += (methodWeight*missingStatementsNumber)
            }
            if (weight>0.0)
            {
                windowScores.put(n, weight)
                staticNodeTotalScore+=weight
            }

        }
        windowsProbability.clear()
        //calculate staticNode probability
        windowScores.forEach { n, s ->
            val pb = s/staticNodeTotalScore
            windowsProbability.put(n,pb)
        }

    }

    companion object {
        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(PhaseThreeStrategy::class.java) }

        val TEST_BUDGET: Int = 25
    }
}