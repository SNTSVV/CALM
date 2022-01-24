package org.droidmate.exploration.strategy.atua.task

import kotlinx.coroutines.runBlocking
import org.atua.calm.modelReuse.ModelVersion
import org.droidmate.deviceInterface.exploration.*
import org.droidmate.exploration.actions.pressBack
import org.atua.modelFeatures.dstg.AbstractAction
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractState
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.Input
import org.atua.modelFeatures.ewtg.window.Window
import org.droidmate.exploration.strategy.atua.ATUATestingStrategy
import org.droidmate.exploration.strategy.atua.PhaseTwoStrategy
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class ExerciseTargetComponentTask private constructor(
    regressionWatcher: org.atua.modelFeatures.ATUAMF,
    atuaTestingStrategy: ATUATestingStrategy,
    delay: Long, useCoordinateClicks: Boolean)
    : AbstractStrategyTask(atuaTestingStrategy, regressionWatcher, delay, useCoordinateClicks){

    private var injectingRandomAction: Boolean = false
    private var recentChangedSystemConfiguration: Boolean = false
    var environmentChange: Boolean = false
    val eventList:  ArrayList<Input> = ArrayList()
    var chosenAbstractAction: AbstractAction? = null
    var fillingData = false
    var dataFilled = false
    var randomRefillingData = false
    var alwaysUseRandomInput = false
    var randomBudget: Int = 3*atuaTestingStrategy.scaleFactor.toInt()
    private var prevAbstractState: AbstractState?=null
    val originalEventList: ArrayList<Input> = ArrayList()

    private val fillDataTask = PrepareContextTask.getInstance(atuaMF,atuaTestingStrategy, delay, useCoordinateClicks)
    val targetItemEvents = HashMap<AbstractAction, HashMap<String,Int>>()
    var recentlyExercisedTarget = false
    var goToLockedWindowTask: GoToAnotherWindowTask? = null

    override fun chooseRandomOption(currentState: State<*>) {
        log.debug("Do nothing")
    }

    override fun hasAnotherOption(currentState: State<*>): Boolean {
        return false
    }

    override fun isTaskEnd(currentState: State<*>): Boolean {
        // Target inputs need to be updated after each action.
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        val availableActions = ArrayList<AbstractAction>()
        eventList.clear()
        eventList.addAll(atuaStrategy.phaseStrategy.getCurrentTargetInputs(currentState))
        eventList.forEach {
            val actions = currentAbstractState.getAbstractActionsWithSpecificInputs(it)
            if (actions.isNotEmpty()) {
                availableActions.addAll(actions)
            }
        }
        if (isCameraOpening(currentState)) {
            return false
        }
        val abstractState = atuaMF.getAbstractState(currentState)!!

//        establishTargetInputs(currentState)
       /* eventList.removeIf {
            exercisedInputs.contains(it)
        }*/

       /* if (currentAbstractState.window is Dialog || currentAbstractState.window is OptionsMenu || currentAbstractState.window is OutOfApp) {
            if (isDoingRandomExplorationTask && randomExplorationTask.isTaskEnd(currentState)) {
                return true
            }
            return false
        }*/
        if (isDoingRandomExplorationTask && !randomExplorationTask.isTaskEnd(currentState)) {
            return false
        }
        if (goToLockedWindowTask != null)
            return false
        if (currentAbstractState.window != targetWindow) {
            if (randomBudget>=0)
                return false
            else if (currentAbstractState.isRequireRandomExploration())
                return false
            else
                return true
        }


        if (availableActions.isNotEmpty()) {
            return false
        }
        return true
    }

    private var mainTaskFinished:Boolean = false
    private val randomExplorationTask = RandomExplorationTask(regressionWatcher,atuaTestingStrategy, delay,useCoordinateClicks,true,3).also {
        it.stopGenerateUserlikeInput = false
    }

    override fun initialize(currentState: State<*>) {
        randomExplorationTask.fillingData=false
        mainTaskFinished = false
        // establishTargetInputs(currentState)
        originalEventList.addAll(eventList)
    }

    /*private fun establishTargetInputs(currentState: State<*>) {
        eventList.clear()
        val currentAbstractState = atuaMF.getAbstractState(currentState)
        eventList.addAll(atuaStrategy.phaseStrategy.getCurrentTargetInputs(currentState))
        eventList.filter { it.eventType.isItemEvent }.forEach { action ->
            currentAbstractState!!.attributeValuationMaps.filter { action.attributeValuationMap!!.isParent(it,currentAbstractState.window) }.forEach { childWidget ->
                val childActionType = when (action.actionType) {
                    AbstractActionType.ITEM_CLICK -> AbstractActionType.CLICK
                    AbstractActionType.ITEM_LONGCLICK -> AbstractActionType.LONGCLICK
                    else -> AbstractActionType.CLICK
                }
                currentAbstractState!!.getAvailableActions().filter { it.attributeValuationMap == childWidget && it.actionType == childActionType }.forEach {
                    if (currentAbstractState.avmCardinalities.get(it.attributeValuationMap!!) == Cardinality.MANY) {
                        val itemActionAttempt = 3 * atuaStrategy.scaleFactor
                        for (i in 1..itemActionAttempt.toInt()) {
                            eventList.add(it)
                        }
                    } else {
                        eventList.add(it)
                    }
                }

            }
        }
    }*/


    override fun reset() {
        extraTasks.clear()
        eventList.clear()
        originalEventList.clear()
        currentExtraTask = null
        mainTaskFinished = false
        prevAbstractState = null
        dataFilled = false
        fillingData = false
        randomRefillingData = false
        recentChangedSystemConfiguration = false
        environmentChange = false
        alwaysUseRandomInput = false
        targetWindow = null
        randomBudget=3*atuaStrategy.scaleFactor.toInt()
        isDoingRandomExplorationTask = false
        recentlyExercisedTarget = false
        injectingRandomAction = false
    }

    var targetWindow: Window? = null

    override fun isAvailable(currentState: State<*>): Boolean {
        reset()
        eventList.addAll(atuaStrategy.phaseStrategy.getCurrentTargetInputs(currentState))
        originalEventList.addAll(eventList)
        if (eventList.isNotEmpty()){
            targetWindow = atuaMF.getAbstractState(currentState)!!.window
            log.info("Current abstrate state has ${eventList.size}  target inputs.")
            return true
        }
        log.info("Current abstrate state has no target input.")
        return false
    }

    override fun chooseWidgets(currentState: State<*>): List<Widget> {
        //check if we can encounter any target component in current state
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        var candidates= ArrayList<Widget>()
        candidates.addAll(atuaMF.getRuntimeWidgets(chosenAbstractAction!!.attributeValuationMap!!,currentAbstractState, currentState))
        if (candidates.isNotEmpty())
        {
            return candidates
        }

        return emptyList()
    }
    var isDoingRandomExplorationTask: Boolean = false

    override fun chooseAction(currentState: State<*>): ExplorationAction? {
        executedCount++
        //TODO Maybe we need an extra task for returning to the target

        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        val availableActions = ArrayList<AbstractAction>()
        eventList.forEach {
            val actions = currentAbstractState.getAbstractActionsWithSpecificInputs(it)
            if (actions.size>1) {
                val unexercisedActions = currentAbstractState.getUnExercisedActions(currentState,atuaMF)
                val unexercisedTargetActions = unexercisedActions.intersect(actions)
                if (unexercisedTargetActions.isNotEmpty()) {
                    availableActions.addAll(unexercisedTargetActions)
                } else {
                    val exercisedInStateActions = currentAbstractState.abstractTransitions
                        .filter { actions.contains(it.abstractAction)
                                && it.interactions.isNotEmpty()
                        }.map { it.abstractAction }.distinct()
                    val unexercisedInStateActions = actions.subtract(exercisedInStateActions)
                    if (unexercisedInStateActions.isNotEmpty()) {
                        availableActions.addAll(unexercisedInStateActions)
                    } else {
                        availableActions.addAll(actions)
                    }
                }
            } else {
                availableActions.addAll(actions)
            }

        }
        val prevAbstractState = if (atuaMF.appPrevState != null)
            atuaMF.getAbstractState(atuaMF.appPrevState!!) ?: currentAbstractState
        else
            currentAbstractState
        if (goToLockedWindowTask!=null) {
            if (!goToLockedWindowTask!!.isTaskEnd(currentState)) {
                return goToLockedWindowTask!!.chooseAction(currentState)
            }
            goToLockedWindowTask = null
        }
        if (isCameraOpening(currentState) ) {
            return doRandomExploration(currentState)
        }
        if (currentAbstractState.window != targetWindow) {
            if (randomBudget>0 || (!currentAbstractState.isRequireRandomExploration()
                && !Helper.isOptionsMenuLayout(currentState)))
                return doRandomExploration(currentState)
            else {
                goToLockedWindowTask = GoToTargetWindowTask(atuaMF,atuaStrategy,delay, useCoordinateClicks)
                if (goToLockedWindowTask!!.isAvailable(currentState,
                        destWindow= targetWindow!!,
                    includeResetApp = false,
                    isWindowAsTarget = false,
                    maxCost = 25.0,
                    includePressback = true,
                    isExploration = false
                    )) {
                    goToLockedWindowTask!!.initialize(currentState)
                    return goToLockedWindowTask!!.chooseAction(currentState)
                }
            }
            /*if ( currentAbstractState.window is Dialog || currentAbstractState.window is OptionsMenu || currentAbstractState.window is OutOfApp) {
                if (!isDoingRandomExplorationTask)
                    randomExplorationTask.initialize(currentState)
                isDoingRandomExplorationTask = true
                return randomExplorationTask.chooseAction(currentState)
            }*/
        }
        if (currentState.widgets.any { it.isKeyboard } && availableActions.isEmpty()) {
            val keyboardAction = dealWithKeyboard(currentState)
            if (keyboardAction != null)
                return keyboardAction
        }

        //TODO check eventList is not empty

        if (availableActions.isEmpty()) {
            dataFilled = false
            fillingData = false
//            Let's see if we can swipe to explore more target inputs
            if (currentAbstractState.window == targetWindow) {
                val swipeActions = currentAbstractState.getActionCountMap().map { it.key }. filter {
                    it.isWidgetAction() && !it.isWebViewAction() && it.actionType == AbstractActionType.SWIPE }
                val unexercisedActions = swipeActions.filter {action->
                    !currentAbstractState.abstractTransitions.any {
                        it.abstractAction == action && (
                                it.modelVersion != ModelVersion.BASE
                                        || (it.modelVersion == ModelVersion.BASE && it.interactions.isNotEmpty())
                                ) } }
                if (unexercisedActions.isNotEmpty() && randomBudget>=0) {
                    val action = unexercisedActions.random()
                    var chosenWidget: Widget? = null
                    val chosenWidgets = action.attributeValuationMap!!.getGUIWidgets(currentState,currentAbstractState.window)
                    if (chosenWidgets.isEmpty()) {
                        chosenWidget = null
                    } else {
                        val candidates = runBlocking { getCandidates(chosenWidgets) }
                        chosenWidget = if (candidates.isEmpty())
                            chosenWidgets.random()
                        else
                            candidates.random()
                    }

                    if (chosenWidget != null) {
                        return chooseActionWithName(action.actionType,action.extra,chosenWidget,currentState,action)
                    }
                }
            }

            log.debug("No more target event. Random exploration.")
            return doRandomExploration(currentState)
        }
        isDoingRandomExplorationTask = false

        if (atuaMF.havingInternetConfiguration(currentAbstractState.window)) {
            if (!recentChangedSystemConfiguration && environmentChange && random.nextBoolean()) {
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
                } else {
                    return GlobalAction(ActionType.EnableData).also {
                        atuaMF.internetStatus = false
                    }
                }
            }
        }
        val unexercisedInputs = currentAbstractState.getAvailableInputs().filter {
            it.exerciseCount == 0
                    && it.eventType.isWidgetEvent()}
        if (unexercisedInputs.isEmpty()
            && atuaStrategy.phaseStrategy is PhaseTwoStrategy
            && unexercisedInputs.intersect(eventList).isNotEmpty()
            && randomBudget>0) {
            return doRandomExploration(currentState)
        }
        if (chosenAbstractAction!=null) {
            if (chosenAbstractAction!!.isWidgetAction()) {
                if (chosenAbstractAction!!.attributeValuationMap!!.getGUIWidgets(currentState,currentAbstractState.window).isEmpty()) {
                    selectFirstAvailableTargetAbstractAction(availableActions,currentAbstractState)
                }
            }
        } else {
            selectFirstAvailableTargetAbstractAction(availableActions, currentAbstractState)
        }

        var action: ExplorationAction? = null

        if (!chosenAbstractAction!!.isCheckableOrTextInput()) {
            // Generate userlike inputs
            if (fillingData && !fillDataTask.isTaskEnd(currentState)) {
                action = fillDataTask.chooseAction(currentState)
            }
            if (action != null) {
                return action
            } else if(fillingData){
                dataFilled = true
                fillingData = false
            }
            if (dataFilled) {
                val fillingTextTask = PrepareContextTask(atuaMF,atuaStrategy,delay,useCoordinateClicks)
                if (fillingTextTask.isAvailable(currentState)) {
                    val currentUserlikeInputWidgets = fillingTextTask.inputFillDecision.keys.map { it.uid }
                    val beforeUserlikeInputWidgets = fillDataTask.inputFillDecision.map { it.key.uid }
                    if (currentUserlikeInputWidgets.subtract(beforeUserlikeInputWidgets).isNotEmpty()) {
                        dataFilled = false
                        fillingData = false
                    }
                }
            }
            if (!dataFilled && !fillingData) {
                val lastAction = atuaStrategy.eContext.getLastAction()
                if (!lastAction.actionType.isTextInsert()) {
                    if (fillDataTask.isAvailable(currentState, alwaysUseRandomInput)) {
                        fillDataTask.initialize(currentState)
                        fillingData = true
                        val action = fillDataTask.chooseAction(currentState)
                        if (action!=null)
                            return action
                    }
                } else {
                    dataFilled = true
                }
            }
        }

        if (atuaStrategy.phaseStrategy is PhaseTwoStrategy) {
            if (injectingRandomAction && randomBudget>0) {
                return doRandomExploration(currentState)
            } else {
                val existingTransition = currentAbstractState.abstractTransitions.find {
                    it.abstractAction == chosenAbstractAction
                            && it.interactions.isNotEmpty()
                }
                if (existingTransition != null && random.nextBoolean()) {
                    if (randomBudget > 0)
                        return doRandomExploration(currentState).also {
                            injectingRandomAction = true
                        }
                }
            }
        }
        injectingRandomAction = false
        /*val unexercisedActions = currentAbstractState.getUnExercisedActions(currentState,atuaMF).filter {
            it.isWidgetAction() }
        if (unexercisedActions.isNotEmpty()
            && atuaStrategy.phaseStrategy is PhaseTwoStrategy
            && !unexercisedActions.contains(chosenAbstractAction!!)
            && randomBudget>0) {
            return doRandomExploration(currentState)
        }*/

        fillingData = false
        if (chosenAbstractAction!=null)
        {
            log.info("Exercise Event: ${chosenAbstractAction!!.actionType}")
            var chosenWidget: Widget? = null
            if (chosenAbstractAction!!.attributeValuationMap!=null)
            {
                val candidates = chooseWidgets(currentState)
                if (candidates.isNotEmpty())  {
                    chosenWidget = runBlocking { getCandidates(candidates) }.random()
                }
                if (chosenWidget==null)
                {
                    log.debug("No widget found. Choose another Window transition.")
                    return chooseAction(currentState)
                }
                log.info("Choose Action for Widget: $chosenWidget")
            }
            isDoingRandomExplorationTask = false
            val recommendedAction = chosenAbstractAction!!.actionType
            log.debug("Target action: $recommendedAction - ${chosenAbstractAction!!.attributeValuationMap}")
            val chosenAction = when (chosenAbstractAction!!.actionType)
            {
                AbstractActionType.SEND_INTENT -> chooseActionWithName(recommendedAction,chosenAbstractAction!!.extra, null, currentState,chosenAbstractAction)
                AbstractActionType.ROTATE_UI -> chooseActionWithName(recommendedAction,90,null,currentState,chosenAbstractAction)
                else -> chooseActionWithName(recommendedAction, chosenAbstractAction!!.extra?:"", chosenWidget, currentState,chosenAbstractAction)
            }
            if (chosenAction == null)
            {
                currentAbstractState.removeInputAssociatedAbstractAction(chosenAbstractAction!!)

                //regressionTestingMF.registerTriggeredEvents(chosenEvent!!)
                if (availableActions.isNotEmpty())
                {
                    return chooseAction(currentState)
                }
                log.debug("Cannot get action for this widget.")
                return ExplorationAction.pressBack()
            }
            else
            {
                recentlyExercisedTarget = true
                randomBudget=3*atuaStrategy.scaleFactor.toInt()
                atuaStrategy.phaseStrategy.registerTriggeredInputs(chosenAbstractAction!!,currentState)
                atuaMF.isAlreadyRegisteringEvent = true
                dataFilled = false
                chosenAbstractAction = null
                return chosenAction
            }
        }
        return ExplorationAction.pressBack()

    }

    private fun selectFirstAvailableTargetAbstractAction(
        availableActions: ArrayList<AbstractAction>,
        currentAbstractState: AbstractState
    ) {
        if (!availableActions.any { it.attributeValuationMap != null && !it.attributeValuationMap.isInputField() }) {
            chosenAbstractAction = availableActions.first()
        } else {
            chosenAbstractAction =
                availableActions.filter { it.attributeValuationMap != null && !it.attributeValuationMap.isInputField() }
                    .first()
        }
    }

    private fun doRandomExploration(currentState: State<*>): ExplorationAction? {
        if (!isDoingRandomExplorationTask) {
            activateRandomExploration(currentState)
        }
        isDoingRandomExplorationTask = true
        val action = randomExplorationTask.chooseAction(currentState)
        if (!randomExplorationTask.fillingData) {
            randomBudget--
        }
        return action
    }

    private fun activateRandomExploration(currentState: State<*>) {
        randomExplorationTask.initialize(currentState)
        randomExplorationTask.isPureRandom = true
        randomExplorationTask.setMaxiumAttempt(2 * atuaStrategy.scaleFactor.toInt())
        randomExplorationTask.dataFilled = true
    }

    companion object
    {
        private val log: Logger by lazy { LoggerFactory.getLogger(this.javaClass.name) }
        private var instance: ExerciseTargetComponentTask? = null
        var executedCount:Int = 0
        fun getInstance(regressionWatcher: org.atua.modelFeatures.ATUAMF,
                        atuaTestingStrategy: ATUATestingStrategy,
                        delay: Long, useCoordinateClicks: Boolean): ExerciseTargetComponentTask {
            if (instance == null)
            {
                instance = ExerciseTargetComponentTask(regressionWatcher, atuaTestingStrategy, delay, useCoordinateClicks)
            }
            return instance!!
        }
    }



}