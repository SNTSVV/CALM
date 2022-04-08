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

class PhaseOneStrategy(
    atuaTestingStrategy: ATUATestingStrategy,
    budgetScale: Double,
    delay: Long,
    useCoordinateClicks: Boolean
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

    val untriggeredWidgets = arrayListOf<EWTGWidget>()
    val phaseTargetInputs = arrayListOf<Input>()

    var attemps: Int
    var targetWindow: Window? = null
    val outofbudgetWindows = HashSet<Window>()
    val unreachableWindows = HashSet<Window>()
    val fullyCoveredWindows = HashSet<Window>()
    val fullyExploredWindows = HashSet<Window>()
    val reachedWindow = HashSet<Window>()
    val targetWindowTryCount: HashMap<Window, Int> = HashMap()
    val targetInputTryCount: HashMap<Input, Int> = HashMap()
    val windowRandomExplorationBudget: HashMap<Window, Int> = HashMap()
    val windowRandomExplorationBudgetUsed: HashMap<Window, Int> = HashMap()
    val windowRandomExplorationBudget2: HashMap<AbstractState, Int> = HashMap()
    val windowRandomExplorationBudgetUsed2: HashMap<AbstractState, Int> = HashMap()
    val window_Widgets = HashMap<Window, HashSet<Widget>>()
    val inputEffectiveness = HashMap<Input, Double>()

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
        atuaMF.notFullyExercisedTargetInputs. forEach {
            val maxModifiedMethodsCnt = atuaMF.modifiedMethodsByWindow[it.sourceWindow]?.size ?: 0
            if (atuaMF.reuseBaseModel) {
                val usefullness = ModelHistoryInformation.INSTANCE.inputUsefulness[it]
                val usefullScore = if (usefullness != null && usefullness!!.second == 0)
                    0.0
                else if (usefullness == null && it.widget != null) {
                    if (!EWTGDiff.instance.getAddedWidgets().contains(it.widget!!)) {
                        0.0
                    } else {
                        1.0
                    }
                } else
                    1.0
                if (usefullScore == 1.0) {
                    val score = (it.modifiedMethods.keys.size + 1) * 1.0 / maxModifiedMethodsCnt
                    inputEffectiveness.put(it, score)
                    phaseTargetInputs.add(it)
                    targetInputTryCount.put(it, 0)
                }
            } else {
                inputEffectiveness.put(it, 1.0)
                phaseTargetInputs.add(it)
                targetInputTryCount.put(it, 0)
            }
        }
    }

    var recentTargetEvent: Input? = null
    override fun registerTriggeredInputs(abstractAction: AbstractAction, guiState: State<*>) {
        val abstractState = AbstractStateManager.INSTANCE.getAbstractState(guiState)!!
        //val abstractInteractions = regressionTestingMF.abstractTransitionGraph.edges(abstractState).filter { it.label.abstractAction.equals(abstractAction) }.map { it.label }

        val inputs = abstractState.getInputsByAbstractAction(abstractAction)
        if (inputs.isEmpty() || phaseTargetInputs.intersect(inputs).isEmpty()) {
            log.warn("No input is mapped with this abstract action")
        }
        inputs.forEach {
            if (phaseTargetInputs.contains(it)) {
                recentTargetEvent = it
                targetInputTryCount[it] = targetInputTryCount[it]!! + 1
                if (it.eventType.isItemEvent && targetInputTryCount[it]!! >= 3) {
                    phaseTargetInputs.remove(it)
                } else if (!it.eventType.isItemEvent) {
                    if (abstractAction.isWidgetAction() && abstractState.avmCardinalities[abstractAction.attributeValuationMap!!] == Cardinality.MANY) {
                        if (targetInputTryCount[it]!! >= 3) {
                            phaseTargetInputs.remove(it)
                        }
                    } else {
                        phaseTargetInputs.remove(it)
                    }
                }
            }
        }
    }


    override fun hasNextAction(currentState: State<*>): Boolean {

        //For debug, end phase one after 100 actions
        /*if (autAutTestingStrategy.eContext.explorationTrace.getActions().size > 100)
            return true*/

        if (atuaMF.lastUpdatedStatementCoverage == 1.0) {
            return false
        }
        phaseTargetInputs.removeIf { (it.exerciseCount > 0 && !it.eventType.isItemEvent) || it.exerciseCount > 3 }
        if (atuaMF.appPrevState!!.isRequestRuntimePermissionDialogBox
            && atuaTestingStrategy.eContext.getLastActionType() != "ResetApp"
        ) {
            if (recentTargetEvent != null) {
                phaseTargetInputs.add(recentTargetEvent!!)
                targetInputTryCount.putIfAbsent(recentTargetEvent!!, 0)
                recentTargetEvent = null
            }
        } else {
            recentTargetEvent = null
        }
        updateUnreachableWindows(currentState)
        updateRandomBudgetForWindow(currentState)
        if (!AbstractStateManager.INSTANCE.ABSTRACT_STATES.any {
                it.guiStates.isNotEmpty()
                        && it.attributeValuationMaps.isNotEmpty()
            })
            return true
        if (episodeCountDown == 0) {
            updateTargetWindows()
            updateOutOfBudgetWindows()
            episodeCountDown = 5
        }
        if (forceEnd)
            return false
        val currentAbstractState = atuaMF.getAbstractState(currentState)
        if (currentAbstractState != null && (currentAbstractState.window is Dialog || currentAbstractState.window is OptionsMenu || currentAbstractState.window is OutOfApp))
            return true
        /*val remaingTargetWindows =
            targetWindowTryCount.filterNot { fullyCoveredWindows.contains(it.key) || outofbudgetWindows.contains(it.key) }
        if (remaingTargetWindows.isEmpty()
            && targetWindowTryCount.isNotEmpty()
            && atuaMF.statementMF!!.executedModifiedMethodsMap.size == atuaMF.statementMF!!.modMethodInstrumentationMap.size
        ) {
            return false
        }
        reachedWindow*/
        /*if (windowRandomExplorationBudget.keys.union(targetWindowTryCount.keys)
                .subtract(outofbudgetWindows.union(unreachableWindows)).isEmpty()
        )
            return false*/
        if (delayCheckingBlockStates > 0) {
            delayCheckingBlockStates--
            return true
        }
        delayCheckingBlockStates = 5
       /* return isAvailableAbstractStatesExisting(currentState).also {
            if (it == false) {
                log.debug("No available abstract states to explore.")
            }
        }*/
        return true
    }


    private fun updateUnreachableWindows(currentState: State<*>) {
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        if (unreachableWindows.contains(currentAppState.window)) {
            unreachableWindows.remove(currentAppState.window)
        }
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

    private fun updateRandomBudgetForWindow(currentState: State<*>) {
        //val currentAppState = autautMF.getAbstractState(currentState)!!
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        val window = currentAppState.window
        if (window is Launcher) {
            return
        }
        if (!window_Widgets.containsKey(window)) {
            window_Widgets.put(window, HashSet())
        }
        val widgets = window_Widgets[window]!!
        val newWidgets = ArrayList<Widget>()

        val interactableWidgets = Helper.getActionableWidgetsWithoutKeyboard(currentState).filter {
            (it.clickable || it.longClickable || it.className == "android.webkit.WebView")
                    && (!Helper.hasParentWithType(
                it,
                currentState,
                "WebView"
            )
                    || it.resourceId.isNotBlank())
                    && !Helper.isUserLikeInput(it) && !it.isInputField
        }
/*
        interactableWidgets.filter { w -> !widgets.any { it.uid == w.uid } }.forEach { w ->
            if (!widgets.any { w.uid == it.uid }) {
                widgets.add(w)
            }
            if (!newWidgets.any { w.uid == it.uid })
                newWidgets.add(w)
        }
*/
        if (widgets.isEmpty()) {
            interactableWidgets.forEach { w ->
                widgets.add(w)
                newWidgets.add(w)
            }
        } else {
/*            if (currentAppState.isOpeningMenus) {
                interactableWidgets.forEach { w ->
                    //interactableWidgets.filter { w -> !widgets.any { it.uid == w.uid } }.forEach { w ->
                    if (!widgets.any { it.id == w.id }) {
                        newWidgets.add(w)
                        widgets.add(w)
                    }
                }
            } else {
            }*/
            interactableWidgets.filter { it.resourceId.isNotBlank() }.forEach { w ->
                //interactableWidgets.filter { w -> !widgets.any { it.uid == w.uid } }.forEach { w ->
                if (!widgets.any { it.resourceId.equals( w.resourceId) }) {
                    newWidgets.add(w)
                    widgets.add(w)
                }
            }
        }
        if (!windowRandomExplorationBudget.containsKey(window)) {
            if (window is Activity)
                windowRandomExplorationBudget[window] = 4
            else
                windowRandomExplorationBudget[window] = 0
            windowRandomExplorationBudgetUsed[window] = 0
        }
        var newActions = 0
        newWidgets.forEach {
            /*if (it.visibleBounds.width > 200 && it.visibleBounds.height > 200 ) {
               newActions += it.availableActions(delay, useCoordinateClicks).filterNot { it is Swipe}.size
            } else {
                newActions += it.availableActions(delay, useCoordinateClicks).filterNot { it is Swipe }.size
            }*/
            newActions += 1
            if (it.className == "android.webkit.WebView") {
                newActions += (5).toInt()
            }
        }
        windowRandomExplorationBudget[window] =
            windowRandomExplorationBudget[window]!! + (newActions * scaleFactor).toInt()
        /*     if (window is OptionsMenu || window is Dialog || window is OutOfApp) {
                 val activityWindow = WindowManager.instance.allMeaningWindows.find { it is Activity && it.activityClass == window.activityClass }
                 if (activityWindow!=null && windowRandomExplorationBudget.containsKey(activityWindow))
                     windowRandomExplorationBudget[activityWindow] = windowRandomExplorationBudget[activityWindow]!! + (newActions*scaleFactor).toInt()
             }*/
        val newBudget = windowRandomExplorationBudget[window]!!
        ExplorationTrace.widgetTargets.clear()
        val toRemove = ArrayList<Window>()
        outofbudgetWindows.forEach {
            if (hasBudgetLeft(it)) {
                toRemove.add(it)
            }
        }
        toRemove.forEach {
            outofbudgetWindows.remove(it)
        }
        windowRandomExplorationBudget.filter { !outofbudgetWindows.contains(it.key) }.forEach { t, u ->
            if (windowRandomExplorationBudgetUsed[t]!! > windowRandomExplorationBudget[t]!!) {
                outofbudgetWindows.add(t)
//                if (t is Activity) {
//                    val optionsMenu = atuaMF.wtg.getOptionsMenu(t)
//                    if (optionsMenu != null) {
//                        outofbudgetWindows.add(optionsMenu)
//                    }
//                    val dialogs = atuaMF.wtg.getDialogs(t)
//                    outofbudgetWindows.addAll(dialogs)
//                }
            }
        }

        val minBudget = window.inputs.filter {
            it.exerciseCount>0
                    && (it.modifiedMethods.isEmpty() || it.exercisedInThePast)
        }.size
        /*val minBudget = widgets.fold(0) {cnt, w ->
            val minExercisedCnt = atuaMF.actionCount.wConcreteIdCount[w.id]?.filter { it.key == window.classType }?.map { it.value }?.maxOrNull()?:0
            if (minExercisedCnt > 0)
                cnt + 1
            else
                cnt
        }*/
        if (windowRandomExplorationBudgetUsed[window]!! < minBudget)
            windowRandomExplorationBudgetUsed[window] = minBudget
        if (fullyExploredWindows.contains(currentAppState.window)) {
            val unexercisedInputs = ArrayList<Input>()
            val unexercisedActions = currentAppState.getUnExercisedActions2(currentState).filter { it.isWidgetAction()}
            val inputs = unexercisedActions.map { action->currentAppState.getInputsByAbstractAction(action) }.flatten()
            inputs.forEach {
                if (!unexercisedInputs.contains(it)) {
                    unexercisedInputs.add(it)
                }
            }
            val replacingWidgets = EWTGDiff.instance.getReplacingWidget()
            val replacingInputs = EWTGDiff.instance.replacingInputs
            if (!unexercisedInputs.all { it.exercisedInThePast
                        && it.eventType != EventType.swipe
                        && it.modifiedMethods.isEmpty()
                        && (!replacingWidgets.contains(it.widget) && !replacingInputs.contains(it))  }) {
                fullyExploredWindows.remove(currentAppState.window)
            }
        }
    }

    private fun updateOutOfBudgetWindows() {
        val availableAbState_Window = AbstractStateManager.INSTANCE.ABSTRACT_STATES
            .filter {
                it !is VirtualAbstractState
                        && it.ignored == false
                        && it.window !is OutOfApp
                        && it.window !is Launcher
                        && it.attributeValuationMaps.isNotEmpty()
                        && it.activated
//                        && (it.guiStates.isNotEmpty())
                        && !outofbudgetWindows.contains(it.window)
                        && !fullyExploredWindows.contains(it.window)
                        && !unreachableWindows.contains(it.window)
                        && windowRandomExplorationBudget.containsKey(it.window)
            }.groupBy { it.window }
        val replacingWidgets = EWTGDiff.instance.getReplacingWidget()
        val replacingInputs = EWTGDiff.instance.replacingInputs
        availableAbState_Window.forEach {
//            val allActions = it.value.map { it.getAvailableActions() }.flatten()
            if (it.value.all {
                    it.getUnExercisedActions(null,atuaMF).filter { action ->
                    !action.isCheckableOrTextInput(it)
                            && it.getInputsByAbstractAction(action).any { it.meaningfulScore>0 }}.isEmpty() }) {
                fullyExploredWindows.add(it.key)
            } else if (atuaMF.reuseBaseModel && !targetWindowTryCount.containsKey(it.key)) {
                val unexercisedInputs = ArrayList<Input>()
                val unexercisedActions = ArrayList<AbstractAction>()
                it.value.forEach {
                    val unexercisedActons = it.getUnExercisedActions(null,atuaMF).filter { action ->
                        !action.isCheckableOrTextInput(it)
                                && it.getInputsByAbstractAction(action).any { it.meaningfulScore>0 }}
                    val inputs = unexercisedActons.map { action->it.getInputsByAbstractAction(action) }.flatten()
                    inputs.forEach {
                        if (!unexercisedInputs.contains(it)) {
                            unexercisedInputs.add(it)
                        }
                    }
                }
                if (unexercisedInputs.all { it.exercisedInThePast
                            && !replacingWidgets.contains(it.widget)
                            && !replacingInputs.contains(it)
                            && !phaseTargetInputs.contains(it)
                            }) {
                    fullyExploredWindows.add(it.key)
                }
            }
        }
    }

    private fun updateTargetWindows() {
        phaseTargetInputs.removeIf { !atuaMF.notFullyExercisedTargetInputs.contains(it) }
        targetWindowTryCount.entries.removeIf {
            !atuaMF.modifiedMethodsByWindow.containsKey(it.key)
                    || it.key is Launcher
        }
        targetWindowTryCount.keys.filterNot { fullyCoveredWindows.contains(it) }.forEach { window ->
            val isWitnessed = AbstractStateManager.INSTANCE.ABSTRACT_STATES.any {
                it !is VirtualAbstractState
                        && it.ignored == false
                        && it.window == window
                        && it.guiStates.isNotEmpty()
            }
            if (isWitnessed) {
                var coverCriteriaCount = 0
                if (phaseTargetInputs.filter { input -> input.sourceWindow == window }.isEmpty()) {
                    coverCriteriaCount++
                }
                if (atuaMF.modifiedMethodsByWindow[window]!!.all { atuaMF.statementMF!!.executedMethodsMap.contains(it) }
                ) {
                    coverCriteriaCount++
                }
                val windowTargetHandlers = atuaMF.allTargetHandlers.intersect(
                    atuaMF.windowHandlersHashMap[window] ?: emptyList()
                )
                val untriggeredHandlers =
                    windowTargetHandlers.subtract(atuaMF.statementMF!!.executedMethodsMap.keys)
                if (untriggeredHandlers.isEmpty()) {
                    // all target hidden handlers are triggered
                    coverCriteriaCount++
                }
                if (coverCriteriaCount >= 3) {
                    if (!fullyCoveredWindows.contains(window)) {
                        fullyCoveredWindows.add(window)
                    }
                }
            }

        }
    }

    override fun nextAction(eContext: ExplorationContext<*, *, *>): ExplorationAction {
        atuaMF.dstg.cleanPredictedAbstractStates()
        //TODO Update target windows
        episodeCountDown--
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
        updateCurrentTargetWindow(currentState, currentAppState)
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

    private fun PhaseOneStrategy.updateCurrentTargetWindow(
        currentState: State<*>,
        currentAppState: AbstractState
    ) {
        val explicitTargetWindows =
            phaseTargetInputs.filter { it.widget?.witnessed ?: true }.map { it.sourceWindow }.distinct()
        val isTargetAppState = getCurrentTargetInputs(currentState).isNotEmpty()
        if (targetWindow != null ) {
            if (!isTargetAppState || currentAppState.window!=targetWindow || !isCandidateWindow(targetWindow!!)) {
                targetWindow = null
                resetStrategyTask(currentState)
            }
        }
        if (targetWindow != currentAppState.window) {
            if (isTargetAppState) {
                resetStrategyTask(currentState)
                targetWindow = currentAppState.window
                log.info("Switch target window to $targetWindow")
            } else if (strategyTask !is GoToTargetWindowTask) {
                if (isAvailableTargetWindow(currentAppState)) {
                    if (!explicitTargetWindows.contains(targetWindow)
                        && !explicitTargetWindows.contains(currentAppState.window)
                        && !outofbudgetWindows.contains(currentAppState.window)
                        && !fullyExploredWindows.contains(currentAppState.window)
                    ) {
                        resetStrategyTask(currentState)
                        targetWindow = currentAppState.window
                        log.info("Switch target window to $targetWindow")
                    } else if (!explicitTargetWindows.contains(targetWindow)
                        && explicitTargetWindows.contains(currentAppState.window)
                        && !outofbudgetWindows.contains(currentAppState.window)
                        && !fullyExploredWindows.contains(currentAppState.window)
                    ) {
                        resetStrategyTask(currentState)
                        targetWindow = currentAppState.window
                        log.info("Switch target window to $targetWindow")
                    } else if (explicitTargetWindows.contains(targetWindow)
                        && explicitTargetWindows.contains(currentAppState.window)
                        && !outofbudgetWindows.contains(currentAppState.window)
                        && !fullyExploredWindows.contains(currentAppState.window)
                    ) {
                        if (getCurrentTargetInputs(currentState).isNotEmpty()) {
                            resetStrategyTask(currentState)
                            targetWindow = currentAppState.window
                            log.info("Switch target window to $targetWindow")
                        } else if (strategyTask !is GoToTargetWindowTask) {
                            resetStrategyTask(currentState)
                            targetWindow = currentAppState.window
                            log.info("Switch target window to $targetWindow")
                        }
                    }
                }
            }
        }
        if (episodeCountDown == 0 && targetWindow != null && !explicitTargetWindows.contains(targetWindow!!)) {
            val oldTargetWindow = targetWindow!!
            if (episodeCountDown == 0
                && explicitTargetWindows.isNotEmpty()
                && explicitTargetWindows.any {
                    isAvailableTargetWindow(
                        it
                    )
                }) {
                selectTargetWindow(currentState, true).also {
                    if (targetWindow != null
                        && targetWindow != oldTargetWindow
                        && explicitTargetWindows.contains(targetWindow!!)
                    ) {
                        resetStrategyTask(currentState)
                        log.info("Switch target window to $targetWindow")
                    } else {
                        targetWindow = oldTargetWindow
                    }
                }
            }
        } else {
            if (targetWindow == null) {
                //try select a target window
                selectTargetWindow(currentState, false).also {
                    if (targetWindow != null) {
                        resetStrategyTask(currentState)
                        log.info("Switch target window to $targetWindow")
                    }
                }
            }
        }
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

        log.debug("${currentAppState.window} - Budget: ${windowRandomExplorationBudgetUsed[currentAppState.window]}/${windowRandomExplorationBudget[currentAppState.window]}")

        if (targetWindow == null) {
            nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherNode)
            return
        }
        if (phaseState == PhaseState.P1_INITIAL || strategyTask == null) {
            nextActionOnInitial(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        if (phaseState == PhaseState.P1_EXERCISE_TARGET_NODE) {
            nextActionOnExerciseTargetWindow(
                currentAppState,
                currentState,
                exerciseTargetComponentTask,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        if (phaseState == PhaseState.P1_RANDOM_IN_EXERCISE_TARGET_NODE) {
            nextActionOnRandomInTargetWindow(
                currentAppState,
                randomExplorationTask,
                exerciseTargetComponentTask,
                currentState,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        if (phaseState == PhaseState.P1_RANDOM_EXPLORATION) {
            nextActionOnRandomExploration(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        if (phaseState == PhaseState.P1_GO_TO_TARGET_NODE) {
            nextActionOnGoToTargetNode(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return

        }
        if (phaseState == PhaseState.P1_GO_TO_EXPLORE_STATE) {
            nextActionOnGoToExploreState(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        selectTargetWindow(currentState, true)
        if (targetWindow == null)
            selectTargetWindow(currentState, false)
        phaseState = PhaseState.P1_INITIAL
        //needResetApp = true
        return

    }

    private fun nextActionWithoutTargetWindow(
        currentState: State<*>,
        currentAppState: AbstractState,
        randomExplorationTask: RandomExplorationTask,
        goToExploreWindows: GoToAnotherWindowTask
    ) {
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState) && it.isWidgetAction()
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (strategyTask !is RandomExplorationTask && meaningfulAbstractActions.isNotEmpty() && currentAppState.window.meaningfullScore>0
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, false)
            return
        }
       /* if (strategyTask is RandomExplorationTask
            && !currentAppState.isRequireRandomExploration()
            && meaningfulAbstractActions.isEmpty()
            && !hasBudgetLeft(currentAppState.window) ) {
            if (exploreApp(currentState, goToExploreWindows))
                return
        }*/

        if (strategyTask != null) {
            if (continueOrEndCurrentTask(currentState)) return
        }
        if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
        if (meaningfulAbstractActions.isNotEmpty() && currentAppState.window.meaningfullScore > 0
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, false)
            return
        }
        if (hasBudgetLeft(currentAppState.window)) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        //Current window has no more random exploration budget
        /*if (strategyTask is GoToAnotherWindowTask) {
            if ((strategyTask as GoToAnotherWindowTask).isReachExpectedState(currentState)){
                setRandomExploration(randomExplorationTask, currentState, false, true)
                return
            }
        }*/
        if (exploreApp(currentState, goToExploreWindows))
            return
        setRandomExploration(randomExplorationTask, currentState, false, false)
        forceEnd = true
        return
    }

    private fun nextActionOnInitial(
        currentAppState: AbstractState,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask,
        goToAnotherNode: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState)) return
            // In case target events not found
            if (goToTargetNodeTask.isAvailable(
                    currentState = currentState,
                    destWindow = targetWindow!!,
                    isWindowAsTarget = false,
                    isExploration = false
                )
            ) {
                if (goToTargetNodeTask.possiblePaths.any { isTargetAbstractState(it.getFinalDestination(), false) }) {
                    setGoToTarget(goToTargetNodeTask, currentState, false)
                    return
                }
            }
            // Try random exploration
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
        //unreachableWindows.add(targetWindow!!)

        //if (goToWindowToExploreOrRandomExploration(currentAppState, goToAnotherNode, currentState, randomExplorationTask)) return
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = false,
                destWindow = targetWindow!!,
                isExploration = false
            )
        ) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState) && it.isWidgetAction()
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherNode))
            return
        setFullyRandomExploration(randomExplorationTask, currentState, currentAppState)
        return
    }

    private fun nextActionOnExerciseTargetWindow(
        currentAppState: AbstractState,
        currentState: State<*>,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        randomExplorationTask: RandomExplorationTask,
        goToAnotherNode: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (continueOrEndCurrentTask(currentState)) return
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState)) return
            // In case target events not found
            if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
            val bkTargetWindow = targetWindow
            selectTargetWindow(currentState, true)
            if (targetWindow == null) {
                targetWindow = bkTargetWindow
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return
            } else {
                log.info("Switch target window to $targetWindow")
                resetStrategyTask(currentState)
                nextActionOnInitial(
                    currentAppState,
                    exerciseTargetComponentTask,
                    currentState,
                    randomExplorationTask,
                    goToAnotherNode,
                    goToTargetNodeTask
                )
                return
            }
        }
        if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
        val bkTargetWindow = targetWindow
        selectTargetWindow(currentState, true)
        if (targetWindow == null)
            targetWindow = bkTargetWindow
        else {
            log.info("Switch target window to $targetWindow")
            resetStrategyTask(currentState)
            nextActionOnInitial(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        // Try random exploration

        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = false,
                isExploration = false,
                isWindowAsTarget = false
            )
        ) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }

        //unreachableWindows.add(targetWindow!!)
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = false,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = true
            )
        ) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState) && it.isWidgetAction()
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherNode)) {
            return
        }
        setFullyRandomExploration(randomExplorationTask, currentState, currentAppState)
        return
    }

    private fun exploreApp(currentState: State<*>, goToAnotherWindow: GoToAnotherWindowTask): Boolean {
        if (goToAnotherWindow.isAvailable(currentState)) {
            setGoToExploreState(goToAnotherWindow, currentState)
            return true
        }
        log.info("No available abstract states to explore.")
        return false
    }

    private fun nextActionOnGoToTargetNode(
        currentAppState: AbstractState,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask,
        goToAnotherNode: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState)) return
            if (continueOrEndCurrentTask(currentState)) return
            /*if (hasBudgetLeft(currentAppState.window)
                && currentAppState.getUnExercisedActions(currentState, atuaMF)
                    .isNotEmpty()
            ) {
            }*/
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        if (continueOrEndCurrentTask(currentState)) return
        if (currentAppState.window != targetWindow)
            unreachableWindows.add(targetWindow!!)
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState)) return
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        if (targetWindow!! !is Dialog && randomExplorationInSpecialWindows(
                currentAppState,
                randomExplorationTask,
                currentState
            )
        ) return
        val bkTargetWindow = targetWindow
        selectTargetWindow(currentState, true)
        if (targetWindow != null && targetWindow != bkTargetWindow) {
            log.info("Switch target window to $targetWindow")
            resetStrategyTask(currentState)
            nextActionOnInitial(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        targetWindow = bkTargetWindow
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState) && it.isWidgetAction()
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState,goToAnotherNode))
            return
        targetWindow = null
        strategyTask = null
        nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherNode)
        return
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

    private fun nextActionOnGoToExploreState(
        currentAppState: AbstractState,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask,
        goToAnotherNode: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState)) return
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        val bkTargetWindow = targetWindow
        selectTargetWindow(currentState, true)
        if (targetWindow == null)
            targetWindow = bkTargetWindow
        else {
            log.info("Switch target window to $targetWindow")
            resetStrategyTask(currentState)
            nextActionOnInitial(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState) && it.isWidgetAction()
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (continueOrEndCurrentTask(currentState)) return
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = false,
                destWindow = targetWindow!!,
                isExploration = false
            )
        ) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        if (exploreApp(currentState, goToAnotherNode))
            return
        targetWindow = null
        strategyTask = null
        nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherNode)
        /*unreachableWindows.add(targetWindow!!)
        val oldTarget = targetWindow
        selectTargetWindow(currentState, true)
        if (targetWindow == null)
            selectTargetWindow(currentState, false)
        if (oldTarget != targetWindow) {
            phaseState = PhaseState.P1_INITIAL
            setRandomExploration(randomExplorationTask, currentState)
            return
        }*/
//        forceEnd = true
        return
    }

    private fun nextActionOnRandomInTargetWindow(
        currentAppState: AbstractState,
        randomExplorationTask: RandomExplorationTask,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        currentState: State<*>,
        goToAnotherNode: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState)) return
            if (continueRandomExplorationIfIsFillingData(randomExplorationTask)) return
            if (continueOrEndCurrentTask(currentState)) return
            if (goToTargetNodeTask.isAvailable(
                    currentState = currentState,
                    isWindowAsTarget = false,
                    destWindow = targetWindow!!,
                    isExploration = true
                )
            ) {
                if (goToTargetNodeTask.possiblePaths.any { isTargetAbstractState(it.getFinalDestination(), false) }) {
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
            }
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        if (continueOrEndCurrentTask(currentState)) return
        if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
        val bkTargetWindow = targetWindow
        selectTargetWindow(currentState, true)
        if (targetWindow == null)
            targetWindow = bkTargetWindow
        else {
            log.info("Switch target window to $targetWindow")
            resetStrategyTask(currentState)
            nextActionOnInitial(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = false,
                isExploration = false,
                isWindowAsTarget = false
            )
        ) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = true,
                isExploration = false,
                isWindowAsTarget = false
            )
        ) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        //unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState) && it.isWidgetAction()
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherNode)) {
            return
        }
        targetWindow = null
        strategyTask = null
        nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherNode)
        return
    }

    private fun doRandomExplorationIfHaveUnexercisedActions(
        currentAppState: AbstractState,
        goToAnotherNode: GoToAnotherWindowTask,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask
    ): Boolean {
        if (hasBudgetLeft(currentAppState.window) && currentAppState.getUnExercisedActions(currentState, atuaMF)
                .isNotEmpty()
        ) {
            setRandomExploration(randomExplorationTask, currentState, true, false)
            return true
        }
        return false
    }

    private fun selectAnotherTargetIfFullyExploration(
        randomExplorationTask: RandomExplorationTask,
        currentState: State<*>
    ): Boolean {
        if (randomExplorationTask.isPureRandom || shouldChangeTargetWindow()) {
            selectTargetWindow(currentState, false)
            phaseState = PhaseState.P1_INITIAL
            //needResetApp = true
            return true
        }
        return false
    }

    private fun nextActionOnDialog(
        currentAppState: AbstractState,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ): Boolean {
        if (currentAppState.window is Dialog || currentAppState.window is OptionsMenu || currentAppState.window is OutOfApp) {
            setRandomExploration(randomExplorationTask, currentState, true, lockWindow = false)
            return true
        }
        return false
    }

    private fun nextActionOnRandomExploration(
        currentAppState: AbstractState,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask,
        goToAnotherNode: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (currentAppState.window == targetWindow) {
            if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState)) return
            if (continueRandomExplorationIfIsFillingData(randomExplorationTask)) return
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            return
        }
        if (continueRandomExplorationIfIsFillingData(randomExplorationTask)) return

        if (randomExplorationTask.stopWhenHavingTestPath
        ) {
            if (goToTargetNodeTask.isAvailable(
                    currentState = currentState,
                    destWindow = targetWindow!!,
                    includePressback = true,
                    includeResetApp = false,
                    isExploration = false,
                    isWindowAsTarget = true
                )
            ) {
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
        }
        if(continueOrEndCurrentTask(currentState))
                return
        if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = false,
                isExploration = false,
                isWindowAsTarget = true
            )
        ) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = true,
                isExploration = false,
                isWindowAsTarget = false
            )
        ) {
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        val bkTargetWindow = targetWindow
        selectTargetWindow(currentState, true)
        if (targetWindow != null) {
            log.info("Switch target window to $targetWindow")
            resetStrategyTask(currentState)
            nextActionOnInitial(
                currentAppState,
                exerciseTargetComponentTask,
                currentState,
                randomExplorationTask,
                goToAnotherNode,
                goToTargetNodeTask
            )
            return
        } else {
            targetWindow = bkTargetWindow
        }
//        unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState) && it.isWidgetAction()
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (meaningfulAbstractActions.isNotEmpty()) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherNode)) {
            return
        }
        targetWindow = null
        strategyTask = null
        nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherNode)
        /*val oldTarget = targetWindow
        selectTargetWindow(currentState, true)
        if (targetWindow == null)
            selectTargetWindow(currentState, false)
        if (oldTarget != targetWindow ) {
            phaseState = PhaseState.P1_INITIAL
            return
        }*/
//        forceEnd = true
        return
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

    private fun randomlyExploreTargetIfHasBudgetAndUnexploredWidgets(
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask
    ): Boolean {
        if (hasBudgetLeft(targetWindow!!) && hasUnexploreWidgets(currentState)) {
            setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            //setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
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


    override fun isTargetState(currentState: State<*>): Boolean {
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        if (isTargetAbstractState(currentAbstractState, checkCurrentState = true)) {
            return getCurrentTargetInputs(currentState).isNotEmpty()
        }
        if (atuaMF.untriggeredTargetHiddenHandlers.intersect(
                atuaMF.windowHandlersHashMap[currentAbstractState.window] ?: emptyList()
            ).isNotEmpty()
        ) {
            if (hasBudgetLeft(currentAbstractState.window)
                || currentAbstractState.getUnExercisedActions(
                    currentState,
                    atuaMF
                ).isNotEmpty()
            )
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


    private fun selectTargetWindow(currentState: State<*>, considerContainingTargetInputsOnly: Boolean) {
        log.info("Trying to select nearest target...")
        //Try finding reachable target
        val maxTry = targetWindowTryCount.size / 2 + 1

        var candidates =
            targetWindowTryCount.filter { isExplicitCandidateWindow(it.key) }.map { it.key }

        if (candidates.isEmpty() && !considerContainingTargetInputsOnly) {
            candidates = targetWindowTryCount.filter { isCandidateWindow(it.key) }.map { it.key }
        }
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        val stateWithGoals = HashMap<AbstractState, List<Goal>>()
        val stateWithScore = HashMap<AbstractState, Double>()

        candidates.forEach { window ->
            val targetInputs = ArrayList<Input>()
            val targetStates = getTargetAbstractStates(currentNode = currentAppState, window = window)
            targetStates.removeIf { (it.modelVersion != ModelVersion.BASE && it.guiStates.isEmpty()) || it == currentAppState }
            targetStates.filterNot {
                AbstractStateManager.INSTANCE.unreachableAbstractState.contains(it)
            }.forEach {
                val allTargetInputs = it.getAvailableInputs().filter {
                    phaseTargetInputs.contains(it)
                }
                targetInputs.addAll(allTargetInputs)

            }
            if (targetInputs.isNotEmpty()) {
                var virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window)
                if (virtualAbstractState != null) {
                    virtualAbstractState =
                        AbstractStateManager.INSTANCE.createVirtualAbstractState(window, window.classType)
                    stateWithScore.put(virtualAbstractState, 1.0)
                    stateWithGoals.put(virtualAbstractState, targetInputs.distinct().map { Goal(input = it,abstractAction = null) })
                }
            }
        }
        if (stateWithGoals.isEmpty()) {
            candidates.forEach { windoww ->
                val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(windoww)
                if (virtualAbstractState != null)
                    stateWithScore.put(virtualAbstractState, 1.0)
            }
        }
        val shorestPaths = ArrayList<TransitionPath>()
        if (stateWithScore.isNotEmpty()) {
            val pathConstraints = HashMap<PathConstraint, Boolean>()
            pathConstraints.put(PathConstraint.INCLUDE_RESET, true)
            pathConstraints.put(PathConstraint.INCLUDE_LAUNCH, true)
            pathConstraints.put(PathConstraint.MAXIMUM_DSTG, true)
            getPathToStatesBasedOnPathType(
                pathType = PathFindingHelper.PathType.WIDGET_AS_TARGET,
                transitionPaths = shorestPaths,
                statesWithScore = stateWithScore,
                currentAbstractState = currentAppState,
                currentState = currentState,
                shortest = true,
                windowAsTarget = stateWithGoals.isEmpty(),
                goalByAbstractState = stateWithGoals,
                maxCost = ProbabilityBasedPathFinder.DEFAULT_MAX_COST,
                abandonedAppStates = emptyList(),
                pathConstraints = pathConstraints
            )
        }
        if (shorestPaths.isNotEmpty()) {
            val minPath = shorestPaths.minByOrNull { it.cost(final = true) }
            targetWindow = minPath!!.destination.window
        } else if (candidates.isNotEmpty()) {
            targetWindow = candidates.random()
        } else {
            targetWindow = null
        }

        log.info("Finish select nearest target...")

        actionCountSinceSelectTarget = 0
    }

    private fun isExplicitCandidateWindow(window: Window): Boolean {
        val explicitTargetWindows = WindowManager.instance.allMeaningWindows.filter { window ->
            phaseTargetInputs.any { it.sourceWindow == window && it.widget?.witnessed ?: true }
        }
        return explicitTargetWindows.contains(window)
                && !fullyCoveredWindows.contains(window)
                && !unreachableWindows.contains(window)
                && !outofbudgetWindows.contains(window)
                && !fullyExploredWindows.contains(window)
    }

    private fun isCandidateWindow(it: Window) =
//        !outofbudgetWindows.contains(it)
//                &&
    !fullyCoveredWindows.contains(it)
                && !unreachableWindows.contains(it)
                && !fullyExploredWindows.contains(it)

    override fun getPathsToExploreStates(
        currentState: State<*>,
        pathType: PathFindingHelper.PathType,
        maxCost: Double,
        pathConstraints: Map<PathConstraint,Boolean>
    ): List<TransitionPath> {
        val transitionPaths = ArrayList<TransitionPath>()
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)
        if (currentAbstractState == null)
            return transitionPaths
        val runtimeAbstractStates = ArrayList(getUnexhaustedExploredAbstractState(currentState))
        val goalByAbstractState = HashMap<AbstractState, List<Goal>>()
        runtimeAbstractStates.removeIf {
            (outofbudgetWindows.contains(it.window) && it.window is Dialog) ||
                      fullyExploredWindows.contains(it.window) ||
                    it.window.meaningfullScore <= 0 ||
                    (pathType != PathFindingHelper.PathType.FULLTRACE
                            && pathType != PathFindingHelper.PathType.PARTIAL_TRACE
                            && AbstractStateManager.INSTANCE.unreachableAbstractState.contains(it))
        }
        runtimeAbstractStates.groupBy { it.window }.forEach { window, appStates ->
            var virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(window)
            if (virtualAbstractState == null) {
                virtualAbstractState = AbstractStateManager.INSTANCE.createVirtualAbstractState(window,appStates.first().activity,appStates.first().isHomeScreen)
            }
            val toExploreInputs = ArrayList<Goal>()
            appStates.forEach { appState ->
                appState.getUnExercisedActions(null, atuaMF).filter { action->
                    !action.isCheckableOrTextInput(appState)
                            && action.isWidgetAction() }.forEach { action ->
                    val inputs = appState.getInputsByAbstractAction(action)
                    if (!ProbabilityBasedPathFinder.disableAbstractActions.contains(action) && inputs.any { it.meaningfulScore > 0 })
                        toExploreInputs.add(Goal(input = null,abstractAction = action))
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
            goalByAbstractState.entries.removeIf { it.key.window is Dialog }
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
        val targetStates = getTargetAbstractStates(currentNode = currentAbstractState, window = targetWindow!!)
        targetStates.removeIf { (it.modelVersion != ModelVersion.BASE && it.guiStates.isEmpty()) || it == currentAbstractState }
        val stateScores: HashMap<AbstractState, Double> = HashMap<AbstractState, Double>()
        val goalByAbstractState: HashMap<AbstractState, List<Goal>> = HashMap()
        val targetInputs = ArrayList<Input>()
        targetStates.filterNot {
            (pathType != PathFindingHelper.PathType.FULLTRACE
                    && pathType != PathFindingHelper.PathType.PARTIAL_TRACE
                    && AbstractStateManager.INSTANCE.unreachableAbstractState.contains(it))
        }.forEach {
            /*if (
                (it !is VirtualAbstractState && !it.isOpeningKeyboard) ||
                (it is VirtualAbstractState
                        &&
                        (pathType == PathFindingHelper.PathType.ANY
                                || pathType == PathFindingHelper.PathType.WTG))
            ) {
            }*/
            val targetInputsInAppState = it.getAvailableInputs().filter {
                phaseTargetInputs.contains(it) &&
                        (it.eventType != EventType.implicit_launch_event && it.eventType != EventType.resetApp)
            }
            targetInputsInAppState.forEach {
                if (!targetInputs.contains(it)) {
                    targetInputs.add(it)
                }
            }
        }

        val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(targetWindow!!)!!
        stateScores.put(virtualAbstractState, 1.0)
        goalByAbstractState.put(virtualAbstractState, targetInputs.distinct().map { Goal(input = it, abstractAction = null) })
        var windowAsTarget = false
        if (targetInputs.isEmpty()) {
            windowAsTarget = true
        }
        if (windowAsTarget && currentAbstractState.window == targetWindow!!)
            return emptyList()
        if (pathType == PathFindingHelper.PathType.WTG && !windowAsTarget) {
            return emptyList()
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
            getPathToStatesBasedOnPathType(
                pathType = pathType,
                transitionPaths = transitionPaths,
                statesWithScore = stateScores,
                currentAbstractState = currentAbstractState,
                currentState = currentState,
                shortest = true,
                windowAsTarget = true,
                goalByAbstractState = goalByAbstractState,
                maxCost = maxCost,
                abandonedAppStates = emptyList(),
                pathConstraints = pathConstraints
            )
            return transitionPaths
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
        if (abstractState.window != targetWindow)
            return emptySet<Input>()
        val currentWindowTargetEvents = phaseTargetInputs.filter { it.sourceWindow == targetWindow }
        // Only inputs which are present in the current state are selected
        currentWindowTargetEvents.forEach {
            val abstractActionss = atuaMF.validateEvent(it, currentState)
            if (abstractActionss.isNotEmpty()) {
                availableTargetInputsWithActions.put(it, abstractActionss)
            }
        }
        if (availableTargetInputsWithActions.isNotEmpty()) {
            // prioritize usefull inputs
            val inputScore = HashMap<Input, Double>()
            availableTargetInputsWithActions.keys.forEach { input ->
                inputScore.put(input, 1.0*input.modifiedMethods.size)
            }
            while (inputScore.isNotEmpty()) {
                val selectedInput = inputScore.keys.first()
                val abstractActions = availableTargetInputsWithActions.get(selectedInput)!!
                if (abstractActions.isNotEmpty()) {
                    result.add(selectedInput)
                }
                inputScore.remove(selectedInput)
            }
        }
        if (availableTargetInputsWithActions.isEmpty()) {
            val currentAppState = atuaMF.getAbstractState(currentState)!!
            val potentialAbstractInteractions = currentAppState.abstractTransitions
                .filter {
                    it.interactions.isEmpty()
                            && it.activated == true
                            && it.abstractAction.isWidgetAction()
                            && it.modelVersion == ModelVersion.BASE
                            && it.modifiedMethods.isNotEmpty()
                            && it.modifiedMethods.keys.any { !atuaMF.statementMF!!.executedMethodsMap.containsKey(it) }
                            && it.abstractAction.attributeValuationMap!!.getActionCount(it.abstractAction)==0
                }
                .map { it.abstractAction }
            return potentialAbstractInteractions.map { currentAppState.getInputsByAbstractAction(it) }.flatten()
                .distinct().toSet()
        }
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
    var needResetApp = false
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


    private fun isAvailableTargetWindow(currentAppState: AbstractState): Boolean {
        return targetWindowTryCount.filterNot {
            fullyCoveredWindows.contains(it.key) ||
                    outofbudgetWindows.contains(it.key) ||
                    fullyExploredWindows.contains(it.key)
        }.any { it.key == currentAppState.window }
    }

    private fun isAvailableTargetWindow(window: Window): Boolean {
        return targetWindowTryCount.filterNot {
            outofbudgetWindows.contains(it.key) || fullyCoveredWindows.contains(it.key) || fullyExploredWindows.contains(it.key)
        }.containsKey(window)
    }

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
        if (outofbudgetWindows.contains(window) || fullyExploredWindows.contains(window))
            return false
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
            val randomBudgetLeft = if (windowRandomExplorationBudget.containsKey(currentAbstractState.window))
                windowRandomExplorationBudget[currentAbstractState.window]!! - windowRandomExplorationBudgetUsed[currentAbstractState.window]!!
            else
                (5 * scaleFactor).toInt()
            val minRandomBudget = (5 * scaleFactor).toInt()
            if (randomBudgetLeft <= minRandomBudget) {
                it.setMaxiumAttempt(minRandomBudget)
            } else {
                it.setMaxiumAttempt(randomBudgetLeft)
            }
            if (lockWindow && currentAbstractState.belongToAUT())
                it.lockTargetWindow(currentAbstractState.window)
        }
        log.info("Random exploration")
        phaseState = PhaseState.P1_RANDOM_EXPLORATION

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
                windowRandomExplorationBudget[currentAbstractState.window]!! - windowRandomExplorationBudgetUsed[currentAbstractState.window]!!
            val minRandomBudget = (5 * scaleFactor).toInt()
            if (randomBudgetLeft <= minRandomBudget) {
                it.setMaxiumAttempt(minRandomBudget)
            } else {
                it.setMaxiumAttempt(randomBudgetLeft)
            }
        }
        log.info("This window is a target window but cannot find any target window transition")
        log.info("Random exploration in current window")
        phaseState = PhaseState.P1_RANDOM_IN_EXERCISE_TARGET_NODE
    }

    fun getTargetAbstractStates(currentNode: AbstractState, window: Window): ArrayList<AbstractState> {
        val candidates = ArrayList<AbstractState>()
        val excludedNodes = arrayListOf<AbstractState>(currentNode)
        var targetAbstractStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES
            .filter {
                it.ignored == false &&
                        it !is VirtualAbstractState
                        && (it.modelVersion == ModelVersion.BASE || it.guiStates.isNotEmpty())
                        && it.window == window
                        && !excludedNodes.contains(it)
                        && it.attributeValuationMaps.isNotEmpty()

            }
        if (targetAbstractStates.isEmpty()) {
            targetAbstractStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES
                .filter {
                    it.ignored == false &&
                            it !is VirtualAbstractState
                            && (it.modelVersion == ModelVersion.BASE)
                            && it.window == window
                            && !excludedNodes.contains(it)
                            && it.attributeValuationMaps.isNotEmpty()

                }
        }
        if (targetAbstractStates.isEmpty()) {
            val virtualAbstractState = AbstractStateManager.INSTANCE.ABSTRACT_STATES.find {
                it is VirtualAbstractState && it.window == window
            }
            if (virtualAbstractState != null) {
                candidates.add(virtualAbstractState)
            } else {
                log.debug("Something is wrong")
            }
        } else {
            //Get all AbstractState contain target events
            targetAbstractStates
                .forEach {
                    val hasUntriggeredTargetEvent: Boolean
                    hasUntriggeredTargetEvent = isTargetAbstractState(it, false)
                    if (hasUntriggeredTargetEvent)
                        candidates.add(it)
                    else
                        excludedNodes.add(it)
                }
            if (candidates.isEmpty()) {
                targetAbstractStates.forEach {
                    candidates.add(it)
                }
            }
        }
        return candidates
    }

    private fun isTargetAbstractState(abstractState: AbstractState, checkCurrentState: Boolean): Boolean {
        if (abstractState is VirtualAbstractState)
            return false
        val abstractStateUntriggeredInputs =
            abstractState.getAvailableInputs().intersect(phaseTargetInputs)
        if (abstractStateUntriggeredInputs.isNotEmpty())
            return true
        if (checkCurrentState && abstractState.abstractTransitions.any {
                it.interactions.isEmpty()
                        && it.modelVersion == ModelVersion.BASE
                        && it.modifiedMethods.isNotEmpty()
                        && it.modifiedMethods.keys.any { !atuaMF.statementMF!!.executedMethodsMap.containsKey(it) }
            })
            return true
        return false
    }

    companion object {

        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(PhaseOneStrategy::class.java) }


    }
}