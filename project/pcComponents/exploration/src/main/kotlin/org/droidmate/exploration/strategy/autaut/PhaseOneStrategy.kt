package org.droidmate.exploration.strategy.autaut

import org.droidmate.deviceInterface.exploration.ActionQueue
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.availableActions
import org.droidmate.exploration.actions.resetApp
import org.droidmate.exploration.modelFeatures.autaut.abstractStateElement.*
import org.droidmate.exploration.modelFeatures.autaut.staticModel.*
import org.droidmate.exploration.strategy.autaut.task.*
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.State
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.random.Random

class PhaseOneStrategy(
        autAutTestingStrategy: AutAutTestingStrategy,
        budgetScale: Double,
        delay: Long,
        useCoordinateClicks: Boolean
):AbstractPhaseStrategy (
        autAutTestingStrategy = autAutTestingStrategy,
        budgetScale = budgetScale,
        delay = delay,
        useCoordinateClicks = useCoordinateClicks,
        useVirtualAbstractState = true
) {
    override fun isTargetWindow(window: WTGNode): Boolean {
        if (window == targetWindow)
            return true
        return false
    }


    val untriggeredWidgets = arrayListOf<StaticWidget>()
    val untriggeredTargetEvents = arrayListOf<StaticEvent>()

    var attemps: Int
    var targetWindow: WTGNode? = null
    val flaggedWindows = HashSet<WTGNode>()
    val unreachableWindows = HashSet<WTGNode>()
    val fullyCoveredWindows = HashSet<WTGNode>()
    val targetWindowTryCount: HashMap<WTGNode,Int> = HashMap()
    val windowRandomExplorationBudget: HashMap<WTGNode,Int> = HashMap()
    val windowRandomExplorationBudgetUsed: HashMap<WTGNode, Int> = HashMap()
    val windowRandomExplorationBudget2: HashMap<AbstractState,Int> = HashMap()
    val windowRandomExplorationBudgetUsed2: HashMap<AbstractState, Int> = HashMap()
    val window_AttrValSetMaps = HashMap<WTGNode, HashSet<AttributeValuationSet>> ()

    init {
        phaseState = PhaseState.P1_INITIAL
        autautMF = autAutTestingStrategy.eContext.getOrCreateWatcher()
        attemps = autautMF.allTargetWindow_ModifiedMethods.size
        autautMF.allTargetWindow_ModifiedMethods.keys.forEach {
            targetWindowTryCount.put(it,0)
        }
        autautMF.allTargetStaticEvents.forEach {
            untriggeredTargetEvents.add(it)
        }
    }

    override fun registerTriggeredEvents( abstractAction: AbstractAction, guiState: State<*>)
    {
        val abstractState = AbstractStateManager.instance.getAbstractState(guiState)!!
        //val abstractInteractions = regressionTestingMF.abstractTransitionGraph.edges(abstractState).filter { it.label.abstractAction.equals(abstractAction) }.map { it.label }

        val staticEvents = abstractState.staticEventMapping[abstractAction]
        if (staticEvents != null) {
            staticEvents.forEach {
                untriggeredTargetEvents.remove(it)
                /*if (autautMF.targetItemEvents.containsKey(it)) {
                    autautMF.targetItemEvents[it]!!["count"] = autautMF!!.targetItemEvents[it]!!["count"]!! + 1
                    if (autautMF.targetItemEvents[it]!!["count"] == autautMF!!.targetItemEvents[it]!!["max"]!!) {
                        untriggeredTargetEvents.remove(it)
                    }
                } else {
                    untriggeredTargetEvents.remove(it)
                }*/
            }
        }
    }

    var tryCount = 0
    var forceEnd = false
    override fun hasNextAction(currentState: State<*>): Boolean {
/*        if (forceEnd)
            return true*/
        //For debug, end phase one after 100 actions
        /*if (autAutTestingStrategy.eContext.explorationTrace.getActions().size > 100)
            return true*/
        if (autautMF.lastUpdatedMethodCoverage == 1.0) {
            return false
        }
        initBudgetForNewWindow(currentState)
        updateTargetWindows()
        updateFlaggedWindows()
        updateUnreachableWindows(currentState)


        if (targetWindowTryCount.filterNot{fullyCoveredWindows.contains(it.key)}.isEmpty()) {
            return false
        }

        val reachedWindows = targetWindowTryCount.filter {
            it.key.mappedStates.isNotEmpty() &&
                    it.key.mappedStates.any { it !is VirtualAbstractState }
        }
        if (reachedWindows.size/targetWindowTryCount.size.toDouble() < 0.25 )
            return true
        return isAvailableAbstractStatesExisting(currentState)
        /*val toExploreWindows = targetWindowTryCount.filterNot { flaggedWindows.contains(it.key) || fullyCoveredWindows.contains(it.key) || unreachableWindows.contains(it.key)}
        if (toExploreWindows.isEmpty()) {

            return true
        } else  {
           return false
        }*/
    }

    private fun updateUnreachableWindows(currentState: State<*>) {
        val currentAppState = autautMF.getAbstractState(currentState)!!
        if (unreachableWindows.contains(currentAppState.window)) {
            unreachableWindows.remove(currentAppState.window)
        }
    }

    private fun isAvailableAbstractStatesExisting(currentState: State<*>): Boolean {
        val availableAbstractStates = AbstractStateManager.instance.ABSTRACT_STATES
                .filter {
                    it !is VirtualAbstractState &&
                            windowRandomExplorationBudget.containsKey(it.window) &&
                    !flaggedWindows.contains(it.window) &&
                            !unreachableWindows.contains(it.window) &&
                            hasBudgetLeft2(null, it)
                }
        if (availableAbstractStates.isEmpty())
            return false
        if (availableAbstractStates.any { !isBlocked(it, currentState) }) {
            return true
        }
        return false
    }

    private fun initBudgetForNewWindow(currentState: State<*>) {
        //val currentAppState = autautMF.getAbstractState(currentState)!!
        window_AttrValSetMaps.clear()
        AbstractStateManager.instance.ABSTRACT_STATES
                .filter { it !is VirtualAbstractState &&
                            !it.isOutOfApplication &&
                            !it.isHomeScreen &&
                            !it.isRequestRuntimePermissionDialogBox &&
                            !it.isAppHasStoppedDialogBox
                }.forEach {
                    val window = it.window
                    if (!window_AttrValSetMaps.containsKey(window)) {
                        window_AttrValSetMaps.put(window,HashSet())
                    }
                    val attrValSets = window_AttrValSetMaps[window]!!
                    it.attributeValuationSets.filter { !attrValSets.contains(it) }.forEach {
                        attrValSets.add(it)
                    }
                }
        WTGNode.allMeaningNodes.forEach { window ->
            if (window_AttrValSetMaps.containsKey(window)) {
                if (window is WTGActivityNode)
                    windowRandomExplorationBudget[window] = window_AttrValSetMaps[window]!!.size + 8
                else
                    windowRandomExplorationBudget[window] = window_AttrValSetMaps[window]!!.size
                if (!windowRandomExplorationBudgetUsed.containsKey(window)) {
                    windowRandomExplorationBudgetUsed.put(window, 0)
                }
            }
        }

/*        val visibleInterativeWidgets =  Helper.getVisibleInteractableWidgets(currentState)
        if (!windowRandomExplorationBudget.containsKey(currentAppState.window)) {
            *//* val isWebViewBased = true
            val budget = if (isWebViewBased) {
                Helper.getVisibleInteractableWidgets(currentState).map { it.availableActions(100, false) }.flatten().size * 2 * budgetScale
            } else {
                Helper.getVisibleInteractableWidgets(currentState).filter { !Helper.hasParentWithType(it, currentState, "WebView") }.map { it.availableActions(100, false) }.flatten().size  * budgetScale
            }*//*
            windowRandomExplorationBudget.put(currentAppState.window, budget.toInt())
            windowRandomExplorationBudgetUsed.put(currentAppState.window, 0)
        }
        if (!windowRandomExplorationBudget2.containsKey(currentAppState)) {
            val isWebViewBased = true
            val budget = if (isWebViewBased) {
                Helper.getVisibleInteractableWidgets(currentState).map { it.availableActions(100, false) }.flatten().size*2* budgetScale
            } else {
                Helper.getVisibleInteractableWidgets(currentState).filter { !Helper.hasParentWithType(it, currentState, "WebView") }.map { it.availableActions(100, false) }.flatten().size * budgetScale
            }
            windowRandomExplorationBudget2.put(currentAppState, budget.toInt())
            windowRandomExplorationBudgetUsed2.put(currentAppState, 0)
        }*/
    }

    private fun updateFlaggedWindows() {
        val availableAbState_Window =  AbstractStateManager.instance.ABSTRACT_STATES
                .filter { it !is VirtualAbstractState &&
                        !flaggedWindows.contains(it.window) &&
                        !unreachableWindows.contains(it.window)
                }.groupBy { it.window }
        availableAbState_Window.forEach {
           if (it.value.all { !hasBudgetLeft2(null,it) }) {
               flaggedWindows.add(it.key)
           }

        }
    }

    private fun updateTargetWindows() {
        autautMF.allTargetWindow_ModifiedMethods.map { it.key }.forEach {
            var coverCriteriaCount = 0
            if (autautMF.allTargetWindow_ModifiedMethods[it]!!.all { autautMF.allModifiedMethod[it] == true }) {
                coverCriteriaCount++
            }
            if (autautMF.untriggeredTargetHandlers.intersect(
                            autautMF.windowHandlersHashMap[it] ?: emptyList()
                    ).isEmpty()) {
                coverCriteriaCount++
            }
            if (coverCriteriaCount==2) {
                if (!fullyCoveredWindows.contains(it)) {
                    fullyCoveredWindows.add(it)
                    if (targetWindow == it) {
                        targetWindow = null
                        strategyTask = null
                    }
                }
            }
            if (!targetWindowTryCount.containsKey(it))
                targetWindowTryCount.put(it, 0)
        }
    }

    private fun isBlocked(abstractState: AbstractState,currentState: State<*>): Boolean {
        val transitionPath = ArrayList<TransitionPath>()
        val abstractStates = HashMap<AbstractState,Double>()
        val currentAbstractState = autautMF.getAbstractState(currentState)!!
        if (abstractState == currentAbstractState)
            return false
        abstractStates.put(abstractState,1.0)
        getPathToStates(transitionPaths=transitionPath,
                stateByScore = abstractStates,
                stopWhenHavingUnexercisedAction = false,
                includeBackEvent = true,
                currentState = currentState,
                currentAbstractState = currentAbstractState,
                useVABS = false,
                shortest = true,
                pathCountLimitation = 1,
                includeReset = true
                )
        if (transitionPath.isEmpty())
            return true
        return false
    }

    override fun nextAction(eContext: ExplorationContext<*,*,*>): ExplorationAction {
        //TODO Update target windows


        val currentState = eContext.getCurrentState()
        val currentAppState = autautMF.getAbstractState(currentState)!!
        log.info("Current abstract state: $currentAppState")
        log.info("Current window: ${currentAppState.window}")
        val toRemove = ArrayList<WTGNode>()
        unreachableWindows.forEach {
            if (currentAppState.window == it) {
                toRemove.add(it)
            } else {
                val transitionPaths = getPathsToWindow(currentState, it, true)
                if (transitionPaths.isNotEmpty())
                    toRemove.add(it)
            }
        }
        unreachableWindows.removeAll(toRemove)
        var chosenAction:ExplorationAction


        /* if (autautMF.updateMethodCovFromLastChangeCount > 25 * budgetScale
                 && actionCountSinceSelectTarget > 25 * budgetScale) {
             selectTargetNode(currentState,0).also {
                 if (targetWindow!=null) {
                     strategyTask = null
                     phaseState = PhaseState.P1_INITIAL
                     autautMF.updateMethodCovFromLastChangeCount = 0
                 }
             }
         }*/
        if (targetWindow != currentAppState.window) {
            if (isTargetWindow(currentAppState)) {
                targetWindow = currentAppState.window
                strategyTask = null
                phaseState = PhaseState.P1_INITIAL
            }
        }

        if (targetWindow == null) {
            selectTargetNode(currentState, 0).also {
                if (targetWindow != null) {
                    strategyTask = null
                    phaseState = PhaseState.P1_INITIAL
                }
            }
        } else if (flaggedWindows.contains(targetWindow!!)) {
            selectTargetNode(currentState, 0).also {
                if (targetWindow != null) {
                    strategyTask = null
                    phaseState = PhaseState.P1_INITIAL
                }
            }
        }

/*        if (regressionTestingMF.appPrevState!=null) {
                val prevAppState = regressionTestingMF.getAbstractState(regressionTestingMF.appPrevState!!)!!
            if (currentAppState.window != prevAppState.window && currentAppState.rotation == Rotation.LANDSCAPE) {
                // rotate to portrait
                return ExplorationAction.rotate(-90)
            }
        }*/




        ExplorationTrace.widgetTargets.clear()
        // check if target window is fully covered
/*        val targetEvents = untriggeredTargetEvents.filter { it.sourceWindow == targetWindow }
        if (targetEvents.isEmpty()) {
            if (regressionTestingMF.windowHandlersHashMap.containsKey(targetWindow)) {
                val targetHandlers = regressionTestingMF.windowHandlersHashMap[targetWindow!!]!!.filter { regressionTestingMF.allTargetHandlers.contains(it) }
                if (regressionTestingMF.untriggeredTargetHandlers.intersect(targetHandlers).isEmpty()) {
                    //No need to test this Window
                    fullyCoveredWindows.add(targetWindow!!)
                    selectTargetNode()
                    //TODO relaunch app
                }
            } else {
                if (targetWindow!=null)
                    fullyCoveredWindows.add(targetWindow!!)
                selectTargetNode()
            }
        }*/

        log.info("Target window: $targetWindow")
        chooseTask_P1(eContext, currentState)
        if (needResetApp) {
            needResetApp = false
            autautMF.updateMethodCovFromLastChangeCount = 0
            return eContext.resetApp()
        }
        if (strategyTask != null) {
            chosenAction = strategyTask!!.chooseAction(currentState)
            if (isCountAction(chosenAction)
                    && windowRandomExplorationBudgetUsed.containsKey(currentAppState.window)
                    && phaseState!=PhaseState.P1_GO_TO_TARGET_NODE
                    && phaseState!=PhaseState.P1_GO_TO_EXPLORE_STATE
                    && phaseState!=PhaseState.P1_EXERCISE_TARGET_NODE) {

                windowRandomExplorationBudgetUsed[currentAppState.window] = windowRandomExplorationBudgetUsed[currentAppState.window]!! + 1
/*                if (windowRandomExplorationBudgetUsed[currentAppState.window]!! > windowRandomExplorationBudget[currentAppState.window]!!) {
                    flaggedWindows.add(currentAppState.window)
                }*/
            }
        } else {
            log.debug("No task seleted. It might be a bug.")
            chosenAction = eContext.resetApp()
        }
        actionCountSinceSelectTarget++
        return chosenAction
    }

    private fun chooseTask_P1(eContext: ExplorationContext<*, *, *>, currentState: State<*>) {
        log.debug("Choosing Task")
        val fillDataTask = PrepareContextTask.getInstance(autautMF,autAutTestingStrategy,delay, useCoordinateClicks)
        val exerciseTargetComponentTask = ExerciseTargetComponentTask.getInstance(autautMF, autAutTestingStrategy, delay, useCoordinateClicks)
        val goToTargetNodeTask = GoToTargetWindowTask.getInstance(autautMF, autAutTestingStrategy, delay, useCoordinateClicks)
        val goToAnotherNode = GoToAnotherWindow.getInstance(autautMF, autAutTestingStrategy, delay, useCoordinateClicks)
        val randomExplorationTask = RandomExplorationTask.getInstance(autautMF, autAutTestingStrategy,delay, useCoordinateClicks)
        val openNavigationBarTask = OpenNavigationBarTask.getInstance(autautMF,autAutTestingStrategy,delay, useCoordinateClicks)
        val currentState = eContext.getCurrentState()
        val currentAppState = autautMF.getAbstractState(currentState)!!

/*        if (strategyTask == null) {
            log.info("No current task selected.")
            log.info("Choose new task.")
            chooseTask_P1(eContext, currentState)
        } else if (phaseState == PhaseState.P1_RANDOM_IN_EXERCISE_TARGET_NODE) {
        } else if (strategyTask != null && strategyTask!!.isTaskEnd(currentState)) {
            log.info("Current task is end.")
            log.info("Choose new task.")
            chooseTask_P1(eContext, currentState)
        } else {
            log.info("Continue ${strategyTask!!.javaClass.name} task.")
        }*/
        log.debug("${currentAppState.window} - Budget: ${windowRandomExplorationBudgetUsed[currentAppState.window]}/${windowRandomExplorationBudget[currentAppState.window]}")

        if (targetWindow == null) {

            if (strategyTask!=null) {
                if (!strategyTask!!.isTaskEnd(currentState)) {
                    // if random can be still run, keep running
                    log.info("Continue current task: $strategyTask")
                    return
                }
            }

            if (hasBudgetLeft2(currentState, currentAppState)) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }
            if (currentAppState.window is WTGDialogNode) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState,true,false)
                return
            }
            if (goToAnotherNode.isAvailable(currentState)) {
              setGoToExploreState(goToAnotherNode, currentState)
                return
            }
            setFullyRandomExploration(randomExplorationTask,currentState,currentAppState)
            return
        }
        if (phaseState == PhaseState.P1_INITIAL) {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    //Target events found
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                // In case target events not found
                // Try random exploration
                if (hasBudgetLeft2(currentState,currentAppState)) {
                    setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                    return
                }
                /*else {
                    flaggedWindows.add(targetWindow!!)
                    selectTargetNode()
                    phaseState = PhaseState.P1_INITIAL
                    //needResetApp = true
                    return
                }*/
            }
            if (currentAppState.window is WTGDialogNode) {
                if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,false)) {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
                setRandomExploration(randomExplorationTask, currentState, currentAppState, true, lockWindow = false)
                return
            }
            if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,true)) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            unreachableWindows.add(targetWindow!!)
            if (hasBudgetLeft2(currentState,currentAppState)) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }
/*            if (isLoginWindow(currentAppState)) {
                // Try random login Window
                log.info("Try random in login window")
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }*/
            if (goToAnotherNode.isAvailable(currentState)) {
                setGoToExploreState(goToAnotherNode, currentState)
                return
            }
            setFullyRandomExploration(randomExplorationTask,currentState,currentAppState)
            return
        }
        if (phaseState == PhaseState.P1_EXERCISE_TARGET_NODE) {

            if (currentAppState.window == targetWindow) {
                if (!strategyTask!!.isTaskEnd(currentState)) {
                    //Keep current task
                    log.info("Continue exercise target window")
                    return
                }
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    //Target events found
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                // In case target events not found
                // Try random exploration
                if (hasBudgetLeft2(currentState,currentAppState)) {
                    setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                    return
                }
            }
/*            if (isLoginWindow(currentAppState) && hasBudgetLeft2(currentAppState.window)) {
                // Try random login Window
                log.info("Try random in login window")
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }*/
            if (currentAppState.window is WTGDialogNode) {
                if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,false)) {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
                setRandomExploration(randomExplorationTask, currentState, currentAppState, true, lockWindow = false)
                return
            }
            if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,true)) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            unreachableWindows.add(targetWindow!!)
            if (hasBudgetLeft2(currentState,currentAppState)) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }
            if (goToAnotherNode.isAvailable(currentState)) {
                setGoToExploreState(goToAnotherNode, currentState)
                return
            }
            /*if (hasBudgetLeft2(currentAppState)) {
                setRandomExploration(randomExplorationTask, currentState,currentAppState)
                return
            }*/
            setFullyRandomExploration(randomExplorationTask,currentState,currentAppState)
            return
        }
        if (phaseState == PhaseState.P1_GO_TO_TARGET_NODE) {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    //Target events found
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                if (!strategyTask!!.isTaskEnd(currentState)) {
                    //Keep current task
                    log.info("Continue go to target window")
                    return
                }
                // In case target events not found
                // Try random exploration if having budget
                if (hasBudgetLeft2(currentState,currentAppState)) {
                    setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                    return
                }
            }
            /* if (isLoginWindow(currentAppState) && hasBudgetLeft2(currentAppState.window)) {
                 // Try random login Window
                 log.info("Try random in login window")
                 setRandomExploration(randomExplorationTask, currentState, currentAppState)
                 return
             }*/
            if (!strategyTask!!.isTaskEnd(currentState)) {
                log.info("Continue ${strategyTask!!}")
                return
            }
            if (currentAppState.window is WTGDialogNode) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState, true, lockWindow = false)
                return
            }
            if (hasBudgetLeft2(currentState,currentAppState)) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState,true)
                return
            }
            setFullyRandomExploration(randomExplorationTask,currentState,currentAppState)
            return

        }
        if (phaseState == PhaseState.P1_GO_TO_EXPLORE_STATE) {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    //Target events found
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                // In case target events not found
                // Try random exploration if having budget
                if (hasBudgetLeft2(currentState,currentAppState)) {
                    setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                    return
                }
            }
            /*if (isLoginWindow(currentAppState) && hasBudgetLeft2(currentAppState)) {
                // Try random login Window
                log.info("Try random in login window")
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }*/
            if (!strategyTask!!.isTaskEnd(currentState)) {
                log.info("Continue ${strategyTask!!}")
                return
            }
            if (currentAppState.window is WTGDialogNode) {
                if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,false)) {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
                setRandomExploration(randomExplorationTask, currentState, currentAppState, true, lockWindow = false)
                return
            }
            if (goToAnotherNode.destWindow == currentAppState.window) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
            }
            if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,true)) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            unreachableWindows.add(targetWindow!!)
            if (hasBudgetLeft2(currentState,currentAppState)) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }
            if (!goToAnotherNode.isReachExpectedNode(currentState) && numOfContinousTry < 10) {
                if (goToAnotherNode.isAvailable(currentState)) {
                    setGoToExploreState(goToAnotherNode, currentState)
                    return
                }
            }
            setFullyRandomExploration(randomExplorationTask, currentState,currentAppState)
            return
        }
        if (phaseState == PhaseState.P1_RANDOM_IN_EXERCISE_TARGET_NODE) {
            if (currentAppState.window == targetWindow) {
                if (randomExplorationTask.fillingData || randomExplorationTask.attemptCount == 0) {
                    // if random can be still run, keep running
                    log.info("Continue filling data")
                    return
                }
                if (!strategyTask!!.isTaskEnd(currentState)) {
                    log.info("Continue random in target window")
                    return
                }
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    //Target events found
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                if (hasBudgetLeft2(currentState,currentAppState)) {
                    setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                    return
                }
                if (randomExplorationTask.isFullyExploration || shouldChangeTargetWindow() ) {
                    selectTargetNode(currentState,0)
                    phaseState = PhaseState.P1_INITIAL
                    //needResetApp = true
                    return
                }
                setFullyRandomExploration(randomExplorationTask, currentState,currentAppState)
                phaseState = PhaseState.P1_RANDOM_IN_EXERCISE_TARGET_NODE
                randomExplorationTask.lockTargetWindow(currentAppState.window)
                return
                /*if (shouldChangeTargetWindow()) {
                    if (goToAnotherNode.isAvailable(currentState)) {
                        strategyTask = goToAnotherNode.also {
                            it.initialize(currentState)
                        }
                        log.info("Go to explore window: ${goToAnotherNode.destWindow.toString()}")

                        phaseState = PhaseState.P1_GO_TO_ANOTHER_NODE
                        return
                    }
                }*/

            }
            /*if (isLoginWindow(currentAppState) && hasBudgetLeft2(currentAppState)) {
                // Try random login Window
                log.info("Try random in login window")
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }*/
            if (currentAppState.window is WTGDialogNode) {
                if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,false)) {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
                setRandomExploration(randomExplorationTask, currentState, currentAppState, true, lockWindow = false)
                return
            }
            if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,true)) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            unreachableWindows.add(targetWindow!!)
            if (hasBudgetLeft2(currentState,currentAppState)) {
                    setRandomExploration(randomExplorationTask, currentState, currentAppState)
                    return
                }

            if (goToAnotherNode.isAvailable(currentState)) {
                setGoToExploreState(goToAnotherNode, currentState)
                return
            }
            /*if (hasBudgetLeft2(currentAppState)) {
               setRandomExploration(randomExplorationTask, currentState,currentAppState)
               return
           }*/
            setFullyRandomExploration(randomExplorationTask,currentState,currentAppState)
            return
        }
        if (phaseState == PhaseState.P1_RANDOM_EXPLORATION) {
            if (currentAppState.window == targetWindow) {
                if (exerciseTargetComponentTask.isAvailable(currentState)) {
                    //Target events found
                    setExerciseTarget(exerciseTargetComponentTask, currentState)
                    return
                }
                if (hasBudgetLeft2(currentState,currentAppState)) {
                    setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                    return
                }
            }
            if (randomExplorationTask.fillingData || randomExplorationTask.attemptCount == 0) {
                // if random can be still run, keep running
                log.info("Continue filling data")
                return
            }
            if (randomExplorationTask.isFullyExploration && !strategyTask!!.isTaskEnd(currentState)) {
                log.info("Continue fully exploration")
                return
            }
            if (randomExplorationTask.stopWhenHavingTestPath && currentAppState.window !is WTGDialogNode) {
                if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,true)) {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
            }
            if (!strategyTask!!.isTaskEnd(currentState)) {
                // if random can be still run, keep running
                log.info("Continue exploration")
                return
            }
            if (currentAppState.window is WTGDialogNode) {
                if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,true)) {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
                setFullyRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }
            if (goToTargetNodeTask.isAvailable(currentState, targetWindow!!,true)) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            unreachableWindows.add(targetWindow!!)

            if (hasBudgetLeft2(currentState,currentAppState)) {
                setRandomExploration(randomExplorationTask, currentState, currentAppState)
                return
            }
            if (goToAnotherNode.isAvailable(currentState)) {
                setGoToExploreState(goToAnotherNode, currentState)
                return
            }


            if (randomExplorationTask.isFullyExploration) {
                selectTargetNode(currentState,0)
                phaseState = PhaseState.P1_INITIAL
                //needResetApp = true
                return
            }
            setFullyRandomExploration(randomExplorationTask, currentState, currentAppState)
            return
        }
        selectTargetNode(currentState,0)
        phaseState = PhaseState.P1_INITIAL
        //needResetApp = true
        return

    }

    override fun isTargetState(currentState: State<*>): Boolean {
        val currentAbstractState = autautMF.getAbstractState(currentState)!!
        if (isTargetAbstractState(currentAbstractState)) {
            return true
        }
        if (autautMF.untriggeredTargetHandlers.intersect(
                        autautMF.windowHandlersHashMap[currentAbstractState.window] ?: emptyList()
                ).isNotEmpty()) {
            if (hasBudgetLeft2(currentState,currentAbstractState))
                return true
        }
        return false
    }

    private fun setGoToExploreState(goToAnotherNode: GoToAnotherWindow, currentState: State<*>) {
        strategyTask = goToAnotherNode.also {
            it.initialize(currentState)
            it.retryTimes = 0
        }
        log.info("Go to target window by visiting another window: ${targetWindow.toString()}")
        phaseState = PhaseState.P2_GO_TO_EXPLORE_STATE
    }

    private fun setGoToTarget(goToTargetNodeTask: GoToTargetWindowTask, currentState: State<*>) {
        strategyTask = goToTargetNodeTask.also {
            it.initialize(currentState)
            it.retryTimes = 0
        }
        log.info("Go to target window: ${targetWindow.toString()}")
        phaseState = PhaseState.P1_GO_TO_TARGET_NODE
    }

    private fun isWindowHasBudgetLeft(window: WTGNode): Boolean {
        if (AbstractStateManager.instance.ABSTRACT_STATES.filter { it !is VirtualAbstractState
                && it.window == window}.any { hasBudgetLeft2(null,it) }) {
            return true
        }
        return false
    }

    private fun selectTargetNode(currentState: State<*>, tried: Int) {
        //Try finding reachable target

        val explicitTargetWindows = WTGNode.allMeaningNodes.filter { window ->
            untriggeredTargetEvents.any { it.sourceWindow == window }
        }
        var candidates = targetWindowTryCount.filter { isExplicitCandidateWindow(explicitTargetWindows,it) }
        if (candidates.isNotEmpty()) {
            val leastTriedWindow = candidates.map { Pair<WTGNode,Int>(first = it.key, second = it.value) }.groupBy { it.second }.entries.sortedBy { it.key }.first()
            targetWindow = leastTriedWindow.value.random().first
        } else {
            targetWindow = null
        }
        if (targetWindow == null) {
            candidates = targetWindowTryCount.filter { isCandidateWindow(it) }
            if (candidates.isNotEmpty()) {
                val leastTriedWindow = candidates.map { Pair<WTGNode, Int>(first = it.key, second = it.value) }.groupBy { it.second }.entries.sortedBy { it.key }.first()
                targetWindow = leastTriedWindow.value.random().first
            }
        }
        if (targetWindow != null) {
            val transitionPaths = getPathsToWindow(currentState,targetWindow!!,true)
            targetWindowTryCount[targetWindow!!] = targetWindowTryCount[targetWindow!!]!! + 1
            if (transitionPaths.isEmpty()) {
                unreachableWindows.add(targetWindow!!)
                return selectTargetNode(currentState, tried+1)
            }
        }

        actionCountSinceSelectTarget = 0
    }

    private fun isExplicitCandidateWindow(explicitTargetWindows: List<WTGNode>, it: Map.Entry<WTGNode, Int>) =
            explicitTargetWindows.contains(it.key) && !flaggedWindows.contains(it.key) && !fullyCoveredWindows.contains(it.key) && !unreachableWindows.contains(it.key)

    private fun isCandidateWindow(it: Map.Entry<WTGNode, Int>) =
            !flaggedWindows.contains(it.key) && !fullyCoveredWindows.contains(it.key) && !unreachableWindows.contains(it.key)

    override fun  getPathsToOtherWindows(currentState: State<*>): List<TransitionPath> {
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.instance.getAbstractState(currentState)
        val prevAbstractState = AbstractStateManager.instance.getAbstractState(autautMF.appPrevState!!)
        if (currentAbstractState==null)
            return transitionPaths
        val runtimeAbstractStates = AbstractStateManager.instance.ABSTRACT_STATES
                .filterNot { it is VirtualAbstractState ||
                        it == currentAbstractState ||
                        it.window is WTGLauncherNode ||
                        it.window is WTGOutScopeNode ||
                        !hasBudgetLeft2(null,it)
                }
        /*val candiateWindows = WTGNode.allMeaningNodes.filter { window -> runtimeAbstractStates.find { it.window == window } != null}
        val candidateVisitedFoundNodes = regressionTestingMF.windowVisitCount
                .filter { candiateWindows.contains(it.key) } as HashMap*/
        val abstratStateCandidates = runtimeAbstractStates

        val stateByActionCount = HashMap<AbstractState,Double>()
        abstratStateCandidates.forEach {
            // compute shortest distance with HomeScreen
           /* val homeScreenDistances = autautMF.abstractStateList.indices.filter { index ->
                autautMF.abstractStateList[index]!!.second == it 
            }.map { index ->
                val beforeHomeScreenIndex = homeScreenIndex.findLast { it != -1 && it < index }
                if (beforeHomeScreenIndex != null) {
                    index - beforeHomeScreenIndex
                } else {
                    -1
                }
            }

            val shortestDistance = homeScreenDistances.min()?:1
*/
            /*val unExerciseActions = it.getUnExercisedActions(null).filter { it.widgetGroup!=null }
            val widgetFrequency = AbstractStateManager.instance.widgetGroupFrequency[it.window]!!
            var weight = 0.0
            unExerciseActions.forEach { action ->
                val widgetGroup = action.widgetGroup!!
                weight += (1/(widgetFrequency[widgetGroup]?:1).toDouble())
            }*/
            val weight =  it.computeScore(autautMF)
            if (weight>0.0) {
                stateByActionCount.put(it, weight)
            }
            /*val unExercisedActionsSize = it.getUnExercisedActions().filter { it.widgetGroup!=null }.size
            if (unExercisedActionsSize > 0 ) {


                stateByActionCount.put(it, it.getUnExercisedActions().size.toDouble())

            }*/
        }
        val stateCandidates: Map<AbstractState,Double>
        stateCandidates = stateByActionCount
       /* if (stateByActionCount.any { AbstractStateManager.instance.launchAbstractStates[AbstractStateManager.LAUNCH_STATE.NORMAL_LAUNCH]!!.window == it.key.window }) {
            stateCandidates = stateByActionCount.filter { it.key.window == AbstractStateManager.instance.launchAbstractStates[AbstractStateManager.LAUNCH_STATE.NORMAL_LAUNCH]!!.window  }
        } else {
            stateCandidates = stateByActionCount
        }*/
        /*if (candidateVisitedFoundNodes.isEmpty())
            return transitionPaths*/
        //val activityVisitCount = HashMap(this.activityVisitCount)
        getPathToStates(transitionPaths, stateCandidates, currentAbstractState, currentState
                ,false,false,true,true,true,1)
        if (transitionPaths.size == 0) {
            forceEnd = true
        }
        return transitionPaths
    }



    override fun getPathsToTargetWindows(currentState: State<*>): List<TransitionPath> {
        log.debug("getAllTargetNodePaths")
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.instance.getAbstractState(currentState)
        val prevAbstractState = AbstractStateManager.instance.getAbstractState(autautMF.appPrevState!!)
        if (currentAbstractState==null)
            return transitionPaths
        val targetStates = getTargetAbstractStates(currentNode = currentAbstractState)
        val stateScores: HashMap<AbstractState,Double> = HashMap<AbstractState,Double>()
        targetStates.filterNot { it == currentState }. forEach {
            val score =  it.computeScore(autautMF)
            stateScores.put(it,score)
        }
        getPathToStates(transitionPaths,stateScores,currentAbstractState,currentState,
                false,useVirtualAbstractState,true,true,true,1)
        return transitionPaths
    }

    override fun getCurrentTargetEvents(currentState: State<*>): Set<AbstractAction> {
        val targetEvents = HashMap<StaticEvent,List<AbstractAction>>()
        if (autautMF.getAbstractState(currentState)!!.window != targetWindow)
            return emptySet<AbstractAction>()
        val currentWindowTargetEvents = untriggeredTargetEvents.filter { it.sourceWindow == targetWindow }
        currentWindowTargetEvents.forEach {
            val abstractInteractions = autautMF.validateEvent(it, currentState)

            if (abstractInteractions.isNotEmpty())
            {
                targetEvents.put(it,abstractInteractions)
            }
        }
        return targetEvents.map { it.value }.flatten().toSet()
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
    var needResetApp = false
    var actionCountSinceSelectTarget: Int = 0


    private fun isCountAction(chosenAction: ExplorationAction) =
           true

    private fun isTargetWindow(currentAppState: AbstractState): Boolean {
        return targetWindowTryCount.filterNot {
            flaggedWindows.contains(it.key) || fullyCoveredWindows.contains(it.key) || unreachableWindows.contains(it.key)
                    || currentAppState.window == targetWindow
        }.any { it.key == currentAppState.window }
    }

    var numOfContinousTry = 0


    private fun shouldChangeTargetWindow() = autautMF.updateMethodCovFromLastChangeCount > 25 * budgetScale

    private fun setFullyRandomExplorationInTargetWindow(randomExplorationTask: RandomExplorationTask, currentState: State<*>, currentAppState: AbstractState) {
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

    private fun hasBudgetLeft(window: WTGNode): Boolean {
        if (!windowRandomExplorationBudget.containsKey(window))
            return true
        if (windowRandomExplorationBudgetUsed[window]!! <= windowRandomExplorationBudget[window]!!)
            return true
        return false
    }

    private fun hasBudgetLeft2(currentState: State<*>?, currentAbstractState: AbstractState): Boolean {
        if (hasBudgetLeft(currentAbstractState.window))
            return true
//        if (currentAbstractState.getUnExercisedActions(currentState).filter { it.isWidgetAction() }.isNotEmpty()) {
//            return true
//        }
       /* if (!windowRandomExplorationBudget2.containsKey(abstractState))
            return true
        val totalActionCount = getAbstractStateExecutedActionsCount(abstractState)
        if (totalActionCount < windowRandomExplorationBudget2[abstractState]!!) {
            return true
        }*/

        return false
    }

    private fun getAbstractStateExecutedActionsCount(abstractState: AbstractState) =
            abstractState.getActionCountMap().filter {it.key.isWidgetAction()} .map { it.value }.sum()

    private fun isLoginWindow(currentAppState: AbstractState): Boolean {
        val activity = currentAppState.window.classType.toLowerCase()
        return activity.contains("login") || activity.contains("signin")
    }

    private fun setRandomExploration(randomExplorationTask: RandomExplorationTask,
                                     currentState: State<*>,
                                     currentAbstractState: AbstractState,
                                     stopWhenTestPathIdentified: Boolean = false,
                                     lockWindow: Boolean = true) {
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.stopWhenHavingTestPath = stopWhenTestPathIdentified
            val randomBudgetLeft = windowRandomExplorationBudget[currentAbstractState.window]!! - windowRandomExplorationBudgetUsed[currentAbstractState.window]!!
            val minRandomBudget = (5 * budgetScale).toInt()
            if (randomBudgetLeft <= minRandomBudget) {
                it.setMaxiumAttempt(minRandomBudget)
            } else {
                it.setMaxiumAttempt(randomBudgetLeft)
            }
            if (lockWindow)
                it.lockTargetWindow(currentAbstractState.window)
        }
        log.info("Random exploration")
        phaseState = PhaseState.P1_RANDOM_EXPLORATION

    }

    private fun setFullyRandomExploration(randomExplorationTask: RandomExplorationTask, currentState: State<*>, currentAbstractState: AbstractState) {
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.isFullyExploration = true
            val randomBudgetLeft = windowRandomExplorationBudget[currentAbstractState.window]!! - windowRandomExplorationBudgetUsed[currentAbstractState.window]!!
            val minRandomBudget = (5 * budgetScale).toInt()
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

    private fun setRandomExplorationInTargetWindow(randomExplorationTask: RandomExplorationTask, currentState: State<*>) {
        val currentAbstractState = autautMF.getAbstractState(currentState)!!
        strategyTask = randomExplorationTask.also {
            it.initialize(currentState)
            it.lockTargetWindow(targetWindow!!)
            val randomBudgetLeft = windowRandomExplorationBudget[currentAbstractState.window]!! - windowRandomExplorationBudgetUsed[currentAbstractState.window]!!
            val minRandomBudget = (5 * budgetScale).toInt()
            if (randomBudgetLeft <= minRandomBudget) {
                it.setMaxiumAttempt(minRandomBudget)
            } else {
                it.setMaxiumAttempt(randomBudgetLeft)
            }
        }
        log.info("This window is a target window but cannot find any target event")
        log.info("Random exploration in current window")
        phaseState = PhaseState.P1_RANDOM_IN_EXERCISE_TARGET_NODE
    }

    fun getTargetAbstractStates(currentNode: AbstractState): ArrayList<AbstractState>
    {
        val candidates = ArrayList<AbstractState>()
        val excludedNode = currentNode
        candidates.add( AbstractStateManager.instance.ABSTRACT_STATES.find{it is VirtualAbstractState && it.window == targetWindow}!!)
        //Get all AbstractState contain target events
        AbstractStateManager.instance.ABSTRACT_STATES
                .filter {
                    it.window == targetWindow &&
                            hasBudgetLeft2(null,it)
                }
                .forEach {
                    val hasUntriggeredTargetEvent: Boolean
                    hasUntriggeredTargetEvent = isTargetAbstractState(it)
                    if (hasUntriggeredTargetEvent)
                        candidates.add(it)
        }
        return candidates
    }

    private fun isTargetAbstractState(abstractState: AbstractState): Boolean {
        val abstractActions = autautMF.abstractTransitionGraph.edges(abstractState).filter { edge ->
            abstractState.staticEventMapping.contains(edge.label.abstractAction)
        }.map { it.label.abstractAction }
        val staticEvents = abstractState.staticEventMapping.filter { abstractActions.contains(it.key) }.map { it.value }
        if (staticEvents.find { untriggeredTargetEvents.intersect(it).isNotEmpty() } != null) {
            return true
        } else {
            return false
        }
    }

    companion object {

        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(PhaseOneStrategy::class.java) }



    }
}