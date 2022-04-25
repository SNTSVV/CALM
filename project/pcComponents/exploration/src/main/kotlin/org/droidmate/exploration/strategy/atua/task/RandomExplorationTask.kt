package org.droidmate.exploration.strategy.atua.task

import kotlinx.coroutines.runBlocking
import org.atua.calm.modelReuse.ModelHistoryInformation
import org.atua.modelFeatures.Rotation
import org.droidmate.deviceInterface.exploration.*
import org.droidmate.exploration.actions.*
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.dstg.PredictedAbstractState
import org.atua.modelFeatures.dstg.VirtualAbstractState
import org.atua.modelFeatures.ewtg.EventType
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.WindowManager
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.modelFeatures.ewtg.window.Window
import org.atua.modelFeatures.ewtg.window.OutOfApp
import org.atua.modelFeatures.helper.ProbabilityBasedPathFinder
import org.atua.modelFeatures.helper.ProbabilityDistribution
import org.droidmate.exploration.strategy.atua.ATUATestingStrategy
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.max

class RandomExplorationTask constructor(
    regressionTestingMF: org.atua.modelFeatures.ATUAMF,
    atuaTestingStrategy: ATUATestingStrategy,
    delay: Long, useCoordinateClicks: Boolean,
    private var randomScroll: Boolean,
    private var maximumAttempt: Int) : AbstractStrategyTask(atuaTestingStrategy, regressionTestingMF, delay, useCoordinateClicks) {
    var stopGenerateUserlikeInput: Boolean = false
    private val MAX_ATTEMP_EACH_EXECUTION = (5*atuaTestingStrategy.scaleFactor).toInt()
    private var prevAbState: AbstractState? = null
    private val BACK_PROB = 0.1
    private val PRESSMENU_PROB = 0.2
    private val ROTATE_PROB = 0.05
    private val SWIPE_PROB = 0.5
    private val clickNavigationUpTask = ClickNavigationUpTask(regressionTestingMF, atuaTestingStrategy, delay, useCoordinateClicks)
    private val fillDataTask = PrepareContextTask(regressionTestingMF, atuaTestingStrategy, delay, useCoordinateClicks)
    private var qlearningRunning = false
    private var qlearningSteps = 0
    var goToLockedWindowTask: GoToAnotherWindowTask? = null
    protected var openNavigationBarTask = OpenNavigationBarTask.getInstance(regressionTestingMF, atuaTestingStrategy, delay, useCoordinateClicks)
    var fillingData = false
    var dataFilled = false
    private var initialExerciseCount = -1
    private var currentExerciseCount = -1
    var reset = false
    var backAction = true
    var isPureRandom: Boolean = false
    var recentChangedSystemConfiguration: Boolean = false
    var environmentChange: Boolean = false
    var alwaysUseRandomInput: Boolean = false
    var stopWhenHavingTestPath: Boolean = false
    var forcingEndTask: Boolean = false

    var lockedWindow: Window? = null
    var lastAction: AbstractAction? = null
    var isScrollToEnd = false

    val recentActions = ArrayList<AbstractAction>()
    var randomInDialogCnt = 0

    val userInputFilled = ArrayList<UUID>()
    init {
        reset()
    }
    override fun reset() {
        randomInDialogCnt = 0
        forcingEndTask = false
        attemptCount = 0
        prevAbState = null
        isPureRandom = false
        initialExerciseCount = -1
        currentExerciseCount = -1
        dataFilled = false
        fillingData = false
        fillDataTask.reset()
        lockedWindow = null
        recentChangedSystemConfiguration = false
        environmentChange = false
        lastAction = null
        alwaysUseRandomInput = false
        triedRandomKeyboard = false
        stopWhenHavingTestPath = false
        recentActions.clear()
        recentGoToExploreState = false
        qlearningRunning = false
        qlearningSteps = 0
        goToLockedWindowTask = null
        userInputFilled.clear()
    }

    override fun initialize(currentState: State<*>) {
        reset()
        setMaxiumAttempt(currentState, MAX_ATTEMP_EACH_EXECUTION)
    }

    fun lockTargetWindow(window: Window) {
        lockedWindow = window

    }

    override fun isTaskEnd(currentState: State<*>): Boolean {
        if (forcingEndTask)
            return true
        if (isCameraOpening(currentState))
            return false
        if (fillingData == true) {
            return false
        }
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        if (currentAbstractState.isOpeningKeyboard)
            return false
        if (attemptCount >= maximumAttempt) {
            return true
        }
        /*if (isFullyExploration)
            return false
        val currentAbstractState = autautMF.getAbstractState(currentState)!!
        if (prevAbState == null)
            return false
        if (currentAbstractState.window != prevAbState!!.window && currentAbstractState.window !is WTGOutScopeNode)
        {
            if (lockedWindow != null) {
                val testPaths = autautStrategy.phaseStrategy.getPathsToWindow(currentState,lockedWindow!!,true)
                if (testPaths.isNotEmpty())
                    return false
            }

        }*/
        return false
        /* if (initialExerciseCount < currentExerciseCount-1)
             return true*/
    }

    fun setMaxiumAttempt(currentState: State<*>, minAttempt: Int) {
        val actionBasedAttempt = (atuaMF.getAbstractState(currentState)?.getUnExercisedActions(currentState,atuaMF)?.size
                ?: 1)
        maximumAttempt = max(actionBasedAttempt, minAttempt)
    }

    fun setMaxiumAttempt(attempt: Int) {
        maximumAttempt = attempt
    }

    override fun chooseRandomOption(currentState: State<*>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    var attemptCount = 0
    override fun isAvailable(currentState: State<*>): Boolean {
        return true
    }

    override fun chooseWidgets(currentState: State<*>): List<Widget> {
        /*if (!isFullyExploration )
        {
            val unexercisedWidgets = autautMF.getLeastExerciseWidgets(currentState)
            if (unexercisedWidgets.isNotEmpty())
            {
                if (initialExerciseCount==-1)
                {
                    initialExerciseCount = unexercisedWidgets.entries.first().value.exerciseCount
                }
                currentExerciseCount = unexercisedWidgets.entries.first().value.exerciseCount
               *//* if (unexercisedWidgets.size>2 &&
                        unexercisedWidgets.values.find { it.resourceIdName.contains("confirm")
                                || it.resourceIdName.contains("cancel") }!=null)
                {

                    return unexercisedWidgets.filter { !it.value.resourceIdName.contains("confirm")
                            && !it.value.resourceIdName.contains("cancel") }.map { it.key }
                }
                if (unexercisedWidgets.size>1 && unexercisedWidgets.values.find {
                                it.resourceIdName.contains("cancel") }!=null)
                {
                    return unexercisedWidgets.filter {
                        !it.value.resourceIdName.contains("cancel") }.map { it.key }
                }*//*
                return unexercisedWidgets.map { it.key }.filter { !it.checked.isEnabled() }
            }
        }*/
        val visibleWidgets: List<Widget>
        visibleWidgets = Helper.getActionableWidgetsWithoutKeyboard(currentState)
        if (currentState.widgets.filter { it.className == "android.webkit.WebView" }.isNotEmpty()) {
            return ArrayList(currentState.widgets.filter { it.className == "android.webkit.WebView" }).also { it.addAll(visibleWidgets) }
        }
        /*if (random.nextInt(100)/100.toDouble()<0.5)
        {
            visibleWidgets = currentState.visibleTargets
        }
        else
        {
            visibleWidgets = Helper.getVisibleInteractableWidgets(currentState)
        }*/

        if (visibleWidgets.isNotEmpty()) {
            return visibleWidgets
        }
        //when DM2 provides incorrect information
        return emptyList()
    }

    var tryLastAction = 0
    val MAX_TRY_LAST_ACTION = 3
    var triedRandomKeyboard = false
    var recentGoToExploreState = false
    var actionOnOutOfAppCount = 0

    override fun chooseAction(currentState: State<*>): ExplorationAction? {
        executedCount++
        atuaMF.isRandomExploration = true
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        val unexercisedActions = currentAbstractState.getUnExercisedActions(currentState,atuaMF)
            .filter {
                !it.isCheckableOrTextInput(currentAbstractState)
                    && currentAbstractState.getInputsByAbstractAction(it).any {
                    it.meaningfulScore>0
                }
            }

        val widgetActions1 = unexercisedActions.filter {
            it.attributeValuationMap != null
        }
        if (goToLockedWindowTask != null) {
            // should go back to target Window
            // reset data filling
                if (lockedWindow!=null && currentAbstractState.window == lockedWindow && widgetActions1.isNotEmpty()) {
                    goToLockedWindowTask = null
                } else if(lockedWindow == null && widgetActions1.isNotEmpty()){
                    goToLockedWindowTask = null
                } else if (!goToLockedWindowTask!!.isTaskEnd(currentState)) {
                    atuaMF.isRandomExploration = false
                    return goToLockedWindowTask!!.chooseAction(currentState)
                }
        } else {
            recentGoToExploreState = false
        }
        if (!isOutOfAppState(currentAbstractState)) {
            actionOnOutOfAppCount = 0
        } else {
            actionOnOutOfAppCount += 1
        }
        if (isCameraOpening(currentState)) {
            atuaMF.isRandomExploration = false
            return dealWithCamera(currentState)
        }
        if (lockedWindow != null
            && lockedWindow != currentAbstractState.window) {
            /*dataFilled = false
            fillingData = false*/

            if ((!currentAbstractState.isRequireRandomExploration()
                && !Helper.isOptionsMenuLayout(currentState))
                || actionOnOutOfAppCount >= 5  // to avoid the case that we are in outOfApp too long
            ) {
                if (currentAbstractState.isOpeningKeyboard) {
                    atuaMF.isRandomExploration = false
                    return GlobalAction(actionType = ActionType.CloseKeyboard)
                }
                if (canReturnToLockedWindow(currentState)) {
                    atuaMF.isRandomExploration = false
                    return goToLockedWindowTask!!.chooseAction(currentState)
                }
            }
            else if (currentAbstractState.isRequireRandomExploration() && randomInDialogCnt>10) {
                if (currentAbstractState.window.inputs.all { it.witnessed && it.exerciseCount>0 }) {
                    if (canReturnToLockedWindow(currentState)) {
                        atuaMF.isRandomExploration = false
                        return goToLockedWindowTask!!.chooseAction(currentState)
                    }
                    else {
                        atuaMF.isRandomExploration = false
                        return ExplorationAction.pressBack()
                    }
                }
            }
        } else if (currentAbstractState.isRequireRandomExploration() && randomInDialogCnt>10) {
            if (currentAbstractState.window.inputs.all { it.witnessed && it.exerciseCount>0 }) {
                atuaMF.isRandomExploration = false
                return ExplorationAction.pressBack()
            }
        }

        if (isOutOfAppState(currentAbstractState)) {
            if (actionOnOutOfAppCount >= 5 || !shouldRandomExplorationOutOfApp(currentAbstractState,currentState)){
                dataFilled = false
                fillingData = false
                if (actionOnOutOfAppCount >= 11) {
                    atuaMF.isRandomExploration = false
                    return atuaStrategy.eContext.resetApp()
                }
                if (actionOnOutOfAppCount >= 10) {
                    atuaMF.isRandomExploration = false
                    return atuaStrategy.eContext.launchApp()
                }
                if (actionOnOutOfAppCount >= 5 || !shouldRandomExplorationOutOfApp(currentAbstractState,currentState)) {
                    atuaMF.isRandomExploration = false
                    return ExplorationAction.pressBack()
                }
            }
        }
        goToLockedWindowTask = null
        isClickedShutterButton = false
        if (currentState.widgets.any { it.isKeyboard } && currentAbstractState.shouldNotCloseKeyboard == false) {
            val keyboardAction = dealWithKeyboard(currentState)
            if (keyboardAction != null)
                return keyboardAction
        }
        if (isTrapActivity(currentAbstractState)) {
            if (currentState.widgets.any { it.text == "I agree" || it.text == "Save and continue" || it.text.toLowerCase() == "accept all" || it.text.contains("Go to end")
                        || it.text.toLowerCase() == "accept and save settings" }) {
                val candidates = currentState.widgets.filter {  it.text == "I agree"
                        || it.text == "Save and continue"
                        || it.text.toLowerCase() == "accept all"
                        || it.text.contains("Go to end")
                        || it.text.toLowerCase() == "accept and save settings"}
                val choosenWidget = runBlocking {  getCandidates(candidates).random() }
                atuaMF.isRandomExploration = false
                return choosenWidget.click()
            }
        }
/*        if (currentAbstractState.rotation == Rotation.LANDSCAPE) {
            if (random.nextBoolean())
                return chooseActionWithName(AbstractActionType.ROTATE_UI, "", null, currentState, null)!!
        }*/

        if (environmentChange) {
            if (!recentChangedSystemConfiguration) {
                recentChangedSystemConfiguration = true
                if (atuaMF.havingInternetConfiguration(currentAbstractState.window)) {
                    if (random.nextInt(4) < 3)
                        return GlobalAction(ActionType.EnableData).also {
                            atuaMF.internetStatus = true
                        }
                    else
                        return GlobalAction(ActionType.DisableData).also {
                            atuaMF.internetStatus = false
                        }
                } else if (atuaMF.internetStatus == false) {
                    return GlobalAction(ActionType.EnableData).also {
                        atuaMF.internetStatus = true
                    }
                }
            } /*else {
                if (isFullyExploration && autautMF.havingInternetConfiguration(currentAbstractState.window)) {
                    //20%
                    if (random.nextInt(4) == 0) {
                        if (random.nextInt(4) < 3)
                            return GlobalAction(ActionType.EnableData).also {
                                autautMF.internetStatus = true
                            }
                        else
                            return GlobalAction(ActionType.DisableData).also {
                                autautMF.internetStatus = false
                            }
                    }
                }
            }*/
        }

       if (dataFilled) {
            val fillingTextTask = PrepareContextTask(atuaMF,atuaStrategy,delay,useCoordinateClicks)
            if (fillingTextTask.isAvailable(currentState)) {
                val currentUserlikeInputWidgets = fillingTextTask.inputFillDecision.keys.map { it.uid }
                if (currentUserlikeInputWidgets.subtract(userInputFilled).isNotEmpty()) {
                    dataFilled = false
                    fillingData = false
                }
            }
        }
        var action: ExplorationAction? = null

        if (fillingData) {
            if (!fillDataTask.isTaskEnd(currentState)) {
                action = fillDataTask.chooseAction(currentState)
            }
            if (action != null) {
                atuaMF.isRandomExploration = false
                return action
            } else {
                fillingData = false
            }
        }

        val affectedByUserlikeinput = atuaMF.dstg.edges().any {
            it.label.interactions.isNotEmpty()
                    && it.label.abstractAction.isCheckableOrTextInput(it.label.source)
                    && it.label.source.getAvailableActions().intersect(unexercisedActions).isNotEmpty()
                    && it.label.dest.getAvailableActions().intersect(unexercisedActions).isEmpty()
        }
        if (!dataFilled && !fillingData && !stopGenerateUserlikeInput && !affectedByUserlikeinput) {
            val lastAction = atuaStrategy.eContext.getLastAction()
            if (!lastAction.actionType.isTextInsert() && !currentAbstractState.isOpeningKeyboard) {
                if (fillDataTask.isAvailable(currentState, alwaysUseRandomInput)) {
                    fillDataTask.initialize(currentState)
                    userInputFilled.addAll(fillDataTask.inputFillDecision.map { it.key.uid })
                    fillingData = true
                    dataFilled = true
                    val action = fillDataTask.chooseAction(currentState)
                    if (action != null) {
                        atuaMF.isRandomExploration = false
                        return action
                    }
                }
            }
        }
        fillingData = false
        attemptCount++
//        val unexercisedActions = currentAbstractState.getUnExercisedActions(currentState,atuaMF)

        val widgetActions = unexercisedActions.filter {
            it.attributeValuationMap != null
        }
        var randomAction: AbstractAction? = null
        if (qlearningRunning) {
            // have not tested
            qlearningSteps-=1
            if (qlearningSteps==0) {
                qlearningRunning = false
            }
            val bestCandidates = atuaMF.getCandidateAction(currentState, delay, useCoordinateClicks)
            if (bestCandidates.any { it.key==null }) {
                if (random.nextBoolean())
                    return bestCandidates.filter { it.key==null }.values.flatten().random()
            }
            val widgetActionCandidates = bestCandidates.filter { it.key!=null }
            val widgetCandidates = runBlocking {getCandidates(widgetActionCandidates.keys.toList() as List<Widget>)  }
            val selectedWidget = widgetCandidates.random()
            val selectedAction = widgetActionCandidates[selectedWidget]!!.random()
            ExplorationTrace.widgetTargets.add(selectedWidget)
            log.info("Widget: ${selectedWidget}")
            return selectedAction
        }
        if (lockedWindow == null || lockedWindow != currentAbstractState.window) {
            if (currentAbstractState.isRequireRandomExploration()) {
                randomInDialogCnt++
            } else {
                randomInDialogCnt = 0
            }
        } else{
            randomInDialogCnt = 0
        }

/*        if(randomAction==null) {
            tryLastAction = 0
            //val widgetActions = currentAbstractState.getAvailableActions().filter { it.widgetGroup != null }
            val prioritizeActions1 = unexercisedActions.filter {
                val inputs = currentAbstractState.getInputsByAbstractAction(it)
                inputs.any { !it.exercisedInThePast || it.eventType == EventType.swipe }
            }.filter {
                widgetActions.contains(it)
            }
            if (prioritizeActions1.isNotEmpty()) {
                randomAction = exerciseUnexercisedWidgetAbstractActions(prioritizeActions1, currentAbstractState)
            }
        }*/

        if (randomAction == null) {
            val priotizeActions = unexercisedActions.filter {
                val inputs = currentAbstractState.getInputsByAbstractAction(it)
                val usefulInputs = inputs.filter {
                    if ((it.eventType == EventType.long_click || it.eventType == EventType.item_long_click || it.eventType == EventType.swipe)
                        && ModelHistoryInformation.INSTANCE.inputUsefulness.containsKey(it)) {
                        ModelHistoryInformation.INSTANCE.inputUsefulness[it]!!.second>0
                    } else {
                        true
                    }
                }
                usefulInputs.isNotEmpty()
            }.filter{
                widgetActions.contains(it) }
                if (priotizeActions.isNotEmpty()) {
                    randomAction = exerciseUnexercisedWidgetAbstractActions(priotizeActions, currentAbstractState)
                    //randomAction = unexercisedActions.random()
                }
        }

        if (randomAction == null) {
            if (unexercisedActions.any { it.attributeValuationMap != null }) {
                val widgetActions = unexercisedActions.filter { it.attributeValuationMap != null }
                randomAction =  exerciseUnexercisedWidgetAbstractActions(widgetActions, currentAbstractState)
            }
        }


        if (randomAction == null) {
            if (unexercisedActions.isNotEmpty()) {
                randomAction = unexercisedActions.maxByOrNull { it.getScore() }
            }
        }
        if (randomAction == null) {
            if (currentAbstractState.rotation == Rotation.LANDSCAPE) {
                randomAction = currentAbstractState.getAvailableActions(currentState).find { it.actionType == AbstractActionType.ROTATE_UI }
            }
        }
        if (randomAction == null) {
            if (!isPureRandom && !recentGoToExploreState
                && canGoToUnexploredStates(
                    currentAbstractState,
                    currentState
                )
            ) {
                atuaMF.isRandomExploration = false
                return goToLockedWindowTask!!.chooseAction(currentState)
            }
        }

        if (randomAction == null && currentAbstractState.isRequireRandomExploration()) {
            randomAction = trySwipeAction(currentAbstractState, randomAction)
            val filter = currentAbstractState.getAvailableActions(currentState)
                .filter {
                    it.isWidgetAction()
                            && !it.isCheckableOrTextInput(currentAbstractState)
                            && it.actionType != AbstractActionType.SWIPE
                }
            if (randomAction == null && filter.isEmpty()) {
                atuaMF.isRandomExploration = false
                return ExplorationAction.pressBack()
            }
        }

        if (randomAction == null) {
            val unexercisedActions2 = currentAbstractState.getUnExercisedActions2(currentState)
                .filter {
                    !it.isCheckableOrTextInput(currentAbstractState)
                            && it.isWidgetAction() }
            randomAction = unexercisedActions2.maxByOrNull { it.getScore() }
        }
  /*      if (randomAction == null) {
            if (!isPureRandom && !recentGoToExploreState
                && canGoToUnexploredStates2(
                    currentAbstractState,
                    currentState
                )
            ) {
                return goToLockedWindowTask!!.chooseAction(currentState)
            }
        }*/


        if (randomAction == null) {
            // Try swipe action
            randomAction = trySwipeAction(currentAbstractState, randomAction)
        }
/*        if (randomAction == null) {
            val unexercisedWidgetActionsInAppState = currentAbstractState.getAvailableActions(currentState).filter {
                !it.isCheckableOrTextInput(currentAbstractState) && it.isWidgetAction() && it.actionType != AbstractActionType.SWIPE
            }.filter { action -> !currentAbstractState.abstractTransitions.any {
                it.abstractAction == action
                        && it.interactions.isNotEmpty() } }
            if (unexercisedWidgetActionsInAppState.isNotEmpty()) {
                randomAction = unexercisedWidgetActionsInAppState.maxByOrNull { it.getScore() }
            }
        }*/
        if (randomAction == null) {
            if (random.nextDouble() < 0.05) {
                val abstractActions = currentAbstractState.getAvailableActions(currentState).filter {
                    !it.isWidgetAction() && !recentActions.contains(it) && !it.isLaunchOrReset()
                }
                if (abstractActions.isNotEmpty()) {
                    randomAction = abstractActions.random()
                }
            } else {
                val visibleTargets = ArrayList(Helper.getActionableWidgetsWithoutKeyboard(currentState))
                val unexploredWidgets = atuaMF.actionCount.getUnexploredWidget2(currentState).filter {
                    /*it.clickable || it.scrollable || (!it.clickable && it.longClickable)*/
                    it.clickable
                }.filterNot { Helper.isUserLikeInput(it) }
                if (unexploredWidgets.isNotEmpty() /*|| isPureRandom*/ ) {
                    return randomlyExploreLessExercisedWidgets(unexploredWidgets, currentState)
                }  else {
                    return randomlyExploreLessExercisedWidgets(visibleTargets,currentState)
                }
            }
        }

        if (randomAction != null) {
            log.info("Action: $randomAction")
            if (randomAction.extra == "SwipeTillEnd") {
                isScrollToEnd = true
            } else {
                isScrollToEnd = false
            }
            lastAction = randomAction
            recentActions.add(randomAction)
            var chosenWidget: Widget? = null
            var isValidAction = true
            if (randomAction.attributeValuationMap != null) {
                chosenWidget = chooseGUIWidgetFromAbstractAction(randomAction, currentState)
                if (chosenWidget == null) {
                    log.debug("No widget found")
                    // remove action
                    randomAction.attributeValuationMap!!.removeAction(randomAction)
                    isValidAction = false
                } else {
                    log.info(" widget: $chosenWidget")
                }
            }
            if (isValidAction) {
                val actionType = randomAction.actionType
                log.debug("Action: $actionType - ${randomAction.extra}")
                val chosenAction = when (actionType) {
                    AbstractActionType.SEND_INTENT -> chooseActionWithName(actionType, randomAction.extra, null, currentState, randomAction)
                    AbstractActionType.ROTATE_UI -> chooseActionWithName(actionType, 90, null, currentState, randomAction)
                    else -> chooseActionWithName(actionType, randomAction.extra
                            ?: "", chosenWidget, currentState, randomAction)
                }
                if (chosenAction != null) {
                    return chosenAction.also {
                        prevAbState = currentAbstractState
                    }
                } else {
                    // this action should be removed from this abstract state
                    currentAbstractState.removeAction(randomAction)
                    return chooseAction(currentState)
                }
            }
        }
        return ExplorationAction.pressBack()

/*        if (executeSystemEvent < 0.05) {
            if (regressionTestingMF.appRotationSupport)
                return chooseActionWithName("RotateUI",90,null,currentState,null)!!
        } else if (executeSystemEvent < 0.1)
        {
            if (haveOpenNavigationBar(currentState))
            {
                return clickOnOpenNavigation(currentState)
            }
            else
            {
                if (currentAbstractState.hasOptionsMenu)
                    return chooseActionWithName("PressMenu", null,null,currentState,null)!!
            }
        }
        else if (executeSystemEvent < 0.15)
        {
            return chooseActionWithName("PressMenu", null,null,currentState,null)!!
        }else if (executeSystemEvent < 0.20)
        {
            if (!regressionTestingMF.isPressBackCanGoToHomescreen(currentState) && backAction)
            {
                log.debug("Randomly back")
                return ExplorationAction.pressBack()
            }
            if(clickNavigationUpTask.isAvailable(currentState))
            {
                return clickNavigationUpTask.chooseAction(currentState)
            }
        } else if (executeSystemEvent < 0.25) {
            //Try swipe on unscrollable widget
            return chooseActionWithName("Swipe", "",null,currentState,null)?:ExplorationAction.pressBack()

        }*/


    }

    private fun canReturnToLockedWindow(currentState: State<*>): Boolean {
        goToLockedWindowTask = GoToAnotherWindowTask(
            atuaTestingStrategy = atuaStrategy,
            autautMF = atuaMF,
            delay = delay,
            useCoordinateClicks = useCoordinateClicks
        )
        if (goToLockedWindowTask!!.isAvailable(
                currentState = currentState,
                destWindow = lockedWindow!!,
                isWindowAsTarget = true,
                includePressback = true,
                includeResetApp = false,
                isExploration = true
            )
        ) {
            if (goToLockedWindowTask!!.possiblePaths.any { it.cost(final = true) <= 5 * atuaStrategy.scaleFactor }) {
                goToLockedWindowTask!!.initialize(currentState)
                return true
            } else {
                log.debug("Unexercised inputs are too far.")
                forcingEndTask = true
            }
        }
        return false
    }

    private fun trySwipeAction(
        currentAbstractState: AbstractState,
        randomAction: AbstractAction?
    ): AbstractAction? {
        var randomAction1 = randomAction
        val swipeActions = currentAbstractState.getActionCountMap().map { it.key }.filter {
            !it.isWebViewAction()
                    && it.isWidgetAction()
                    && it.actionType == AbstractActionType.SWIPE
        }
        val unexercisedActionsInCurrentState = swipeActions.filter { action ->
            !currentAbstractState.abstractTransitions.any {
                it.abstractAction == action &&
                        (it.dest !is VirtualAbstractState
                                && it.dest !is PredictedAbstractState)
                        && it.interactions.isNotEmpty()
            } && action.meaningfulScore > 0
        }
        if (unexercisedActionsInCurrentState.isNotEmpty()) {
            randomAction1 = unexercisedActionsInCurrentState.random()
        }
        return randomAction1
    }

    private fun isOutOfAppState(currentAbstractState: AbstractState) =
        currentAbstractState.window is OutOfApp ||
                (currentAbstractState.window is Dialog
                        && WindowManager.instance.updatedModelWindows.filter { it is OutOfApp }.map { it.classType }
                    .contains(currentAbstractState.activity))

    private fun randomlyExploreLessExercisedWidgets(
        unexploredWidgets: List<Widget>,
        currentState: State<*>
    ): ExplorationAction {
        if (unexploredWidgets.isEmpty())
            return ExplorationAction.pressBack()
        val notUserlikeInputs = unexploredWidgets.filter {
            it.clickable || it.longClickable || it.scrollable
        }.filterNot { Helper.isUserLikeInput(it) }

        val candidates = if (notUserlikeInputs.isNotEmpty() && random.nextBoolean()) {
            notUserlikeInputs
        } else {
            unexploredWidgets
        }
        if (candidates.isEmpty()) {
            val chosenWidget = unexploredWidgets.random()
            log.info("Widget: $chosenWidget")
            return doRandomActionOnWidget(chosenWidget, currentState)
        } else {
            val lessExercisedWidgets = runBlocking {
                ArrayList(
                    getCandidates(
                        candidates
                    )
                )
            }
            val chosenWidget = if (unexploredWidgets.any { lessExercisedWidgets.contains(it) })
                unexploredWidgets.filter { lessExercisedWidgets.contains(it) }.random()
            else
                lessExercisedWidgets.random()
            log.info("Widget: $chosenWidget")
            return doRandomActionOnWidget(chosenWidget, currentState)
        }

    }

    private fun canGoToUnexploredStates(
        currentAbstractState: AbstractState,
        currentState: State<*>
    ): Boolean {
        if ((!currentAbstractState.isRequireRandomExploration() || currentAbstractState.window == lockedWindow)
            && !Helper.isOptionsMenuLayout(currentState)
            && !recentGoToExploreState) {
            var targetStates = atuaStrategy.phaseStrategy.getUnexhaustedExploredAbstractState().filter {
                it.window == lockedWindow
                        && it != currentAbstractState
            } .toHashSet()
            if (targetStates.isNotEmpty()) {
                goToLockedWindowTask = GoToAnotherWindowTask(
                    atuaTestingStrategy = atuaStrategy,
                    autautMF = atuaMF,
                    delay = delay,
                    useCoordinateClicks = useCoordinateClicks
                )
                if (goToLockedWindowTask!!.isAvailable(
                        currentState = currentState,
                        destWindow = currentAbstractState.window,
                        includePressback = true,
                        includeResetApp = false,
                        isExploration = true,
                        maxCost = 25.0
                    )
                ) {
                    recentGoToExploreState = true
                    goToLockedWindowTask!!.initialize(currentState)
                    return true
                    /*if (goToLockedWindowTask!!.possiblePaths)
                    if (goToLockedWindowTask!!.possiblePaths.any { it.cost()<=5*atuaStrategy.scaleFactor }) {
                    } else {
                        log.debug("Unexercised inputs are too far.")
                    }*/
                    /*if (goToLockedWindowTask!!.possiblePaths.isNotEmpty()) {
                        recentGoToExploreState = true
                        goToLockedWindowTask!!.initialize(currentState)
                        return true
                    }*/
                }
            }
        }
        goToLockedWindowTask = null
        return false
    }

    private fun canGoToUnexploredStates2(
        currentAbstractState: AbstractState,
        currentState: State<*>
    ): Boolean {
        if (!currentAbstractState.isRequireRandomExploration() && !Helper.isOptionsMenuLayout(currentState) && !recentGoToExploreState) {
            var targetStates = AbstractStateManager.INSTANCE.ABSTRACT_STATES.filter { appState ->
                appState.window == currentAbstractState.window
                        && appState.ignored == false
                        && appState != currentAbstractState
                        && appState !is VirtualAbstractState
                        && appState.guiStates.isNotEmpty()
                        && appState.attributeValuationMaps.isNotEmpty()
                        && appState.getUnExercisedActions2(null)
                    .filter { it.isWidgetAction()
                            && !it.attributeValuationMap!!.getClassName().contains("WebView")
                            && !it.isCheckableOrTextInput(appState)}
                    .isNotEmpty()
            }.toHashSet()
            if (targetStates.isNotEmpty()) {
                goToLockedWindowTask = GoToAnotherWindowTask(
                    atuaTestingStrategy = atuaStrategy,
                    autautMF = atuaMF,
                    delay = delay,
                    useCoordinateClicks = useCoordinateClicks
                )
                if (goToLockedWindowTask!!.isAvailable(
                        currentState = currentState,
                        destWindow = currentAbstractState.window,
                        includePressback = true,
                        includeResetApp = true,
                        isExploration = true,
                        maxCost = 25.0
                    )
                ) {
                    recentGoToExploreState = true
                    goToLockedWindowTask!!.initialize(currentState)
                    return true
                    /*if (goToLockedWindowTask!!.possiblePaths)
                    if (goToLockedWindowTask!!.possiblePaths.any { it.cost()<=5*atuaStrategy.scaleFactor }) {
                    } else {
                        log.debug("Unexercised inputs are too far.")
                    }*/
                    /*if (goToLockedWindowTask!!.possiblePaths.isNotEmpty()) {
                        recentGoToExploreState = true
                        goToLockedWindowTask!!.initialize(currentState)
                        return true
                    }*/
                }
            }
        }
        goToLockedWindowTask = null
        return false
    }

    private fun isTrapActivity(currentAbstractState: AbstractState) =
            currentAbstractState.window.classType == "com.oath.mobile.platform.phoenix.core.TrapActivity"
                    || currentAbstractState.window.classType == "com.yahoo.mobile.client.share.account.controller.activity.TrapsActivity"
                    || currentAbstractState.window.classType =="com.cnn.mobile.android.phone.features.splash.SplashActivity"


    private fun exerciseUnexercisedWidgetAbstractActions(unexercisedActions: List<AbstractAction>, currentAbstractState: AbstractState): AbstractAction? {
        val unWitnessedActionsn = unexercisedActions.filter { !atuaMF.dstg.abstractActionEnables.containsKey(it) }
        val toExerciseActionsn = if (unWitnessedActionsn.isNotEmpty()) {
            unWitnessedActionsn
        } else {
            unexercisedActions
        }
        var randomAction1: AbstractAction?
        if (toExerciseActionsn.any { it.attributeValuationMap != null }) {
            // Swipe on widget should be executed by last
            val widgetActions = toExerciseActionsn.filter { it.attributeValuationMap != null }
            val nonWebViewActions = widgetActions.filterNot { it.attributeValuationMap!!.getClassName().contains("WebView") }
            val candidateActions = if (nonWebViewActions.isEmpty())
                ArrayList(widgetActions)
            else
                ArrayList(nonWebViewActions)
            //prioritize the less frequent widget
            val actionByScore = HashMap<AbstractAction, Double>()
            val windowWidgetFrequency = AbstractStateManager.INSTANCE.attrValSetsFrequency[currentAbstractState.window]!!
            candidateActions.forEach { abstractAction ->
                var actionScore = abstractAction.getScore()
                val widgetGroup = abstractAction.attributeValuationMap!!
                /*var witnessInThePast = currentAbstractState.abstractTransitions.any {
                    it.abstractAction == abstractAction && (
                            it.interactions.isNotEmpty()
                                    || it.modelVersion == ModelVersion.BASE) }
                if (witnessInThePast)
                    actionScore = actionScore/10*/
                if (windowWidgetFrequency.containsKey(widgetGroup)) {
                    actionByScore.put(abstractAction, actionScore /  windowWidgetFrequency[widgetGroup]!!)
                } else {
                    actionByScore.put(abstractAction, actionScore)
                }
            }

            if (actionByScore.isNotEmpty()) {
                val pb = ProbabilityDistribution<AbstractAction>(actionByScore)
                randomAction1 = pb.getRandomVariable()
            } else {
                randomAction1 = candidateActions.maxByOrNull { it.getScore() }
            }
        } else {
            randomAction1 = toExerciseActionsn.maxByOrNull { it.getScore() }
        }
        return randomAction1
    }


    override fun hasAnotherOption(currentState: State<*>): Boolean {
        return false
    }


    companion object {
        private val log: Logger by lazy { LoggerFactory.getLogger(this.javaClass.name) }
        var executedCount: Int = 0
        var instance: RandomExplorationTask? = null
        fun getInstance(regressionTestingMF: org.atua.modelFeatures.ATUAMF,
                        atuaTestingStrategy: ATUATestingStrategy,
                        delay: Long,
                        useCoordinateClicks: Boolean,
                        randomScroll: Boolean = true,
                        maximumAttempt: Int = 1): RandomExplorationTask {
            if (instance == null) {
                instance = RandomExplorationTask(regressionTestingMF, atuaTestingStrategy,
                        delay, useCoordinateClicks, randomScroll, maximumAttempt)
            }
            return instance!!
        }
    }

}