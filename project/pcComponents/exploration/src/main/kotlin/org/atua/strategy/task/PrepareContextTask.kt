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

import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.isEnabled
import org.droidmate.exploration.actions.pressBack
import org.atua.modelFeatures.dstg.AbstractActionType
import org.atua.modelFeatures.dstg.AbstractStateManager
import org.atua.modelFeatures.inputRepo.textInput.DataField
import org.atua.modelFeatures.ewtg.Helper
import org.atua.modelFeatures.ewtg.window.Activity
import org.atua.modelFeatures.ewtg.window.Dialog
import org.atua.strategy.ATUATestingStrategy
import org.atua.modelFeatures.inputRepo.textInput.TextInput
import org.droidmate.exploration.actions.*
import org.droidmate.explorationModel.ExplorationTrace
import org.droidmate.explorationModel.interaction.State
import org.droidmate.explorationModel.interaction.Widget
import org.droidmate.explorationModel.toUUID
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.HashMap

class PrepareContextTask constructor(
    atuaMF: org.atua.modelFeatures.ATUAMF,
    atuaTestingStrategy: ATUATestingStrategy,
    delay: Long, useCoordinateClicks: Boolean): AbstractStrategyTask(atuaTestingStrategy, atuaMF,delay,useCoordinateClicks){

    val stateInputCoverages = HashMap<UUID, HashMap<InputCoverage,Boolean>>()
    var currentInputCoverageType: InputCoverage = InputCoverage.FILL_ALL
    var randomExplorationTask: RandomExplorationTask? = null
    override fun isTaskEnd(currentState: State<*>): Boolean {
        if (isOpeningInputDialog) {
            val currentAbstractState = atuaMF.getAbstractState(currentState)!!
            if (currentAbstractState.window is Dialog) {
                return false
            }
            isOpeningInputDialog = false
        }
        val availableWidgets = currentState.widgets.filter { fillActions.containsKey(it) }
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
        val visibleWidgets = Helper.getVisibleWidgets(currentState)
        val allInputWidgets = visibleWidgets.filter {inputFillDecision[it]?.equals(true)?:false }
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        allInputWidgets.forEach {widget ->
            if (!widget.checked.isEnabled() && !widget.isInputField) {
                val avm = currentAbstractState.getAttributeValuationSet(widget,currentState,atuaMF)
                if (avm != null) {
                    val openDialogActions = currentAbstractState.abstractTransitions.filter {
                        it.dest.window is Dialog
                                && it.source.activity == it.dest.activity
                                && it.abstractAction.attributeValuationMap == avm
                    }
                    if (openDialogActions.isNotEmpty()) {
                        val action = openDialogActions.random().abstractAction.actionType
                        when (action) {
                            AbstractActionType.CLICK ->  {
                                fillActions[widget] = widget.click()
                                ExplorationTrace.widgetTargets.removeLast()
                            }
                            AbstractActionType.LONGCLICK -> {
                                fillActions[widget] = widget.longClick()
                                ExplorationTrace.widgetTargets.removeLast()
                            }
                        }
                    }
                }
            } else {
                val inputValue = TextInput.getSetTextInputValue(widget,currentState,randomInput,currentInputCoverageType)
                if (widget.checked.isEnabled()) {
                    if(widget.checked.toString() != inputValue) {
                        fillActions[widget] = widget.click()
                        ExplorationTrace.widgetTargets.removeLast()
                    }
                } else {
                    val inputAction = if (allInputWidgets.size == 1)
                        widget.setText(inputValue,sendEnter = false ,enableValidation = false)
                    else
                        widget.setText(inputValue,sendEnter = false ,enableValidation = false)
                    ExplorationTrace.widgetTargets.removeLast()
                    fillActions[widget] = inputAction
                }
            }

        }

    }

    override fun chooseAction(currentState: State<*>): ExplorationAction? {
        log.info("Choose an user-like input.")
        if (isOpeningInputDialog) {
            if (!randomExplorationTask!!.isTaskEnd(currentState)) {
                return randomExplorationTask!!.chooseAction(currentState)
            }
             val currentAbstractState = atuaMF.getAbstractState(currentState)!!
            if (currentAbstractState.window is Dialog) {
                val endActions = currentAbstractState.abstractTransitions.filter {
                    it.dest.window != it.source.window
                            && it.dest.window is Activity
                            && it.dest.activity == it.source.activity
                            && it.abstractAction.actionType == AbstractActionType.CLICK
                }.map { it.abstractAction }
                if (endActions.isNotEmpty()) {
                    val randomAction = endActions.random()
                    val widget = if (randomAction.isWidgetAction()) {
                        randomAction.attributeValuationMap!!.getGUIWidgets(currentState,currentAbstractState.window).random()
                    } else
                        null
                    return chooseActionWithName(randomAction.actionType,randomAction.extra,widget,currentState,randomAction)?:ExplorationAction.pressBack()
                }
            }
        }
        isOpeningInputDialog = false
        randomExplorationTask = null
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
        isOpeningInputDialog = false
    }

    override fun hasAnotherOption(currentState: State<*>): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun chooseRandomOption(currentState: State<*>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun isAvailable(currentState: State<*>): Boolean {
        randomInput = false
        return hasInput(currentState) && shouldInsertText(currentState)
    }
    fun isAvailable(currentState: State<*>, random: Boolean): Boolean {
        randomInput = random
        return isAvailable(currentState)
    }
    val inputFillDecision = HashMap<Widget,Boolean>()

    private fun hasInput(currentState: State<*>): Boolean {
        val appState = atuaMF.getAbstractState(currentState)!!
        val userlikeInputWidgets = getUserLikeInputWidgets(currentState)
        return  userlikeInputWidgets.isNotEmpty()
}

    var isOpeningInputDialog: Boolean = false
    internal fun randomlyFill(currentState: State<*>): ExplorationAction? {
        val availableWidgets = currentState.widgets.filter { w->fillActions.containsKey(w)}
        if (availableWidgets.isEmpty()) {
            log.debug("No more filling data.")
            return null
        }
        val toFillWidget = availableWidgets.random()
        log.debug("Selected widget: ${toFillWidget}")
        ExplorationTrace.widgetTargets.add(toFillWidget)
        val action = fillActions[toFillWidget]!!
        fillActions.remove(toFillWidget)
        if (!toFillWidget.isInputField && !toFillWidget.checked.isEnabled()) {
            isOpeningInputDialog = true
            randomExplorationTask = RandomExplorationTask(atuaMF,atuaStrategy,delay,useCoordinateClicks,false,10)
            randomExplorationTask!!.isPureRandom = true
            randomExplorationTask!!.setMaxiumAttempt(10)
        }
        return action
    }

    var randomInput: Boolean = false
    private fun shouldInsertText(currentState: State<*>): Boolean {
        inputFillDecision.clear()
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        val uuid = currentAbstractState.window.toString().toUUID()
        if (!stateInputCoverages.containsKey(uuid)) {
            stateInputCoverages.put(uuid, HashMap())
            val stateInputCoverage = stateInputCoverages.get(uuid)!!
            stateInputCoverage.put(InputCoverage.FILL_ALL,false)
            stateInputCoverage.put(InputCoverage.FILL_EMPTY,false)
            stateInputCoverage.put(InputCoverage.FILL_NONE,false)
            stateInputCoverage.put(InputCoverage.FILL_RANDOM,false)
        }
        val inputCoverage = stateInputCoverages.get(uuid)!!
        val inputCoverageType = if (!inputCoverage.get(InputCoverage.FILL_ALL)!!)
            InputCoverage.FILL_ALL
        else if (!inputCoverage.get(InputCoverage.FILL_NONE)!!) {
            InputCoverage.FILL_NONE
        } else if (!inputCoverage.get(InputCoverage.FILL_EMPTY)!!) {
            InputCoverage.FILL_EMPTY
        } else
            InputCoverage.FILL_RANDOM

        inputCoverage.put(inputCoverageType,true)
        currentInputCoverageType = inputCoverageType
        if (inputCoverageType == InputCoverage.FILL_NONE) {
            return inputFillDecision.isNotEmpty()
        }
//        val inputFields = Helper.getUserInputFields(currentState)
        val userInputFields = Helper.getUserInputFields(currentState)
        val userlikeInputWidgets = getUserLikeInputWidgets(currentState)
        // we group widgets by its resourceId to easily deal with radio button
        val groupedInputWidgets = userlikeInputWidgets.filter { userInputFields.contains(it) }.groupBy { it.resourceId }
        groupedInputWidgets.forEach { resourceId, widgets ->
            val processingWidgets = widgets.toMutableList()
            processingWidgets.forEach {
                when (inputCoverageType) {
                    InputCoverage.FILL_ALL, InputCoverage.FILL_EMPTY -> inputFillDecision.put(it,value = true)
                    InputCoverage.FILL_NONE ->  inputFillDecision.put(it,value = false)
                    InputCoverage.FILL_RANDOM -> {
                        if (!it.isPassword && it.isInputField) {
                            if (random.nextDouble() < 0.75) {
                                inputFillDecision.put(it, true)
                            } else {
                                inputFillDecision.put(it, false)
                            }
                        } else {
                            var ignoreWidget = false
                            if (!it.isInputField) {
                                // check if a click on this widget will go to another window
                                val abstractState = AbstractStateManager.INSTANCE.getAbstractState(currentState)!!
                                val widgetGroup = abstractState.getAttributeValuationSet(widget = it, guiState = currentState,atuaMF = atuaMF)
                                /*if (widgetGroup != null) {
                                    val isGoToAnotherWindow = atuaMF.DSTG.edges(abstractState).any {
                                        it.destination!!.data.window != it.source.data.window
                                                && it.label.abstractAction.actionType == AbstractActionType.CLICK
                                                && it.label.abstractAction.isWidgetAction()
                                                && it.label.abstractAction.attributeValuationMap == widgetGroup
                                                && it.label.isExplicit()
                                    }
                                    if (isGoToAnotherWindow) {
                                        // any widget can lead to another window should be ignore.
                                        ignoreWidget = true
                                    }
                                }*/
                            }
                            if (!ignoreWidget) {
                                inputFillDecision.put(it, true)
                            }
                        }
                    }
                }
            }
        }

        // we consider widgets as user-inputs that will open a dialog to input the data
        userlikeInputWidgets.filter { !userInputFields.contains(it) }. forEach {
            when (inputCoverageType) {
                InputCoverage.FILL_ALL -> inputFillDecision.put(it,true)
                InputCoverage.FILL_RANDOM -> {
                    if (random.nextBoolean())
                        inputFillDecision.put(it,true)
                }
            }

        }

        return inputFillDecision.filter { it.value == true }.isNotEmpty()
    }

    private fun getUserLikeInputWidgets(currentState: State<*>): List<Widget> {
        val currentAbstractState = atuaMF.getAbstractState(currentState)!!
        val userlikeInputAVMs = currentAbstractState.EWTGWidgetMapping.filter {
            (it.value.isUserLikeInput || it.key.isUserLikeInput(appState = currentAbstractState)) && !it.value.verifiedNotUserlikeInput }
        val userlikeInputWidgets = userlikeInputAVMs.map {
            it.key.getGUIWidgets(currentState,currentAbstractState.window)
        }.flatten()
        return userlikeInputWidgets
    }

    companion object {
        private val log: Logger by lazy { LoggerFactory.getLogger(this.javaClass.name) }
        var executedCount:Int = 0
        var instance: PrepareContextTask? = null
        fun getInstance(regressionWatcher: org.atua.modelFeatures.ATUAMF,
                        atuaTestingStrategy: ATUATestingStrategy,
                        delay: Long,
                        useCoordinateClicks: Boolean): PrepareContextTask {
            if (instance == null) {
                instance = PrepareContextTask(regressionWatcher, atuaTestingStrategy, delay,useCoordinateClicks)
            }
            return instance!!
        }
    }
}

enum class InputCoverage {
    FILL_ALL,
    FILL_NONE,
    FILL_EMPTY,
    FILL_RANDOM
}

enum class FillDataMode {
    SIMPLE,
    FULL
}
