package org.droidmate.exploration.strategy.autaut.task

import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.isEnabled
import org.droidmate.exploration.actions.click
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.setText
import org.droidmate.exploration.modelFeatures.autaut.AutAutMF
import org.droidmate.exploration.modelFeatures.autaut.abstractStateElement.AbstractActionType
import org.droidmate.exploration.modelFeatures.autaut.abstractStateElement.AbstractStateManager
import org.droidmate.exploration.modelFeatures.autaut.inputRepo.textInput.DataField
import org.droidmate.exploration.modelFeatures.autaut.staticModel.Helper
import org.droidmate.exploration.strategy.autaut.AutAutTestingStrategy
import org.droidmate.exploration.modelFeatures.autaut.inputRepo.textInput.TextInput
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.random.Random

class PrepareContextTask private constructor(
        regressionWatcher: AutAutMF,
        autAutTestingStrategy: AutAutTestingStrategy,
        delay: Long, useCoordinateClicks: Boolean): AbstractStrategyTask(autAutTestingStrategy, regressionWatcher,delay,useCoordinateClicks){

    override fun isTaskEnd(currentState: State<*>): Boolean {
        val availableWidgets = fillActions.map { it.key }.filter { currentState.widgets.contains(it) }
        if (availableWidgets.isNotEmpty())
            return false
        var isEnd = true
/*        currentState.widgets.filter { filledTexts.containsKey(it)}.asSequence().forEach {
            if (it.text!=filledTexts[it])
            {
                isEnd = false
                fillTextDecision[it]=false
            }
        }*/
        return isEnd
    }

    var fillDataMode: FillDataMode = FillDataMode.SIMPLE
    protected val filledData = HashMap<DataField, Boolean>()
    val fillActions = HashMap<Widget,ExplorationAction>()
    override fun initialize(currentState: State<*>) {
        reset()
        prepareFillActions(currentState)
/*        Helper.getInputFields(currentState).forEach {
            val dataField = TextInput.getInputWidgetAssociatedDataField(widget = it, state = currentState)
            if (dataField!=null)
            {
                filledData.put(dataField,false)
            }
        }*/
    }

    private fun prepareFillActions(currentState: State<*>) {
        val allInputWidgets = Helper.getInputFields(currentState).filter { inputFillDecision.containsKey(it) }
        allInputWidgets.forEach {widget ->
            val inputValue = TextInput.getSetTextInputValue(widget,currentState,randomInput)
            if (widget.checked.isEnabled()) {
                if(widget.checked.toString() != inputValue) {
                    fillActions[widget] = widget.click()
                    ExplorationTrace.widgetTargets.removeLast()
                }
            } else {
                val inputAction = if (allInputWidgets.size == 1)
                    widget.setText(inputValue,sendEnter = true ,enableValidation = false)
                else
                    widget.setText(inputValue,sendEnter = true ,enableValidation = false)
                ExplorationTrace.widgetTargets.removeLast()
                fillActions[widget] = inputAction
            }
        }

    }

    override fun chooseAction(currentState: State<*>): ExplorationAction {
        if (fillDataMode == FillDataMode.SIMPLE)
        {
            return randomlyFill(currentState)
        }
        else
        {
            return ExplorationAction.pressBack()
        }
    }

    override fun reset() {
        TextInput.resetInputData()
        fillActions.clear()
    }

    override fun hasAnotherOption(currentState: State<*>): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun chooseRandomOption(currentState: State<*>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun isAvailable(currentState: State<*>): Boolean {
        return hasInput(currentState) && shouldInsertText(currentState)
    }
    fun isAvailable(currentState: State<*>, random: Boolean): Boolean {
        randomInput = random
        return isAvailable(currentState)
    }
    val inputFillDecision = HashMap<Widget,Boolean>()

    private fun hasInput(currentState: State<*>): Boolean {
        return  Helper.getInputFields(currentState).isNotEmpty()
}

    internal fun randomlyFill(currentState: State<*>): ExplorationAction {
        val availableWidgets = fillActions.map { it.key }.filter { currentState.widgets.contains(it) }
        if (availableWidgets.isEmpty()) {
            log.debug("No more filling data. Press back.")
            return ExplorationAction.pressBack()
        }
        val toFillWidget = availableWidgets.first()
        log.debug("Selected widget: ${toFillWidget}")
        ExplorationTrace.widgetTargets.add(toFillWidget)
        val action = fillActions[toFillWidget]!!
        fillActions.remove(toFillWidget)
        return action
    }

    var randomInput: Boolean = false
    private fun shouldInsertText(currentState: State<*>): Boolean {
        inputFillDecision.clear()

        // we group widgets by its resourceId to easily deal with radio button
        val allInputWidgets = Helper.getInputFields(currentState).groupBy { it.resourceId }
        allInputWidgets.forEach { resourceId, widgets ->
            val processingWidgets = widgets.toMutableList()
            processingWidgets.forEach {
                if (!it.isPassword && it.isInputField) {
                    if (randomInput) {
                        if (TextInput.historyTextInput.contains(it.text)) {
                            if (random.nextInt(100) < 25)
                                inputFillDecision.put(it, false)
                        } else {
                            inputFillDecision.put(it, false)
                        }
                    } else {
                        inputFillDecision.put(it,false)
                    }
                } else {
                    var ignoreWidget = false
                    if (!it.isInputField) {
                        // check if a click on this widget will go to another window
                        val abstractState = AbstractStateManager.instance.getAbstractState(currentState)!!
                        val widgetGroup = abstractState.getWidgetGroup(widget = it, guiState = currentState)
                        if (widgetGroup != null) {
                            val isGoToAnotherWindow = autautMF.abstractTransitionGraph.edges(abstractState).any {
                                it.destination!!.data.window != it.source.data.window
                                        && it.label.abstractAction.actionType == AbstractActionType.CLICK
                                        && it.label.abstractAction.isWidgetAction()
                                        && it.label.abstractAction!!.attributeValuationSet == widgetGroup
                                        && it.label.isExplicit()
                            }
                            if (isGoToAnotherWindow) {
                                // any widget can lead to another window should be ignore.
                                ignoreWidget = true
                            }
                        }
                    }
                    if (!ignoreWidget) {
                        inputFillDecision.put(it, false)
                    }
                }
            }
        }
        return inputFillDecision.isNotEmpty()
    }

    companion object {
        private val log: Logger by lazy { LoggerFactory.getLogger(this.javaClass.name) }
        var executedCount:Int = 0
        var instance: PrepareContextTask? = null
        fun getInstance(regressionWatcher: AutAutMF,
                        autAutTestingStrategy: AutAutTestingStrategy,
                        delay: Long,
                        useCoordinateClicks: Boolean): PrepareContextTask {
            if (instance == null) {
                instance = PrepareContextTask(regressionWatcher, autAutTestingStrategy, delay,useCoordinateClicks)
            }
            return instance!!
        }
    }
}

enum class FillDataMode {
    SIMPLE,
    FULL
}
