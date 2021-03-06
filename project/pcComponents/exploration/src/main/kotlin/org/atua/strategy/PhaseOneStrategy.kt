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

import org.calm.ewtgdiff.EWTGDiff
import org.calm.modelReuse.ModelHistoryInformation
import org.calm.modelReuse.ModelVersion
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
import org.atua.strategy.task.ExerciseTargetComponentTask
import org.atua.strategy.task.GoToAnotherWindowTask
import org.atua.strategy.task.GoToTargetWindowTask
import org.atua.strategy.task.OpenNavigationBarTask
import org.atua.strategy.task.PrepareContextTask
import org.atua.strategy.task.RandomExplorationTask
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.Integer.min

private val Widget.hierDepth: Int
    get() {
       return this.xpath.count { it == '/' }
    }

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

    private val MAX_ITEM: Int = 5
    val untriggeredWidgets = arrayListOf<EWTGWidget>()
    val phaseTargetInputs = arrayListOf<Input>()
    val phaseTargetAbstractActions = arrayListOf<AbstractAction>()

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
    val window_Widgets = HashMap<Window, HashMap<Widget,Int>>()
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
        atuaMF.notFullyExercisedTargetInputs.forEach {
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
        AbstractStateManager.INSTANCE.ABSTRACT_STATES.forEach {
            if (it.modelVersion == ModelVersion.BASE && it.window !is Dialog)  {
                it.abstractTransitions.forEach {
                    if (it.modifiedMethods.isNotEmpty() && !phaseTargetAbstractActions.contains(it.abstractAction)) {
                        phaseTargetAbstractActions.add(it.abstractAction)
                    }
                }
            }
        }

    }

    var recentTargetEvent: Input? = null
    override fun registerTriggeredInputs(abstractAction: AbstractAction, guiState: State<*>) {
        val abstractState = AbstractStateManager.INSTANCE.getAbstractState(guiState)!!
        //val abstractInteractions = regressionTestingMF.abstractTransitionGraph.edges(abstractState).filter { it.label.abstractAction.equals(abstractAction) }.map { it.label }
        phaseTargetAbstractActions.removeIf {
            it.isEquivalent(abstractAction)
        }
        val inputs = abstractState.getInputsByAbstractAction(abstractAction)
        /*if (inputs.isEmpty() || phaseTargetInputs.intersect(inputs).isEmpty()) {
            log.warn("No input is mapped with this abstract action")
        }*/
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
        val exercisedAbstractActions =  phaseTargetAbstractActions.filter{atuaMF.actionCount.abstractActionCount.get(it)?:0 > 0 }
        phaseTargetAbstractActions.removeIf { exercisedAbstractActions.contains(it) }
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
        if (episodeCountDown < 0) {
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
            window_Widgets.put(window, HashMap())
        }
        val widgets = window_Widgets[window]!!
        val newWidgets = HashMap<Widget,Int>()

        val interactableWidgets = Helper.getActionableWidgetsWithoutKeyboard(currentState).filter {
            (it.clickable || it.longClickable || it.className == "android.webkit.WebView")
                    && (
                    !Helper.hasParentWithType(
                it,
                currentState,
                "WebView")
                            || it.resourceId.isNotBlank())
                    && !Helper.isUserLikeInput(it) && !it.isInputField
        }
        val rootWidgets = interactableWidgets.filter {it.parentId == null || !currentState.widgets.any { w-> w.id == it.parentId } }
/*
        interactableWidgets.filter { w -> !widgets.any { it.uid == w.uid } }.forEach { w ->
            if (!widgets.any { w.uid == it.uid }) {
                widgets.add(w)
            }
            if (!newWidgets.any { w.uid == it.uid })
                newWidgets.add(w)
        }
*/
      /*  if (widgets.isEmpty()) {
            interactableWidgets.forEach { w ->
                widgets.putIfAbsent(w,1)
            }
        } else {
*//*            if (currentAppState.isOpeningMenus) {
                interactableWidgets.forEach { w ->
                    //interactableWidgets.filter { w -> !widgets.any { it.uid == w.uid } }.forEach { w ->
                    if (!widgets.any { it.id == w.id }) {
                        newWidgets.add(w)
                        widgets.add(w)
                    }
                }
            } else {
            }*//*

        }*/
        val interactableWidgetsByUUIDs =  interactableWidgets.groupBy { it.uid }
        interactableWidgetsByUUIDs.forEach { uid, g ->
            //interactableWidgets.filter { w -> !widgets.any { it.uid == w.uid } }.forEach { w ->
            if (!widgets.keys.any { it.uid == uid ||
                        (
                                ((g.first().resourceId.isBlank() && it.resourceId.isBlank())
                                        || (g.first().resourceId.isNotBlank() && g.first().resourceId == it.resourceId))
                                && it.xpath.replace(Regex("\\[\\d+\\]"),"") == g.first().xpath.replace(Regex("\\[\\d+\\]"),"")
                                && it.className == g.first().className)
                       }) {

//                val reducedXpath = g.first().xpath.replace(Regex("\\[\\d+\\]"),"")
                widgets.put(g.first(),min(g.size,MAX_ITEM))
            } else {
                var similarWidget = widgets.entries.find { it.key.uid == uid }
                var increaseSize = false
                if (similarWidget == null) {
                    if (g.first().resourceId.isBlank())
                        similarWidget = widgets.entries.find {
                            it.key.resourceId.isBlank()
                                    && it.key.xpath.replace(Regex("\\[\\d+\\]"),"") == g.first().xpath.replace(Regex("\\[\\d+\\]"),"")
                                    && it.key.className == g.first().className
                        }
                    else {
                        similarWidget = widgets.entries.find {
                            it.key.resourceId == g.first().resourceId
                                    && it.key.xpath.replace(Regex("\\[\\d+\\]"),"") == g.first().xpath.replace(Regex("\\[\\d+\\]"),"")
                                    && it.key.className == g.first().className
                        }
                    }
                    if (similarWidget != null)
                        increaseSize = true
                }
                if (similarWidget!=null) {
                    val currentSize = similarWidget!!.value
                    val newSize = if (increaseSize)
                        currentSize+1
                    else
                        g.size
                    if (currentSize < newSize && currentSize < MAX_ITEM ) {
                        widgets.put(g.first(),min(currentSize,MAX_ITEM))
                    }
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
        if (window is Activity)
            newActions = 4
        else
            newActions = 0
        widgets.forEach {
            /*if (it.visibleBounds.width > 200 && it.visibleBounds.height > 200 ) {
               newActions += it.availableActions(delay, useCoordinateClicks).filterNot { it is Swipe}.size
            } else {
                newActions += it.availableActions(delay, useCoordinateClicks).filterNot { it is Swipe }.size
            }*/

            if (it.key.className == "android.webkit.WebView") {
                newActions += (5*it.value).toInt()
            } else {
                if (it.key.clickable || it.key.longClickable) {
                    newActions += it.value
                }
            }
        }
        val updateBudget = if (windowRandomExplorationBudget.containsKey(window)) {
            if (windowRandomExplorationBudget[window]!! > newActions) {
                false
            } else {
                true
            }
        } else {
            true
        }
        if(updateBudget)
            windowRandomExplorationBudget[window] = (newActions * scaleFactor).toInt()
        /*     if (window is OptionsMenu || window is Dialog || window is OutOfApp) {
                 val activityWindow = WindowManager.instance.allMeaningWindows.find { it is Activity && it.activityClass == window.activityClass }
                 if (activityWindow!=null && windowRandomExplorationBudget.containsKey(activityWindow))
                     windowRandomExplorationBudget[activityWindow] = windowRandomExplorationBudget[activityWindow]!! + (newActions*scaleFactor).toInt()
             }*/
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
            it.exerciseCount > 0
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
    }

    private fun updateOutOfBudgetWindows() {
        val replacingWidgets = EWTGDiff.instance.getReplacingWidget()
        val replacingInputs = EWTGDiff.instance.replacingInputs
        val unexhaustedExploredAbstractStates = super.getUnexhaustedExploredAbstractState(true)
        fullyExploredWindows.associateWith { window -> unexhaustedExploredAbstractStates.filter { it.window == window } }
            .forEach {
                if (it.value.isNotEmpty()) {
                    if (!atuaMF.reuseBaseModel || true) {
                        if (it.value.any {
                                it.getUnExercisedActions(null, atuaMF).filter { action ->
                                    !ProbabilityBasedPathFinder.disableAbstractActions1.contains(action)
                                            && ProbabilityBasedPathFinder.disableInputs1.intersect(it.getInputsByAbstractAction(action)).isEmpty()
                                            && !action.isCheckableOrTextInput(it)
                                            && it.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                                }.isNotEmpty()
                            }) {
                            if (atuaTestingStrategy.eContext.apk.packageName=="bbc.mobile.news.ww") {
                                if (it.key.meaningfullScore>0)
                                    fullyExploredWindows.remove(it.key)
                            }
                            else
                                fullyExploredWindows.remove(it.key)
                        }
                    } else {
                        val unexercisedInputs = ArrayList<Input>()
                        val unexercisedActions = ArrayList<AbstractAction>()
                        it.value.forEach {
                            val unexercisedActons = it.getUnExercisedActions(null, atuaMF).filter { action ->
                                !ProbabilityBasedPathFinder.disableAbstractActions1.contains(action)
                                        && !action.isCheckableOrTextInput(it)
                                        && it.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                            }
                            val inputs =
                                unexercisedActons.map { action -> it.getInputsByAbstractAction(action) }.flatten()
                            inputs.forEach {
                                if (!unexercisedInputs.contains(it)) {
                                    unexercisedInputs.add(it)
                                }
                            }
                        }
                        if (!unexercisedInputs.all {
                                it.exercisedInThePast
                                        && !replacingWidgets.contains(it.widget)
                                        && !replacingInputs.contains(it)
                                        && !phaseTargetInputs.contains(it)
                            }) {
                            fullyExploredWindows.remove(it.key)
                        }
                    }

                }
            }
        val meaningfulWindows = WindowManager.instance.allMeaningWindows
            .filter { window ->
                !fullyExploredWindows.contains(window)
                        && AbstractStateManager.INSTANCE.ABSTRACT_STATES.any {
                    it.window == window && it.guiStates.isNotEmpty()
                }
            }
        meaningfulWindows.forEach {
            if (it.meaningfullScore <= 0 && it is Dialog) {
                fullyExploredWindows.add(it)
            }
        }
        val availableAbState_Window = meaningfulWindows.filter { !fullyExploredWindows.contains(it) }
            .associateWith { window -> unexhaustedExploredAbstractStates.filter { it.window == window } }

        availableAbState_Window.forEach {
//            val allActions = it.value.map { it.getAvailableActions() }.flatten()
            if (it.value.isEmpty()) {
                fullyExploredWindows.add(it.key)
            } else {
                if (it.value.all {
                        it.getUnExercisedActions2(null).filter { action ->
                            !ProbabilityBasedPathFinder.disableAbstractActions1.contains(action)
                                    && ProbabilityBasedPathFinder.disableInputs1.intersect(it.getInputsByAbstractAction(action)).isEmpty()
                                    && !action.isCheckableOrTextInput(it)
                                    && it.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                        }.isEmpty()
                    }) {
                    fullyExploredWindows.add(it.key)
                } else if (atuaTestingStrategy.eContext.apk.packageName=="bbc.mobile.news.ww" && it.key.meaningfullScore<=0) {
                    fullyExploredWindows.add(it.key)
                }
                else if (atuaMF.reuseBaseModel && false) {
                    val unexercisedInputs = ArrayList<Input>()
                    val unexercisedActions = ArrayList<AbstractAction>()
                    it.value.forEach {
                        val unexercisedActons = it.getUnExercisedActions2(null).filter { action ->
                            !ProbabilityBasedPathFinder.disableAbstractActions1.contains(action)
                                    && !action.isCheckableOrTextInput(it)
                                    && it.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                        }
                        val inputs = unexercisedActons.map { action -> it.getInputsByAbstractAction(action) }.flatten()
                        inputs.forEach {
                            if (!unexercisedInputs.contains(it)) {
                                unexercisedInputs.add(it)
                            }
                        }
                    }
                    if (unexercisedInputs.all {
                            it.exercisedInThePast
                                    && !replacingWidgets.contains(it.widget)
                                    && !replacingInputs.contains(it)
                                    && !phaseTargetInputs.contains(it)
                        }) {
                        fullyExploredWindows.add(it.key)
                    }
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

    private fun updateCurrentTargetWindow(
        currentState: State<*>,
        currentAppState: AbstractState
    ) {
        val explicitTargetWindows =
            phaseTargetInputs.filter {
                !ProbabilityBasedPathFinder.disableInputs1.contains(it)
            }.map { it.sourceWindow }
                .filter { !unreachableWindows.contains(it)
                        && !ProbabilityBasedPathFinder.disableWindows1.contains(it)}.distinct()
        val isTargetAppState = getCurrentTargetInputs(currentState).isNotEmpty()

        if (targetWindow != currentAppState.window) {
            if (isTargetAppState) {
                resetStrategyTask(currentState)
                targetWindow = currentAppState.window
                log.info("Switch target window to $targetWindow")
                episodeCountDown = 5
            } /*else if (targetWindow == null
                || !explicitTargetWindows.contains(targetWindow!!)) {
                if (strategyTask !is GoToTargetWindowTask ||
                    (strategyTask is GoToTargetWindowTask &&
                            ((strategyTask as GoToTargetWindowTask).isWindowAsTarget
                                    || (strategyTask as GoToTargetWindowTask).pathTraverser!!.transitionPath.goal.isEmpty()))) {
                    resetStrategyTask(currentState)
                    targetWindow = currentAppState.window
                    log.info("Switch target window to $targetWindow")
                }
                *//*if (isAvailableTargetWindow(currentAppState)) {
                    if (!explicitTargetWindows.contains(targetWindow)
                        && !explicitTargetWindows.contains(currentAppState.window)

                        && !fullyExploredWindows.contains(currentAppState.window)
                    ) {
                        resetStrategyTask(currentState)
                        targetWindow = currentAppState.window
                        log.info("Switch target window to $targetWindow")
                    } else if (!explicitTargetWindows.contains(targetWindow)
                        && explicitTargetWindows.contains(currentAppState.window)

                        && !fullyExploredWindows.contains(currentAppState.window)
                    ) {
                        resetStrategyTask(currentState)
                        targetWindow = currentAppState.window
                        log.info("Switch target window to $targetWindow")
                    } else if (explicitTargetWindows.contains(targetWindow)
                        && explicitTargetWindows.contains(currentAppState.window)

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
                }*//*
            }*/
        }
        if (episodeCountDown < 0  && strategyTask !is GoToTargetWindowTask) {
            if (targetWindow != null) {
                if (!isTargetAppState && (!isCandidateWindow(targetWindow!!) || unreachableWindows.contains(targetWindow!!))) {
                    if (unreachableWindows.contains(targetWindow)) {
                        log.info("Unset target window.")
                        targetWindow = null
                        resetStrategyTask(currentState)
                    }
                    else if (!isCandidateWindow(targetWindow!!)) {
                         if (isExplicitCandidateWindow(targetWindow!!) ){
                            if (strategyTask !is GoToTargetWindowTask
                                && strategyTask !is ExerciseTargetComponentTask
                            ) {
                                log.info("Unset target window.")
                                targetWindow = null
                                resetStrategyTask(currentState)
                            }
                        } else {
                            log.info("Unset target window.")
                            targetWindow = null
                            resetStrategyTask(currentState)
                        }
                    }
                }
            }
            if (targetWindow!=null && currentAppState.window != targetWindow) {
                val oldTargetWindow = targetWindow!!
                if (phaseState == PhaseState.P1_GO_TO_EXPLORE_STATE || phaseState == PhaseState.P1_RANDOM_EXPLORATION || phaseState == PhaseState.P1_GO_TO_TARGET_NODE) {
                    // Try to prevent doing reset if we just executed it
                    val includeReset = if (phaseState == PhaseState.P1_RANDOM_EXPLORATION)
                        true
                    else if (strategyTask is GoToAnotherWindowTask) {
                        !(strategyTask as GoToAnotherWindowTask).includeResetAction
                    } else {
                        true
                    }
                    selectTargetWindow(currentState, false,includeReset).also {
                        if (targetWindow != null
                            && targetWindow != oldTargetWindow
                        ) {
                            resetStrategyTask(currentState)
                            log.info("Switch target window to $targetWindow")
                        } else {
                            targetWindow = oldTargetWindow
                        }
                    }
                }
            }
            if (targetWindow == null) {
                //try select a target window
                val includeResetAction = if (phaseState == PhaseState.P1_RANDOM_EXPLORATION)
                    true
                else if (strategyTask is GoToAnotherWindowTask) {
                    !(strategyTask as GoToAnotherWindowTask).includeResetAction
                } else {
                    true
                }
                selectTargetWindow(currentState, true,includeResetAction).also {
                    if (targetWindow != null) {
                        resetStrategyTask(currentState)
                        log.info("Switch target window to $targetWindow")
                    }
                }
            }
            if (targetWindow == null) {
                if (isCandidateWindow(currentAppState.window)) {
                    targetWindow = currentAppState.window
                    resetStrategyTask(currentState)
                    log.info("Switch target window to $targetWindow")
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
//        episodeCountDown = 5
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
        if (targetWindow == null)
            selectTargetWindow(currentState, false,true)
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
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (strategyTask !is RandomExplorationTask
//            && meaningfulAbstractActions.isNotEmpty()
            && hasBudgetLeft(currentAppState.window)
            && currentAppState.window.meaningfullScore > 0
        ) {
            setRandomExploration(randomExplorationTask, currentState, true, false)
            return
        }
        /* if (strategyTask is RandomExplorationTask
             && !currentAppState.isRequireRandomExploration()
             && meaningfulAbstractActions.isEmpty()
             && !hasBudgetLeft(currentAppState.window) ) {
             if (exploreApp(currentState, goToExploreWindows))
                 returwindowRandomExplorationBudget
         }*/

        if (strategyTask != null) {
            if (continueOrEndCurrentTask(currentState)) return
        }

        if (
//            meaningfulAbstractActions.isNotEmpty()
            hasBudgetLeft(currentAppState.window)
        ) {
            setRandomExploration(randomExplorationTask, currentState, true, false)
            return
        }
        if (strategyTask is GoToAnotherWindowTask && (strategyTask as GoToAnotherWindowTask).reachedDestination) {
            setRandomExploration(randomExplorationTask, currentState, false, false)
            return
        }
        /*if (strategyTask is GoToAnotherWindowTask
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }*/
        //Current window has no more random exploration budget
        /*if (strategyTask is GoToAnotherWindowTask) {
            if ((strategyTask as GoToAnotherWindowTask).isReachExpectedState(currentState)){
                setRandomExploration(randomExplorationTask, currentState, false, true)
                return
            }
        }*/
        if (exploreApp(currentState, goToExploreWindows, randomExplorationTask,true))
            return
        setRandomExploration(randomExplorationTask, currentState, false, false)
        forceEnd = true
        return
    }

    private fun  nextActionOnInitial(
        currentAppState: AbstractState,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask,
        goToAnotherWindowTask: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState))
            return
        if (currentAppState.window == targetWindow) {
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
            if (isCandidateWindow(targetWindow!!)) {
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return
            }
        }
//        if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
        //unreachableWindows.add(targetWindow!!)

        //if (goToWindowToExploreOrRandomExploration(currentAppState, goToAnotherNode, currentState, randomExplorationTask)) return
        if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = false,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = false
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = true,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = false
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = false,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = true,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (
//            meaningfulAbstractActions.isNotEmpty()
                hasBudgetLeft(currentAppState.window)
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherWindowTask, randomExplorationTask,true))
            return
        setFullyRandomExploration(randomExplorationTask, currentState, currentAppState)
        return
    }

    private fun nextActionOnExerciseTargetWindow(
        currentAppState: AbstractState,
        currentState: State<*>,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        randomExplorationTask: RandomExplorationTask,
        goToAnotherWindowTask: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (continueOrEndCurrentTask(currentState)) return
        if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState))
            return
        if (currentAppState.window == targetWindow) {
            // In case target events not found
            if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
            if (isCandidateWindow(targetWindow!!)) {
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return
            }
            /*val bkTargetWindow = targetWindow
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
            }*/
        }
        if (isCandidateWindow(targetWindow!!) && randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
        /*val bkTargetWindow = targetWindow
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
        }*/
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
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }

        if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = true,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = false
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (isCandidateWindow(targetWindow!!) && goToAnotherWindowTask.isAvailable(
                currentState = currentState,
                destWindow = null,
                includeResetApp = false,
                isExploration = true,
                includePressback = true,
                isWindowAsTarget = false
            )
        ) {
            setGoToExploreState(goToAnotherWindowTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = false,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = true,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (
//            meaningfulAbstractActions.isNotEmpty()
            hasBudgetLeft(currentAppState.window)
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherWindowTask, randomExplorationTask,true)) {
            return
        }
        setFullyRandomExploration(randomExplorationTask, currentState, currentAppState)
        return
    }

    private fun exploreApp(
        currentState: State<*>,
        goToAnotherWindow: GoToAnotherWindowTask,
        randomExplorationTask: RandomExplorationTask,
        doResetApp: Boolean
    ): Boolean {
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        if (goToAnotherWindow.isAvailable(
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
       /* if (strategyTask is GoToAnotherWindowTask) {
            if ((strategyTask as GoToAnotherWindowTask).includeResetAction) {
                if (hasBudgetLeft(currentAppState.window)) {
                    setRandomExploration(randomExplorationTask, currentState, false, true)
                    return true
                } else {
                    return false
                }
            }
        }*/
        if (doResetApp && goToAnotherWindow.isAvailable(
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

    private fun nextActionOnGoToTargetNode(
        currentAppState: AbstractState,
        exerciseTargetComponentTask: ExerciseTargetComponentTask,
        currentState: State<*>,
        randomExplorationTask: RandomExplorationTask,
        goToAnotherWindowTask: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState))
            return
        if (currentAppState.window == targetWindow) {
            if (continueOrEndCurrentTask(currentState)) return
            /*if (hasBudgetLeft(currentAppState.window)
                && currentAppState.getUnExercisedActions(currentState, atuaMF)
                    .isNotEmpty()
            ) {
            }*/
            if (isCandidateWindow(targetWindow!!)) {
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
            }
            return
        }
        if (continueOrEndCurrentTask(currentState)) return

        if (targetWindow!! !is Dialog && randomExplorationInSpecialWindows(
                currentAppState,
                randomExplorationTask,
                currentState
            )
        ) return
        if (goToTargetNodeTask.isWindowAsTarget == false) {
            if (goToTargetNodeTask.includeResetAction == false) {
                if (isCandidateWindow(targetWindow!!) && goToAnotherWindowTask.isAvailable(
                        currentState = currentState,
                        destWindow = null,
                        includeResetApp = false,
                        isExploration = true,
                        includePressback = true,
                        isWindowAsTarget = false
                    )
                ) {
                    setGoToExploreState(goToAnotherWindowTask, currentState)
                    return
                }
                if (goToTargetNodeTask.isAvailable(
                        currentState = currentState,
                        isWindowAsTarget = false,
                        destWindow = targetWindow!!,
                        isExploration = false,
                        includeResetApp = true
                    )
                ) {
                    unreachableWindows.remove(targetWindow!!)
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
            }
            if (isCandidateWindow(targetWindow!! ) && goToTargetNodeTask.isAvailable(
                    currentState = currentState,
                    isWindowAsTarget = true,
                    destWindow = targetWindow!!,
                    isExploration = false,
                    includeResetApp = false
                )
            ) {
                unreachableWindows.remove(targetWindow!!)
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
            if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                    currentState = currentState,
                    isWindowAsTarget = true,
                    destWindow = targetWindow!!,
                    isExploration = false,
                    includeResetApp = true
                )
            ) {
                unreachableWindows.remove(targetWindow!!)
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
        } else {
            if (isCandidateWindow(targetWindow!!) && goToAnotherWindowTask.isAvailable(
                    currentState = currentState,
                    destWindow = null,
                    includeResetApp = false,
                    isExploration = true,
                    includePressback = true,
                    isWindowAsTarget = false
                )
            ) {
                setGoToExploreState(goToAnotherWindowTask, currentState)
                return
            }

            if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.includeResetAction == false) {
                if (goToTargetNodeTask.isAvailable(
                        currentState = currentState,
                        isWindowAsTarget = true,
                        destWindow = targetWindow!!,
                        isExploration = false,
                        includeResetApp = true
                    )
                ) {
                    unreachableWindows.remove(targetWindow!!)
                    setGoToTarget(goToTargetNodeTask, currentState)
                    return
                }
            }
        }
        unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (
//            meaningfulAbstractActions.isNotEmpty()
            hasBudgetLeft(currentAppState.window)
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherWindowTask, randomExplorationTask,true))
            return
        targetWindow = null
        strategyTask = null
        nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherWindowTask)
        return
    }

    private fun randomExplorationInSpecialWindows(
        currentAppState: AbstractState,
        randomExplorationTask: RandomExplorationTask,
        currentState: State<*>
    ): Boolean {
        if (currentAppState.isRequireRandomExploration() && strategyTask !is RandomExplorationTask) {
            setRandomExploration(randomExplorationTask, currentState, false, false)
            return true
        }
        if (Helper.isOptionsMenuLayout(currentState) && strategyTask !is RandomExplorationTask) {
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
        goToAnotherWindowTask: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState))
            return
        if (currentAppState.window == targetWindow) {
            if (isCandidateWindow(targetWindow!!)) {
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return
            }
        }

/*        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (
//            meaningfulAbstractActions.isNotEmpty()
            hasBudgetLeft(currentAppState.window)
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }*/
        if (continueOrEndCurrentTask(currentState)) return

        if (
//            meaningfulAbstractActions.isNotEmpty()
            hasBudgetLeft(currentAppState.window) || goToAnotherWindowTask.reachedDestination
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = false,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = false
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }

        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = true,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = false
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = false,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                isWindowAsTarget = true,
                destWindow = targetWindow!!,
                isExploration = false,
                includeResetApp = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        if (
//            meaningfulAbstractActions.isNotEmpty()
            hasBudgetLeft(currentAppState.window) || goToAnotherWindowTask.reachedDestination
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherWindowTask, randomExplorationTask,true))
            return
        targetWindow = null
        strategyTask = null
        nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherWindowTask)
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
        goToAnotherWindowTask: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState))
            return
        if (currentAppState.window == targetWindow) {
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
            if (isCandidateWindow(targetWindow!!)) {
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return
            }
        }
        if (continueOrEndCurrentTask(currentState)) return
        if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return

        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = false,
                isExploration = false,
                isWindowAsTarget = false
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }

        if ( goToAnotherWindowTask.isAvailable(
                currentState = currentState,
                destWindow = null,
                includeResetApp = false,
                isExploration = true,
                includePressback = true,
                isWindowAsTarget = false
            )
        ) {

            setGoToExploreState(goToAnotherWindowTask, currentState)
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
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = true,
                isExploration = false,
                isWindowAsTarget = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
        //unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (
//            meaningfulAbstractActions.isNotEmpty()
            hasBudgetLeft(currentAppState.window)
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherWindowTask, randomExplorationTask,true)) {
            return
        }
        targetWindow = null
        strategyTask = null
        nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherWindowTask)
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
        goToAnotherWindowTask: GoToAnotherWindowTask,
        goToTargetNodeTask: GoToTargetWindowTask
    ) {
        if (exerciseTargetIfAvailable(exerciseTargetComponentTask, currentState))
            return
        if (currentAppState.window == targetWindow) {
            if (continueRandomExplorationIfIsFillingData(randomExplorationTask)) return
            if (isCandidateWindow(targetWindow!!)) {
                setRandomExplorationInTargetWindow(randomExplorationTask, currentState)
                return
            }
        }
        if (continueRandomExplorationIfIsFillingData(randomExplorationTask)) return

        if (randomExplorationTask.stopWhenHavingTestPath
        ) {
            if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                    currentState = currentState,
                    destWindow = targetWindow!!,
                    includePressback = true,
                    includeResetApp = false,
                    isExploration = false
                )
            ) {
                unreachableWindows.remove(targetWindow!!)
                setGoToTarget(goToTargetNodeTask, currentState)
                return
            }
        }
        if (continueOrEndCurrentTask(currentState))
            return
        if (randomExplorationInSpecialWindows(currentAppState, randomExplorationTask, currentState)) return
        if (goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = false,
                isExploration = false,
                isWindowAsTarget = false
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = false,
                isExploration = false,
                isWindowAsTarget = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if ( goToAnotherWindowTask.isAvailable(
                currentState = currentState,
                destWindow = null,
                includeResetApp = false,
                isExploration = true,
                includePressback = true,
                isWindowAsTarget = false
            )
        ) {
            setGoToExploreState(goToAnotherWindowTask, currentState)
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
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        if (isCandidateWindow(targetWindow!!) && goToTargetNodeTask.isAvailable(
                currentState = currentState,
                destWindow = targetWindow!!,
                includePressback = true,
                includeResetApp = true,
                isExploration = false,
                isWindowAsTarget = true
            )
        ) {
            unreachableWindows.remove(targetWindow!!)
            setGoToTarget(goToTargetNodeTask, currentState)
            return
        }
        unreachableWindows.add(targetWindow!!)
//        unreachableWindows.add(targetWindow!!)
        val meaningfulAbstractActions = currentAppState.getUnExercisedActions(currentState, atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAppState)
                        && currentAppState.getInputsByAbstractAction(it).any { it.meaningfulScore > 0 }
            }
        if (
//            meaningfulAbstractActions.isNotEmpty()
            hasBudgetLeft(currentAppState.window)
        ) {
            setRandomExploration(randomExplorationTask, currentState, false, true)
            return
        }
        if (exploreApp(currentState, goToAnotherWindowTask, randomExplorationTask,true)) {
            return
        }
        targetWindow = null
        strategyTask = null
        nextActionWithoutTargetWindow(currentState, currentAppState, randomExplorationTask, goToAnotherWindowTask)
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


    private fun selectTargetWindow(currentState: State<*>, considerContainingTargetInputsOnly: Boolean, includeResetAction: Boolean) {
        log.info("Trying to select nearest target...")
        //Try finding reachable target
        val maxTry = targetWindowTryCount.size / 2 + 1

        var candidates =
            targetWindowTryCount.filter { isExplicitCandidateWindow(it.key) }.map { it.key }
        val currentAppState = atuaMF.getAbstractState(currentState)!!
        val stateWithGoals = HashMap<AbstractState, List<Goal>>()
        val stateWithScore = HashMap<AbstractState, Double>()
        val shorestPaths = ArrayList<TransitionPath>()
        if (candidates.isNotEmpty()) {
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
                        stateWithGoals.put(
                            virtualAbstractState,
                            targetInputs.distinct().map { Goal(input = it, abstractAction = null) })
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

            if (stateWithScore.isNotEmpty()) {
                val pathConstraints = HashMap<PathConstraint, Boolean>()
                pathConstraints.put(PathConstraint.INCLUDE_RESET, includeResetAction)
                pathConstraints.put(PathConstraint.INCLUDE_LAUNCH, true)
                pathConstraints.put(PathConstraint.MAXIMUM_DSTG, true)
                getPathToStatesBasedOnPathType(
                    pathType = PathFindingHelper.PathType.WIDGET_AS_TARGET,
                    transitionPaths = shorestPaths,
                    statesWithScore = stateWithScore,
                    currentAbstractState = currentAppState,
                    currentState = currentState,
                    shortest = true,
                    windowAsTarget = false,
                    goalByAbstractState = stateWithGoals,
                    maxCost = ProbabilityBasedPathFinder.DEFAULT_MAX_COST,
                    abandonedAppStates = emptyList(),
                    pathConstraints = pathConstraints
                )
            }
        }
        if (shorestPaths.isNotEmpty()) {
            val minPath = shorestPaths.minByOrNull { it.cost(final = true) }
            targetWindow = minPath!!.destination.window
        } else {
            if (!considerContainingTargetInputsOnly) {
                candidates = targetWindowTryCount.filter { isCandidateWindow(it.key) }.map { it.key }
            }
            if (candidates.isNotEmpty()) {
                val pathConstraints = HashMap<PathConstraint, Boolean>()
                pathConstraints.put(PathConstraint.INCLUDE_RESET, includeResetAction)
                pathConstraints.put(PathConstraint.INCLUDE_LAUNCH, true)
                pathConstraints.put(PathConstraint.MAXIMUM_DSTG, true)
                getPathToStatesBasedOnPathType(
                    pathType = PathFindingHelper.PathType.WIDGET_AS_TARGET,
                    transitionPaths = shorestPaths,
                    statesWithScore = stateWithScore,
                    currentAbstractState = currentAppState,
                    currentState = currentState,
                    shortest = true,
                    windowAsTarget = true,
                    goalByAbstractState = stateWithGoals,
                    maxCost = ProbabilityBasedPathFinder.DEFAULT_MAX_COST,
                    abandonedAppStates = emptyList(),
                    pathConstraints = pathConstraints
                )
                if (shorestPaths.isNotEmpty()) {
                    val minPath = shorestPaths.minByOrNull { it.cost(final = true) }
                    targetWindow = minPath!!.destination.window
                }
                else {
                    targetWindow = null
                }
            } else {
                targetWindow = null
            }
        }
        log.info("Finish select nearest target...")
        actionCountSinceSelectTarget = 0
    }

    private fun isExplicitCandidateWindow(window: Window): Boolean {
        val explicitTargetWindows = WindowManager.instance.allMeaningWindows.filter { window ->
             !ProbabilityBasedPathFinder.disableWindows1.contains(window)
                     && phaseTargetInputs.any {
                it.sourceWindow == window
                       /* && (it.widget==null ||
                        (it.widget!!.witnessed))*/
                        && !ProbabilityBasedPathFinder.disableInputs1.contains(it)
                        }
        }.union(phaseTargetAbstractActions
            .filter { !ProbabilityBasedPathFinder.disableAbstractActions1.contains(it) }
            .map { it.window })

        return explicitTargetWindows.contains(window)
//                && !outofbudgetWindows.contains(window)
                && !fullyExploredWindows.contains(window)
                && !fullyCoveredWindows.contains(window)
//                && !unreachableWindows.contains(window)
//                && !fullyExploredWindows.contains(window)
    }

    private fun isCandidateWindow(it: Window) =
        targetWindowTryCount.keys.contains(it) &&
        !outofbudgetWindows.contains(it) &&
                !fullyCoveredWindows.contains(it)
//                !unreachableWindows.contains(it) &&
                && !fullyExploredWindows.contains(it)
                && !ProbabilityBasedPathFinder.disableWindows1.contains(it)

    override fun getUnexhaustedExploredAbstractState(includeResetAction: Boolean): List<AbstractState> {
        return super.getUnexhaustedExploredAbstractState(includeResetAction).filter {
            !fullyExploredWindows.contains(it.window)
                    && !unreachableWindows.contains(it.window)
                    && !outofbudgetWindows.contains(it.window)
        }
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
        val includeResetAction = pathConstraints[PathConstraint.INCLUDE_RESET]!!
        val goalByAbstractState = HashMap<AbstractState, List<Goal>>()
        val runtimeAbstractStates = ArrayList(getUnexhaustedExploredAbstractState(includeResetAction))
        if (runtimeAbstractStates.isNotEmpty()) {
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

                appStates.filter{ it.guiStates.isNotEmpty()}. forEach { appState ->
                    appState.getUnExercisedActions(null, atuaMF).filter { action ->
                        !action.isCheckableOrTextInput(appState)
                                && appState.getInputsByAbstractAction(action).any { it.meaningfulScore > 0 }
                                && !ProbabilityBasedPathFinder.disableAbstractActions1.contains(action)
                                && ProbabilityBasedPathFinder.disableInputs1.intersect(
                                    appState.getInputsByAbstractAction(action)).isEmpty()
                                && (includeResetAction || (
                                !ProbabilityBasedPathFinder.disableAbstractActions2.contains(action)
                                        && ProbabilityBasedPathFinder.disableInputs2.intersect(appState.getInputsByAbstractAction(action)).isEmpty()
                                )  )
                    }.forEach { action ->
                        toExploreInputs.add(Goal(input = null, abstractAction = action))
                    }
                }
                goalByAbstractState.put(virtualAbstractState, toExploreInputs.distinct())
            }
            if (goalByAbstractState.values.flatten().isNotEmpty()) {
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
            }
        }
        if (transitionPaths.isEmpty()) {
            goalByAbstractState.clear()
            val unexhaustedTestedWindows =
                WindowManager.instance.allMeaningWindows.filter {
                    windowRandomExplorationBudget.contains(it)
                            && !outofbudgetWindows.contains(it)
                            && !fullyExploredWindows.contains(it)
                            && !ProbabilityBasedPathFinder.disableWindows1.contains(it)
                            && (includeResetAction || !ProbabilityBasedPathFinder.disableWindows2.contains(it))
                            && it !is Dialog
                }
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
        val targetStates = getTargetAbstractStates(currentNode = currentAbstractState, window = targetWindow!!)
        targetStates.removeIf { (it.modelVersion != ModelVersion.BASE && it.guiStates.isEmpty()) || it == currentAbstractState }
        val stateScores: HashMap<AbstractState, Double> = HashMap<AbstractState, Double>()
        val goalByAbstractState: HashMap<AbstractState, List<Goal>> = HashMap()
        val targetInputs = HashSet<Input>()
        val targetAbstractActions = HashSet<AbstractAction>()
        val includeResetAction = pathConstraints[PathConstraint.INCLUDE_RESET]?:false
        val reachedMethods = atuaMF.statementMF!!.executedMethodsMap.keys
        targetStates.filterNot {
            (pathType != PathFindingHelper.PathType.FULLTRACE
                    && pathType != PathFindingHelper.PathType.PARTIAL_TRACE
                    && AbstractStateManager.INSTANCE.unreachableAbstractState.contains(it))
        }.forEach {

            val targetInputsInAppState = it.getAvailableInputs().filter {
                phaseTargetInputs.contains(it) &&
                        (it.eventType != EventType.implicit_launch_event && it.eventType != EventType.resetApp)
            }
            targetInputsInAppState.forEach {
                if (!targetInputs.contains(it)
                    && !ProbabilityBasedPathFinder.disableInputs1.contains(it)
                    && (includeResetAction || !ProbabilityBasedPathFinder.disableInputs2.contains(it))
                ) {
                    targetInputs.add(it)
                }
            }
            val local_targetAbstractActions = it.getAvailableActions().filter {
                !ProbabilityBasedPathFinder.disableAbstractActions1.contains(it)
                    && phaseTargetAbstractActions.contains(it)
                        && (includeResetAction || !ProbabilityBasedPathFinder.disableAbstractActions2.contains(it))}
            targetAbstractActions.addAll(local_targetAbstractActions)
        }

        val virtualAbstractState = AbstractStateManager.INSTANCE.getVirtualAbstractState(targetWindow!!)!!
        stateScores.put(virtualAbstractState, 1.0)

        val windowTargetInputs = phaseTargetInputs.filter { it.sourceWindow == targetWindow
                && !ProbabilityBasedPathFinder.disableInputs1.contains(it)
                && (includeResetAction || !ProbabilityBasedPathFinder.disableInputs2.contains(it))}
 /*       windowTargetInputs.subtract(targetInputs).forEach {
            ProbabilityBasedPathFinder.disableInputs1.add(it)
        }*/

        var windowAsTarget = false
        if (targetInputs.isEmpty()) {
            if (targetAbstractActions.isNotEmpty()) {
                goalByAbstractState.put(
                    virtualAbstractState,
                    targetAbstractActions.distinct().map { Goal(input = null, abstractAction = it) }
                )
                getPathToStatesBasedOnPathType(
                    pathType,
                    transitionPaths,
                    stateScores,
                    currentAbstractState,
                    currentState,
                    true,
                    false,
                    goalByAbstractState,
                    maxCost,
                    abandonedAppStates = emptyList(),
                    pathConstraints = pathConstraints
                )
                if (transitionPaths.isNotEmpty())
                    return transitionPaths
            }
            return getPathsToWindowToExplore(currentState, targetWindow!!, pathType, true, maxCost, pathConstraints)
        }
        goalByAbstractState.clear()
        goalByAbstractState.put(
            virtualAbstractState,
            targetInputs.distinct().map { Goal(input = it, abstractAction = null) })
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
            if (targetAbstractActions.isNotEmpty()) {
                goalByAbstractState.put(
                    virtualAbstractState,
                    targetAbstractActions.distinct().map { Goal(input = null, abstractAction = it) }
                )
                getPathToStatesBasedOnPathType(
                    pathType,
                    transitionPaths,
                    stateScores,
                    currentAbstractState,
                    currentState,
                    true,
                    false,
                    goalByAbstractState,
                    maxCost,
                    abandonedAppStates = emptyList(),
                    pathConstraints = pathConstraints
                )
                if (transitionPaths.isNotEmpty())
                    return transitionPaths
            }
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

    override fun getCurrentTargetInputs(currentState: State<*>): Set<Goal> {
        val result = ArrayList<Goal>()
        val availableTargetInputsWithActions = HashMap<Input, List<AbstractAction>>()

        val abstractState = atuaMF.getAbstractState(currentState)!!
        val currentWindowTargetEvents = phaseTargetInputs.filter { it.sourceWindow == abstractState.window }
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
                inputScore.put(input, 1.0 * input.modifiedMethods.size)
            }
            while (inputScore.isNotEmpty()) {
                val selectedInput = inputScore.keys.first()
                val abstractActions = availableTargetInputsWithActions.get(selectedInput)!!
                if (abstractActions.isNotEmpty()) {
                    result.add(Goal(input = selectedInput,abstractAction = null))
                }
                inputScore.remove(selectedInput)
            }
        }
        if (result.isEmpty()) {
            val currentAppState = atuaMF.getAbstractState(currentState)!!
            /*val potentialAbstractInteractions = currentAppState.abstractTransitions
                .filter {
                    it.interactions.isEmpty()
                            && it.activated == true
                            && it.abstractAction.isWidgetAction()
                            && it.modelVersion == ModelVersion.BASE
                            && it.modifiedMethods.isNotEmpty()
                            && it.modifiedMethods.keys.any { !atuaMF.statementMF!!.executedMethodsMap.containsKey(it) }
                            && it.abstractAction.attributeValuationMap!!.getActionCount(it.abstractAction) == 0
                }
                .map { it.abstractAction }*/
            val potentialAbstractActions = currentAppState.abstractTransitions.filter {
                phaseTargetAbstractActions.any { abstractAction ->
                    it.abstractAction.isEquivalent(abstractAction)

                }
                        && it.interactions.isEmpty()
            }.forEach {
                result.add(Goal(input = null,abstractAction = it.abstractAction))
            }
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
            outofbudgetWindows.contains(it.key) || fullyCoveredWindows.contains(it.key) || fullyExploredWindows.contains(
                it.key
            )
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
        abstractState.getActionCountMap(atuaMF).filter { it.key.isWidgetAction() }.map { it.value }.sum()

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
                (2 * scaleFactor).toInt()
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
            val minRandomBudget = (10 * scaleFactor).toInt()
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