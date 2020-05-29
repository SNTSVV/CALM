package org.droidmate.exploration.strategy.autaut.task

import kotlinx.coroutines.runBlocking
import org.droidmate.deviceInterface.exploration.*
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.*
import org.droidmate.exploration.modelFeatures.ActionCounterMF
import org.droidmate.exploration.modelFeatures.explorationWatchers.BlackListMF
import org.droidmate.exploration.modelFeatures.listOfSmallest
import org.droidmate.exploration.modelFeatures.autaut.staticModel.EventType
import org.droidmate.exploration.modelFeatures.autaut.RegressionTestingMF
import org.droidmate.exploration.modelFeatures.autaut.Rotation
import org.droidmate.exploration.modelFeatures.autaut.intent.IntentFilter
import org.droidmate.exploration.modelFeatures.autaut.staticModel.Helper
import org.droidmate.exploration.strategy.autaut.RegressionTestingStrategy
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.debugT
import org.droidmate.explorationModel.firstCenter
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.HashMap
import kotlin.math.abs
import kotlin.random.Random

abstract class AbstractStrategyTask (val regressionTestingStrategy: RegressionTestingStrategy,
                                          val regressionTestingMF: RegressionTestingMF,
                                          val delay: Long,
                                          val useCoordinateClicks: Boolean){


    protected var random = java.util.Random(Random.nextLong())
        private set

    protected var counter: ActionCounterMF = regressionTestingStrategy.getActionCounter()
    protected var blackList: BlackListMF = regressionTestingStrategy.getBlacklist()

    protected suspend fun getCandidates(widgets: List<Widget>): List<Widget> {
        return widgets
                .let { filteredCandidates ->
                    // for each widget in this state the number of interactions
                    counter.numExplored(regressionTestingStrategy.eContext.getCurrentState(), filteredCandidates).entries
                            .groupBy { it.key.packageName }.flatMap { (pkgName, countEntry) ->
                                if (pkgName != regressionTestingStrategy.eContext.apk.packageName) {
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
    }

    open suspend fun ExplorationContext<*, *, *>.computeCandidates(widgets: List<Widget>): Collection<Widget> = debugT("blacklist computation", {
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
    protected open suspend fun<S: State<*>> excludeBlacklisted(
            currentState: S,
            candidates: List<Widget>,
            tInState: Int = 1,
            tOverall: Int = 2,
            block: (listedInsState: List<Widget>, blacklisted: List<Widget>) -> List<Widget>
    ): List<Widget> =
            candidates.filterNot { blackList.isBlacklistedInState(it.uid, currentState.uid, tInState) }.let { noBlacklistedInState ->
                block(noBlacklistedInState, noBlacklistedInState.filterNot { blackList.isBlacklisted(it.uid, tOverall) })
            }

    protected val extraTasks = ArrayList<AbstractStrategyTask>()
    protected var currentExtraTask: AbstractStrategyTask? =null
    open protected fun executeExtraTasks(currentState: State<*>): List<Widget>{
        var widgets: List<Widget>
        if (currentExtraTask==null)
        {
            if (extraTasks.size > 0)
            {
                currentExtraTask = extraTasks.first()
                extraTasks.remove(currentExtraTask!!)
            }
        }
        while (currentExtraTask!=null)
        {
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
            if (extraTasks.size > 0)
            {
                currentExtraTask = extraTasks.first()
                extraTasks.remove(currentExtraTask!!)
            }
            else
            {
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

    open fun chooseWidgets(currentState: State<*>): List<Widget>{
         val visibleWidgets = currentState.widgets.filter {
             it.isVisible && !it.isKeyboard
                     && !it.isInputField && (it.clickable || it.longClickable || it.scrollable )}
         return visibleWidgets
     }

     abstract fun chooseAction(currentState:State<*>): ExplorationAction

     abstract fun reset()

    abstract fun initialize(currentState: State<*>)

    //Some task can have many options to execute
     abstract fun hasAnotherOption(currentState: State<*>): Boolean
     abstract fun chooseRandomOption (currentState: State<*>)

    abstract fun isTaskEnd(currentState: State<*>): Boolean

    internal fun chooseActionWithName(action: String, data: Any?, widget: Widget?, currentState: State<*>): ExplorationAction? {
        //special operating for list view
        if (widget == null)
        {
            return when (action){
                "PressMenu" -> pressMenuOrClickMoreOption(currentState)
                "PressBack" -> ExplorationAction.pressBack()
                "PressHome" -> ExplorationAction.minimizeMaximize()
                "MinimizeMaximize" -> ExplorationAction.minimizeMaximize()
                "RotateUI" -> {
                    if (regressionTestingMF.currentRotation==Rotation.PORTRAIT) {
                        ExplorationAction.rotate(90)
                    }
                    else
                    {
                        ExplorationAction.rotate(-90)
                    }

                }
                "CallIntent" -> callIntent(data)
                "Swipe" -> doSwipe(currentState,data as String)
                else -> ExplorationAction.pressBack()
            }
        }
        val chosenWidget: Widget = widget
        val isItemEvent = when (action) {
            "ItemClick", "ItemLongClick", "ItemSelected" -> true
            else -> false
        }

        if (isItemEvent) {
            return chooseActionForItemEvent(chosenWidget, currentState, action)
        } else {
            return chooseActionForNonItemEvent(action, chosenWidget, currentState, data)
        }
    }

    private fun doSwipe(currentState: State<*>, data: String): ExplorationAction? {
        var outBoundLayout = currentState.widgets.find { it.resourceId == "android.id/content" }
        if (outBoundLayout == null) {
            outBoundLayout = currentState.widgets.find { !it.hasParent }
        }
        if (outBoundLayout == null) {
            return ExplorationAction.pressBack()
        }
        val screenHeight = outBoundLayout.visibleBounds.height
        val screenWidth = outBoundLayout.visibleBounds.width
        val swipeAction = when (data) {
            "SwipeUp" -> {
                val startY = outBoundLayout.visibleBounds.bottomY
                ExplorationAction.swipe(Pair(screenWidth / 2, startY ), Pair(screenWidth / 2, startY - screenHeight / 2))
            }
            "SwipeDown" -> {
                val startY = outBoundLayout.visibleBounds.topY
                ExplorationAction.swipe(Pair(screenWidth / 2, startY), Pair(screenWidth / 2, startY + screenHeight / 2))
            }
            "SwipeLeft" -> {
                val startX = outBoundLayout.visibleBounds.rightX
                ExplorationAction.swipe(Pair(startX, screenHeight / 2), Pair(startX - screenWidth / 2, screenHeight / 2))}
            "SwipeRight" -> {
                val startX = outBoundLayout.visibleBounds.leftX
                ExplorationAction.swipe(Pair(startX, screenHeight / 2), Pair(startX + screenWidth / 2, screenHeight / 2))}
            else -> {
                if (random.nextBoolean()) {
                    //Swipe up
                    return ExplorationAction.swipe(Pair(screenWidth / 2, outBoundLayout.visibleBounds.bottomY), Pair(screenWidth / 2, screenHeight / 2))
                } else {
                    //Swipe right
                    return ExplorationAction.swipe(Pair(outBoundLayout.visibleBounds.leftX, screenHeight / 2), Pair(screenWidth / 2, screenHeight / 2))
                }

            }
        }
        return swipeAction
    }

    private fun chooseActionForNonItemEvent(action: String, chosenWidget: Widget, currentState: State<*>, data: Any?): ExplorationAction? {
        if (action == "TextInsert" && chosenWidget.isInputField) {
            return chooseActionForTextInput(chosenWidget, currentState)
        }
        if (action == "Swipe" && data is String) {
            val swipeAction =
             when (data) {
                "SwipeUp" -> chosenWidget.swipeUp()
                "SwipeDown" -> chosenWidget.swipeDown()
                "SwipeLeft" -> chosenWidget.swipeLeft()
                "SwipeRight" -> chosenWidget.swipeRight()
                else -> {
                    if (data.isNotBlank()) {
                        val swipeInfo: List<Pair<Int,Int>> = Helper.parseSwipeData(data)
                        Swipe(swipeInfo[0],swipeInfo[1],25,true)
                    } else {
                        arrayListOf(chosenWidget.swipeUp(), chosenWidget.swipeDown(),chosenWidget.swipeLeft(),chosenWidget.swipeRight()).random()
                    }
                }
            }
            ExplorationTrace.widgetTargets.clear()
            ExplorationTrace.widgetTargets.add(chosenWidget)
            return swipeAction
        }
        val actionList = chosenWidget.availableActions(delay, useCoordinateClicks)
        val widgetActions = actionList.filter {
            when (action) {
                "Click" -> (it.name == "Click" || it.name == "ClickEvent")
                "LongClick" -> it.name == "LongClick" || it.name == "LongClickEvent"
                "Swipe" -> it.name == "Swipe"
                else -> it.name == "Click" || it.name == "ClickEvent"
            }
        }
        if (widgetActions.isNotEmpty()) {

            return widgetActions.random()
        }
        ExplorationTrace.widgetTargets.clear()
        val hardAction = when (action) {
            "Click" -> chosenWidget.clickEvent(delay=delay, ignoreClickable = true)
            "LongClick" -> chosenWidget.longClickEvent(delay=delay, ignoreVisibility = true)
            else -> chosenWidget.click(ignoreClickable = true)
        }
        return hardAction
    }





    private fun chooseActionForTextInput(chosenWidget: Widget, currentState: State<*>): ExplorationAction {
        val inputValue = TextInput.getSetTextInputValue(chosenWidget, currentState)
        val explorationAction = chosenWidget.setText(inputValue, delay = delay, sendEnter = true)
        return explorationAction
    }

    private fun chooseActionForItemEvent(chosenWidget: Widget, currentState: State<*>, action: String): ExplorationAction? {
        if (chosenWidget.childHashes.size == 0)
            return null
        regressionTestingMF.isRecentItemAction = true
        var childWidgets = Helper.getAllInteractiveChild(currentState.widgets, chosenWidget)
        if (childWidgets.isEmpty()) {
            childWidgets = Helper.getAllInteractiveChild2(currentState.widgets, chosenWidget)
        }
        if (childWidgets.isEmpty()) {
            childWidgets = Helper.getAllChild(currentState.widgets, chosenWidget)
        }
        if (childWidgets.isEmpty()) {
            return null
        }
        val actionableWidgets = childWidgets.filter {
            when (action) {
                "ItemClick" -> it.clickable
                "ItemLongClick" -> it.longClickable
                "ItemSelected" -> it.clickable && it.selected?.let { it == false } ?: false && it.checked != null
                else -> it.clickable
            }
        }
        if (actionableWidgets.size > 0) {
            val randomWidgets =
                    runBlocking {
                        getCandidates(actionableWidgets)
                    }
            val randomWidget = if (randomWidgets.isEmpty())
                actionableWidgets.random()
            else
                randomWidgets.random()
            log.info("Item widget: $randomWidget")
            val chosenAction = randomWidget.availableActions(delay, useCoordinateClicks).find {
                when (action) {
                    "ItemClick" -> it.name == ClickEvent.name || it.name == Click.name
                    "ItemLongClick" -> it.name == LongClickEvent.name || it.name == LongClick.name
                    "ItemSelected" -> it.name == Tick.name
                    else -> it.name == ClickEvent.name || it.name == Click.name
                }
            }
            return chosenAction
        }

        val randomWidget = childWidgets[random.nextInt(childWidgets.size)]
        log.info("Item widget: $randomWidget")
        val hardAction = when (action) {
            "ItemClick" -> randomWidget.clickEvent(delay=delay, ignoreClickable = true)
            "ItemLongClick" -> randomWidget.longClickEvent(delay=delay, ignoreVisibility = true)
            "ItemSelected" -> randomWidget.clickEvent(delay=delay, ignoreClickable = true)
            else -> randomWidget.clickEvent(delay=delay, ignoreClickable = true)
        }
        return hardAction
    }

    private fun callIntent(data: Any?): CallIntent {
        if (data is IntentFilter) {
            val intentFilter = data as IntentFilter
            return regressionTestingStrategy.eContext.callIntent(intentFilter.getActions().random(),
                    intentFilter.getCategories().random(), intentFilter.getDatas().random().testData.random(), intentFilter.activity)

        } else {
            val intentData: HashMap<String,String> = parseIntentData(data as String)
            return regressionTestingStrategy.eContext.callIntent(
                    action = intentData["action"]?:"",
                    category = intentData["category"]?:"",
                    activity = intentData["activity"]?:"",
                    uriString = intentData["uriString"]?:""
            )
        }
    }

    private fun parseIntentData(s: String): HashMap<String, String> {
        val data = HashMap<String,String>()
        val splits = s.split(';')
        splits.forEach {
            val parts = it.split(" = ")
            val key = parts[0]
            val value = parts[1]
            data.put(key,value)
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
        val longClickAction = LongClick(x = coordinate.first, y = coordinate.second, delay = delay, hasWidgetTarget = false)
        return longClickAction
    }

    internal fun randomSwipe(chosenWidget: Widget, delay: Long, useCoordinateClicks: Boolean, actionList: ArrayList<ExplorationAction>) {
        val swipeActions = chosenWidget.availableActions(delay, useCoordinateClicks).filter { it is Swipe }
        val choseSwipe = swipeActions[random.nextInt(swipeActions.size)]
        actionList.add(choseSwipe)
    }
    internal fun haveOpenNavigationBar(currentState: State<*>): Boolean {
        if (currentState.widgets.filter { it.isVisible }.find { it.contentDesc.contains("Open navigation") } != null)
        {
            return true
        }
        return false
    }

    internal fun pressMenuOrClickMoreOption(currentState: State<*>): ExplorationAction {
        val moreOptionWidget = regressionTestingMF.getToolBarMoreOptions(currentState)
        if (moreOptionWidget != null) {
            log.info("Widget: $moreOptionWidget")
            return moreOptionWidget.click()

        } else {
            if (haveOpenNavigationBar(currentState))
            {
                return clickOnOpenNavigation(currentState)
            }
            regressionTestingMF.isRecentPressMenu = true
            return ExplorationAction.pressMenu()
        }
    }

     fun clickOnOpenNavigation(currentState: State<*>): ExplorationAction {
        val openNavigationWidget = currentState.widgets.filter { it.isVisible }.find { it.contentDesc.contains("Open navigation") }!!
         log.info("Widget: $openNavigationWidget")
        return chooseActionWithName("Click", null, openNavigationWidget, currentState)!!
    }

    fun isCameraOpening(currentState: State<*>): Boolean {
        return currentState.widgets.any{it.packageName == "com.android.camera2"}
    }
    /** filters out all crashing marked widgets from the actionable widgets of the current state **/
    suspend fun Collection<Widget>.nonCrashingWidgets() = filterNot { regressionTestingStrategy.eContext.crashlist.isBlacklistedInState(it.uid,regressionTestingStrategy.eContext.getCurrentState().uid) }
    companion object {
        private val log: Logger by lazy { LoggerFactory.getLogger(this.javaClass.name) }
    }

}