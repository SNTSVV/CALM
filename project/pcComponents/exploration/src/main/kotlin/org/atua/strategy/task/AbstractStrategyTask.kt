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

package org.atua.strategy.task

import kotlinx.coroutines.runBlocking
import org.calm.StringComparison
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.inputRepo.intent.IntentFilter
import org.atua.modelFeatures.inputRepo.textInput.TextInput
import org.droidmate.deviceInterface.exploration.ActionQueue
import org.droidmate.deviceInterface.exploration.ActionType
import org.droidmate.deviceInterface.exploration.Click
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.GlobalAction
import org.droidmate.deviceInterface.exploration.LongClick
import org.droidmate.deviceInterface.exploration.Rectangle
import org.droidmate.deviceInterface.exploration.Swipe
import org.droidmate.deviceInterface.exploration.isClick
import org.droidmate.deviceInterface.exploration.isEnabled
import org.droidmate.deviceInterface.exploration.isFetch
import org.droidmate.deviceInterface.exploration.isLongClick
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.availableActions
import org.droidmate.exploration.actions.callIntent
import org.droidmate.exploration.actions.click
import org.droidmate.exploration.actions.clickEvent
import org.droidmate.exploration.actions.launchApp
import org.droidmate.exploration.actions.longClick
import org.droidmate.exploration.actions.longClickEvent
import org.droidmate.exploration.actions.minimizeMaximize
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.pressEnter
import org.droidmate.exploration.actions.pressMenu
import org.droidmate.exploration.actions.resetApp
import org.droidmate.exploration.actions.rotate
import org.droidmate.exploration.actions.setText
import org.droidmate.exploration.actions.swipe
import org.droidmate.exploration.actions.swipeDown
import org.droidmate.exploration.actions.swipeLeft
import org.droidmate.exploration.actions.swipeRight
import org.droidmate.exploration.actions.swipeUp
import org.droidmate.exploration.modelFeatures.ActionCounterMF
import org.droidmate.exploration.modelFeatures.explorationWatchers.BlackListMF
import org.droidmate.exploration.modelFeatures.listOfSmallest
import org.atua.strategy.ATUATestingStrategy
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.debugT
import org.droidmate.explorationModel.firstCenter
import org.droidmate.explorationModel.interaction.Interaction
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

abstract class AbstractStrategyTask(
    val atuaStrategy: ATUATestingStrategy,
    val atuaMF: org.atua.modelFeatures.ATUAMF,
    val delay: Long,
    val useCoordinateClicks: Boolean
) {
    protected var random = java.util.Random(Random.nextLong())
        private set

    protected var counter: ActionCounterMF = atuaStrategy.getActionCounter()
    protected var blackList: BlackListMF = atuaStrategy.getBlacklist()

    protected suspend fun getCandidates(widgets: List<Widget>): List<Widget> {
        val lessExerciseWidgetsByUUID = widgets
            .let { filteredCandidates ->
                // for each widget in this state the number of interactions
                atuaMF.actionCount.widgetnNumExplored(
                    atuaStrategy.eContext.getCurrentState(),
                    filteredCandidates
                ).entries
                    .groupBy { it.key.packageName }.flatMap { (pkgName, countEntry) ->
                        if (pkgName != atuaStrategy.eContext.apk.packageName) {
                            val pkgActions = counter.pkgCount(pkgName)
                            countEntry.map { Pair(it.key, pkgActions) }
                        } else
                            countEntry.map { Pair(it.key, it.value) }
                    }// we sum up all counters of widgets which do not belong to the app package to prioritize app targets
                    .groupBy { (_, countVal) -> countVal }.let { map ->
                        map.listOfSmallest()?.map { (w, _) -> w }?.let { leastInState: List<Widget> ->
                            // determine the subset of widgets which were least interacted with
                            // if multiple widgets clicked with same frequency, choose the one least clicked over all states
                            if (leastInState.size > 1) {
                                leastInState.groupBy { counter.widgetCnt(it.uid) }.listOfSmallest()
                            } else leastInState
                        }
                    }
                    ?: emptyList()
            }

        val lessExceriseWidgets = lessExerciseWidgetsByUUID.let { filteredCandidates ->
            atuaMF.actionCount.widgetNumExplored2(atuaStrategy.eContext.getCurrentState(), filteredCandidates).entries
                .groupBy { it.value }.let { map ->
                    map.listOfSmallest()?.map { (w, _) -> w } ?: emptyList()
                }
        }
        // val lessExceriseWidgets = lessExerciseWidgetsByUUID
        return lessExceriseWidgets
    }

    open suspend fun ExplorationContext<*, *, *>.computeCandidates(widgets: List<Widget>): Collection<Widget> =
        debugT("blacklist computation", {
            val nonCrashing = widgets.nonCrashingWidgets()
            excludeBlacklisted(getCurrentState(), nonCrashing) { noBlacklistedInState, noBlacklisted ->
                when {
                    noBlacklisted.isNotEmpty() -> noBlacklisted
                    noBlacklistedInState.isNotEmpty() -> noBlacklistedInState
                    else -> nonCrashing
                }
            }
        }, inMillis = true)
            .filter { it.clickable || it.longClickable || it.checked != null } // the other actions are currently not supported

    /** use this function to filter potential candidates against previously blacklisted widgets
     * @param block your function determining the ExplorationAction based on the filtered candidates
     * @param tInState the threshold to consider the widget blacklisted within the current state eContext
     * @param tOverall the threshold to consider the widget blacklisted over all states
     */
    protected open suspend fun <S : State<*>> excludeBlacklisted(
        currentState: S,
        candidates: List<Widget>,
        tInState: Int = 1,
        tOverall: Int = 2,
        block: (listedInsState: List<Widget>, blacklisted: List<Widget>) -> List<Widget>
    ): List<Widget> =
        candidates.filterNot { blackList.isBlacklistedInState(it.uid, currentState.uid, tInState) }
            .let { noBlacklistedInState ->
                block(
                    noBlacklistedInState,
                    noBlacklistedInState.filterNot { blackList.isBlacklisted(it.uid, tOverall) })
            }

    protected val extraTasks = ArrayList<AbstractStrategyTask>()
    protected var currentExtraTask: AbstractStrategyTask? = null
    open protected fun executeExtraTasks(currentState: State<*>): List<Widget> {
        var widgets: List<Widget>
        if (currentExtraTask == null) {
            if (extraTasks.size > 0) {
                currentExtraTask = extraTasks.first()
                extraTasks.remove(currentExtraTask!!)
            }
        }
        while (currentExtraTask != null) {
            if (currentExtraTask!!.isAvailable(currentState)) {
                widgets = currentExtraTask!!.chooseWidgets(currentState)
                if (widgets.size > 0)
                    return widgets
                while (currentExtraTask!!.hasAnotherOption(currentState)) {
                    widgets = currentExtraTask!!.chooseWidgets(currentState)
                    if (widgets.size > 0)
                        return widgets
                }
            }
            if (extraTasks.size > 0) {
                currentExtraTask = extraTasks.first()
                extraTasks.remove(currentExtraTask!!)
            } else {
                currentExtraTask = null
            }
        }
        return emptyList()
    }

    open protected fun executeCurrentExtraTask(currentState: State<*>): List<Widget> {
        val widgets: List<Widget>
        if (currentExtraTask != null) {
            if (!currentExtraTask!!.isTaskEnd(currentState)) {
                widgets = currentExtraTask!!.chooseWidgets(currentState)
            } else {
                widgets = emptyList()
            }
        } else {
            widgets = emptyList()
        }
        return widgets
    }

    abstract fun isAvailable(currentState: State<*>): Boolean

    open fun chooseWidgets(currentState: State<*>): List<Widget> {
        val visibleWidgets = currentState.widgets.filter {
            it.isVisible && !it.isKeyboard
                    && !it.isInputField && (it.clickable || it.longClickable || it.scrollable)
        }
        return visibleWidgets
    }

    abstract fun chooseAction(currentState: State<*>): ExplorationAction?

    abstract fun reset()

    abstract fun initialize(currentState: State<*>)

    //Some task can have many options to execute
    abstract fun hasAnotherOption(currentState: State<*>): Boolean
    abstract fun chooseRandomOption(currentState: State<*>)

    abstract fun isTaskEnd(currentState: State<*>): Boolean

    internal fun chooseActionWithName(
        action: AbstractActionType,
        data: Any?,
        widget: Widget?,
        currentState: State<*>,
        abstractAction: AbstractAction?
    ): ExplorationAction? {
        //special operating for list view
        val currentAbstactState = atuaMF.getAbstractState(currentState)!!
        if (widget == null) {
            return when (action) {
                AbstractActionType.WAIT -> GlobalAction(ActionType.FetchGUI)
                AbstractActionType.ENABLE_DATA -> GlobalAction(ActionType.EnableData)
                AbstractActionType.DISABLE_DATA -> GlobalAction(ActionType.DisableData)
                AbstractActionType.PRESS_MENU -> pressMenuOrClickMoreOption(currentState)
                AbstractActionType.PRESS_BACK -> ExplorationAction.pressBack()
                AbstractActionType.PRESS_HOME -> ExplorationAction.minimizeMaximize()
                AbstractActionType.MINIMIZE_MAXIMIZE -> ExplorationAction.minimizeMaximize()
                AbstractActionType.ROTATE_UI -> {
                    if (currentAbstactState.rotation == org.atua.modelFeatures.Rotation.PORTRAIT) {
                        ExplorationAction.rotate(90)
                    } else {
                        ExplorationAction.rotate(-90)
                    }
                }
                AbstractActionType.SEND_INTENT -> callIntent(data)
                AbstractActionType.SWIPE -> doSwipe(currentState, data as String)
                AbstractActionType.LAUNCH_APP -> atuaStrategy.eContext.launchApp()
                AbstractActionType.RESET_APP -> atuaStrategy.eContext.resetApp()
                AbstractActionType.RANDOM_KEYBOARD -> doRandomKeyboard(currentState, data)
                AbstractActionType.CLOSE_KEYBOARD -> GlobalAction(ActionType.CloseKeyboard)
                AbstractActionType.CLICK_OUTBOUND -> doClickOutbound(currentState, abstractAction!!)
                AbstractActionType.ACTION_QUEUE -> doActionQueue(data, currentState)
                AbstractActionType.CLICK -> doClickWithoutTarget(data, currentState)
                AbstractActionType.UNKNOWN -> doUnderivedAction(data, currentState)
                else -> ExplorationAction.pressBack()
            }
        }
        val chosenWidget: Widget = widget
        val isItemEvent = when (action) {
            AbstractActionType.ITEM_CLICK, AbstractActionType.ITEM_LONGCLICK, AbstractActionType.ITEM_SELECTED -> true
            else -> false
        }

        if (isItemEvent) {
            return chooseActionForItemEvent(chosenWidget, currentState, action, abstractAction,data)
        } else {
            return chooseActionForNonItemEvent(action, chosenWidget, currentState, data, abstractAction)
        }
    }

    private fun doUnderivedAction(data: Any?, currentState: State<*>): ExplorationAction? {
        if (data !is Interaction<Widget>) {
            return null
        }
        val interaction = data as Interaction<Widget>
        var action: ExplorationAction? = null
        if (interaction.targetWidget == null) {
            action = when (interaction.actionType) {
                "PressBack" -> ExplorationAction.pressBack()
                "PressHome" -> ExplorationAction.pressMenu()
                "PressEnter" -> ExplorationAction.pressEnter()
                "Swipe" -> {
                    val swipeData = Helper.parseSwipeData(interaction.data)
                    ExplorationAction.swipe(swipeData[0], swipeData[1], 25)
                }
                else -> ExplorationAction.pressEnter()
            }
        } else {
            val widget = currentState.widgets.find { it.uid == interaction.targetWidget!!.uid }
            if (widget != null) {
                action = when (interaction.actionType) {
                    "Click" -> widget.availableActions(delay, useCoordinateClicks).filter {
                        it.name.isClick()
                    }.singleOrNull()
                    "LongClick" -> widget.availableActions(delay, useCoordinateClicks).filter {
                        it.name.isLongClick()
                    }.singleOrNull()
                    "Swipe" -> {
                        val swipeData = Helper.parseSwipeData(interaction.data)
                        ExplorationTrace.widgetTargets.add(widget)
                        Swipe(swipeData[0], swipeData[1], 25, true)
                    }
                    else -> widget.availableActions(delay, useCoordinateClicks).random()
                }
            }
        }
        return action
    }

    private fun doClickOutbound(currentState: State<*>, abstractAction: AbstractAction): ExplorationAction? {
        val guiDimension = Helper.computeGuiTreeDimension(currentState)
        if (guiDimension.leftX - 50 < 0) {
            return Click(guiDimension.leftX, y = guiDimension.topY - 50)
        }
        if (guiDimension.topY - 50 < 0)
            return Click(guiDimension.leftX - 50, y = guiDimension.topY)
        val abstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)!!
        abstractState.increaseActionCount2(abstractAction, atuaMF)
        return Click(guiDimension.leftX - 50, y = guiDimension.topY - 50)
    }

    private fun doClickWithoutTarget(data: Any?, currentState: State<*>): ExplorationAction? {
        if (data is String && data.isNotBlank()) {
            val point = Helper.parseCoordinationData(data)
            return Click(x = point.first, y = point.second)
        }
        val guiDimension = Helper.computeGuiTreeDimension(currentState)
        return Click(x = guiDimension.width / 2, y = guiDimension.height / 2)

    }

    private fun doActionQueue(data: Any?, currentState: State<*>): ExplorationAction? {
        if (data == null)
            return ExplorationAction.pressBack()
        try {
            val interactions = data as List<Interaction<Widget>>
            val actionList = ArrayList<ExplorationAction>()
            interactions.forEach { interaction ->
                if (interaction.targetWidget == null) {
                    val action = when (interaction.actionType) {
                        "PressBack" -> ExplorationAction.pressBack()
                        "PressHome" -> ExplorationAction.pressMenu()
                        "PressEnter" -> ExplorationAction.pressEnter()
                        "Swipe" -> {
                            val swipeData = Helper.parseSwipeData(interaction.data)
                            ExplorationAction.swipe(swipeData[0], swipeData[1], 25)
                        }
                        else -> ExplorationAction.pressEnter()
                    }
                    actionList.add(action)
                } else {
                    val widget = currentState.widgets.find { it.uid == interaction.targetWidget!!.uid }
                    if (widget != null) {
                        val action = when (interaction.actionType) {
                            "Click" -> widget.availableActions(delay, useCoordinateClicks).filter {
                                it.name.isClick()
                            }.singleOrNull()
                            "LongClick" -> widget.availableActions(delay, useCoordinateClicks).filter {
                                it.name.isLongClick()
                            }.singleOrNull()
                            "Swipe" -> {
                                val swipeData = Helper.parseSwipeData(interaction.data)
                                ExplorationTrace.widgetTargets.add(widget)
                                Swipe(swipeData[0], swipeData[1], 25, true)
                            }
                            else -> widget.availableActions(delay, useCoordinateClicks).random()
                        }
                        if (action != null) {
                            actionList.add(action)
                        } else {
                            if (interaction.actionType == "Click" || interaction.actionType == "LongClick") {
                                ExplorationTrace.widgetTargets.removeLast()
                            }
                        }
                    }
                }
            }
            if (actionList.isEmpty())
                return null
            return ActionQueue(actionList, 0)
        } catch (e: Exception) {
            log.debug("$e")
            return ExplorationAction.pressBack()
        }
    }

    fun doRandomKeyboard(currentState: State<*>, data: Any?): ExplorationAction? {
        var childWidgets = currentState.widgets.filter { it.isKeyboard && it.isInteractive }
        if (childWidgets.isEmpty())
            return null
        return childWidgets.random().click()
    }

    private fun doSwipe(currentState: State<*>, data: String): ExplorationAction? {
        var outBoundLayout = currentState.widgets.find { it.resourceId == "android.id/content" }
        if (outBoundLayout == null) {
            outBoundLayout = currentState.widgets.find { !it.hasParent }
        }
        if (outBoundLayout == null) {
            return ExplorationAction.pressBack()
        }
        val screenHeight =
            if (outBoundLayout.visibleBounds.height == 0)
                outBoundLayout.boundaries.height
            else
                outBoundLayout.visibleBounds.height
        val screenWidth = if (outBoundLayout.visibleBounds.width == 0)
            outBoundLayout.boundaries.width
        else
            outBoundLayout.visibleBounds.width
        val swipeAction = when (data) {
            "SwipeUp" -> {
                val startY = outBoundLayout.visibleBounds.bottomY - screenHeight / 4
                ExplorationAction.swipe(Pair(screenWidth / 2, startY), Pair(screenWidth / 2, startY - screenHeight))
            }
            "SwipeDown" -> {
                val startY = outBoundLayout.visibleBounds.topY + screenHeight / 4
                ExplorationAction.swipe(Pair(screenWidth / 2, startY), Pair(screenWidth / 2, startY + screenHeight))
            }
            "SwipeLeft" -> {
                val startX = outBoundLayout.visibleBounds.rightX - screenWidth / 4
                ExplorationAction.swipe(Pair(startX, screenHeight / 2), Pair(startX - screenWidth, screenHeight / 2))
            }
            "SwipeRight" -> {
                val startX = outBoundLayout.visibleBounds.leftX + screenWidth / 4
                ExplorationAction.swipe(Pair(startX, screenHeight / 2), Pair(startX + screenWidth, screenHeight / 2))
            }
            else -> {
                if (data.isNotBlank()) {
                    val swipeData = Helper.parseSwipeData(data)
                    if (swipeData.size == 2) {
                        return ExplorationAction.swipe(swipeData[0], swipeData[1], 25)
                    }
                }
                if (random.nextBoolean()) {
                    //Swipe up
                    return ExplorationAction.swipe(
                        Pair(screenWidth / 2, outBoundLayout.visibleBounds.bottomY),
                        Pair(screenWidth / 2, outBoundLayout.visibleBounds.bottomY - screenHeight)
                    )
                } else {
                    //Swipe right
                    return ExplorationAction.swipe(
                        Pair(outBoundLayout.visibleBounds.leftX, screenHeight / 2),
                        Pair(outBoundLayout.visibleBounds.leftX + screenWidth, screenHeight / 2)
                    )
                }

            }
        }
        return swipeAction
    }

    private fun chooseActionForNonItemEvent(
        action: AbstractActionType,
        chosenWidget: Widget,
        currentState: State<*>,
        data: Any?,
        abstractAction: AbstractAction?
    ): ExplorationAction? {
        if (action == AbstractActionType.TEXT_INSERT && chosenWidget.isInputField) {
            return chooseActionForTextInput(chosenWidget, currentState, data as String?)
        }
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!

        if (chosenWidget.className == "android.webkit.WebView") {
            val explorationAction: ExplorationAction?;
            var childWidgets = Helper.getAllChild(currentState.widgets, chosenWidget)
            //val allAvailableActions = childWidgets.plus(chosenWidget).map { it.availableActions(delay, true)}.flatten()
            val actionList: ArrayList<ExplorationAction> = ArrayList<ExplorationAction>()
            if (childWidgets.isEmpty()) {
                if (abstractAction != null) {
                    abstractAction.attributeValuationMap!!.removeAction(abstractAction,atuaMF)
                }
                return null
            }
            if (action == AbstractActionType.CLICK) {
                if (data == "RandomMultiple") {
                    for (i in 0..10) {
                        actionList.add(childWidgets.random().click())
                    }
                    explorationAction = ActionQueue(actionList, 50)
                } else if (data is String && data != "") {
                    val targetChildWidgets = childWidgets.filter { it.nlpText == data || StringComparison.compareStringsLevenshtein(it.nlpText,data)>0.4 }
                    val webViewWidget = if (targetChildWidgets.isNotEmpty())
                         targetChildWidgets.random()
                    else
                        childWidgets.random()
                    explorationAction = webViewWidget.click()
                    if (abstractAction!=null && currentAbstractState.getAttributeValuationSet(webViewWidget, currentState, atuaMF)!=null) {
                        currentAbstractState.increaseActionCount2(abstractAction,atuaMF)
                    }
                } else {
                    val webViewWidget = childWidgets.random()
                    explorationAction = webViewWidget.click()
                    if (abstractAction!=null && currentAbstractState.getAttributeValuationSet(webViewWidget, currentState, atuaMF)!=null) {
                        currentAbstractState.increaseActionCount2(abstractAction,atuaMF)
                    }
                }
            } else if (action == AbstractActionType.LONGCLICK) {

                if (data == "RandomMultiple") {
                    //val actions = allAvailableActions.filter {it is LongClickEvent || it is LongClick }
                    for (i in 0..5) {
                        actionList.add(childWidgets.random().longClick())
                    }
                    explorationAction = ActionQueue(actionList, 50)
                } else if (data is String && data != "")  {
                    val targetChildWidgets = childWidgets.filter { it.nlpText == data || StringComparison.compareStringsLevenshtein(it.nlpText,data)>0.4}
                    val webViewWidget = if (targetChildWidgets.isNotEmpty())
                        targetChildWidgets.random()
                    else
                        childWidgets.random()
                    explorationAction = webViewWidget.longClick()
                } else {
                    val webViewWidget = childWidgets.random()
                    explorationAction = webViewWidget.longClick()
                }
            } else if (action == AbstractActionType.SWIPE && data is String) {
                val swipeableWidgets = childWidgets.union(listOf(chosenWidget)).filter { Helper.isScrollableWidget(it) }
                val actionWidget = if (swipeableWidgets.isEmpty()) {
                    chosenWidget
                } else
                    swipeableWidgets.random()
                val swipeAction =
                    computeSwipeAction(data, actionWidget, currentState, abstractAction, currentAbstractState)
                currentAbstractState.increaseActionCount2(abstractAction!!, atuaMF)
                return swipeAction
            } else {
                if (abstractAction != null) {
                    abstractAction.attributeValuationMap!!.removeAction(abstractAction,atuaMF)
                }
                return null
            }
            if (abstractAction != null) {
                currentAbstractState.increaseActionCount2(abstractAction, atuaMF)
            }
            return explorationAction
        }
        if (action == AbstractActionType.SWIPE && data is String) {
            val swipeAction =
                computeSwipeAction(data, chosenWidget, currentState, abstractAction, currentAbstractState)

            return swipeAction
        }
        val actionList = Helper.getAvailableActionsForWidget(chosenWidget, currentState, delay, useCoordinateClicks)
        val widgetActions = actionList.filter {
            when (action) {
                AbstractActionType.CLICK -> (it.name == "Click" || it.name == "ClickEvent")
                AbstractActionType.LONGCLICK -> it.name == "LongClick" || it.name == "LongClickEvent"
                AbstractActionType.SWIPE -> it.name == "Swipe"
                else -> it.name == "Click" || it.name == "ClickEvent"
            }
        }
        if (widgetActions.isNotEmpty()) {
            return widgetActions.random()
        }
        ExplorationTrace.widgetTargets.clear()
        val hardAction = when (action) {
            AbstractActionType.CLICK -> chosenWidget.clickEvent(delay = delay, ignoreClickable = true)
            AbstractActionType.LONGCLICK -> chosenWidget.longClickEvent(delay = delay, ignoreVisibility = true)
            else -> chosenWidget.click(ignoreClickable = true)
        }
        return hardAction
    }

    private fun computeSwipeAction(
        data: String,
        actionWidget: Widget,
        currentState: State<*>,
        abstractAction: AbstractAction?,
        currentAbstractState: AbstractState
    ): ExplorationAction? {
        var scrollWidget = actionWidget
        if (actionWidget.className == "androidx.viewpager.widget.ViewPager"
            || actionWidget.className == "android.widget.ScrollView") {
            var childWidget: Widget = actionWidget
            while (childWidget.childHashes.size==1) {
                childWidget = currentState.widgets.find { it.idHash == childWidget.childHashes.single()}!!
                if (childWidget.visibleBounds.isNotEmpty() && childWidget.focused.isEnabled()) {
                    scrollWidget = childWidget
                    break
                } else if (childWidget.childHashes.size>1 && childWidget.visibleBounds.isNotEmpty()) {
                    scrollWidget = childWidget
                    break
                }
            }
        }
        val swipeAction =
            when (data) {
                "SwipeUp" -> {
                    if (scrollWidget.className == "androidx.recyclerview.widget.RecyclerView") {
                        val childWidgets = currentState.widgets.filter { it.parentId == scrollWidget.id }
                        if (childWidgets.isNotEmpty() && !childWidgets.any { it.boundaries.topY == scrollWidget.boundaries.topY }) {
                            // Here, the children of this RecyclerView are layout specially (i.e., there is a space before the first item)
                            val visibleBounderies = childWidgets.fold(Rectangle.empty(), {r,w->
                                val left = min(r.leftX,w.visibleBounds.leftX)
                                val right = max(r.rightX,w.visibleBounds.rightX)
                                val top = min(r.topY,w.visibleBounds.topY)
                                val bottom = max(r.bottomY,w.visibleBounds.bottomY)
                                Rectangle(left,top,right-left,bottom-top)
                            })
                            Swipe(Pair(visibleBounderies.center.first, visibleBounderies.center.second+visibleBounderies.height/4) , Pair(visibleBounderies.center.first, visibleBounderies.topY), 35, true)
                        } else {
                            scrollWidget.swipeUp()
                        }
                    } else {
                        scrollWidget.swipeUp()
                    }
                }
                "SwipeDown" -> scrollWidget.swipeDown()
                "SwipeLeft" -> scrollWidget.swipeLeft()
                "SwipeRight" -> scrollWidget.swipeRight()
                "SwipeTillEnd" -> doDeepSwipeUp(scrollWidget, currentState).also {
                    if (abstractAction != null)
                        currentAbstractState.increaseActionCount2(abstractAction, atuaMF)
                }
                else -> {
                    if (data.isNotBlank()) {
                        val swipeInfo: List<Pair<Int, Int>> = Helper.parseSwipeData(data)
                        Swipe(swipeInfo[0], swipeInfo[1], 25, true)
                    } else {
                        arrayListOf(
                            scrollWidget.swipeUp(),
                            scrollWidget.swipeDown(),
                            scrollWidget.swipeLeft(),
                            scrollWidget.swipeRight()
                        ).random()
                    }
                }
            }
        ExplorationTrace.widgetTargets.clear()
        ExplorationTrace.widgetTargets.add(actionWidget)
        return swipeAction
    }

    fun doRandomActionOnWidget(chosenWidget: Widget, currentState: State<*>): ExplorationAction {
        var actionList = Helper.getAvailableActionsForWidget(chosenWidget, currentState, delay, useCoordinateClicks)
        if (actionList.isNotEmpty()) {
            val maxVal = actionList.size

            assert(maxVal > 0) { "No actions can be performed on the widget $chosenWidget" }

            //val randomAction = chooseActionWithName(AbstractActionType.values().find { it.actionName.equals(actionList[randomIdx].name) }!!, "", chosenWidget, currentState, null)
            val randomAction = actionList.random()
            log.info("$randomAction")
            return randomAction ?: ExplorationAction.pressBack().also { log.info("Action null -> PressBack") }
        } else {
            ExplorationTrace.widgetTargets.clear()
            if (!chosenWidget.hasClickableDescendant && chosenWidget.selected.isEnabled()) {
                return chosenWidget.longClick()
            } else {
                return ExplorationAction.pressBack()
            }

        }
    }


    private fun doDeepSwipeUp(chosenWidget: Widget, currentState: State<*>): ExplorationAction? {
        val actionList = ArrayList<ExplorationAction>()
        if (chosenWidget.className == "android.webkit.WebView") {
            var childWidgets = getChildWidgets(currentState, chosenWidget,AbstractActionType.SWIPE)
            val swipeActions = childWidgets.filter { it.scrollable }.map { it.swipeUp(stepSize = 5) }
            if (swipeActions.isNotEmpty()) {
                for (i in 0..5) {
                    actionList.add(swipeActions.random())
                }
            } else {
                val randomForcedSwipeActions = childWidgets.map { it.swipeUp(stepSize = 5) }
                for (i in 0..5) {
                    actionList.add(randomForcedSwipeActions.random())
                }
            }

        } else {
            for (i in 0..5) {
                actionList.add(chosenWidget.swipeUp(stepSize = 5))
            }
        }
        return ActionQueue(actionList, 0)
    }


    private fun chooseActionForTextInput(chosenWidget: Widget, currentState: State<*>, data: String?): ExplorationAction {
        if (data == null) {
            val inputValue = TextInput.getSetTextInputValue(chosenWidget, currentState, true, InputCoverage.FILL_RANDOM)
            val explorationAction = chosenWidget.setText(inputValue, delay = delay, sendEnter = false)
            return explorationAction
        }
        val explorationAction = chosenWidget.setText(data, delay = delay, sendEnter = false)
        return explorationAction
    }

    private fun chooseActionForItemEvent(
        chosenWidget: Widget,
        currentState: State<*>,
        action: AbstractActionType,
        abstractAction: AbstractAction?,
        data: Any?
    ): ExplorationAction? {
        if (chosenWidget.childHashes.size == 0)
            return null
        atuaMF.isRecentItemAction = true
        var explorationAction: ExplorationAction? = null
        var candidateWidgets = ArrayList(getChildWidgets(currentState, chosenWidget,action))
        if (candidateWidgets.isEmpty()) {
            /*if (abstractAction != null) {
                abstractAction.attributeValuationMap!!.removeAction(abstractAction)
            }*/
            return null
        }
        val currentAbstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)!!
       /* if (abstractAction != null)
            currentAbstractState.increaseActionCount2(abstractAction, false)*/
        if (chosenWidget.className == "android.webkit.WebView") {
            if (data is String && data != "") {
                val targetChildWidgets = candidateWidgets.filter { it.nlpText == data || StringComparison.compareStringsLevenshtein(it.nlpText,data)>0.4 }
                if (targetChildWidgets.isNotEmpty()) {
                    candidateWidgets.clear()
                    candidateWidgets.addAll(targetChildWidgets)
                }
            }
        }
        if (candidateWidgets.size > 0) {
            val lessTryWidgets =
                runBlocking {
                    getCandidates(candidateWidgets)
                }
            val chosenWidget = if (lessTryWidgets.isEmpty())
                candidateWidgets.random()
            else
                lessTryWidgets.random()
            log.info("Item widget: $chosenWidget")
//            if (abstractAction!=null && currentAbstractState.getAttributeValuationSet(chosenWidget, currentState, atuaMF)!=null) {
//                currentAbstractState.increaseActionCount2(abstractAction,true)
//            }
            val chosenAction = when (action) {
                    AbstractActionType.ITEM_CLICK -> chosenWidget.click()
                    AbstractActionType.ITEM_LONGCLICK -> chosenWidget.longClick()
                    AbstractActionType.ITEM_SELECTED ->chosenWidget.click()
                    else -> chosenWidget.click()
            }
            return chosenAction
        }

        val randomWidget = candidateWidgets[random.nextInt(candidateWidgets.size)]
        log.info("Item widget: $randomWidget")
        val hardAction = when (action) {
            AbstractActionType.ITEM_CLICK -> randomWidget.clickEvent(delay = delay, ignoreClickable = true)
            AbstractActionType.ITEM_LONGCLICK -> randomWidget.longClickEvent(delay = delay, ignoreVisibility = true)
            AbstractActionType.ITEM_SELECTED -> randomWidget.clickEvent(delay = delay, ignoreClickable = true)
            else -> randomWidget.clickEvent(delay = delay, ignoreClickable = true)
        }
        return hardAction
    }

    private fun getChildWidgets(currentState: State<*>, chosenWidget: Widget, actionType: AbstractActionType): List<Widget> {
        var childWidgets = Helper.getAllInteractiveChild(currentState.widgets, chosenWidget)
        if (childWidgets.isEmpty()) {
            childWidgets = Helper.getAllInteractiveChild2(currentState.widgets, chosenWidget)
        }
        if (childWidgets.isEmpty()) {
            childWidgets = Helper.getAllChild(currentState.widgets, chosenWidget)
        }
        return childWidgets.filter {
            when (actionType) {
                AbstractActionType.CLICK,AbstractActionType.ITEM_CLICK -> it.clickable
                AbstractActionType.LONGCLICK, AbstractActionType.ITEM_LONGCLICK -> it.longClickable || !it.hasClickableDescendant
                AbstractActionType.ITEM_SELECTED -> it.clickable && it.selected?.let { it == false } ?: false && it.checked != null
                else -> false
            }
        }
    }

    private fun callIntent(data: Any?): ExplorationAction {
        if (data is IntentFilter) {
            val intentFilter = data as IntentFilter
            val action = intentFilter.getActions().random()
            val category = if (intentFilter.getCategories().isNotEmpty())
                intentFilter.getCategories().random()
            else
                ""
            val data = if (intentFilter.getDatas().isNotEmpty())
                intentFilter.getDatas().random().testData.random()
            else
                ""
            return atuaStrategy.eContext.callIntent(
                action,
                category, data, intentFilter.activity
            )

        } else {
            if (data == null) {
                return GlobalAction(ActionType.FetchGUI)
            }
            val intentData: HashMap<String, String> = parseIntentData(data as String)
            if (intentData["activity"] == null) {
                return GlobalAction(ActionType.FetchGUI)
            }
            return atuaStrategy.eContext.callIntent(
                action = intentData["action"] ?: "",
                category = intentData["category"] ?: "",
                activity = intentData["activity"] ?: "",
                uriString = intentData["uriString"] ?: ""
            )
        }
    }

    private fun parseIntentData(s: String): HashMap<String, String> {
        val data = HashMap<String, String>()
        val splits = s.split(';')
        splits.forEach {
            val parts = it.split(" = ")
            val key = parts[0]
            val value = parts[1]
            data.put(key, value)
        }
        return data
    }

    private fun hasTextInput(currentState: State<*>): Boolean {
        return currentState.actionableWidgets.find { it.isInputField } != null
    }


    internal fun hardClick(chosenWidget: Widget, delay: Long): Click {
        val coordinate: Pair<Int, Int> = chosenWidget.let {
            if (it.visibleAreas.firstCenter() != null)
                it.visibleAreas.firstCenter()!!
            else if (it.visibleBounds.width != 0 && it.visibleBounds.height != 0)
                it.visibleBounds.center
            else
                it.boundaries.center
        }
        val clickAction = Click(x = coordinate.first, y = coordinate.second, delay = delay, hasWidgetTarget = false)
        return clickAction
    }

    internal fun hardLongClick(chosenWidget: Widget, delay: Long): LongClick {
        val coordinate: Pair<Int, Int> = chosenWidget.let {
            if (it.visibleAreas.firstCenter() != null)
                it.visibleAreas.firstCenter()!!
            else if (it.visibleBounds.width != 0 && it.visibleBounds.height != 0)
                it.visibleBounds.center
            else
                it.boundaries.center
        }
        val longClickAction =
            LongClick(x = coordinate.first, y = coordinate.second, delay = delay, hasWidgetTarget = false)
        return longClickAction
    }

    internal fun randomSwipe(
        chosenWidget: Widget,
        delay: Long,
        useCoordinateClicks: Boolean,
        actionList: ArrayList<ExplorationAction>
    ) {
        val swipeActions = chosenWidget.availableActions(delay, useCoordinateClicks).filter { it is Swipe }
        val choseSwipe = swipeActions[random.nextInt(swipeActions.size)]
        actionList.add(choseSwipe)
    }

    internal fun haveOpenNavigationBar(currentState: State<*>): Boolean {
        if (currentState.widgets.filter { it.isVisible }.find { it.contentDesc.contains("Open navigation") } != null) {
            return true
        }
        return false
    }

    internal fun pressMenuOrClickMoreOption(currentState: State<*>): ExplorationAction {
        return ExplorationAction.pressMenu()
    }

    var waitForCameraApp = false
    var isClickedShutterButton = false
    internal fun dealWithCamera(currentState: State<*>,explorationContext: ExplorationContext<*,*,*>): ExplorationAction {
        val gotItButton = currentState.widgets.find { it.text.toLowerCase().equals("got it") }
        if (gotItButton != null) {
            log.info("Widget: $gotItButton")
            return gotItButton.click()
        }
        if (!isClickedShutterButton) {
            val shutterbutton = currentState.widgets.find { it.resourceId.contains("shutter_button") }
            if (shutterbutton != null) {
                log.info("Widget: $shutterbutton")
                val clickActions =
                    shutterbutton.availableActions(delay, useCoordinateClicks).filter { it.name.isClick() }
                if (clickActions.isNotEmpty()) {
                    isClickedShutterButton = true
                    return clickActions.random()
                }
                ExplorationTrace.widgetTargets.clear()
            } else {
                if (!explorationContext.getLastAction().actionType.isFetch()) {
                    return GlobalAction(actionType = ActionType.FetchGUI)
                }
            }
        }
        isClickedShutterButton = false
        val doneButton = currentState.widgets.find { it.resourceId.contains("done") }
        if (doneButton != null) {
            log.info("Widget: $doneButton")
            val clickActions = doneButton.availableActions(delay, useCoordinateClicks).filter { it.name.isClick() }
            if (clickActions.isNotEmpty()) {
                return clickActions.random()
            }
            ExplorationTrace.widgetTargets.clear()
        }
        if (explorationContext.getLastAction().actionType.isFetch()) {
            log.info("Cannot find the expected widgets in the camera app. Press back.")
            return ExplorationAction.pressBack()
        }
        return GlobalAction(actionType = ActionType.FetchGUI)
    }

    fun clickOnOpenNavigation(currentState: State<*>): ExplorationAction {
        val openNavigationWidget =
            currentState.widgets.filter { it.isVisible }.find { it.contentDesc.contains("Open navigation") }!!
        log.info("Widget: $openNavigationWidget")
        return chooseActionWithName(AbstractActionType.CLICK, null, openNavigationWidget, currentState, null)!!
    }

    fun isCameraOpening(currentState: State<*>): Boolean {
        return  currentState.widgets.any { it.packageName == "com.android.camera2" || it.packageName == "com.android.camera" }
    }

    /** filters out all crashing marked widgets from the actionable widgets of the current state **/
    suspend fun Collection<Widget>.nonCrashingWidgets() = filterNot {
        atuaStrategy.eContext.crashlist.isBlacklistedInState(
            it.uid,
            atuaStrategy.eContext.getCurrentState().uid
        )
    }

    protected fun dealWithKeyboard(currentState: State<*>): ExplorationAction? {
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        if (atuaMF.packageName == "de.rampro.activitydiary" || currentAbstractState.shouldNotCloseKeyboard) {
            if (random.nextBoolean()) {
                return GlobalAction(actionType = ActionType.CloseKeyboard)
            }
            if (random.nextBoolean()) {
                return doRandomKeyboard(currentState, null)!!
            }
            //find search button
            val searchButtons = currentState.visibleTargets.filter { it.isKeyboard }
                .filter { it.contentDesc.toLowerCase().contains("search") }
            if (searchButtons.isNotEmpty()) {
                //Give a 50/50 chance to click on the search button
                if (random.nextBoolean()) {
                    val randomButton = searchButtons.random()
                    log.info("Widget: $random")
                    return randomButton.click()
                }
            }
            return null
        } else {
            return GlobalAction(actionType = ActionType.CloseKeyboard)
        }
    }

    protected fun shouldRandomExplorationOutOfApp(
        currentAbstractState: AbstractState,
        currentState: State<*>
    ): Boolean {
        if (isCameraOpening(currentState)) {
            return true
        }
        if (currentAbstractState.activity == "com.android.internal.app.ChooserActivity"
            || currentAbstractState.activity == "com.android.internal.app.ResolverActivity"
        )
            return true
        if (currentAbstractState.window is Dialog) {
            return true
        }
        if (currentAbstractState.window is OutOfApp)
            return false
        return false
    }

    protected fun chooseGUIWidgetFromAbstractAction(
        randomAction: AbstractAction,
        currentState: State<*>
    ): Widget? {
        var chosenWidget1:Widget? = null
        val chosenWidgets = randomAction.attributeValuationMap!!.getGUIWidgets(currentState,randomAction.window)
        if (chosenWidgets.isEmpty()) {
            chosenWidget1 = null
        } else {
            val candidates = runBlocking { getCandidates(chosenWidgets) }
            chosenWidget1 = if (candidates.isEmpty())
                chosenWidgets.random()
            else
                candidates.random()
        }
        return chosenWidget1
    }

    companion object {
        private val log: Logger by lazy { LoggerFactory.getLogger(this.javaClass.name) }
    }

}